package mikey.me.antiBase;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BaseObfuscator {
    private final int hideBelowY;
    private final int proximitySquared;
    private final Material replacementBlock;
    private final Map<UUID, Set<Long>> obscuredChunks = new ConcurrentHashMap<>();

    public BaseObfuscator(int hideBelowY, int proximityDistance, String replacementBlock) {
        this.hideBelowY = hideBelowY;
        this.proximitySquared = proximityDistance * proximityDistance;
        this.replacementBlock = Material.getMaterial(replacementBlock) != null ? Material.valueOf(replacementBlock)
                : Material.STONE;
    }

    public boolean shouldObfuscate(Material material, int x, int y, int z, Player player) {
        if (y > hideBelowY) {
            return false;
        }

        Location loc = player.getLocation();
        double distanceSquared = Math.pow(loc.getX() - x, 2) + Math.pow(loc.getY() - y, 2)
                + Math.pow(loc.getZ() - z, 2);

        if (distanceSquared > proximitySquared) {
            return material != replacementBlock && material != Material.DEEPSLATE;
        }

        return false;
    }

    public Material getReplacementBlock() {
        return replacementBlock;
    }

    public boolean isObscured(UUID playerId, long chunkKey) {
        Set<Long> set = obscuredChunks.get(playerId);
        return set != null && set.contains(chunkKey);
    }

    public void setObscured(UUID playerId, long chunkKey, boolean obscured) {
        if (obscured) {
            obscuredChunks.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(chunkKey);
        } else {
            Set<Long> set = obscuredChunks.get(playerId);
            if (set != null) {
                set.remove(chunkKey);
                if (set.isEmpty())
                    obscuredChunks.remove(playerId);
            }
        }
    }

    public boolean shouldHideEntity(org.bukkit.entity.Entity entity, Player viewer) {
        if (entity.equals(viewer))
            return false;
        if (entity instanceof org.bukkit.entity.Player && ((Player) entity).hasMetadata("NPC"))
            return false; // Skip NPCs if any

        Location loc = entity.getLocation();
        double distSq = viewer.getLocation().distanceSquared(loc);

        // Underground logic
        if (loc.getY() < hideBelowY) {
            if (distSq > proximitySquared) {
                return true;
            }
        }

        // Occlusion logic: If not in line of sight and beyond a minimum distance
        if (distSq > 256) { // 16 blocks buffer
            return !viewer.hasLineOfSight(entity);
        }

        return false;
    }

    public void clear(UUID playerId) {
        obscuredChunks.remove(playerId);
    }

    public int getHideBelowY() {
        return hideBelowY;
    }

    public int getProximityDistance() {
        return (int) Math.sqrt(proximitySquared);
    }
}
