package mikey.me.antiBase;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.entity.EntityPoseChangeEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class MovementListener implements Listener {
    private final AntiBase plugin;
    private final BaseObfuscator obfuscator;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "AntiBase-visibility");
                thread.setDaemon(true);
                return thread;
            });
    // these are server thread only, dont touch from packet threads
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private final Set<UUID> urgent = new LinkedHashSet<>();
    private final Map<UUID, Set<Long>> corrections = new HashMap<>();
    private final Map<UUID, ScanTicket> running = new HashMap<>();
    private final Map<UUID, Set<UUID>> hiddenEntities = new HashMap<>();
    private final Map<UUID, Set<UUID>> trackedEntities = new HashMap<>();
    private final Set<UUID> pendingEntities = new LinkedHashSet<>();
    private final Set<PendingPairing> pendingPairings = new HashSet<>();
    private final Map<UUID, LongHashSet> refreshQueue = new HashMap<>();
    private final BlockUpdates blockUpdates;
    private final Map<UUID, Location> localEyes = new HashMap<>();
    private final Map<UUID, ConnectedVisibilityScanner.Window> floodWindows = new HashMap<>();
    private final Set<UUID> invalidFloods = new HashSet<>();
    private final Map<UUID, Map<Long, ChunkSnapshot>> tickSnapshots = new HashMap<>();
    private final Map<UUID, TerrainChangeWork> terrainChanges = new LinkedHashMap<>();
    private final Set<ClientChunk> clientChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<ScanResult> completed = new ConcurrentLinkedQueue<>();
    private final BukkitTask task;
    private int ticks;

    public MovementListener(AntiBase plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
        blockUpdates = new BlockUpdates(plugin, obfuscator);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || event.getTo() == null) return;
        Location from = event.getFrom(), to = event.getTo();
        plugin.updatePosition(event.getPlayer(), to);
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location eye = to.clone().add(0, event.getPlayer().getEyeHeight(), 0);
            if (alreadyCovered(event.getPlayer(), eye)) return;
            updateVisibility(event.getPlayer());
            requestLocal(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPoseChange(EntityPoseChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (alreadyCovered(player, player.getEyeLocation())) return;
            updateVisibility(player);
            requestLocal(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        resetPlayer(event.getPlayer(), event.getTo());
    }

    /** just marks dirty. captures + entity stuff stay on the server thread */
    public void updateVisibility(Player player) {
        if (plugin.isObfuscationEnabled() && !obfuscator.isWorldBlacklisted(player.getWorld())) {
            dirty.add(player.getUniqueId());
        }
    }

    private void requestLocal(Player player) {
        if (plugin.isObfuscationEnabled() && !obfuscator.isWorldBlacklisted(player.getWorld())) urgent.add(player.getUniqueId());
    }

    private boolean alreadyCovered(Player player, Location eye) {
        if (!obfuscator.usesConnectedRendering()) return false;
        ViewerState state = plugin.getViewerState(player.getUniqueId());
        return state != null && state.worldId().equals(player.getWorld().getUID())
                && state.visibility().isConnected(eye.getBlockX(), eye.getBlockY(), eye.getBlockZ())
                && window(eye).equals(floodWindows.get(player.getUniqueId()));
    }

    private ConnectedVisibilityScanner.Window window(Location eye) {
        return ConnectedVisibilityScanner.Window.around(eye.getX(), eye.getY(), eye.getZ(),
                eye.getWorld().getMinHeight(), eye.getWorld().getMaxHeight(), obfuscator.getScanRadius());
    }

    /** packet threads call this when a chunk send lands. bukkit work stays on the server thread */
    void clientChunkSent(UUID player, UUID world, int x, int z) {
        clientChunks.add(new ClientChunk(player, world, x, z));
    }

    private void processClientChunks() {
        Map<UUID, LongHashSet> received = new HashMap<>();
        for (ClientChunk chunk : clientChunks) {
            if (!clientChunks.remove(chunk)) continue;
            Player player = plugin.getServer().getPlayer(chunk.player);
            ViewerState state = plugin.getViewerState(chunk.player);
            if (player == null || !player.isOnline() || state == null || !state.worldId().equals(chunk.world)
                    || !player.getWorld().getUID().equals(chunk.world) || !plugin.isObfuscationEnabled() || !state.protectedWorld()) continue;
            received.computeIfAbsent(chunk.player, key -> new LongHashSet(16)).add(Coordinates.chunk(chunk.x, chunk.z));
        }
        received.forEach((id, chunks) -> {
            Player player = plugin.getServer().getPlayer(id);
            ViewerState state = plugin.getViewerState(id);
            // a delta can get skipped before the client even had the chunk. retry even if the
            // next scan is unchanged. walk the visible set once per player, not per chunk
            state.visibility().forEachTerrainBlock(key -> {
                if (chunks.contains(Coordinates.chunk(Coordinates.blockX(key) >> 4, Coordinates.blockZ(key) >> 4))) {
                    blockUpdates.enqueue(id, state.worldId(), key);
                }
            });
            updateVisibility(player);
            requestLocal(player);
            plugin.diagnostics().count(id, ConsoleDebug.Metric.CLIENT_CHUNKS, chunks.size());
            localEyes.remove(id);
            plugin.interactions().invalidateLocal(id);
        });
    }

    /** one request per observer per explosion, merged across blasts til end of tick */
    void queueExplosion(World world, Collection<Block> edited) {
        queueTerrainChanges(world, edited, false, false);
    }

    /** falling blocks remove their source then place a block later. only placing can close space */
    void queueFallingBlockChange(Block block, BlockData destination) {
        queueTerrainChanges(block.getWorld(), java.util.List.of(block), true, BlockOcclusion.classify(destination) == 1);
    }

    private void queueTerrainChanges(World world, Collection<Block> edited, boolean physics, boolean closesSpace) {
        if (!plugin.isObfuscationEnabled() || obfuscator.isWorldBlacklisted(world) || edited.isEmpty()) return;
        LongHashSet changed = new LongHashSet(edited.size());
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Block block : edited) {
            if (!obfuscator.usesConnectedRendering()
                    && block.getY() >= (long) obfuscator.getHideBelowY() + obfuscator.getScanRadius()) continue;
            int x = block.getX(), y = block.getY(), z = block.getZ();
            changed.add(Coordinates.block(x, y, z));
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + 1); maxY = Math.max(maxY, y + 1); maxZ = Math.max(maxZ, z + 1);
        }
        if (changed.size() == 0) return;
        double radius = obfuscator.getScanRadius() + 16;
        for (Player player : world.getPlayers()) {
            Location eye = player.getEyeLocation();
            double dx = Math.max(minX - eye.getX(), Math.max(0, eye.getX() - maxX));
            double dy = Math.max(minY - eye.getY(), Math.max(0, eye.getY() - maxY));
            double dz = Math.max(minZ - eye.getZ(), Math.max(0, eye.getZ() - maxZ));
            if (obfuscator.usesConnectedRendering()) {
                ConnectedVisibilityScanner.Window bounds = window(eye);
                if (maxX <= bounds.minX() || minX >= bounds.maxX() || maxY <= bounds.minY() || minY >= bounds.maxY()
                        || maxZ <= bounds.minZ() || minZ >= bounds.maxZ()) continue;
            } else if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
            TerrainChangeWork work = terrainChanges.compute(player.getUniqueId(), (id, old) ->
                    old != null && old.world.equals(world.getUID()) ? old : new TerrainChangeWork(world.getUID()));
            if (physics) work.physics = true;
            else work.explosion = true;
            changed.forEach(key -> {
                if (work.changed.size() < 8192) work.changed.add(key);
                else work.overflow = true;
            });
            // removing blocks keeps connectivity, so a falling column shouldnt eat the worker budget.
            // a landing can seal a passage, kill any pre-landing capture right away.
            if (closesSpace) {
                work.closing = true;
                invalidateVisibility(player);
            } else updateVisibility(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        tickSnapshots.clear(); // edits landed after the scheduler capture
        processPendingEntities();
        long deadline = System.nanoTime() + 8_000_000L;
        var pending = terrainChanges.entrySet().iterator();
        int processed = 0;
        while (pending.hasNext() && processed < 4 && (processed == 0 || System.nanoTime() < deadline)) {
            var entry = pending.next();
            UUID id = entry.getKey();
            TerrainChangeWork work = entry.getValue();
            pending.remove();
            Player player = plugin.getServer().getPlayer(id);
            ViewerState state = plugin.getViewerState(id);
            if (player == null || !player.isOnline() || state == null || !state.worldId().equals(work.world)
                    || !player.getWorld().getUID().equals(work.world) || !plugin.isObfuscationEnabled() || !state.protectedWorld()) continue;
            processed++;
            World world = player.getWorld();
            Location eye = player.getEyeLocation();
            VisibilityScanner.BlockAccess access = (x, y, z) -> {
                if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return -1;
                Map<Long, ChunkSnapshot> shared = tickSnapshots.computeIfAbsent(work.world, key -> new HashMap<>());
                ChunkSnapshot snapshot = shared.computeIfAbsent(Coordinates.chunk(x >> 4, z >> 4), key ->
                        world.getChunkAt(x >> 4, z >> 4).getChunkSnapshot(false, false, false));
                return BlockOcclusion.classify(snapshot.getBlockData(x & 15, y, z & 15));
            };
            long started = System.nanoTime();
            VisibilitySnapshot patch = obfuscator.usesConnectedRendering() && !invalidFloods.contains(id)
                    ? ConnectedVisibilityScanner.expandChanges(access, eye.getX(), eye.getY(), eye.getZ(),
                            world.getMinHeight(), world.getMaxHeight(), obfuscator.getHideBelowY(), obfuscator.getScanRadius(),
                            state.visibility(), work.changed, 32768)
                    : work.overflow
                    ? VisibilityScanner.scan(access, eye.getX(), eye.getY(), eye.getZ(), world.getMinHeight(), world.getMaxHeight(),
                            obfuscator.getHideBelowY(), obfuscator.getScanRadius(), 4096, 200000)
                    : VisibilityScanner.scanChangedBlocks(access, eye.getX(), eye.getY(), eye.getZ(), world.getMinHeight(), world.getMaxHeight(),
                            obfuscator.getHideBelowY(), obfuscator.getScanRadius(), 4096, 200000, work.changed);
            patch = padTerrain(access, patch, eye.getX(), eye.getY(), eye.getZ(), world.getMinHeight(),
                    world.getMaxHeight(), obfuscator.getHideBelowY(), obfuscator.getScanRadius(), 32768);
            plugin.setVisibility(id, state.visibility().withRevealed(patch));
            ScanTicket pendingScan = running.get(id);
            if (pendingScan != null && pendingScan.worldId.equals(work.world)) {
                pendingScan.revealed = pendingScan.revealed.withRevealed(patch);
            }
            LongHashSet outgoing = new LongHashSet(patch.terrainCount() + work.changed.size());
            patch.forEachTerrainBlock(key -> {
                if (!state.visibility().isTerrainVisible(Coordinates.blockX(key), Coordinates.blockY(key), Coordinates.blockZ(key))) outgoing.add(key);
            });
            work.changed.forEach(outgoing::add); // committed air/fluid/landed state gets resolved at send time
            blockUpdates.sendNow(player, outgoing);
            if (work.explosion) plugin.diagnostics().count(id, ConsoleDebug.Metric.EXPLOSION_BATCHES, 1);
            if (work.physics) plugin.diagnostics().count(id, ConsoleDebug.Metric.PHYSICS_BATCHES, 1);
            String source = work.explosion ? "explosion" : "physics";
            if (plugin.diagnostics().accepts(id)) plugin.diagnostics().important(id,
                    source + "Ms=" + (System.nanoTime() - started) / 1_000_000
                            + " " + source + "AgeMs=" + (System.nanoTime() - work.started) / 1_000_000
                            + " edited=" + work.changed.size() + " visible=" + patch.blockCount()
                            + " physics=" + work.physics + " closing=" + work.closing
                            + " budgetCapReached=" + patch.budgetLimited() + " overflow=" + work.overflow);
        }
        tickSnapshots.clear();
    }

    /** a topology change must not get stomped by a scan of the old world thats still running */
    void invalidateVisibility(Player player) {
        tickSnapshots.remove(player.getWorld().getUID());
        floodWindows.remove(player.getUniqueId());
        if (obfuscator.usesConnectedRendering()) invalidFloods.add(player.getUniqueId());
        cancelScan(player.getUniqueId());
        updateVisibility(player);
    }

    /** only pin blocks the player was actually shown, never a whole area */
    void protectInteraction(Player player, Block block, String operation, boolean cancelled) {
        if (!plugin.isObfuscationEnabled() || obfuscator.isWorldBlacklisted(block.getWorld())) return;
        UUID id = player.getUniqueId(), worldId = block.getWorld().getUID();
        ViewerState state = plugin.getViewerState(id);
        ClientViewTracker.View client = plugin.clientViews().get(id, worldId);
        boolean known = state != null && state.worldId().equals(worldId)
                && (state.visibility().isBlockVisible(block.getX(), block.getY(), block.getZ())
                    || (client != null && client.wasReal(block.getX(), block.getY(), block.getZ()))
                    || plugin.interactions().allows(id, worldId, block.getX(), block.getY(), block.getZ()));
        plugin.diagnostics().count(id, ConsoleDebug.Metric.MINING, 1);
        if (plugin.diagnostics().accepts(id)) plugin.diagnostics().important(id, "interaction=" + operation
                + " playerName=" + player.getName() + " world=" + block.getWorld().getName()
                + " pos=" + block.getX() + "," + block.getY() + "," + block.getZ()
                + " material=" + block.getType() + " cancelled=" + cancelled + " trusted=" + known);
        if (!known) return;
        plugin.interactions().pin(id, worldId, block.getX(), block.getY(), block.getZ());
        Set<Long> pending = corrections.computeIfAbsent(id, key -> new HashSet<>());
        if (pending.size() < 128) pending.add(Coordinates.block(block.getX(), block.getY(), block.getZ()));
        urgent.add(id);
    }

    String queueStatus() {
        return "terrainMode=" + (obfuscator.usesConnectedRendering() ? "connected" : "line-of-sight")
                + " terrainPadding=" + obfuscator.getTerrainPadding()
                + " entityChecksPending=" + pendingEntities.size()
                + " queued=" + dirty.size() + " running=" + running.size() + " urgent=" + urgent.size()
                + " pendingBlocks=" + blockUpdates.size()
                + " explosions=" + terrainChanges.values().stream().filter(work -> work.explosion).count()
                + " physics=" + terrainChanges.values().stream().filter(work -> work.physics).count()
                + " clientChunks=" + clientChunks.size();
    }

    private void tick() {
        tickSnapshots.clear();
        processPendingEntities();
        processClientChunks();
        processInteractions();
        plugin.interactions().drainRefreshes((player, world, blocks) -> {
            ViewerState viewer = plugin.getViewerState(player);
            if (viewer == null || !viewer.worldId().equals(world)) return;
            blocks.forEach(key -> {
                int x = Coordinates.blockX(key), y = Coordinates.blockY(key), z = Coordinates.blockZ(key);
                if (viewer.shouldHide(x, y, z, obfuscator.getHideBelowY()) && !plugin.interactions().allows(player, world, x, y, z)) {
                    blockUpdates.enqueue(player, world, key);
                }
            });
        });
        if (++ticks % 20 == 0 && plugin.isObfuscationEnabled()) {
            // also catches other plugins edits + state changes with no event
            for (Player player : plugin.getServer().getOnlinePlayers()) updateVisibility(player);
        }
        ScanResult result;
        while ((result = completed.poll()) != null) {
            ScanTicket ticket = result.ticket();
            if (running.get(ticket.playerId) != ticket) {
                plugin.diagnostics().count(ticket.playerId, ConsoleDebug.Metric.STALE_SCANS, 1);
                continue;
            }
            running.remove(ticket.playerId);
            Player player = plugin.getServer().getPlayer(ticket.playerId);
            if (player == null || !player.isOnline() || !plugin.isObfuscationEnabled()
                    || !player.getWorld().getUID().equals(ticket.worldId)) continue;
            if (result.error() != null) {
                plugin.getLogger().log(Level.WARNING, "Visibility scan failed for " + ticket.playerId, result.error());
                continue;
            }
            // the flood stays valid anywhere in its component, even after moving around a corner
            Location currentEye = player.getEyeLocation();
            boolean moved = currentEye.distanceSquared(ticket.origin) > 1;
            if (moved && (!obfuscator.usesConnectedRendering() || !result.visibility().isConnected(
                    currentEye.getBlockX(), currentEye.getBlockY(), currentEye.getBlockZ()))) {
                plugin.diagnostics().count(ticket.playerId, ConsoleDebug.Metric.STALE_SCANS, 1);
                dirty.add(ticket.playerId);
                continue;
            }
            // only keep patches made after this capture. merging the whole old view could
            // bring back a room a door closed before the capture
            VisibilitySnapshot next = result.visibility().withRevealed(ticket.revealed);
            applyResults(player, next);
            if (obfuscator.usesConnectedRendering()) {
                floodWindows.put(ticket.playerId, window(ticket.origin));
                invalidFloods.remove(ticket.playerId);
            }
            plugin.diagnostics().count(ticket.playerId, ConsoleDebug.Metric.SCANS, 1);
            if (result.visibility().budgetLimited()) plugin.diagnostics().count(ticket.playerId, ConsoleDebug.Metric.BUDGET_LIMITS, 1);
            if (plugin.diagnostics().accepts(ticket.playerId)) plugin.diagnostics().sample(ticket.playerId,
                    "scanAgeMs=" + ((System.nanoTime() - ticket.started) / 1_000_000)
                            + " captureMs=" + ticket.captureNanos / 1_000_000 + " workerMs=" + result.workerNanos() / 1_000_000
                            + " mode=" + (obfuscator.usesConnectedRendering() ? "connected" : "line-of-sight")
                            + " visible=" + result.visibility().blockCount() + " terrain=" + result.visibility().terrainCount()
                            + " budgetCapReached=" + result.visibility().budgetLimited());
        }
        Iterator<UUID> requests = dirty.iterator();
        int captures = 0;
        int captureBudget = 1;
        while (requests.hasNext() && running.size() < 2 && captures < captureBudget) {
            UUID id = requests.next();
            if (running.containsKey(id)) continue;
            requests.remove();
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline() || !plugin.isObfuscationEnabled()
                    || obfuscator.isWorldBlacklisted(player.getWorld())) continue;
            plugin.updatePosition(player, player.getLocation());
            if (window(player.getEyeLocation()).minY() >= obfuscator.getHideBelowY()) {
                applyResults(player, VisibilitySnapshot.EMPTY);
                continue;
            }
            startScan(player);
            captures++;
        }
        blockUpdates.flush(16384, 4096);
        flushRefreshes(4); // full chunks are only for resets/restores
        tickSnapshots.clear();
        if (ticks % 5 == 1 && plugin.isObfuscationEnabled()) {
            for (Player player : plugin.getServer().getOnlinePlayers()) updateEntitiesVisibility(player);
        }
        if (ticks % 40 == 0) plugin.diagnostics().flush(plugin.getLogger(), queueStatus());
    }

    /** next tick sees the committed break/cancel result, not the events old block */
    private void processInteractions() {
        Iterator<UUID> pending = urgent.iterator();
        int scans = 0;
        while (pending.hasNext() && scans++ < 2) {
            UUID id = pending.next();
            pending.remove();
            Set<Long> targets = corrections.remove(id);
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline() || !plugin.isObfuscationEnabled()) continue;
            World world = player.getWorld();
            if (obfuscator.isWorldBlacklisted(world)) continue;
            long start = System.nanoTime();
            Location eye = player.getEyeLocation();
            Location previousEye = localEyes.get(id);
            if (targets == null && previousEye != null && previousEye.getWorld().equals(world)
                    && previousEye.distanceSquared(eye) < 0.0625) continue;
            if (eye.getY() - 8 >= obfuscator.getHideBelowY() && targets == null) continue;
            localEyes.put(id, eye.clone());
            Map<Long, ChunkSnapshot> snapshots = capture(world, eye, 8);
            VisibilitySnapshot local = scanTerrain((x, y, z) -> {
                ChunkSnapshot snapshot = snapshots.get(Coordinates.chunk(x >> 4, z >> 4));
                return snapshot == null ? -1 : BlockOcclusion.classify(snapshot.getBlockData(x & 15, y, z & 15));
            }, eye.getX(), eye.getY(), eye.getZ(), world.getMinHeight(), world.getMaxHeight(),
                    obfuscator.getHideBelowY(), 8, 4096, 100000);
            VisibilitySnapshot previousLocal = plugin.interactions().local(id, world.getUID(), local);
            LongHashSet changes = new LongHashSet(256);
            local.forEachTerrainBlock(key -> {
                if (!previousLocal.isTerrainVisible(Coordinates.blockX(key), Coordinates.blockY(key), Coordinates.blockZ(key))) changes.add(key);
            });
            if (targets != null) for (long key : targets) {
                int x = Coordinates.blockX(key), y = Coordinates.blockY(key), z = Coordinates.blockZ(key);
                // keep the real correction even if the sight scan ran out of budget
                if (plugin.interactions().allows(id, world.getUID(), x, y, z)) changes.add(key);
            }
            blockUpdates.sendNow(player, changes);
            // a movement preview cant kill the full scan or distant reveals starve
            if (targets != null) invalidateVisibility(player);
            plugin.diagnostics().count(id, ConsoleDebug.Metric.LOCAL_SCANS, 1);
            if (plugin.diagnostics().accepts(id)) plugin.diagnostics().sample(id,
                    "localRefreshMs=" + ((System.nanoTime() - start) / 1_000_000)
                            + " changes=" + changes.size() + " budgetCapReached=" + local.budgetLimited());
        }
    }

    private Map<Long, ChunkSnapshot> capture(World world, Location origin, int radius) {
        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        Map<Long, ChunkSnapshot> shared = tickSnapshots.computeIfAbsent(world.getUID(), key -> new HashMap<>());
        int padding = obfuscator.usesConnectedRendering() ? ((radius + 15) >> 4) << 4 : radius;
        for (int cx = (origin.getBlockX() - padding) >> 4; cx <= (origin.getBlockX() + padding) >> 4; cx++) {
            for (int cz = (origin.getBlockZ() - padding) >> 4; cz <= (origin.getBlockZ() + padding) >> 4; cz++) {
                if (world.isChunkLoaded(cx, cz)) {
                    long key = Coordinates.chunk(cx, cz);
                    ChunkSnapshot snapshot = shared.get(key);
                    if (snapshot == null) {
                        snapshot = world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
                        shared.put(key, snapshot);
                    }
                    snapshots.put(key, snapshot);
                }
            }
        }
        return snapshots;
    }

    private void startScan(Player player) {
        World world = player.getWorld();
        Location origin = player.getEyeLocation();
        int radius = obfuscator.getScanRadius();
        int minY = world.getMinHeight(), maxY = world.getMaxHeight();
        ScanTicket ticket = new ScanTicket(player.getUniqueId(), world.getUID(), origin);
        Map<Long, ChunkSnapshot> snapshots = capture(world, origin, radius);
        ticket.captureNanos = System.nanoTime() - ticket.started;
        running.put(ticket.playerId, ticket);
        try {
            ticket.future = executor.submit(() -> {
                long started = System.nanoTime();
                try {
                    VisibilitySnapshot visibility = scanTerrain((x, y, z) -> {
                        ChunkSnapshot snapshot = snapshots.get(Coordinates.chunk(x >> 4, z >> 4));
                        return snapshot == null ? -1 : BlockOcclusion.classify(snapshot.getBlockData(x & 15, y, z & 15));
                    }, origin.getX(), origin.getY(), origin.getZ(), minY, maxY, obfuscator.getHideBelowY(),
                            radius, obfuscator.getMaxScanBlocks(), obfuscator.getMaxRaySteps());
                    completed.add(new ScanResult(ticket, visibility, null, System.nanoTime() - started));
                } catch (Exception exception) {
                    completed.add(new ScanResult(ticket, null, exception, System.nanoTime() - started));
                }
            });
        } catch (RejectedExecutionException exception) {
            running.remove(ticket.playerId);
            // cancelled job might still be exiting. retry next move/world update
        }
    }

    private VisibilitySnapshot scanTerrain(VisibilityScanner.BlockAccess access, double x, double y, double z,
                                           int minY, int maxY, int hideBelow, int radius, int budget, int rayBudget) {
        VisibilitySnapshot visible = obfuscator.usesConnectedRendering()
                ? ConnectedVisibilityScanner.scan(access, x, y, z, minY, maxY, hideBelow, radius)
                : VisibilityScanner.scan(access, x, y, z, minY, maxY, hideBelow, radius, budget, rayBudget);
        return padTerrain(access, visible, x, y, z, minY, maxY, hideBelow, radius,
                radius <= 8 ? 32768 : Integer.MAX_VALUE);
    }

    private VisibilitySnapshot padTerrain(VisibilityScanner.BlockAccess access, VisibilitySnapshot visible,
                                          double x, double y, double z, int minY, int maxY, int hideBelow,
                                          int radius, int maxReads) {
        return TerrainPadding.add(access, visible, ConnectedVisibilityScanner.Window.around(x, y, z, minY, maxY, radius),
                hideBelow, obfuscator.getTerrainPadding(), maxReads);
    }

    private void applyResults(Player player, VisibilitySnapshot visibility) {
        ViewerState previous = plugin.getViewerState(player.getUniqueId());
        if (previous == null) return;
        // publish first so packet filtering sees the new visibility before deltas go out
        plugin.setVisibility(player.getUniqueId(), visibility);
        visibility.forEachChangedBlock(previous.visibility(), key ->
                blockUpdates.enqueue(player.getUniqueId(), player.getWorld().getUID(), key));
        if (plugin.isDebugEnabled(player.getUniqueId())) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    "AntiBase | Visible blocks: " + visibility.blockCount() + " | Sections: " + visibility.sectionCount()));
        }
    }

    private void flushRefreshes(int budget) {
        Iterator<Map.Entry<UUID, LongHashSet>> worlds = refreshQueue.entrySet().iterator();
        while (worlds.hasNext() && budget > 0) {
            Map.Entry<UUID, LongHashSet> entry = worlds.next();
            World world = plugin.getServer().getWorld(entry.getKey());
            if (world == null) { worlds.remove(); continue; }
            LongHashSet batch = new LongHashSet(Math.min(budget, entry.getValue().size()));
            int limit = budget;
            entry.getValue().forEach(key -> { if (batch.size() < limit) batch.add(key); });
            batch.forEach(key -> {
                entry.getValue().remove(key);
                int cx = Coordinates.chunkX(key), cz = Coordinates.chunkZ(key);
                if (world.isChunkLoaded(cx, cz)) world.refreshChunk(cx, cz);
            });
            budget -= batch.size();
            if (entry.getValue().size() == 0) worlds.remove();
        }
    }

    private void updateEntitiesVisibility(Player viewer) {
        ViewerState state = plugin.getViewerState(viewer.getUniqueId());
        if (state == null || !state.protectedWorld()) return;
        Set<UUID> checked = new HashSet<>();
        for (Player target : viewer.getWorld().getPlayers()) {
            if (!target.equals(viewer) && checked.add(target.getUniqueId())) updateEntityForViewer(viewer, target);
        }
        for (Entity target : viewer.getNearbyEntities(64, 64, 64)) {
            if (!(target instanceof Player) && checked.add(target.getUniqueId())) updateEntityForViewer(viewer, target);
        }
        // paper tracks mobs past the 64 block query, check those too
        Set<UUID> tracked = trackedEntities.get(viewer.getUniqueId());
        if (tracked != null) for (UUID id : Set.copyOf(tracked)) {
            Entity target = plugin.getServer().getEntity(id);
            if (target == null || !viewer.getWorld().equals(target.getWorld())) tracked.remove(id);
            else if (checked.add(id)) updateEntityForViewer(viewer, target);
        }
        Set<UUID> hidden = hiddenEntities.get(viewer.getUniqueId());
        if (hidden != null) {
            for (UUID id : Set.copyOf(hidden)) {
                Entity target = plugin.getServer().getEntity(id);
                if (target == null) hidden.remove(id);
                else if (checked.add(id)) updateEntityForViewer(viewer, target);
            }
        }
    }

    void updateEntityForViewer(Player viewer, Entity target) {
        setEntityVisibility(viewer, target, entityVisibleAt(viewer, target, target.getLocation()));
    }

    /** paper calls this before an entitys first pairing packets. hiding is deferred
     * til the tracker callback returns, cancelling the event kills the pairing */
    boolean trackEntity(Player viewer, Entity target) {
        trackedEntities.computeIfAbsent(viewer.getUniqueId(), key -> new HashSet<>()).add(target.getUniqueId());
        boolean visible = entityVisibleAt(viewer, target, target.getLocation());
        if (!visible) {
            pendingEntities.add(target.getUniqueId());
            pendingPairings.add(new PendingPairing(viewer.getUniqueId(), target.getUniqueId()));
        }
        return visible;
    }

    void untrackEntity(Player viewer, Entity target) {
        Set<UUID> tracked = trackedEntities.get(viewer.getUniqueId());
        if (tracked != null) {
            tracked.remove(target.getUniqueId());
            if (tracked.isEmpty()) trackedEntities.remove(viewer.getUniqueId());
        }
    }

    void entityTeleporting(Entity target, Location destination) {
        pendingEntities.add(target.getUniqueId());
        if (destination.getWorld() == null) return;
        tickSnapshots.remove(destination.getWorld().getUID());
        for (Player viewer : destination.getWorld().getPlayers()) {
            plugin.diagnostics().count(viewer.getUniqueId(), ConsoleDebug.Metric.ENTITY_TELEPORTS, 1);
            // hide before the teleport packet, reveal only after the move commits
            if (!entityVisibleAt(viewer, target, destination)) setEntityVisibility(viewer, target, false);
        }
    }

    private void processPendingEntities() {
        if (pendingEntities.isEmpty()) return;
        // a cancelled pairing is already in papers seenBy set. set up the hide API
        // state before rechecking so even an instant reveal forces a fresh pairing
        Set<PendingPairing> pairings = Set.copyOf(pendingPairings);
        pendingPairings.clear();
        for (PendingPairing pair : pairings) {
            Player viewer = plugin.getServer().getPlayer(pair.viewer);
            Entity target = plugin.getServer().getEntity(pair.entity);
            if (viewer != null && viewer.isOnline() && target != null) setEntityVisibility(viewer, target, false);
        }
        Set<UUID> pending = Set.copyOf(pendingEntities);
        pendingEntities.clear();
        for (UUID id : pending) {
            Entity target = plugin.getServer().getEntity(id);
            if (target == null) continue;
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                if (!viewer.equals(target)) updateEntityForViewer(viewer, target);
            }
        }
    }

    private boolean entityVisibleAt(Player viewer, Entity target, Location location) {
        ViewerState state = plugin.getViewerState(viewer.getUniqueId());
        if (!plugin.isObfuscationEnabled() || state == null || !state.protectedWorld()
                || !viewer.getWorld().equals(location.getWorld()) || obfuscator.isWorldBlacklisted(viewer.getWorld())
                || location.getBlockY() >= obfuscator.getHideBelowY()) return true;
        double height = target.getHeight();
        if (!Double.isFinite(height) || height <= 0) height = 1.8;
        boolean eligible = false;
        for (double fraction : ENTITY_SAMPLES) {
            int y = VoxelRayTracer.floor(location.getY() + height * fraction);
            eligible |= y >= obfuscator.getHideBelowY()
                    || state.visibility().isBlockVisible(location.getBlockX(), y, location.getBlockZ());
        }
        // padding or stale terrain must not bypass the actual sight check
        return eligible && entityInSight(viewer, location, height);
    }

    private static final double[] ENTITY_SAMPLES = {0.5, 0.95, 0.05};

    /** terrain connectivity must not turn into entity visibility through walls */
    private boolean entityInSight(Player viewer, Location target, double height) {
        World world = viewer.getWorld();
        Location eye = viewer.getEyeLocation();
        VoxelRayTracer rays = new VoxelRayTracer((x, y, z) -> {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return -1;
            Map<Long, ChunkSnapshot> shared = tickSnapshots.computeIfAbsent(world.getUID(), key -> new HashMap<>());
            ChunkSnapshot snapshot = shared.computeIfAbsent(Coordinates.chunk(x >> 4, z >> 4), key ->
                    world.getChunkAt(x >> 4, z >> 4).getChunkSnapshot(false, false, false));
            return BlockOcclusion.classify(snapshot.getBlockData(x & 15, y, z & 15));
        }, eye.getX(), eye.getY(), eye.getZ(), world.getMinHeight(), world.getMaxHeight(),
                obfuscator.getHideBelowY(), 2048, (x, y, z, type) -> { });
        for (double fraction : ENTITY_SAMPLES) {
            if (rays.traceOpen(target.getX(), target.getY() + height * fraction, target.getZ())) return true;
        }
        return false;
    }

    private void setEntityVisibility(Player viewer, Entity target, boolean visible) {
        Set<UUID> hidden = hiddenEntities.computeIfAbsent(viewer.getUniqueId(), key -> new HashSet<>());
        UUID id = target.getUniqueId();
        if (visible) {
            if (!hidden.remove(id)) return;
            recordEntityChange(viewer, target, true);
            if (target instanceof Player targetPlayer) {
                plugin.setHidden(viewer.getUniqueId(), id, false);
                viewer.showPlayer(plugin, targetPlayer);
            } else viewer.showEntity(plugin, target);
        } else {
            if (!hidden.add(id)) return;
            recordEntityChange(viewer, target, false);
            if (target instanceof Player targetPlayer) {
                // before hidePlayer, it fires the tab list packet synchronously
                plugin.setHidden(viewer.getUniqueId(), id, true);
                viewer.hidePlayer(plugin, targetPlayer);
            } else viewer.hideEntity(plugin, target);
        }
    }

    private void recordEntityChange(Player viewer, Entity target, boolean visible) {
        UUID viewerId = viewer.getUniqueId();
        plugin.diagnostics().count(viewerId, visible ? ConsoleDebug.Metric.ENTITY_SHOWS : ConsoleDebug.Metric.ENTITY_HIDES, 1);
        if (plugin.diagnostics().accepts(viewerId)) {
            Location location = target.getLocation();
            plugin.diagnostics().sample(viewerId, "entity=" + target.getUniqueId() + " type=" + target.getType()
                    + " visible=" + visible + " pos=" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
        }
    }

    void resetPlayer(Player player, Location destination) {
        floodWindows.remove(player.getUniqueId());
        invalidFloods.remove(player.getUniqueId());
        terrainChanges.remove(player.getUniqueId());
        clientChunks.removeIf(chunk -> chunk.player.equals(player.getUniqueId()));
        blockUpdates.remove(player.getUniqueId());
        localEyes.remove(player.getUniqueId());
        plugin.interactions().remove(player.getUniqueId());
        urgent.remove(player.getUniqueId());
        corrections.remove(player.getUniqueId());
        cancelScan(player.getUniqueId());
        restoreEntities(player);
        ViewerState previous = plugin.getViewerState(player.getUniqueId());
        if (previous != null && previous.worldId().equals(destination.getWorld().getUID())) {
            applyResults(player, VisibilitySnapshot.EMPTY);
            // also the temp interaction reveals, those arent in the full scan
            queueClientChunks(player);
        } else plugin.setVisibility(player.getUniqueId(), VisibilitySnapshot.EMPTY);
        plugin.updatePosition(player, destination);
        if (plugin.isObfuscationEnabled() && !obfuscator.isWorldBlacklisted(destination.getWorld())) dirty.add(player.getUniqueId());
    }

    public void resetAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            resetPlayer(player, player.getLocation());
            queueClientChunks(player);
        }
    }

    public void restoreAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            restoreEntities(player);
            queueClientChunks(player);
        }
        flushRefreshes(Integer.MAX_VALUE);
    }

    private void queueClientChunks(Player player) {
        LongHashSet pending = refreshQueue.computeIfAbsent(player.getWorld().getUID(), key -> new LongHashSet(64));
        for (org.bukkit.Chunk chunk : player.getSentChunks()) {
            pending.add(Coordinates.chunk(chunk.getX(), chunk.getZ()));
        }
    }

    private void restoreEntities(Player viewer) {
        Set<UUID> hidden = hiddenEntities.get(viewer.getUniqueId());
        if (hidden == null) return;
        for (UUID id : Set.copyOf(hidden)) {
            Entity target = plugin.getServer().getEntity(id);
            if (target != null) setEntityVisibility(viewer, target, true);
        }
        hiddenEntities.remove(viewer.getUniqueId());
    }

    private void cancelScan(UUID id) {
        dirty.remove(id);
        ScanTicket ticket = running.remove(id);
        if (ticket != null && ticket.future != null) ticket.future.cancel(true);
        executor.purge();
    }

    public void cleanupPlayer(UUID id) {
        trackedEntities.remove(id);
        trackedEntities.values().forEach(targets -> targets.remove(id));
        pendingEntities.remove(id);
        pendingPairings.removeIf(pair -> pair.viewer.equals(id) || pair.entity.equals(id));
        floodWindows.remove(id);
        invalidFloods.remove(id);
        terrainChanges.remove(id);
        clientChunks.removeIf(chunk -> chunk.player.equals(id));
        blockUpdates.remove(id);
        localEyes.remove(id);
        urgent.remove(id);
        corrections.remove(id);
        cancelScan(id);
        hiddenEntities.remove(id);
        hiddenEntities.values().forEach(targets -> targets.remove(id));
    }

    public void shutdown() {
        task.cancel();
        running.values().forEach(ticket -> { if (ticket.future != null) ticket.future.cancel(true); });
        executor.shutdownNow();
        trackedEntities.clear();
        pendingEntities.clear();
        pendingPairings.clear();
        dirty.clear();
        urgent.clear();
        corrections.clear();
        running.clear();
        completed.clear();
        refreshQueue.clear();
        blockUpdates.clear();
        localEyes.clear();
        floodWindows.clear();
        invalidFloods.clear();
        tickSnapshots.clear();
        terrainChanges.clear();
        clientChunks.clear();
        hiddenEntities.clear();
    }

    private static final class ScanTicket {
        final UUID playerId;
        final UUID worldId;
        final Location origin;
        final long started = System.nanoTime();
        long captureNanos;
        VisibilitySnapshot revealed = VisibilitySnapshot.EMPTY; // server thread only
        Future<?> future;
        ScanTicket(UUID playerId, UUID worldId, Location origin) {
            this.playerId = playerId;
            this.worldId = worldId;
            this.origin = origin;
        }
    }

    private record ScanResult(ScanTicket ticket, VisibilitySnapshot visibility, Exception error, long workerNanos) { }
    private record PendingPairing(UUID viewer, UUID entity) { }
    private record ClientChunk(UUID player, UUID world, int x, int z) { }
    private static final class TerrainChangeWork {
        final UUID world;
        final LongHashSet changed = new LongHashSet(256);
        final long started = System.nanoTime();
        boolean overflow;
        boolean explosion, physics, closing;
        TerrainChangeWork(UUID world) { this.world = world; }
    }
}
