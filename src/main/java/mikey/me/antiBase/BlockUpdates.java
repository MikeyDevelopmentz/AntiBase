package mikey.me.antiBase;

import io.papermc.paper.math.Position;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** per viewer block deltas, server thread only. real state gets resolved at send time */
final class BlockUpdates {
    private final AntiBase plugin;
    private final BaseObfuscator obfuscator;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private BlockData air;

    BlockUpdates(AntiBase plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    void enqueue(UUID player, UUID world, long block) {
        Pending updates = pending.compute(player, (id, old) -> old != null && old.world.equals(world) ? old : new Pending(world));
        long section = Coordinates.section(Coordinates.blockX(block) >> 4, Coordinates.blockY(block) >> 4, Coordinates.blockZ(block) >> 4);
        if (updates.sections.computeIfAbsent(section, key -> new LongHashSet(16)).add(block)) updates.size++;
    }

    int size() { return pending.values().stream().mapToInt(value -> value.size).sum(); }
    void remove(UUID player) { pending.remove(player); }
    void clear() { pending.clear(); }

    /** round robin players, nearest sections first. separate global + per player caps */
    void flush(int totalBudget, int playerBudget) {
        int players = pending.size();
        while (players-- > 0 && totalBudget > 0 && !pending.isEmpty()) {
            var iterator = pending.entrySet().iterator();
            var entry = iterator.next();
            UUID id = entry.getKey();
            Pending updates = entry.getValue();
            iterator.remove();
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline() || !player.getWorld().getUID().equals(updates.world)
                    || !plugin.isObfuscationEnabled()) continue;
            Location eye = player.getEyeLocation();
            List<Long> sections = new ArrayList<>(updates.sections.keySet());
            sections.sort(Comparator.comparingDouble(key -> distanceSquared(key, eye)));
            int limit = Math.min(totalBudget, playerBudget);
            LongHashSet batch = new LongHashSet(limit);
            for (long section : sections) {
                LongHashSet blocks = updates.sections.get(section);
                LongHashSet selected = new LongHashSet(Math.min(limit - batch.size(), blocks.size()));
                blocks.forEach(key -> { if (batch.size() + selected.size() < limit) selected.add(key); });
                selected.forEach(key -> { blocks.remove(key); batch.add(key); });
                if (blocks.size() == 0) updates.sections.remove(section);
                if (batch.size() >= limit) break;
            }
            updates.size -= batch.size();
            totalBudget -= batch.size();
            // sendNow reads chunks so it has to run on the players region
            if (plugin.getServer().isOwnedByCurrentRegion(player)) sendNow(player, batch);
            else player.getScheduler().run(plugin, task -> sendNow(player, batch), null);
            if (plugin.diagnostics().accepts(id)) plugin.diagnostics().sample(id,
                    "deltaAgeMs=" + ((System.nanoTime() - updates.started) / 1_000_000)
                            + " processed=" + batch.size() + " remaining=" + updates.size);
            if (updates.size > 0) pending.put(id, updates);
        }
    }

    /** urgent corrections go through this too, even for blocks already shown */
    void sendNow(Player player, LongHashSet blocks) {
        UUID id = player.getUniqueId();
        World world = player.getWorld();
        ViewerState state = plugin.getViewerState(id);
        if (state == null || !state.worldId().equals(world.getUID())) return;
        Set<Long> sent = player.getSentChunkKeys();
        Map<Position, BlockData> changes = new HashMap<>();
        Map<Long, Chunk> chunks = new HashMap<>();
        LongHashSet revealed = new LongHashSet(16);
        blocks.forEach(key -> {
            int x = Coordinates.blockX(key), y = Coordinates.blockY(key), z = Coordinates.blockZ(key);
            int cx = x >> 4, cz = z >> 4;
            if (y < world.getMinHeight() || y >= world.getMaxHeight()
                    || !sent.contains((cx & 0xFFFFFFFFL) | ((long) cz << 32))
                    || !world.isChunkLoaded(cx, cz)) return;
            boolean visible = !plugin.isObfuscationEnabled() || !state.shouldHide(x, y, z, obfuscator.getHideBelowY())
                    || plugin.interactions().allows(id, state.worldId(), x, y, z);
            // dont skip reveals based on after-send tracking. an earlier AIR clear can still
            // be sitting in the connection queue, packet order has to survive
            Position position = Position.block(x, y, z);
            if (visible) {
                Chunk chunk = chunks.computeIfAbsent(Coordinates.chunk(cx, cz), ignored -> world.getChunkAt(cx, cz));
                changes.put(position, chunk.getBlock(x & 15, y, z & 15).getBlockData());
                revealed.add(key);
            } else {
                if (air == null) air = plugin.getServer().createBlockData(Material.AIR);
                changes.put(position, air);
            }
        });
        if (changes.isEmpty()) return;
        player.sendMultiBlockChange(changes);
        // keep sign/chest/etc metadata without resending the whole chunk to everyone
        for (Chunk chunk : chunks.values()) for (BlockState tile : chunk.getTileEntities(false)) {
            if (tile instanceof TileState tileState && revealed.contains(Coordinates.block(tile.getX(), tile.getY(), tile.getZ()))) {
                player.sendBlockUpdate(tile.getLocation(), tileState);
            }
        }
        plugin.diagnostics().count(id, ConsoleDebug.Metric.DELTA_BLOCKS, changes.size());
    }

    private static double distanceSquared(long section, Location eye) {
        double dx = Coordinates.sectionX(section) * 16.0 + 8 - eye.getX();
        double dy = Coordinates.sectionY(section) * 16.0 + 8 - eye.getY();
        double dz = Coordinates.sectionZ(section) * 16.0 + 8 - eye.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static final class Pending {
        final UUID world;
        final Map<Long, LongHashSet> sections = new HashMap<>();
        final long started = System.nanoTime();
        int size;
        Pending(UUID world) { this.world = world; }
    }
}
