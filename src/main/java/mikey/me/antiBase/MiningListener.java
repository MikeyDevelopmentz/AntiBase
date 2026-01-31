package mikey.me.antiBase;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public class MiningListener implements Listener {
    private final AntiBase plugin;
    private final BaseObfuscator obfuscator;

    public MiningListener(AntiBase plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.isObfuscationEnabled()) return;
        if (obfuscator.isWorldBlacklisted(event.getBlock().getWorld())) return;
        Block block = event.getBlock();
        int y = block.getY();
        int hideBelow = obfuscator.getHideBelowY();
        int buffer = obfuscator.getProximityDistance();
        
        if (y > (hideBelow + buffer + 16)) return;

        Player player = event.getPlayer();
        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;
        int sectionY = block.getY() >> 4;
        plugin.updateSectionVisibility(player.getUniqueId(), chunkX, sectionY, chunkZ, true);

        player.getScheduler().runDelayed(plugin, (task) -> {
            int radius = 2;
            for (int x = -radius; x <= radius; x++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (x == 0 && dy == 0 && z == 0) continue;
                        Block neighbor = block.getRelative(x, dy, z);
                        if (!neighbor.getType().isAir()) {
                            player.sendBlockChange(neighbor.getLocation(), neighbor.getBlockData());
                        }
                    }
                }
            }
        }, null, 2L);
    }
}