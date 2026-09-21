package mikey.me.antiBase;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

public final class AntibaseCommand implements TabExecutor {
    private static final List<String> SUBCOMMANDS = List.of("status", "debug", "enable", "disable");
    private final AntiBase plugin;

    public AntibaseCommand(AntiBase plugin) { this.plugin = plugin; }

    private boolean allowed(CommandSender sender, String subcommand) {
        return sender.hasPermission("antibase.admin") || (subcommand.equals("debug") && sender.hasPermission("antibase.debug"));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        String subcommand = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("debug") && (args.length > 1 || !(sender instanceof Player))) {
            return consoleDebug(sender, args);
        }
        if (!SUBCOMMANDS.contains(subcommand) || args.length > 1) {
            sender.sendRichMessage("<gray>Usage: /antibase <status|debug|enable|disable>");
            return true;
        }
        if (!allowed(sender, subcommand)) {
            sender.sendRichMessage("<red>You don't have permission to use this command.");
            return true;
        }
        switch (subcommand) {
            case "debug" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendRichMessage("<red>This command can only be used by players.");
                    return true;
                }
                boolean enabled = !plugin.isDebugEnabled(player.getUniqueId());
                plugin.setDebug(player.getUniqueId(), enabled);
                player.sendRichMessage("<gray>[AntiBase] Debug mode: " + (enabled ? "<green>enabled" : "<red>disabled"));
            }
            case "enable", "disable" -> {
                boolean enabled = subcommand.equals("enable");
                plugin.setObfuscationEnabled(enabled);
                sender.sendRichMessage("<gray>[AntiBase] Obfuscation: " + (enabled ? "<green>enabled" : "<red>disabled")
                        + "<gray>. Loaded client chunks will refresh in batches.");
            }
            case "status" -> {
                sender.sendRichMessage("<gray>[AntiBase] Obfuscation: "
                        + (plugin.isObfuscationEnabled() ? "<green>enabled" : "<red>disabled"));
                sender.sendMessage("[AntiBase] mode=AIR+SUPPRESS consoleDebug=" + plugin.diagnostics().status()
                        + " " + plugin.getMovementListener().queueStatus());
            }
        }
        return true;
    }

    private boolean consoleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("antibase.admin")) {
            sender.sendRichMessage("<red>You don't have permission to control console debugging.");
            return true;
        }
        String operation = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if ((!List.of("on", "off", "status").contains(operation)) || args.length > 3
                || (!operation.equals("on") && args.length > 2)) {
            sender.sendMessage("Usage: /antibase debug on [player] | off | status");
            return true;
        }
        if (operation.equals("status")) {
            sender.sendMessage("[AntiBase] Console debug " + plugin.diagnostics().status()
                    + ". Use /antibase debug on [player] to capture a reproduction.");
            return true;
        }
        Player target = args.length == 3 ? plugin.getServer().getPlayerExact(args[2]) : null;
        if (args.length == 3 && target == null) {
            sender.sendMessage("[AntiBase] Player must be online: " + args[2]);
            return true;
        }
        if (operation.equals("off")) {
            // keep the last interaction even if capture ends before the next log interval
            plugin.diagnostics().flush(plugin.getLogger(), plugin.getMovementListener().queueStatus());
        }
        plugin.diagnostics().configure(operation.equals("on"), target == null ? null : target.getUniqueId(),
                target == null ? "all players" : target.getName());
        plugin.getLogger().info("[debug] " + plugin.diagnostics().status() + " AntiBase=" + plugin.getPluginMeta().getVersion()
                + " server=" + plugin.getServer().getVersion() + " mode=AIR+SUPPRESS");
        sender.sendMessage("[AntiBase] Console debug " + plugin.diagnostics().status()
                + ". Capture [AntiBase] lines from logs/latest.log; /antibase debug off ends capture.");
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                              @NotNull String alias, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("antibase.admin")) {
            if (args.length == 2) return List.of("on", "off", "status").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            if (args.length == 3 && args[1].equalsIgnoreCase("on")) return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(prefix) && allowed(sender, sub)).toList();
    }
}
