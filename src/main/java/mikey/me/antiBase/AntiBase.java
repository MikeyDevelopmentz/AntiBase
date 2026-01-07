package mikey.me.antiBase;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

public final class AntiBase extends JavaPlugin {
    private final Map<UUID, Set<Long>> visibleSections = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> visibleBlocks = new ConcurrentHashMap<>();
    private final Set<UUID> debugPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int hideBelowY = getConfig().getInt("hide-below-y");
        int proximityDistance = getConfig().getInt("proximity-distance");
        String replacementBlock = getConfig().getString("replacement-block");
        List<String> blacklistedWorlds = getConfig().getStringList("blacklisted-worlds");
        BaseObfuscator obfuscator = new BaseObfuscator(hideBelowY, proximityDistance, replacementBlock, blacklistedWorlds);
        MovementListener movementListener = new MovementListener(this, obfuscator);

        try {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(new PacketHandler(this, obfuscator));
        } catch (Exception e) {
            getLogger().warning("Failed to register PacketEvents listener: " + e.getMessage());
        }

        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
                obfuscator.clear(event.getPlayer().getUniqueId());
                visibleSections.remove(event.getPlayer().getUniqueId());
                visibleBlocks.remove(event.getPlayer().getUniqueId());
            }

            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                Bukkit.getScheduler().runTaskLater(AntiBase.this, () -> {
                    movementListener.updateVisibility(event.getPlayer());
                    movementListener.updateOthersViewOfPlayer(event.getPlayer());
                }, 5L);
            }
        }, this);

        getServer().getPluginManager().registerEvents(movementListener, this);
        getServer().getPluginManager().registerEvents(new MiningListener(this, obfuscator), this);
        getServer().getPluginManager().registerEvents(new EntityListener(this, obfuscator), this);
        getCommand("antibase").setExecutor(new DebugCommand(this));
    }

    public boolean isSectionVisible(UUID playerId, int chunkX, int sectionY, int chunkZ) {
        Set<Long> visible = visibleSections.get(playerId);
        return visible != null && visible.contains(getSectionKey(chunkX, sectionY, chunkZ));
    }

    public void updateSectionVisibility(UUID playerId, int chunkX, int sectionY, int chunkZ, boolean isVisible) {
        Set<Long> visible = visibleSections.computeIfAbsent(playerId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        long key = getSectionKey(chunkX, sectionY, chunkZ);
        if (isVisible) visible.add(key); else visible.remove(key);
    }

    public boolean isBlockVisible(UUID playerId, int x, int y, int z) {
        Set<Long> blocks = visibleBlocks.get(playerId);
        return blocks != null && blocks.contains(packCoord(x, y, z));
    }

    public void setVisibleBlocks(UUID playerId, Set<Long> blocks) { visibleBlocks.put(playerId, blocks); }
    public static long packCoord(int x, int y, int z) { return (((long)x & 0x3FFFFFFL) << 38) | (((long)z & 0x3FFFFFFL) << 12) | ((long)y & 0xFFFL); }
    private long getSectionKey(int x, int y, int z) { return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFF); }
    public boolean isDebugEnabled(UUID playerId) { return debugPlayers.contains(playerId); }
    public void setDebug(UUID playerId, boolean enabled) { if (enabled) debugPlayers.add(playerId); else debugPlayers.remove(playerId); }
}
