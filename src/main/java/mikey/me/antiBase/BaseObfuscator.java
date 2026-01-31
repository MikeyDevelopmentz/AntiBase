package mikey.me.antiBase;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;
import org.bukkit.World;
import java.util.Set;
import java.util.HashSet;
import java.util.List;

public class BaseObfuscator {
    private final int hideBelowY;
    private final int proximityDistance;
    private final Material replacementBlock;
    private final int replacementBlockStateId;
    private final WrappedBlockState replacementBlockState;
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
        this.replacementBlockState = SpigotConversionUtil.fromBukkitBlockData(blockMaterial.createBlockData());
        this.replacementBlockStateId = this.replacementBlockState.getGlobalId();
    }

    public Material getReplacementBlock() { return replacementBlock; }
    public int getReplacementBlockStateId() { return replacementBlockStateId; }
    public WrappedBlockState getReplacementBlockState() { return replacementBlockState; }
    public int getHideBelowY() { return hideBelowY; }
    public int getProximityDistance() { return proximityDistance; }
    public boolean isWorldBlacklisted(World world) { return blacklistedWorlds.contains(world.getName()); }
}
