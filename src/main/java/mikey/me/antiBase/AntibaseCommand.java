package mikey.me.antiBase;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class AntibaseCommand extends Command {
    private final AntiBase plugin;
    public AntibaseCommand(AntiBase plugin) {
        super("antibase");
        this.setDescription("AntiBase debug commands");
        this.setPermission("antibase.debug");
        this.setUsage("/antibase debug");
        this.plugin = plugin;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String raw, String[] args) {
        if (args.length == 0) return true;

        switch (args[0].toLowerCase()) {
            case "debug" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendRichMessage("<red>This command can only be used by players.");
                    return true;
                }
                boolean newState = !plugin.isDebugEnabled(player.getUniqueId());
                plugin.setDebug(player.getUniqueId(), newState);
                if (newState) {
                    player.sendRichMessage("<green>[AntiBase]</green> <gray>Debug mode</gray> <green>ENABLED");
                    player.sendRichMessage("<gray>You will see visibility changes in your action bar");
                } else {
                    player.sendRichMessage("<red>[AntiBase]</red> <gray>Debug mode</gray> <red>DISABLED");
                }
            }
            case "enable" -> {
                plugin.setObfuscationEnabled(true);
                sender.sendRichMessage("<green>[AntiBase]</green> <gray>Obfuscation</gray> <green>ENABLED");
            }
            case "disable" -> {
                plugin.setObfuscationEnabled(false);
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    int viewDistance = online.getViewDistance();
                    int chunkX = online.getLocation().getBlockX() >> 4;
                    int chunkZ = online.getLocation().getBlockZ() >> 4;
                    for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                        for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                            int cx = chunkX + dx;
                            int cz = chunkZ + dz;
                            if (online.getWorld().isChunkLoaded(cx, cz)) {
                                online.getWorld().refreshChunk(cx, cz);
                            }
                        }
                    }
                }
                sender.sendRichMessage("<red>[AntiBase]</red> <gray>Obfuscation</gray> <red>DISABLED");
            }
        }
        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (String sub : new String[]{"debug", "enable", "disable"}) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
            return completions;
        }
        return List.of();
    }
}
