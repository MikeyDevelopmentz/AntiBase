package mikey.me.antiBase;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.Plugin;

public class ObfuscationTask extends BukkitRunnable {
    private final BaseObfuscator obfuscator;
    private final Plugin plugin;

    public ObfuscationTask(Plugin plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateSurroundingBlocks(player);
            updateEntities(player);
        }
    }

    private void updateSurroundingBlocks(Player player) {
        World world = player.getWorld();
        org.bukkit.Location playerLoc = player.getLocation();
        org.bukkit.Chunk center = playerLoc.getChunk();
        int range = 2;

        for (int x = -range; x <= range; x++) {
            for (int z = -range; z <= range; z++) {
                int cx = center.getX() + x;
                int cz = center.getZ() + z;

                if (!world.isChunkLoaded(cx, cz))
                    continue;
                org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
                long chunkKey = chunk.getChunkKey();

                // calcuate chunk center shit
                int chunkCenterX = (cx << 4) + 8;
                int chunkCenterZ = (cz << 4) + 8;
                double distSq = Math.pow(playerLoc.getX() - chunkCenterX, 2)
                        + Math.pow(playerLoc.getZ() - chunkCenterZ, 2);
                boolean isFar = distSq > Math.pow(obfuscator.getProximityDistance(), 2); // Buffer

                boolean currentlyObscured = obfuscator.isObscured(player.getUniqueId(), chunkKey);

                if (isFar && !currentlyObscured) {
                    obfuscator.setObscured(player.getUniqueId(), chunkKey, true);
                    processChunk(player, chunk, true);
                } else if (!isFar && currentlyObscured) {
                    obfuscator.setObscured(player.getUniqueId(), chunkKey, false);
                    processChunk(player, chunk, false);
                }
            }
        }
    }

    private void processChunk(Player player, org.bukkit.Chunk chunk, boolean hide) {
        org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        World world = player.getWorld();
        int cx = chunk.getX();
        int cz = chunk.getZ();

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            if (!player.isOnline())
                return;

            java.util.Map<org.bukkit.Location, org.bukkit.block.data.BlockData> changes = new java.util.HashMap<>();
            int minY = world.getMinHeight();
            int maxY = obfuscator.getHideBelowY();

            for (int by = minY; by <= maxY; by++) {
                for (int bx = 0; bx < 16; bx++) {
                    for (int bz = 0; bz < 16; bz++) {
                        Material type = snapshot.getBlockType(bx, by, bz);

                        // optmize by not spamming blocks like a retard
                        if (type == Material.STONE || type == Material.DEEPSLATE || type == Material.TUFF)
                            continue;

                        int absX = (cx << 4) + bx;
                        int absZ = (cz << 4) + bz;

                        if (hide) {
                            if (obfuscator.shouldObfuscate(type, absX, by, absZ, player)) {
                                changes.put(new org.bukkit.Location(world, absX, by, absZ),
                                        obfuscator.getReplacementBlock().createBlockData());
                            }
                        } else {
                            // only reveal if needed
                            changes.put(new org.bukkit.Location(world, absX, by, absZ),
                                    snapshot.getBlockData(bx, by, bz));
                        }
                    }
                }
            }

            if (!changes.isEmpty()) {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMultiBlockChange(changes);
                    }
                });
            }
        });
    }

    private void updateEntities(Player player) {
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(64, 64, 64)) {
            if (entity instanceof org.bukkit.entity.LivingEntity) {
                boolean shouldHide = obfuscator.shouldHideEntity(entity, player);
                boolean currentlyHidden = !player.canSee(entity);

                if (shouldHide && !currentlyHidden) {
                    player.hideEntity(plugin, entity);
                } else if (!shouldHide && currentlyHidden) {
                    player.showEntity(plugin, entity);
                }
            }
        }
    }
}
