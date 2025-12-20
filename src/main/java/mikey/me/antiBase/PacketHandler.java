package mikey.me.antiBase;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class PacketHandler extends PacketAdapter {
    private final BaseObfuscator obfuscator;
    private final Plugin plugin;

    public PacketHandler(Plugin plugin, BaseObfuscator obfuscator) {
        super(plugin, PacketType.Play.Server.BLOCK_CHANGE, PacketType.Play.Server.MULTI_BLOCK_CHANGE,
                PacketType.Play.Server.MAP_CHUNK, PacketType.Play.Server.SPAWN_ENTITY,
                PacketType.Play.Server.NAMED_ENTITY_SPAWN);
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        Player player = event.getPlayer();
        PacketType type = event.getPacketType();

        if (type == PacketType.Play.Server.BLOCK_CHANGE) {
            handleBlockChange(event, player);
        } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            handleMultiBlockChange(event, player);
        } else if (type == PacketType.Play.Server.MAP_CHUNK) {
            handleMapChunk(event, player);
        } else if (type == PacketType.Play.Server.SPAWN_ENTITY || type == PacketType.Play.Server.NAMED_ENTITY_SPAWN) {
            handleSpawnEntity(event, player);
        }
    }

    private void handleBlockChange(PacketEvent event, Player player) {
        PacketContainer packet = event.getPacket();
        BlockPosition pos = packet.getBlockPositionModifier().read(0);
        WrappedBlockData blockData = packet.getBlockData().read(0);
        Material type = blockData.getType();

        if (obfuscator.shouldObfuscate(type, pos.getX(), pos.getY(), pos.getZ(), player)) {
            packet.getBlockData().write(0, WrappedBlockData.createData(obfuscator.getReplacementBlock()));
        }
    }

    private void handleMultiBlockChange(PacketEvent event, Player player) {
    }

    private void handleMapChunk(PacketEvent event, Player player) {
        PacketContainer packet = event.getPacket();
        int cx = packet.getIntegers().read(0);
        int cz = packet.getIntegers().read(1);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline())
                return;
            World world = player.getWorld();
            if (!world.isChunkLoaded(cx, cz))
                return;
            Chunk chunk = world.getChunkAt(cx, cz);
            long chunkKey = chunk.getChunkKey();

            // calcuations
            org.bukkit.Location playerLoc = player.getLocation();
            int chunkCenterX = (cx << 4) + 8;
            int chunkCenterZ = (cz << 4) + 8;
            double distSq = Math.pow(playerLoc.getX() - chunkCenterX, 2) + Math.pow(playerLoc.getZ() - chunkCenterZ, 2);
            boolean isFar = distSq > Math.pow(obfuscator.getProximityDistance(), 2);

            if (isFar) {
                // process right now if needed
                obfuscator.setObscured(player.getUniqueId(), chunkKey, true);

                org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    if (!player.isOnline())
                        return;
                    java.util.Map<org.bukkit.Location, org.bukkit.block.data.BlockData> changes = new java.util.HashMap<>();
                    int minY = world.getMinHeight();
                    int maxY = obfuscator.getHideBelowY();

                    for (int by = minY; by <= maxY; by++) {
                        for (int bx = 0; bx < 16; bx++) {
                            for (int bz = 0; bz < 16; bz++) {
                                if (obfuscator.shouldObfuscate(snapshot.getBlockType(bx, by, bz), (cx << 4) + bx, by,
                                        (cz << 4) + bz, player)) {
                                    changes.put(new org.bukkit.Location(world, (cx << 4) + bx, by, (cz << 4) + bz),
                                            obfuscator.getReplacementBlock().createBlockData());
                                }
                            }
                        }
                    }

                    if (!changes.isEmpty()) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline())
                                player.sendMultiBlockChange(changes);
                        });
                    }
                });
            } else {

                obfuscator.setObscured(player.getUniqueId(), chunkKey, false);
            }
        });
    }

    private void handleSpawnEntity(PacketEvent event, Player player) {
        PacketContainer packet = event.getPacket();

        org.bukkit.entity.Entity entity = packet.getEntityModifier(player.getWorld()).read(0);

        if (entity != null) {
            if (obfuscator.shouldHideEntity(entity, player)) {
                event.setCancelled(true);
                org.bukkit.entity.Entity finalEntity = entity;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.hideEntity(plugin, finalEntity);
                    }
                });
            }
        }
    }
}
