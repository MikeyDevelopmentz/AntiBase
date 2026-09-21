package mikey.me.antiBase;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.Set;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockOcclusionTest {
    private static final Set<BlockFace> FULL = Set.of(BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST);
    record Shape(String name, Material material, boolean canOcclude, Set<BlockFace> fullFaces, int expected) {
        @Override public String toString() { return name; }
        BlockData data() {
            BlockData data = material.name().endsWith("_TRAPDOOR") ? mock(TrapDoor.class)
                    : material.name().endsWith("_DOOR") ? mock(Door.class) : mock(BlockData.class);
            if (data instanceof Openable openable) when(openable.isOpen()).thenReturn(name.startsWith("open "));
            when(data.getMaterial()).thenReturn(material);
            when(data.isOccluding()).thenReturn(canOcclude);
            when(data.isFaceSturdy(any(BlockFace.class), eq(BlockSupport.FULL)))
                    .thenAnswer(call -> fullFaces.contains(call.getArgument(0)));
            return data;
        }
    }
    static Stream<Shape> shapes() {
        // canOcclude=true on partial shapes on purpose: the old impl trusted that flag
        // and stopped there. these face profiles model gaps, not live paper states
        return Stream.of(
                new Shape("stone cube", Material.STONE, true, FULL, 1),
                new Shape("deepslate cube", Material.DEEPSLATE, true, FULL, 1),
                new Shape("double slab", Material.STONE_SLAB, true, FULL, 1),
                new Shape("bottom slab", Material.STONE_SLAB, true, Set.of(BlockFace.DOWN), 2),
                new Shape("top slab", Material.STONE_SLAB, true, Set.of(BlockFace.UP), 2),
                new Shape("cobblestone wall post", Material.COBBLESTONE_WALL, true, Set.of(), 2),
                new Shape("connected deepslate wall", Material.COBBLED_DEEPSLATE_WALL, true, Set.of(), 2),
                new Shape("stairs", Material.STONE_STAIRS, true, Set.of(BlockFace.DOWN, BlockFace.SOUTH), 2),
                new Shape("fence", Material.OAK_FENCE, true, Set.of(), 2),
                new Shape("fence gate", Material.OAK_FENCE_GATE, true, Set.of(), 2),
                new Shape("closed spruce trapdoor", Material.SPRUCE_TRAPDOOR, false, Set.of(BlockFace.DOWN), 2),
                new Shape("closed birch trapdoor", Material.BIRCH_TRAPDOOR, false, Set.of(BlockFace.DOWN), 2),
                new Shape("closed dark oak trapdoor", Material.DARK_OAK_TRAPDOOR, false, Set.of(BlockFace.DOWN), 2),
                new Shape("open iron trapdoor", Material.IRON_TRAPDOOR, true, Set.of(BlockFace.NORTH), 2),
                new Shape("closed spruce door", Material.SPRUCE_DOOR, false, Set.of(BlockFace.NORTH), 2),
                new Shape("closed iron door", Material.IRON_DOOR, true, Set.of(BlockFace.NORTH), 2),
                new Shape("ladder", Material.LADDER, false, Set.of(), 2),
                new Shape("carpet", Material.RED_CARPET, false, Set.of(BlockFace.DOWN), 2),
                new Shape("snow layers", Material.SNOW, true, Set.of(BlockFace.DOWN), 2),
                new Shape("farmland", Material.FARMLAND, true, Set.of(BlockFace.DOWN), 2),
                new Shape("glass cube", Material.GLASS, false, FULL, 2),
                new Shape("glass pane", Material.GLASS_PANE, false, Set.of(), 2),
                new Shape("leaves", Material.OAK_LEAVES, false, FULL, 2),
                new Shape("water", Material.WATER, false, Set.of(), 2));
    }

    @Test void airIsNotTerrain() {
        for (Material material : new Material[]{Material.AIR, Material.CAVE_AIR, Material.VOID_AIR}) {
            BlockData air = mock(BlockData.class);
            when(air.getMaterial()).thenReturn(material);
            assertEquals(0, BlockOcclusion.classify(air));
        }
    }

    @ParameterizedTest(name="{0}") @MethodSource("shapes")
    void partialShapesAreNotFullCubes(Shape shape) {
        assertEquals(shape.expected, BlockOcclusion.classify(shape.data()));
    }

    @ParameterizedTest @EnumSource(value=BlockFace.class, names={"DOWN","UP","NORTH","SOUTH","WEST","EAST"})
    void oneOpenFaceBreaksTheSeal(BlockFace gap) {
        BlockData data = new Shape("incomplete cube", Material.STONE, true, FULL, 2).data();
        when(data.isFaceSturdy(gap, BlockSupport.FULL)).thenReturn(false);
        assertEquals(2, BlockOcclusion.classify(data));
    }

    @ParameterizedTest(name="room behind {0}") @MethodSource("shapes")
    void partialBlocksKeepGapOpenInBothModes(Shape shape) {
        BlockData opening = shape.data();
        BlockData stone = new Shape("stone", Material.STONE, true, FULL, 1).data();
        BlockData air = mock(BlockData.class);
        when(air.getMaterial()).thenReturn(Material.AIR);
        VisibilityScanner.BlockAccess map = (x,y,z) -> BlockOcclusion.classify(
                x == 1 && y == -10 && z == 0 ? opening
                        : y == -10 && z == 0 && (x == 0 || (x >= 2 && x <= 6)) ? air : stone);
        VisibilitySnapshot connected = ConnectedVisibilityScanner.scan(map, .5, -9.5, .5, -32, 0, 0, 16);
        VisibilitySnapshot strict = VisibilityScanner.scan(map, .5, -9.5, .5, -32, 0, 0, 16, 50000, 2000000);
        assertTrue(connected.isTerrainVisible(1, -10, 0), "the block itself should still render");
        assertEquals(shape.expected == 2, connected.isTerrainVisible(7, -10, 0), "room wall behind the gap");
        assertEquals(shape.expected == 2, connected.isConnected(6, -10, 0), "space behind the gap should connect");
        assertEquals(shape.expected == 2, strict.isTerrainVisible(7, -10, 0), "strict mode same rule");
        assertFalse(connected.isBlockVisible(8, -10, 0), "rock past the wall should stay hidden");
    }
}
