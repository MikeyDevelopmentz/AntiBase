package mikey.me.antiBase;

import org.bukkit.Material;
import java.util.UUID;

public class BaseObfuscator {
    private final int hideBelowY;
    private final int proximityDistance;
    private final Material replacementBlock;

    public BaseObfuscator(int hideBelowY, int proximityDistance, String replacementBlock) {
        this.hideBelowY = hideBelowY;
        this.proximityDistance = proximityDistance;
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
    public void clear(UUID playerId) { }
}
