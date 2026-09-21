package mikey.me.antiBase;

import org.bukkit.entity.Player;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;

public final class EntityListener implements Listener {
    private final AntiBase plugin;
    private final BaseObfuscator obfuscator;

    public EntityListener(AntiBase plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (!plugin.isObfuscationEnabled() || obfuscator.isWorldBlacklisted(event.getLocation().getWorld())) return;
        for (Player viewer : event.getLocation().getWorld().getPlayers()) {
            if (!viewer.equals(event.getEntity())) {
                plugin.getMovementListener().updateEntityForViewer(viewer, event.getEntity());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockChange(EntityChangeBlockEvent event) {
        // paper fires this BEFORE the source turns to air and before the landing block exists
        if (event.isCancelled() || !(event.getEntity() instanceof FallingBlock)
                || !plugin.isObfuscationEnabled() || obfuscator.isWorldBlacklisted(event.getBlock().getWorld())) return;
        plugin.getMovementListener().queueFallingBlockChange(event.getBlock(), event.getBlockData());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        if (event.isCancelled() || !plugin.isObfuscationEnabled()
                || obfuscator.isWorldBlacklisted(event.getPlayer().getWorld())) return;
        if (!plugin.getMovementListener().trackEntity(event.getPlayer(), event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntrack(PlayerUntrackEntityEvent event) {
        plugin.getMovementListener().untrackEntity(event.getPlayer(), event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent event) {
        if (event.isCancelled() || event.getTo() == null || !plugin.isObfuscationEnabled()) return;
        plugin.getMovementListener().entityTeleporting(event.getEntity(), event.getTo());
    }
}
