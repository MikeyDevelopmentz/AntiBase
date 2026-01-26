package mikey.me.antiBase;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;

public class MiningListener implements Listener {
    private final Plugin plugin;
    private final BaseObfuscator obfuscator;

    public MiningListener(Plugin plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (!(plugin instanceof AntiBase)) return;
        if (obfuscator.isWorldBlacklisted(event.getBlock().getWorld())) return;
        Block block = event.getBlock();
        int y = block.getY();
        int hideBelow = obfuscator.getHideBelowY();
        int buffer = obfuscator.getProximityDistance();
        

        if (y > (hideBelow + buffer + 16)) return;
        ((AntiBase) plugin).getMovementListener().updateVisibility(event.getPlayer());
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
