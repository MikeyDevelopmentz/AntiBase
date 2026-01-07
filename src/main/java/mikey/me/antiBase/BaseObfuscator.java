package mikey.me.antiBase;

import org.bukkit.Material;
import org.bukkit.World;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.List;

public class BaseObfuscator {
    private final int hideBelowY;
    private final int proximityDistance;
    private final Material replacementBlock;
    private final Set<String> blacklistedWorlds;

    public BaseObfuscator(int hideBelowY, int proximityDistance, String replacementBlock, List<String> blacklistedWorlds) {
        this.hideBelowY = hideBelowY;
        this.proximityDistance = proximityDistance;
        this.blacklistedWorlds = new HashSet<>(blacklistedWorlds);
        Material blockMaterial;
        try {
            blockMaterial = Material.valueOf(replacementBlock.toUpperCase());
        } catch (IllegalArgumentException e) {
            blockMaterial = Material.STONE;
        }
        this.replacementBlock = blockMaterial;
    }

    public Material getReplacementBlock() { return replacementBlock; }
    public int getHideBelowY() { return hideBelowY; }
    public int getProximityDistance() { return proximityDistance; }
    public boolean isWorldBlacklisted(World world) { return blacklistedWorlds.contains(world.getName()); }
    public void clear(UUID playerId) { }
}
