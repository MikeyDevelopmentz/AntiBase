package mikey.me.antiBase;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageEvent;

public final class MiningListener implements Listener {
    private final AntiBase plugin;
    private final BaseObfuscator obfuscator;

    public MiningListener(AntiBase plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        plugin.getMovementListener().protectInteraction(event.getPlayer(), event.getBlock(), "break", event.isCancelled());
        if (!event.isCancelled()) refreshNear(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(BlockDamageEvent event) {
        plugin.getMovementListener().protectInteraction(event.getPlayer(), event.getBlock(), "damage", event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        plugin.getMovementListener().protectInteraction(event.getPlayer(), event.getBlock(), "place", event.isCancelled());
        if (!event.isCancelled()) refreshNear(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            refreshNear(event.getClickedBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRedstone(BlockRedstoneEvent event) {
        if (event.getOldCurrent() != event.getNewCurrent()) refreshNear(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        refreshNear(event.getBlock().getLocation());
        event.getBlocks().forEach(block -> refreshNear(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        refreshNear(event.getBlock().getLocation());
        event.getBlocks().forEach(block -> refreshNear(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!event.isCancelled()) plugin.getMovementListener().queueExplosion(event.getLocation().getWorld(), event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!event.isCancelled()) plugin.getMovementListener().queueExplosion(event.getBlock().getWorld(), event.blockList());
    }

    private void refreshNear(Location location) {
        if (!plugin.isObfuscationEnabled() || obfuscator.isWorldBlacklisted(location.getWorld())) return;
        if (!obfuscator.usesConnectedRendering() && location.getY() >= (long) obfuscator.getHideBelowY() + obfuscator.getScanRadius()) return;
        double radius = obfuscator.getScanRadius() + 16;
        // handled on the next scheduler tick, after the edit actually lands
        for (Player viewer : location.getWorld().getPlayers()) {
            Location eye = viewer.getEyeLocation();
            boolean affected = obfuscator.usesConnectedRendering()
                    ? ConnectedVisibilityScanner.Window.around(eye.getX(), eye.getY(), eye.getZ(), location.getWorld().getMinHeight(),
                            location.getWorld().getMaxHeight(), obfuscator.getScanRadius()).contains(location.getBlockX(), location.getBlockY(), location.getBlockZ())
                    : viewer.getLocation().distanceSquared(location) <= radius * radius;
            if (affected) {
                plugin.getMovementListener().invalidateVisibility(viewer);
            }
        }
    }
}
