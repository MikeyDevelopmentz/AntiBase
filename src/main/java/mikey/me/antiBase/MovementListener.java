package mikey.me.antiBase;

import org.bukkit.Location;
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
    private final Map<UUID, Set<Long>> playerVisibleSections = new ConcurrentHashMap<>();

    public MovementListener(AntiBase plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    private void update(Location to, Location from, Player player) {
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ() && from.getBlockY() == to.getBlockY()) return;
        if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
        long currentTick = player.getWorld().getFullTime();
        long lastUpdate = lastUpdateTick.getOrDefault(player.getUniqueId(), 0L);
        if (currentTick - lastUpdate < 2) return;
        lastUpdateTick.put(player.getUniqueId(), currentTick);
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
        visitedBlocks.add(plugin.packCoord(startX, startY, startZ));
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
                newVisibleSections.add(plugin.packSection(x >> 4, y >> 4, z >> 4));
                visibleBlocks.add(plugin.packCoord(x, y, z));
            }
            for (int[] n : NEIGHBORS) {
                int nx = x + n[0], ny = y + n[1], nz = z + n[2];
                if (ny < minHeight || ny >= maxHeight) continue;
                long key = plugin.packCoord(nx, ny, nz);
                if (visitedBlocks.add(key)) {
                    int cx = nx >> 4;
                    int cz = nz >> 4;
                    if (!world.isChunkLoaded(cx, cz)) continue;
                    long chunkKey = ((long)cx << 32) | (cz & 0xFFFFFFFFL);
                    org.bukkit.Chunk chunk = chunkCache.computeIfAbsent(chunkKey, k -> world.getChunkAt(cx, cz));
                    org.bukkit.block.Block block = chunk.getBlock(nx & 15, ny, nz & 15);
                    if (ny < hideBelow) {
                        newVisibleSections.add(plugin.packSection(cx, ny >> 4, cz));
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
        plugin.setVisibleBlocks(player.getUniqueId(), visibleBlocks);
        updateEntitiesVisibility(player, visibleBlocks);
        Set<String> refreshedChunks = new HashSet<>();
        Set<Long> oldVisibleSet = playerVisibleSections.getOrDefault(player.getUniqueId(), java.util.Collections.emptySet());
        for (Long sectionKey : newVisibleSections) {
            int[] coords = plugin.unpackSection(sectionKey);
            if (!plugin.isSectionVisible(player.getUniqueId(), coords[0], coords[1], coords[2])) {
                plugin.updateSectionVisibility(player.getUniqueId(), coords[0], coords[1], coords[2], true);
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
                int[] coords = plugin.unpackSection(oldKey);
                plugin.updateSectionVisibility(player.getUniqueId(), coords[0], coords[1], coords[2], false);
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
        if (plugin.isDebugEnabled(player.getUniqueId()) && !refreshedChunks.isEmpty()) {
            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gray>[<gold>AntiBase</gold>]</gray> <green>Visible:</green> <yellow>" + visitedBlocks.size() + "</yellow> <gray>|</gray> <green>Sections:</green> <yellow>" + newVisibleSections.size() + "</yellow>"
            ));
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
            boolean shouldHide = ey < hideBelow && !visibleBlocks.contains(plugin.packCoord(ex, ey, ez));
            setEntityVisibility(player, e, !shouldHide);
        }
    }

    public void updateOthersViewOfPlayer(Player movingPlayer) {
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

            boolean shouldHide = ey < hideBelow && !plugin.isBlockVisible(other.getUniqueId(), ex, ey, ez);
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
}
