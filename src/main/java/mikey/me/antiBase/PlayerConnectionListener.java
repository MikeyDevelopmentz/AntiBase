package mikey.me.antiBase;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerConnectionListener implements Listener {
    private final AntiBase plugin;

    public PlayerConnectionListener(AntiBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.cleanupPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, (task) -> {
            plugin.getMovementListener().updateVisibility(event.getPlayer());
            plugin.getMovementListener().updateOthersViewOfPlayer(event.getPlayer());
        }, null, 5L);
    }
}
