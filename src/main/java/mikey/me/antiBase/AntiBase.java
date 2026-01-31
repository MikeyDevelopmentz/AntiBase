package mikey.me.antiBase;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

public final class AntiBase extends JavaPlugin {
    private final Map<UUID, Set<Long>> visibleSections = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> visibleBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> hiddenPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> debugPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private MovementListener movementListener;
    private volatile boolean enabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.enabled = getConfig().getBoolean("enabled", true);
        int hideBelowY = getConfig().getInt("hide-below-y");
        int proximityDistance = getConfig().getInt("proximity-distance");
        String replacementBlock = getConfig().getString("replacement-block");
        List<String> blacklistedWorlds = getConfig().getStringList("blacklisted-worlds");
        BaseObfuscator obfuscator = new BaseObfuscator(hideBelowY, proximityDistance, replacementBlock, blacklistedWorlds);
        this.movementListener = new MovementListener(this, obfuscator);

        try {
            PacketEvents.getAPI().getEventManager().registerListener(new PacketHandler(this, obfuscator));
        } catch (Exception e) {
            getLogger().warning("Failed to register PacketEvents listener: " + e.getMessage());
        }

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);

        getServer().getPluginManager().registerEvents(movementListener, this);
        getServer().getPluginManager().registerEvents(new MiningListener(this, obfuscator), this);
        getServer().getPluginManager().registerEvents(new EntityListener(this, obfuscator), this);
        getServer().getCommandMap().register("antibase", new AntibaseCommand(this));
    }

    public boolean isSectionVisible(UUID playerId, int chunkX, int sectionY, int chunkZ) {
        Set<Long> visible = visibleSections.get(playerId);
        return visible != null && visible.contains(packSection(chunkX, sectionY, chunkZ));
    }

    public void updateSectionVisibility(UUID playerId, int chunkX, int sectionY, int chunkZ, boolean isVisible) {
        Set<Long> visible = visibleSections.computeIfAbsent(playerId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        long key = packSection(chunkX, sectionY, chunkZ);
        if (isVisible) {
            visible.add(key);
        } else {
            visible.remove(key);
        }
    }

    public boolean isBlockVisible(UUID playerId, int x, int y, int z) {
        Set<Long> blocks = visibleBlocks.get(playerId);
        return blocks != null && blocks.contains(packCoord(x, y, z));
    }

    public void setHidden(UUID viewerId, UUID targetId, boolean hidden) {
        Set<UUID> hiddenSet = hiddenPlayers.computeIfAbsent(viewerId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        if (hidden) {
            hiddenSet.add(targetId);
        } else {
            hiddenSet.remove(targetId);
        }
    }

    public boolean isHidden(UUID viewerId, UUID targetId) {
        Set<UUID> hiddenSet = hiddenPlayers.get(viewerId);
        return hiddenSet != null && hiddenSet.contains(targetId);
    }

    public void setVisibleBlocks(UUID playerId, Set<Long> blocks) {
        visibleBlocks.put(playerId, blocks);
    }

    public long packCoord(int x, int y, int z) {
        return (((long)x & 0x3FFFFFFL) << 38) | (((long)z & 0x3FFFFFFL) << 12) | ((long)y & 0xFFFL);
    }

    public long packSection(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFF);
    }

    public int[] unpackSection(long key) {
        int cx = (int)((key >> 42) & 0x3FFFFF);
        int cz = (int)((key >> 20) & 0x3FFFFF);
        int sy = (int)(key & 0xFF);
        if (cx > 0x1FFFFF) cx -= 0x400000;
        if (cz > 0x1FFFFF) cz -= 0x400000;
        if (sy > 127) sy -= 256;
        return new int[]{cx, sy, cz};
    }

    public boolean isDebugEnabled(UUID playerId) {
        return debugPlayers.contains(playerId);
    }

    public void setDebug(UUID playerId, boolean enabled) {
        if (enabled) {
            debugPlayers.add(playerId);
        } else {
            debugPlayers.remove(playerId);
        }
    }

    public boolean isObfuscationEnabled() {
        return enabled;
    }

    public void setObfuscationEnabled(boolean enabled) {
        this.enabled = enabled;
        getConfig().set("enabled", enabled);
        saveConfig();
    }

    public MovementListener getMovementListener() {
        return movementListener;
    }

    public void cleanupPlayer(UUID uuid) {
        visibleSections.remove(uuid);
        visibleBlocks.remove(uuid);
        hiddenPlayers.remove(uuid);
        for (Set<UUID> hidden : hiddenPlayers.values()) {
            hidden.remove(uuid);
        }
    }
}