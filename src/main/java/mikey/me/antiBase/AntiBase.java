package mikey.me.antiBase;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class AntiBase extends JavaPlugin {
    private final Map<UUID, ViewerState> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> hiddenPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> debugPlayers = ConcurrentHashMap.newKeySet();
    private final ClientViewTracker clientViews = new ClientViewTracker();
    private final InteractionVisibility interactions = new InteractionVisibility();
    private final ConsoleDebug diagnostics = new ConsoleDebug();
    private MovementListener movementListener;
    private PacketHandler packetHandler;
    private BaseObfuscator obfuscator;
    private volatile boolean enabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        obfuscator = new BaseObfuscator(getConfig(), getLogger());
        enabled = getConfig().getBoolean("enabled", true);
        diagnostics.configure(getConfig().getBoolean("console-debug", false), null, "all players");
        packetHandler = new PacketHandler(this, obfuscator);
        try {
            PacketEvents.getAPI().getEventManager().registerListener(packetHandler);
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Cannot start AntiBase without its packet listener.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        movementListener = new MovementListener(this, obfuscator);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(movementListener, this);
        getServer().getPluginManager().registerEvents(new MiningListener(this, obfuscator), this);
        getServer().getPluginManager().registerEvents(new EntityListener(this, obfuscator), this);
        AntibaseCommand command = new AntibaseCommand(this);
        getCommand("antibase").setExecutor(command);
        getCommand("antibase").setTabCompleter(command);
        getLogger().info("Rendering mode: AIR for hidden chunk data; suppress updates only for already-masked blocks. "
                + "Use /antibase debug on [player] for console diagnostics.");
        for (Player player : getServer().getOnlinePlayers()) {
            updatePosition(player, player.getLocation());
            movementListener.updateVisibility(player);
        }
    }

    @Override
    public void onDisable() {
        enabled = false;
        if (movementListener != null) {
            movementListener.restoreAll();
            movementListener.shutdown();
        }
        if (packetHandler != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetHandler);
        }
        viewers.clear();
        hiddenPlayers.clear();
        debugPlayers.clear();
        clientViews.clear();
        interactions.clear();
        diagnostics.configure(false, null, "all players");
    }

    ViewerState getViewerState(UUID playerId) { return viewers.get(playerId); }
    ClientViewTracker clientViews() { return clientViews; }
    InteractionVisibility interactions() { return interactions; }
    ConsoleDebug diagnostics() { return diagnostics; }

    void updatePosition(Player player, Location location) {
        ViewerState previous = viewers.get(player.getUniqueId());
        UUID worldId = location.getWorld().getUID();
        if (previous == null || !previous.worldId().equals(worldId)) clientViews.reset(player.getUniqueId(), worldId);
        VisibilitySnapshot visibility = previous != null && previous.worldId().equals(worldId)
                ? previous.visibility() : VisibilitySnapshot.EMPTY;
        viewers.put(player.getUniqueId(), new ViewerState(worldId, location.getWorld().getMinHeight(),
                !obfuscator.isWorldBlacklisted(location.getWorld()),
                location.getX(), location.getY(), location.getZ(), visibility));
    }

    void setVisibility(UUID playerId, VisibilitySnapshot visibility) {
        viewers.computeIfPresent(playerId, (id, state) -> state.withVisibility(visibility));
    }

    public boolean isBlockVisible(UUID playerId, int x, int y, int z) {
        ViewerState state = viewers.get(playerId);
        return state != null && state.visibility().isBlockVisible(x, y, z);
    }

    public void setHidden(UUID viewerId, UUID targetId, boolean hidden) {
        if (hidden) hiddenPlayers.computeIfAbsent(viewerId, key -> ConcurrentHashMap.newKeySet()).add(targetId);
        else {
            Set<UUID> targets = hiddenPlayers.get(viewerId);
            if (targets != null) {
                targets.remove(targetId);
                if (targets.isEmpty()) hiddenPlayers.remove(viewerId, targets);
            }
        }
    }

    public boolean isHidden(UUID viewerId, UUID targetId) {
        Set<UUID> targets = hiddenPlayers.get(viewerId);
        return targets != null && targets.contains(targetId);
    }

    public boolean isDebugEnabled(UUID playerId) { return debugPlayers.contains(playerId); }

    public void setDebug(UUID playerId, boolean enabled) {
        if (enabled) debugPlayers.add(playerId);
        else debugPlayers.remove(playerId);
    }

    public boolean isObfuscationEnabled() { return enabled; }

    public void setObfuscationEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        getConfig().set("enabled", enabled);
        saveConfig();
        clientViews.clear();
        for (Player player : getServer().getOnlinePlayers()) clientViews.reset(player.getUniqueId(), player.getWorld().getUID());
        movementListener.resetAll();
    }

    public MovementListener getMovementListener() { return movementListener; }

    public void cleanupPlayer(UUID uuid) {
        if (movementListener != null) movementListener.cleanupPlayer(uuid);
        viewers.remove(uuid);
        debugPlayers.remove(uuid);
        clientViews.remove(uuid);
        interactions.remove(uuid);
        hiddenPlayers.remove(uuid);
        hiddenPlayers.values().forEach(targets -> targets.remove(uuid));
    }
}
