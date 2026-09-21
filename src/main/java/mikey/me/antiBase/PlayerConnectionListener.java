package mikey.me.antiBase;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.ArrayList;
import java.util.List;

public final class PlayerConnectionListener implements Listener {
    private final AntiBase plugin;

    public PlayerConnectionListener(AntiBase plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player leaving = event.getPlayer();
        List<Player> retainedTabEntries = new ArrayList<>();
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (plugin.isHidden(viewer.getUniqueId(), leaving.getUniqueId())) retainedTabEntries.add(viewer);
        }
        plugin.cleanupPlayer(leaving.getUniqueId());
        // hidePlayer already killed normal tracking so clean up the tab entries manually
        for (Player viewer : retainedTabEntries) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                    new WrapperPlayServerPlayerInfoRemove(List.of(leaving.getUniqueId())));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.updatePosition(player, player.getLocation());
        plugin.getMovementListener().updateVisibility(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.getMovementListener().resetPlayer(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getMovementListener().resetPlayer(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.isObfuscationEnabled()) return;
        for (Player player : event.getWorld().getPlayers()) {
            int dx = Math.abs(event.getChunk().getX() - (player.getLocation().getBlockX() >> 4));
            int dz = Math.abs(event.getChunk().getZ() - (player.getLocation().getBlockZ() >> 4));
            if (dx <= 6 && dz <= 6) plugin.getMovementListener().updateVisibility(player);
        }
    }
}
