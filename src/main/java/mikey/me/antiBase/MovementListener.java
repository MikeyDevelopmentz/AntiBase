package mikey.me.antiBase;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.Queue;
import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;

public class MovementListener implements Listener {
    private final Plugin plugin;
    private final BaseObfuscator obfuscator;
    private final Map<UUID, Long> lastUpdateTick = new HashMap<>();
    private final Map<UUID, Set<Long>> playerVisibleSections = new HashMap<>();

    public MovementListener(Plugin plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ() && from.getBlockY() == to.getBlockY()) return;
        long currentTick = event.getPlayer().getWorld().getFullTime();
        long lastUpdate = lastUpdateTick.getOrDefault(event.getPlayer().getUniqueId(), 0L);
        if (currentTick - lastUpdate < 2) return;
        lastUpdateTick.put(event.getPlayer().getUniqueId(), currentTick);
        updateVisibility(event.getPlayer());
        updateOthersViewOfPlayer(event.getPlayer());
    }

    public void updateVisibility(Player player) {
        if (!(plugin instanceof AntiBase)) return;
        AntiBase antiBase = (AntiBase) plugin;
        int hideBelow = obfuscator.getHideBelowY();
        Location playerLoc = player.getLocation();
        org.bukkit.World world = player.getWorld();
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        Set<Long> newVisibleSections = new HashSet<>();
        Set<Long> visitedBlocks = new HashSet<>();
        Queue<int[]> queue = new LinkedList<>();
        int startX = playerLoc.getBlockX();
        int startY = Math.max(minHeight, Math.min(maxHeight - 1, playerLoc.getBlockY()));
        int startZ = playerLoc.getBlockZ();
        queue.add(new int[]{startX, startY, startZ});
        visitedBlocks.add(AntiBase.packCoord(startX, startY, startZ));
        int maxDistance = 160;
        int maxDistSq = maxDistance * maxDistance;
        int blocksChecked = 0;
        int maxBlocks = 100000;
        Map<Long, org.bukkit.Chunk> chunkCache = new HashMap<>();
        while (!queue.isEmpty() && blocksChecked < maxBlocks) {
            int[] pos = queue.poll();
            int x = pos[0], y = pos[1], z = pos[2];
            double dSq = Math.pow(x - startX, 2) + Math.pow(y - startY, 2) + Math.pow(z - startZ, 2);
            if (dSq > maxDistSq) continue;
            if (y < hideBelow) newVisibleSections.add(packSection(x >> 4, y >> 4, z >> 4));
            int[][] neighbors = {{1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}};
            for (int[] n : neighbors) {
                int nx = x + n[0], ny = y + n[1], nz = z + n[2];
                if (ny < minHeight || ny >= maxHeight) continue;
                long key = AntiBase.packCoord(nx, ny, nz);
                if (!visitedBlocks.contains(key)) {
                    visitedBlocks.add(key);
                    long chunkKey = ((long)(nx >> 4) << 32) | ((nz >> 4) & 0xFFFFFFFFL);
                    org.bukkit.Chunk chunk = chunkCache.computeIfAbsent(chunkKey, k -> world.getChunkAt(nx >> 4, nz >> 4));
                    org.bukkit.block.Block block = chunk.getBlock(nx & 15, ny, nz & 15);
                    if (ny < hideBelow) newVisibleSections.add(packSection(nx >> 4, ny >> 4, nz >> 4));
                    if (!block.getType().isOccluding() || block.getLightFromSky() == 15) queue.add(new int[]{nx, ny, nz});
                }
            }
            blocksChecked++;
        }
        antiBase.setVisibleBlocks(player.getUniqueId(), visitedBlocks);
        updateEntitiesVisibility(player, visitedBlocks);
        Set<String> refreshedChunks = new HashSet<>();
        Set<Long> oldVisibleSet = playerVisibleSections.getOrDefault(player.getUniqueId(), new HashSet<>());
        for (Long sectionKey : newVisibleSections) {
            int[] coords = unpackSection(sectionKey);
            if (!antiBase.isSectionVisible(player.getUniqueId(), coords[0], coords[1], coords[2])) {
                antiBase.updateSectionVisibility(player.getUniqueId(), coords[0], coords[1], coords[2], true);
                String chunkKey = coords[0] + "," + coords[2];
                if (!refreshedChunks.contains(chunkKey)) { if (world.isChunkLoaded(coords[0], coords[2])) { world.refreshChunk(coords[0], coords[2]); refreshedChunks.add(chunkKey); } }
            }
        }
        for (Long oldKey : oldVisibleSet) {
            if (!newVisibleSections.contains(oldKey)) {
                int[] coords = unpackSection(oldKey);
                antiBase.updateSectionVisibility(player.getUniqueId(), coords[0], coords[1], coords[2], false);
                String chunkKey = coords[0] + "," + coords[2];
                if (!refreshedChunks.contains(chunkKey)) { if (world.isChunkLoaded(coords[0], coords[2])) { world.refreshChunk(coords[0], coords[2]); refreshedChunks.add(chunkKey); } }
            }
        }
        playerVisibleSections.put(player.getUniqueId(), newVisibleSections);
        if (antiBase.isDebugEnabled(player.getUniqueId()) && !refreshedChunks.isEmpty()) {
            player.sendActionBar(String.format("§7[§6AntiBase§7] §aVisible: §e%d §7| Sections: §e%d", visitedBlocks.size(), newVisibleSections.size()));
        }
    }

    private void updateEntitiesVisibility(Player player, Set<Long> visibleBlocks) {
        int hideBelow = obfuscator.getHideBelowY();
        for (Entity e : player.getNearbyEntities(160, 160, 160)) {
            if (e.equals(player)) continue;
            int ex = e.getLocation().getBlockX();
            int ey = e.getLocation().getBlockY();
            int ez = e.getLocation().getBlockZ();
            if (ey < hideBelow) {
                if (!visibleBlocks.contains(AntiBase.packCoord(ex, ey, ez))) {
                    player.hideEntity(plugin, e);
                } else {
                    player.showEntity(plugin, e);
                }
            } else {
                player.showEntity(plugin, e);
            }
        }
    }

    public void updateOthersViewOfPlayer(Player movingPlayer) {
        if (!(plugin instanceof AntiBase)) return;
        AntiBase antiBase = (AntiBase) plugin;
        int hideBelow = obfuscator.getHideBelowY();
        int ex = movingPlayer.getLocation().getBlockX();
        int ey = movingPlayer.getLocation().getBlockY();
        int ez = movingPlayer.getLocation().getBlockZ();

        for (Player other : movingPlayer.getWorld().getPlayers()) {
            if (other.equals(movingPlayer)) continue;
            if (other.getLocation().distanceSquared(movingPlayer.getLocation()) > 25600) continue;

            if (ey < hideBelow) {
                if (!antiBase.isBlockVisible(other.getUniqueId(), ex, ey, ez)) {
                    other.hideEntity(plugin, movingPlayer);
                } else {
                    other.showEntity(plugin, movingPlayer);
                }
            } else {
                other.showEntity(plugin, movingPlayer);
            }
        }
    }

    private long packSection(int chunkX, int sectionY, int chunkZ) { return ((long)(chunkX & 0x3FFFFF) << 42) | ((long)(chunkZ & 0x3FFFFF) << 20) | (sectionY & 0xFF); }
    private int[] unpackSection(long key) {
        int cx = (int)((key >> 42) & 0x3FFFFF);
        int cz = (int)((key >> 20) & 0x3FFFFF);
        int sy = (int)(key & 0xFF);
        if (cx > 0x1FFFFF) cx -= 0x400000;
        if (cz > 0x1FFFFF) cz -= 0x400000;
        if (sy > 127) sy -= 256;
        return new int[]{cx, sy, cz};
    }
}
