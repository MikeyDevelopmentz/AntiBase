package mikey.me.antiBase;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AntibaseCommandTest {
    @Test
    void debugPermCannotStartCapture() {
        AntiBase plugin = mock(AntiBase.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("antibase.debug")).thenReturn(true);
        new AntibaseCommand(plugin).onCommand(sender, mock(Command.class), "antibase", new String[]{"debug", "on"});
        verifyNoInteractions(plugin);
        verify(sender).sendRichMessage(contains("permission"));
    }

    @Test
    void consoleCaptureFiltersPlayer() {
        AntiBase plugin = mock(AntiBase.class);
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        io.papermc.paper.plugin.configuration.PluginMeta meta = mock(io.papermc.paper.plugin.configuration.PluginMeta.class);
        java.util.logging.Logger logger = mock(java.util.logging.Logger.class);
        MovementListener movement = mock(MovementListener.class);
        ConsoleDebug diagnostics = new ConsoleDebug();
        when(plugin.diagnostics()).thenReturn(diagnostics);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getMovementListener()).thenReturn(movement);
        when(movement.queueStatus()).thenReturn("queued=0");
        Player miner = mock(Player.class);
        java.util.UUID id = java.util.UUID.randomUUID();
        when(miner.getUniqueId()).thenReturn(id);
        when(miner.getName()).thenReturn("Miner");
        when(server.getPlayerExact("Miner")).thenReturn(miner);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("antibase.admin")).thenReturn(true);
        AntibaseCommand command = new AntibaseCommand(plugin);
        command.onCommand(sender, mock(Command.class), "antibase", new String[]{"debug", "on", "Miner"});
        assertTrue(diagnostics.accepts(id));
        assertFalse(diagnostics.accepts(java.util.UUID.randomUUID()));
        diagnostics.important(id, "interaction=break cancelled=false");
        command.onCommand(sender, mock(Command.class), "antibase", new String[]{"debug", "off"});
        assertFalse(diagnostics.enabled());
        verify(logger).info(contains("interaction=break cancelled=false"));
    }

    @Test
    void debugPermCannotDisable() {
        AntiBase plugin = mock(AntiBase.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("antibase.debug")).thenReturn(true);
        AntibaseCommand command = new AntibaseCommand(plugin);
        command.onCommand(sender, mock(Command.class), "antibase", new String[]{"disable"});
        verify(plugin, never()).setObfuscationEnabled(anyBoolean());
        assertEquals(List.of("debug"), command.onTabComplete(sender, mock(Command.class), "antibase", new String[]{""}));
    }

    @Test
    void onlyAdminTogglesProtection() {
        AntiBase plugin = mock(AntiBase.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("antibase.admin")).thenReturn(true);
        AntibaseCommand command = new AntibaseCommand(plugin);
        command.onCommand(sender, mock(Command.class), "antibase", new String[]{"DISABLE"});
        verify(plugin).setObfuscationEnabled(false);
        command.onCommand(sender, mock(Command.class), "antibase", new String[]{"enable"});
        verify(plugin).setObfuscationEnabled(true);
    }

    @Test
    void debugNeedsPermission() {
        AntiBase plugin = mock(AntiBase.class);
        Player sender = mock(Player.class);
        new AntibaseCommand(plugin).onCommand(sender, mock(Command.class), "antibase", new String[]{"debug"});
        verify(plugin, never()).setDebug(any(), anyBoolean());
    }

    @Test
    void consoleDebugHandlesArgs() {
        AntiBase plugin = mock(AntiBase.class);
        when(plugin.diagnostics()).thenReturn(new ConsoleDebug());
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        AntibaseCommand command = new AntibaseCommand(plugin);
        assertTrue(command.onCommand(sender, mock(Command.class), "antibase", new String[]{"debug"}));
        assertTrue(command.onCommand(sender, mock(Command.class), "antibase", new String[]{"enable", "extra"}));
        verify(plugin, never()).setDebug(any(), anyBoolean());
        verify(plugin, never()).setObfuscationEnabled(anyBoolean());
    }
}
