package mikey.me.antiBase;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class DebugCommand implements CommandExecutor {
    private final AntiBase plugin;
    public DebugCommand(AntiBase plugin) { this.plugin = plugin; }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("antibase.debug")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }
        boolean newState = !plugin.isDebugEnabled(player.getUniqueId());
        plugin.setDebug(player.getUniqueId(), newState);
        if (newState) {
            player.sendMessage("§a[AntiBase] §7Debug mode §aENABLED");
            player.sendMessage("§7You will see visibility changes in your action bar");
        } else {
            player.sendMessage("§c[AntiBase] §7Debug mode §cDISABLED");
        }
        return true;
    }
}
