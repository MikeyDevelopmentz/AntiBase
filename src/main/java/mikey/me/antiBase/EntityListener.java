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
        int hideBelow = obfuscator.getHideBelowY();
        int ex = entity.getLocation().getBlockX();
        int ey = entity.getLocation().getBlockY();
        int ez = entity.getLocation().getBlockZ();
        
        for (Player p : entity.getWorld().getPlayers()) {
            if (p.equals(entity)) continue;
            if (p.getLocation().distanceSquared(entity.getLocation()) > 25600) continue;
            if (ey < hideBelow) {
                if (!plugin.isBlockVisible(p.getUniqueId(), ex, ey, ez)) {
                    p.hideEntity(plugin, entity);
                } else {
                    p.showEntity(plugin, entity);
                }
            } else {
                p.showEntity(plugin, entity);
            }
        }
    }
}
