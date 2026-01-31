package mikey.me.antiBase;

import org.bukkit.block.Block;
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

        plugin.getMovementListener().updateVisibility(event.getPlayer());

        int radius = 2; 
        for (int x = -radius; x <= radius; x++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && dy == 0 && z == 0) continue; 
                    Block neighbor = block.getRelative(x, dy, z);
                    if (!neighbor.getType().isAir()) {
                        event.getPlayer().sendBlockChange(neighbor.getLocation(), neighbor.getBlockData());
                    }
                }
            }
        }
    }
}