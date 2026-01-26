package mikey.me.antiBase;

import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import java.util.UUID;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;

public class PacketHandler extends PacketListenerAbstract {
    private final BaseObfuscator obfuscator;
    private final Plugin plugin;

    public PacketHandler(Plugin plugin, BaseObfuscator obfuscator) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        try {
            Player player = (Player) event.getPlayer();
            if (player == null) return;
            if (obfuscator.isWorldBlacklisted(player.getWorld())) return;
            UUID playerId = player.getUniqueId();
            PacketTypeCommon type = event.getPacketType();

            if (type.getName().equals(PacketType.Play.Server.CHUNK_DATA.getName())) {
                WrapperPlayServerChunkData chunkData = new WrapperPlayServerChunkData(event);
                Column column = chunkData.getColumn();
                if (column != null) {
                    BaseChunk[] chunks = column.getChunks();
                    if (chunks != null) {
                        boolean modified = false;
                        int hideBelow = obfuscator.getHideBelowY();
                        int minHeight = player.getWorld().getMinHeight();
                        int chunkX = column.getX();
                        int chunkZ = column.getZ();
                        int playerChunkX = player.getLocation().getBlockX() >> 4;
                        int playerChunkZ = player.getLocation().getBlockZ() >> 4;
                        int playerY = player.getLocation().getBlockY();
                        
                        int distX = Math.abs(chunkX - playerChunkX);
                        int distZ = Math.abs(chunkZ - playerChunkZ);
                        boolean isNearChunk = (distX <= 1 && distZ <= 1);

                        for (int i = 0; i < chunks.length; i++) {
                            BaseChunk section = chunks[i];
                            if (section == null) continue;
                            
        
                            int sectionBaseY = minHeight + (i * 16);
                            int sectionMaxY = sectionBaseY + 16;

                            if (sectionMaxY <= hideBelow) {

                                boolean isNearY = (playerY >= sectionBaseY - 32 && playerY <= sectionMaxY + 32);
                                
                                if (isNearChunk && isNearY) {
                                    continue; 
                                }

                                if (plugin instanceof AntiBase && !((AntiBase) plugin).isSectionVisible(playerId, chunkX, sectionBaseY >> 4, chunkZ)) {
                                    clearChunkSection(section);
                                    modified = true;
                                }
                            }
                        }
                        if (modified) {
                            chunkData.setColumn(column);
                            chunkData.write();
                        }
                    }
                }
                return;
            }

            if (type.getName().equals(PacketType.Play.Server.BLOCK_CHANGE.getName())) {
                WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(event);
                handleSingleBlockUpdate(player, blockChange.getBlockPosition().getX(), blockChange.getBlockPosition().getY(), blockChange.getBlockPosition().getZ(), blockChange);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error in PacketHandler: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleSingleBlockUpdate(Player player, int bx, int by, int bz, WrapperPlayServerBlockChange packet) {
        int hideBelow = obfuscator.getHideBelowY();
        if (by <= hideBelow) {
            int proximity = obfuscator.getProximityDistance();
            int dx = (int) (player.getLocation().getX() - bx);
            int dy = (int) (player.getLocation().getY() - by);
            int dz = (int) (player.getLocation().getZ() - bz);
            int proximityLimit = Math.max(proximity, 64);
            if (dx * dx + dy * dy + dz * dz > proximityLimit * proximityLimit) {
                Material replacement = obfuscator.getReplacementBlock();
                packet.setBlockState(SpigotConversionUtil.fromBukkitBlockData(replacement.createBlockData()));
            }
        }
    }

    private void clearChunkSection(BaseChunk section) {
        try {
            Material replacement = obfuscator.getReplacementBlock();
            int globalId = SpigotConversionUtil.fromBukkitBlockData(replacement.createBlockData()).getGlobalId();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        section.set(x, y, z, globalId);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error clearing chunk section: " + e.getMessage());
        }
    }
}
