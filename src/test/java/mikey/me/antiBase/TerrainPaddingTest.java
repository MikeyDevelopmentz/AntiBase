package mikey.me.antiBase;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TerrainPaddingTest {
    private static final ConnectedVisibilityScanner.Window BOUNDS =
            ConnectedVisibilityScanner.Window.around(.5, -7.5, .5, -16, 0, 16);

    private static VisibilitySnapshot flood(VisibilityScanner.BlockAccess map) {
        return ConnectedVisibilityScanner.scan(map, .5, -7.5, .5, -16, 0, 0, 16);
    }

    @Test void twoLayersCoverTwoBreaks() {
        VisibilityScanner.BlockAccess map = (x,y,z) -> x == 0 && z == 0 && (y == -8 || y == -7) ? 0 : 1;
        VisibilitySnapshot original = flood(map);
        VisibilitySnapshot padded = TerrainPadding.add(map, original, BOUNDS, 0, 2, Integer.MAX_VALUE);
        assertTrue(original.isTerrainVisible(1,-8,0));
        assertFalse(original.isTerrainVisible(2,-8,0));
        // pretend the client mined these before any server response arrives
        for (int minedX = 1; minedX <= 2; minedX++) {
            for (int[] face : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                int x = minedX + face[0], y = -8 + face[1], z = face[2];
                if (x < minedX && y == -8 && z == 0) continue;
                assertTrue(padded.isTerrainVisible(x,y,z), "Missing preloaded face after mining " + minedX);
            }
        }
        assertFalse(padded.isTerrainVisible(4,-8,0), "Exactly two extra layers");
        assertFalse(padded.isConnected(1,-8,0), "padding must not connect anything");
        assertFalse(padded.isConnected(2,-8,0));
    }

    @Test void paddingStopsAtSealedAir() {
        VisibilityScanner.BlockAccess map = (x,y,z) -> {
            if (y == -8 && z == 0) {
                if (x == 0 || x == 2) return 0;
                if (x == 3) return 2; // chest inside the sealed room
            }
            return 1;
        };
        VisibilitySnapshot padded = TerrainPadding.add(map, flood(map), BOUNDS, 0, 2, Integer.MAX_VALUE);
        assertFalse(padded.isBlockVisible(2,-8,0));
        assertFalse(padded.isTerrainVisible(3,-8,0));
        LongHashSet edited = new LongHashSet(1);
        edited.add(Coordinates.block(3,-8,0));
        VisibilitySnapshot patch = ConnectedVisibilityScanner.expandChanges(map,.5,-7.5,.5,-16,0,0,16,padded,edited,1000);
        assertEquals(0, patch.blockCount(), "padding is not a connection");
    }

    @Test void paddingRespectsBoundsAndBudget() {
        VisibilityScanner.BlockAccess map = (x,y,z) -> x == 0 && y == -8 && z == 0 ? 0 : 1;
        VisibilitySnapshot original = flood(map);
        AtomicInteger reads = new AtomicInteger();
        VisibilitySnapshot limited = TerrainPadding.add((x,y,z) -> {
            assertTrue(BOUNDS.contains(x,y,z));
            assertTrue(y < -6);
            reads.incrementAndGet(); return -1;
        }, original, BOUNDS, -6, 4, 3);
        assertEquals(3, reads.get());
        assertTrue(limited.budgetLimited());
        assertEquals(original.terrainCount(), limited.terrainCount());
        assertSame(original, TerrainPadding.add(map, original, BOUNDS, 0, 0, 100));
    }

    @Test void urgentOpeningGetsPadding() {
        VisibilityScanner.BlockAccess before = (x,y,z) -> x == 0 && y == -8 && z == 0 ? 0 : 1;
        VisibilitySnapshot previous = TerrainPadding.add(before, flood(before), BOUNDS,0,2,Integer.MAX_VALUE);
        VisibilityScanner.BlockAccess after = (x,y,z) -> (x == 0 || x == 1) && y == -8 && z == 0 ? 0 : 1;
        LongHashSet edited = new LongHashSet(1); edited.add(Coordinates.block(1,-8,0));
        VisibilitySnapshot patch = ConnectedVisibilityScanner.expandChanges(after,.5,-7.5,.5,-16,0,0,16,previous,edited,1000);
        patch = TerrainPadding.add(after,patch,BOUNDS,0,2,1000);
        assertTrue(patch.isTerrainVisible(4,-8,0));
        assertFalse(patch.isTerrainVisible(5,-8,0));
        assertTrue(patch.isConnected(1,-8,0));
        assertFalse(patch.isConnected(2,-8,0));
    }
}
