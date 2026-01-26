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
import java.util.Map;
import java.util.HashMap;

public class MovementListener implements Listener {
    private static final int[][] NEIGHBORS = {{1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}};
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
        Player player = event.getPlayer();
        if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
        long currentTick = player.getWorld().getFullTime();
        long lastUpdate = lastUpdateTick.getOrDefault(player.getUniqueId(), 0L);
        if (currentTick - lastUpdate < 2) return;
        lastUpdateTick.put(player.getUniqueId(), currentTick);
        updateVisibility(player);
        updateOthersViewOfPlayer(player);
    }

    public void updateVisibility(Player player) {
        if (!(plugin instanceof AntiBase)) return;
        AntiBase antiBase = (AntiBase) plugin;
        if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
        int hideBelow = obfuscator.getHideBelowY();
        Location playerLoc = player.getLocation();
        org.bukkit.World world = player.getWorld();
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        int maxBlocks = 100000;
        Set<Long> newVisibleSections = new HashSet<>();
        Set<Long> visitedBlocks = new HashSet<>(maxBlocks * 2);
        Set<Long> visibleBlocks = new HashSet<>();
        int queueCapacity = maxBlocks;
        int[] queueX = new int[queueCapacity];
        int[] queueY = new int[queueCapacity];
        int[] queueZ = new int[queueCapacity];
        int head = 0;
        int tail = 0;
        int size = 0;
        int startX = playerLoc.getBlockX();
        int startY = Math.max(minHeight, Math.min(maxHeight - 1, playerLoc.getBlockY()));
        int startZ = playerLoc.getBlockZ();
        queueX[tail] = startX;
        queueY[tail] = startY;
        queueZ[tail] = startZ;
        tail = (tail + 1) % queueCapacity;
        size++;
        visitedBlocks.add(AntiBase.packCoord(startX, startY, startZ));
        int maxDistance = 160;
        int maxDistSq = maxDistance * maxDistance;
        int blocksChecked = 0;
        Map<Long, org.bukkit.Chunk> chunkCache = new HashMap<>();
        while (size > 0 && blocksChecked < maxBlocks) {
            int x = queueX[head];
            int y = queueY[head];
            int z = queueZ[head];
            head = (head + 1) % queueCapacity;
            size--;
            int dx = x - startX;
            int dy = y - startY;
            int dz = z - startZ;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;
            if (y < hideBelow) {
                newVisibleSections.add(packSection(x >> 4, y >> 4, z >> 4));
                visibleBlocks.add(AntiBase.packCoord(x, y, z));
            }
            for (int[] n : NEIGHBORS) {
                int nx = x + n[0], ny = y + n[1], nz = z + n[2];
                if (ny < minHeight || ny >= maxHeight) continue;
                long key = AntiBase.packCoord(nx, ny, nz);
                if (visitedBlocks.add(key)) {
                    int cx = nx >> 4;
                    int cz = nz >> 4;
                    if (!world.isChunkLoaded(cx, cz)) continue;
                    long chunkKey = ((long)cx << 32) | (cz & 0xFFFFFFFFL);
                    org.bukkit.Chunk chunk = chunkCache.computeIfAbsent(chunkKey, k -> world.getChunkAt(cx, cz));
                    org.bukkit.block.Block block = chunk.getBlock(nx & 15, ny, nz & 15);
                    if (ny < hideBelow) {
                        newVisibleSections.add(packSection(cx, ny >> 4, cz));
                    }
                    if (!block.getType().isOccluding() || block.getLightFromSky() == 15) {
                        if (size < queueCapacity) {
                            queueX[tail] = nx;
                            queueY[tail] = ny;
                            queueZ[tail] = nz;
                            tail = (tail + 1) % queueCapacity;
                            size++;
                        }
                    }
                }
            }
            blocksChecked++;
        }
        antiBase.setVisibleBlocks(player.getUniqueId(), visibleBlocks);
        updateEntitiesVisibility(player, visibleBlocks);
        Set<String> refreshedChunks = new HashSet<>();
        Set<Long> oldVisibleSet = playerVisibleSections.getOrDefault(player.getUniqueId(), java.util.Collections.emptySet());
        for (Long sectionKey : newVisibleSections) {
            int[] coords = unpackSection(sectionKey);
            if (!antiBase.isSectionVisible(player.getUniqueId(), coords[0], coords[1], coords[2])) {
                antiBase.updateSectionVisibility(player.getUniqueId(), coords[0], coords[1], coords[2], true);
                String chunkKey = coords[0] + "," + coords[2];
                if (!refreshedChunks.contains(chunkKey)) {
                    if (world.isChunkLoaded(coords[0], coords[2])) {
                        world.refreshChunk(coords[0], coords[2]);
                        refreshedChunks.add(chunkKey);
                    }
                }
            }
        }
        for (Long oldKey : oldVisibleSet) {
            if (!newVisibleSections.contains(oldKey)) {
                int[] coords = unpackSection(oldKey);
                antiBase.updateSectionVisibility(player.getUniqueId(), coords[0], coords[1], coords[2], false);
                String chunkKey = coords[0] + "," + coords[2];
                if (!refreshedChunks.contains(chunkKey)) {
                    if (world.isChunkLoaded(coords[0], coords[2])) {
                        world.refreshChunk(coords[0], coords[2]);
                        refreshedChunks.add(chunkKey);
                    }
                }
            }
        }
        playerVisibleSections.put(player.getUniqueId(), newVisibleSections);
        if (antiBase.isDebugEnabled(player.getUniqueId()) && !refreshedChunks.isEmpty()) {
            player.sendActionBar(String.format("§7[§6AntiBase§7] §aVisible: §e%d §7| Sections: §e%d", visitedBlocks.size(), newVisibleSections.size()));
        }
    }

    private void updateEntitiesVisibility(Player player, Set<Long> visibleBlocks) {
        if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
        int hideBelow = obfuscator.getHideBelowY();
        for (Entity e : player.getNearbyEntities(160, 160, 160)) {
            if (e.equals(player)) continue;
            int ex = e.getLocation().getBlockX();
            int ey = e.getLocation().getBlockY();
            int ez = e.getLocation().getBlockZ();
            if (ey < hideBelow) {
                if (!visibleBlocks.contains(AntiBase.packCoord(ex, ey, ez))) {
                    if (e instanceof Player) player.hidePlayer(plugin, (Player) e);
                    else player.hideEntity(plugin, e);
                } else {
                    if (e instanceof Player) player.showPlayer(plugin, (Player) e);
                    else player.showEntity(plugin, e);
                }
            } else {
                if (e instanceof Player) player.showPlayer(plugin, (Player) e);
                else player.showEntity(plugin, e);
            }
        }
    }

    public void updateOthersViewOfPlayer(Player movingPlayer) {
        if (!(plugin instanceof AntiBase)) return;
        AntiBase antiBase = (AntiBase) plugin;
        if (obfuscator.isWorldBlacklisted(movingPlayer.getWorld())) return;
        int hideBelow = obfuscator.getHideBelowY();
        int ex = movingPlayer.getLocation().getBlockX();
        int ey = movingPlayer.getLocation().getBlockY();
        int ez = movingPlayer.getLocation().getBlockZ();

        for (Player other : movingPlayer.getWorld().getPlayers()) {
            if (other.equals(movingPlayer)) continue;
            double dx = other.getLocation().getX() - movingPlayer.getLocation().getX();
            double dy = other.getLocation().getY() - movingPlayer.getLocation().getY();
            double dz = other.getLocation().getZ() - movingPlayer.getLocation().getZ();
            if (dx * dx + dy * dy + dz * dz > 25600) continue;

            if (ey < hideBelow) {
                if (!antiBase.isBlockVisible(other.getUniqueId(), ex, ey, ez)) {
                    other.hidePlayer(plugin, movingPlayer);
                } else {
                    other.showPlayer(plugin, movingPlayer);
                }
            } else {
                other.showPlayer(plugin, movingPlayer);
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
