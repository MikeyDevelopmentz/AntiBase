package mikey.me.antiBase;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public class EntityListener implements Listener {
    private final AntiBase plugin;
    private final BaseObfuscator obfuscator;

    public EntityListener(AntiBase plugin, BaseObfuscator obfuscator) {
        this.plugin = plugin;
        this.obfuscator = obfuscator;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        updateEntityVisibility(event.getEntity());
    }

    public void updateEntityVisibility(Entity entity) {
        if (!plugin.isObfuscationEnabled()) return;
        if (obfuscator.isWorldBlacklisted(entity.getWorld())) return;
        int hideBelow = obfuscator.getHideBelowY();
        int ex = entity.getLocation().getBlockX();
        int ey = entity.getLocation().getBlockY();
        int ez = entity.getLocation().getBlockZ();
        
        for (Player p : entity.getWorld().getPlayers()) {
            if (p.equals(entity)) continue;
            double dx = p.getLocation().getX() - entity.getLocation().getX();
            double dy = p.getLocation().getY() - entity.getLocation().getY();
            double dz = p.getLocation().getZ() - entity.getLocation().getZ();
            if (dx * dx + dy * dy + dz * dz > 25600) continue;
            if (ey < hideBelow) {
                if (!plugin.isBlockVisible(p.getUniqueId(), ex, ey, ez)) {
                    if (entity instanceof Player) {
                        p.hidePlayer(plugin, (Player) entity);
                        plugin.setHidden(p.getUniqueId(), entity.getUniqueId(), true);
                    } else {
                        p.hideEntity(plugin, entity);
                    }
                } else {
                    if (entity instanceof Player) {
                        p.showPlayer(plugin, (Player) entity);
                        plugin.setHidden(p.getUniqueId(), entity.getUniqueId(), false);
                    } else {
                        p.showEntity(plugin, entity);
                    }
                }
            } else {
                if (entity instanceof Player) {
                    p.showPlayer(plugin, (Player) entity);
                    plugin.setHidden(p.getUniqueId(), entity.getUniqueId(), false);
                } else {
                    p.showEntity(plugin, entity);
                }
            }
        }
    }
}
