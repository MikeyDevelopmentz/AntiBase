package mikey.me.antiBase;

import org.bukkit.plugin.java.JavaPlugin;

public final class AntiBase extends JavaPlugin {

    private PacketHandler packetHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        int hideBelowY = getConfig().getInt("hide-below-y");
        int proximityDistance = getConfig().getInt("proximity-distance");
        String replacementBlock = getConfig().getString("replacement-block");

        BaseObfuscator obfuscator = new BaseObfuscator(hideBelowY, proximityDistance, replacementBlock);

        packetHandler = new PacketHandler(this, obfuscator);
        com.comphenix.protocol.ProtocolLibrary.getProtocolManager().addPacketListener(packetHandler);

        new ObfuscationTask(this, obfuscator).runTaskTimer(this, 20L, 20L);

        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
                obfuscator.clear(event.getPlayer().getUniqueId());
            }
        }, this);
    }

    @Override
    public void onDisable() {
        if (packetHandler != null) {
            com.comphenix.protocol.ProtocolLibrary.getProtocolManager().removePacketListener(packetHandler);
        }
    }
}
