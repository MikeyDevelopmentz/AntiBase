package mikey.me.antiBase;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

public final class BaseObfuscator {
    private final int hideBelowY;
    private final int maxRaySteps;
    private final int scanRadius;
    private final int terrainPadding;
    private final int maxScanBlocks;
    private final int airStateId;
    private final boolean connectedRendering;
    private final Set<String> blacklistedWorlds;

    public BaseObfuscator(FileConfiguration config, Logger logger) {
        this(config, logger, SpigotConversionUtil.fromBukkitBlockData(Material.AIR.createBlockData()).getGlobalId());
    }

    BaseObfuscator(FileConfiguration config, Logger logger, int airStateId) {
        hideBelowY = bounded(config, logger, "hide-below-y", 0, -2032, 2032);
        String mode = config.getString("render-mode", "connected");
        connectedRendering = !"line-of-sight".equalsIgnoreCase(mode);
        if (!"line-of-sight".equalsIgnoreCase(mode) && !"connected".equalsIgnoreCase(mode)) {
            logger.warning("Unknown render-mode; using connected.");
        }
        logger.info("Terrain rendering: " + (connectedRendering ? "connected cave flood (sealed spaces stay hidden)" : "line-of-sight"));
        maxRaySteps = bounded(config, logger, "max-ray-steps", 2000000, 10000, 10000000);
        if (config.contains("proximity-distance", true)) {
            logger.warning("proximity-distance is obsolete and ignored; render-mode controls terrain visibility.");
        }
        scanRadius = bounded(config, logger, "scan-radius", 64, 16, 96);
        terrainPadding = bounded(config, logger, "terrain-padding", 2, 0, 4);
        logger.info("Terrain padding: " + terrainPadding + " extra solid layers behind visible surfaces.");
        maxScanBlocks = bounded(config, logger, "max-scan-blocks", 50000, 1000, 200000);
        blacklistedWorlds = new HashSet<>(config.getStringList("blacklisted-worlds"));
        if (config.contains("replacement-block", true)) {
            logger.info("replacement-block is obsolete and ignored. Hidden blocks now use AIR to avoid fake solid blocks.");
        }
        this.airStateId = airStateId;
    }

    private static int bounded(FileConfiguration config, Logger logger, String key, int fallback, int min, int max) {
        int configured = config.getInt(key, fallback);
        int value = Math.max(min, Math.min(max, configured));
        if (configured != value) logger.warning(key + " must be between " + min + " and " + max + "; using " + value + ".");
        return value;
    }

    public int getAirStateId() { return airStateId; }
    public boolean usesConnectedRendering() { return connectedRendering; }
    public int getHideBelowY() { return hideBelowY; }
    public int getMaxRaySteps() { return maxRaySteps; }
    public int getScanRadius() { return scanRadius; }
    public int getTerrainPadding() { return terrainPadding; }
    public int getMaxScanBlocks() { return maxScanBlocks; }
    public boolean isWorldBlacklisted(World world) { return blacklistedWorlds.contains(world.getName()); }
}
