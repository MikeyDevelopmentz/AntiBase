package mikey.me.antiBase;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class MovementListener implements Listener {
    private static final int[][] NEIGHBORS = {{1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}};
    private final AntiBase plugin;
    private final BaseObfuscator obfuscator;
    private final Map<UUID, Long> lastUpdateTick = new ConcurrentHashMap<>();
    private final Map<UUID, LongHashSet> playerVisibleSections = new ConcurrentHashMap<>();
    private final Map<UUID, BFSContext> bfsContexts = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> lastBFSPosition = new ConcurrentHashMap<>();

    private static class BFSContext {
        final int[] queueX = new int[100000];
        final int[] queueY = new int[100000];
        final int[] queueZ = new int[100000];
        final LongHashSet visitedBlocks = new LongHashSet(200000);
        final LongHashSet visibleBlocks = new LongHashSet(50000);
        final LongHashSet newVisibleSections = new LongHashSet(4096);
        final HashMap<Long, Chunk> chunkCache = new HashMap<>();

        void clear() {
            visitedBlocks.clear();
            visibleBlocks.clear();
            newVisibleSections.clear();
            chunkCache.clear();
        }
    }

    public MovementListener(AntiBase plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    private void update(Location to, Location from, Player player) {
        if (!plugin.isObfuscationEnabled()) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ() && from.getBlockY() == to.getBlockY()) return;
        if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
        long currentTick = player.getWorld().getFullTime();
        long lastUpdate = lastUpdateTick.getOrDefault(player.getUniqueId(), 0L);
        if (currentTick - lastUpdate < 5) return;
        lastUpdateTick.put(player.getUniqueId(), currentTick);

        int bx = to.getBlockX();
        int by = to.getBlockY();
        int bz = to.getBlockZ();
        long[] lastPos = lastBFSPosition.get(player.getUniqueId());
        if (lastPos != null) {
            long dx = bx - lastPos[0];
            long dy = by - lastPos[1];
            long dz = bz - lastPos[2];
            if (dx * dx + dy * dy + dz * dz < 4) return;
        }
        lastBFSPosition.put(player.getUniqueId(), new long[]{bx, by, bz});

        updateVisibility(player);
        updateOthersViewOfPlayer(player);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        update(event.getTo(), event.getFrom(), event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        update(event.getTo(), event.getFrom(), event.getPlayer());
    }

    public void updateVisibility(Player player) {
        if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
        int hideBelow = obfuscator.getHideBelowY();
        Location playerLoc = player.getLocation();

        if (playerLoc.getBlockY() > hideBelow + 32) {
            updateEntitiesVisibility(player, null);
            return;
        }

        World world = player.getWorld();
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();

        BFSContext ctx = bfsContexts.computeIfAbsent(player.getUniqueId(), k -> new BFSContext());
        ctx.clear();

        int head = 0;
        int tail = 0;
        int size = 0;
        int startX = playerLoc.getBlockX();
        int startY = Math.max(minHeight, Math.min(maxHeight - 1, playerLoc.getBlockY()));
        int startZ = playerLoc.getBlockZ();
        ctx.queueX[tail] = startX;
        ctx.queueY[tail] = startY;
        ctx.queueZ[tail] = startZ;
        tail = (tail + 1) % 100000;
        size++;
        ctx.visitedBlocks.add(plugin.packCoord(startX, startY, startZ));
        int maxDistance = 160;
        int maxDistSq = maxDistance * maxDistance;
        int blocksChecked = 0;
        int maxBlocks = 100000;
        while (size > 0 && blocksChecked < maxBlocks) {
            int x = ctx.queueX[head];
            int y = ctx.queueY[head];
            int z = ctx.queueZ[head];
            head = (head + 1) % 100000;
            size--;
            int dx = x - startX;
            int dy = y - startY;
            int dz = z - startZ;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;
            if (y < hideBelow) {
                ctx.newVisibleSections.add(plugin.packSection(x >> 4, y >> 4, z >> 4));
                ctx.visibleBlocks.add(plugin.packCoord(x, y, z));
            }
            for (int[] n : NEIGHBORS) {
                int nx = x + n[0], ny = y + n[1], nz = z + n[2];
                if (ny < minHeight || ny >= maxHeight) continue;
                long key = plugin.packCoord(nx, ny, nz);
                if (ctx.visitedBlocks.add(key)) {
                    int cx = nx >> 4;
                    int cz = nz >> 4;
                    if (!world.isChunkLoaded(cx, cz)) continue;
                    long chunkKey = ((long)cx << 32) | (cz & 0xFFFFFFFFL);
                    Chunk chunk = ctx.chunkCache.computeIfAbsent(chunkKey, k -> world.getChunkAt(cx, cz));
                    Block block = chunk.getBlock(nx & 15, ny, nz & 15);
                    if (ny < hideBelow) {
                        ctx.newVisibleSections.add(plugin.packSection(cx, ny >> 4, cz));
                    }
                    if (!block.getType().isOccluding() || block.getLightFromSky() == 15) {
                        if (size < 100000) {
                            ctx.queueX[tail] = nx;
                            ctx.queueY[tail] = ny;
                            ctx.queueZ[tail] = nz;
                            tail = (tail + 1) % 100000;
                            size++;
                        }
                    }
                }
            }
            blocksChecked++;
        }
        plugin.setVisibleBlocks(player.getUniqueId(), ctx.visibleBlocks);
        updateEntitiesVisibility(player, ctx.visibleBlocks);
        Set<String> refreshedChunks = new HashSet<>();
        LongHashSet oldVisibleSet = playerVisibleSections.get(player.getUniqueId());
        ctx.newVisibleSections.forEach(sectionKey -> {
            int[] coords = plugin.unpackSection(sectionKey);
            if (!plugin.isSectionVisible(player.getUniqueId(), coords[0], coords[1], coords[2])) {
                plugin.updateSectionVisibility(player.getUniqueId(), coords[0], coords[1], coords[2], true);
                String chunkKeyStr = coords[0] + "," + coords[2];
                if (!refreshedChunks.contains(chunkKeyStr)) {
                    if (world.isChunkLoaded(coords[0], coords[2])) {
                        world.refreshChunk(coords[0], coords[2]);
                        refreshedChunks.add(chunkKeyStr);
                    }
                }
            }
        });
        if (oldVisibleSet != null) {
            oldVisibleSet.forEach(oldKey -> {
                if (!ctx.newVisibleSections.contains(oldKey)) {
                    int[] coords = plugin.unpackSection(oldKey);
                    plugin.updateSectionVisibility(player.getUniqueId(), coords[0], coords[1], coords[2], false);
                    String chunkKeyStr = coords[0] + "," + coords[2];
                    if (!refreshedChunks.contains(chunkKeyStr)) {
                        if (world.isChunkLoaded(coords[0], coords[2])) {
                            world.refreshChunk(coords[0], coords[2]);
                            refreshedChunks.add(chunkKeyStr);
                        }
                    }
                }
            });
        }
        LongHashSet savedSections = new LongHashSet(ctx.newVisibleSections.size());
        ctx.newVisibleSections.forEach(savedSections::add);
        playerVisibleSections.put(player.getUniqueId(), savedSections);
        if (plugin.isDebugEnabled(player.getUniqueId()) && !refreshedChunks.isEmpty()) {
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gray>[<gold>AntiBase</gold>]</gray> <green>Visible:</green> <yellow>" + ctx.visitedBlocks.size() + "</yellow> <gray>|</gray> <green>Sections:</green> <yellow>" + ctx.newVisibleSections.size() + "</yellow>"
            ));
        }
    }

    private void updateEntitiesVisibility(Player player, LongHashSet visibleBlocks) {
        if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
        int hideBelow = obfuscator.getHideBelowY();
        Location pLoc = player.getLocation();
        double px = pLoc.getX();
        double py = pLoc.getY();
        double pz = pLoc.getZ();

        for (Player other : player.getWorld().getPlayers()) {
            if (other.equals(player)) continue;
            double dx = other.getLocation().getX() - px;
            double dy = other.getLocation().getY() - py;
            double dz = other.getLocation().getZ() - pz;
            if (dx * dx + dy * dy + dz * dz > 25600) continue;
            int ey = other.getLocation().getBlockY();
            if (ey < hideBelow && visibleBlocks != null) {
                boolean visible = visibleBlocks.contains(plugin.packCoord(
                    other.getLocation().getBlockX(), ey, other.getLocation().getBlockZ()));
                setPlayerVisibility(player, other, visible);
            } else {
                setPlayerVisibility(player, other, true);
            }
        }

        for (Entity e : player.getNearbyEntities(48, 48, 48)) {
            if (e instanceof Player) continue;
            int ey = e.getLocation().getBlockY();
            if (ey < hideBelow && visibleBlocks != null) {
                boolean visible = visibleBlocks.contains(plugin.packCoord(
                    e.getLocation().getBlockX(), ey, e.getLocation().getBlockZ()));
                setEntityVisibility(player, e, visible);
            }
        }
    }

    public void updateOthersViewOfPlayer(Player movingPlayer) {
        if (obfuscator.isWorldBlacklisted(movingPlayer.getWorld())) return;
        int hideBelow = obfuscator.getHideBelowY();
        int ex = movingPlayer.getLocation().getBlockX();
        int ey = movingPlayer.getLocation().getBlockY();
        int ez = movingPlayer.getLocation().getBlockZ();

        if (ey >= hideBelow) {
            for (Player other : movingPlayer.getWorld().getPlayers()) {
                if (other.equals(movingPlayer)) continue;
                double dx = other.getLocation().getX() - movingPlayer.getLocation().getX();
                double dy = other.getLocation().getY() - movingPlayer.getLocation().getY();
                double dz = other.getLocation().getZ() - movingPlayer.getLocation().getZ();
                if (dx * dx + dy * dy + dz * dz > 25600) continue;
                setPlayerVisibility(other, movingPlayer, true);
            }
            return;
        }

        for (Player other : movingPlayer.getWorld().getPlayers()) {
            if (other.equals(movingPlayer)) continue;
            double dx = other.getLocation().getX() - movingPlayer.getLocation().getX();
            double dy = other.getLocation().getY() - movingPlayer.getLocation().getY();
            double dz = other.getLocation().getZ() - movingPlayer.getLocation().getZ();
            if (dx * dx + dy * dy + dz * dz > 25600) continue;

            boolean shouldHide = !plugin.isBlockVisible(other.getUniqueId(), ex, ey, ez);
            setPlayerVisibility(other, movingPlayer, !shouldHide);
        }
    }

    private void setEntityVisibility(Player viewer, Entity target, boolean visible) {
        if (target instanceof Player targetPlayer) {
            setPlayerVisibility(viewer, targetPlayer, visible);
        } else {
            if (visible) {
                viewer.showEntity(plugin, target);
            } else {
                viewer.hideEntity(plugin, target);
            }
        }
    }

    private void setPlayerVisibility(Player viewer, Player target, boolean visible) {
        if (visible) {
            viewer.showPlayer(plugin, target);
            plugin.setHidden(viewer.getUniqueId(), target.getUniqueId(), false);
        } else {
            viewer.hidePlayer(plugin, target);
            plugin.setHidden(viewer.getUniqueId(), target.getUniqueId(), true);
        }
    }

    public void cleanupPlayer(UUID uuid) {
        bfsContexts.remove(uuid);
        lastBFSPosition.remove(uuid);
        lastUpdateTick.remove(uuid);
        playerVisibleSections.remove(uuid);
    }
}
