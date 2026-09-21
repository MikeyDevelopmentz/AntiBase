package mikey.me.antiBase;

import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;

/** classify snapshot block data, no live world access here */
final class BlockOcclusion {
    private static final BlockFace[] FACES = {BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};
    private BlockOcclusion() { }

    static int classify(BlockData data) {
        if (data.isOccluding()) {
            // papers flag just means it CAN occlude, not that it fills a whole cube.
            // isFaceSturdy(FULL) is state aware and doesnt touch the live world.
            // walls, half slabs, stairs, doors etc gotta keep their gaps.
            for (BlockFace face : FACES) if (!data.isFaceSturdy(face, BlockSupport.FULL)) return 2;
            return 1;
        }
        // glass and other full shaped transparent blocks stay transmissive too
        return switch (data.getMaterial().name()) {
            case "AIR", "CAVE_AIR", "VOID_AIR" -> 0;
            default -> 2;
        };
    }
}
