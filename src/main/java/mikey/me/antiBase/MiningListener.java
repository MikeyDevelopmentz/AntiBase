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
        Block block = event.getBlock();
        int y = block.getY();
        int hideBelow = obfuscator.getHideBelowY();
        int buffer = obfuscator.getProximityDistance();
        if (y > (hideBelow + buffer + 5)) return;
        BlockFace[] faces = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : faces) {
            Block neighbor = block.getRelative(face);
            if (!neighbor.getType().isAir()) {
                 event.getPlayer().sendBlockChange(neighbor.getLocation(), neighbor.getBlockData());
            }
        }
    }
}
