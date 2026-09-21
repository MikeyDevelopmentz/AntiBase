package mikey.me.antiBase;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConnectedVisibilityScannerTest {
    private VisibilitySnapshot scan(VisibilityScanner.BlockAccess map, double x, double y, double z) {
        return ConnectedVisibilityScanner.scan(map, x, y, z, -64, 32, 0, 32);
    }

    @Test void wholeRoomPreloadsBeforeCorner() {
        VisibilityScanner.BlockAccess map = (x,y,z) -> y >= -20 && y <= -17
                && ((x >= 0 && x <= 20 && z >= 0 && z <= 2) || (x >= 18 && x <= 30 && z >= 0 && z <= 22)) ? 0 : 1;
        VisibilitySnapshot flood = scan(map, .5, -18.5, 1.5);
        VisibilitySnapshot rays = VisibilityScanner.scan(map, .5, -18.5, 1.5, -64, 32, 0, 32, 50000, 2000000);
        assertTrue(flood.isTerrainVisible(30, -21, 20), "floor around the bend should be preloaded");
        assertTrue(flood.isConnected(30, -18, 20));
        assertFalse(rays.isTerrainVisible(30, -21, 20), "rays should miss this, else the test is pointless");
        assertFalse(flood.isBlockVisible(30, -22, 20), "buried blocks should stay hidden");
    }

    @Test void sealedAndDiagonalRoomsStayHidden() {
        VisibilityScanner.BlockAccess map = (x,y,z) -> {
            if (y != -10) return 1;
            if (z == 0 && (x == 0 || x == 2)) return 0;
            if (z == 0 && x == 1) return 2;
            if ((x == 3 && z == 1) || (x == 5 && z == 0)) return 0;
            return 1;
        };
        VisibilitySnapshot flood = scan(map, .5, -9.5, .5);
        assertTrue(flood.isConnected(2, -10, 0));
        assertTrue(flood.isTerrainVisible(1, -10, 0));
        assertFalse(flood.isBlockVisible(3, -10, 1));
        assertFalse(flood.isBlockVisible(5, -10, 0));
    }

    @Test void largeRoomCompletes() {
        AtomicInteger reads = new AtomicInteger();
        VisibilitySnapshot flood = scan((x,y,z) -> {
            reads.incrementAndGet();
            return x >= -28 && x <= 28 && y >= -60 && y <= -3 && z >= -28 && z <= 28 ? 0 : 1;
        }, .5, -30.5, .5);
        assertTrue(flood.isTerrainVisible(0, -61, 0));
        assertTrue(flood.isTerrainVisible(-29, -30, 0));
        assertTrue(flood.blockCount() > 180000);
        assertFalse(flood.budgetLimited());
        assertEquals(flood.blockCount(), reads.get(), "each cell should be read once");
    }

    @Test void missingChunksStopTheFlood() {
        VisibilityScanner.BlockAccess map = (x,y,z) -> y == -10 && z == 0 && x >= -5 && x <= 30 ? 0 : 1;
        VisibilitySnapshot partial = scan((x,y,z) -> x >= 16 ? -1 : map.blockAt(x,y,z), -.5, -9.5, .5);
        assertFalse(partial.isBlockVisible(16, -10, 0));
        assertFalse(partial.isBlockVisible(20, -11, 0));
        VisibilitySnapshot complete = scan(map, -.5, -9.5, .5);
        assertTrue(complete.isTerrainVisible(20, -11, 0));
    }

    @Test void pathsCanLeaveTheCutoff() {
        VisibilityScanner.BlockAccess map = (x,y,z) -> z == 0
                && ((y == 2 && x >= 0 && x <= 10) || ((x == 0 || x == 10) && y >= -5 && y <= 2)) ? 0 : 1;
        VisibilitySnapshot flood = scan(map, .5, -4.5, .5);
        assertTrue(flood.isTerrainVisible(10, -6, 0));
        assertFalse(flood.isTerrainVisible(10, 3, 0), "no corrections above the cutoff");
    }

    @Test void sectionAnchoredBoundsAreStable() {
        VisibilityScanner.BlockAccess map = (x,y,z) -> y == -10 ? 0 : 1;
        VisibilitySnapshot before = scan(map, -15.5, -9.5, -15.5), after = scan(map, -.5, -9.1, -.5);
        AtomicInteger changes = new AtomicInteger();
        after.forEachChangedBlock(before, key -> changes.incrementAndGet());
        assertEquals(0, changes.get());
        assertEquals(ConnectedVisibilityScanner.Window.around(-15.5, -9.5, -15.5, -64, 32, 32),
                ConnectedVisibilityScanner.Window.around(-.5, -9.1, -.5, -64, 32, 32));
    }

    @Test void explosionDoesntRevealSealedRoom() {
        Set<Long> removed = new HashSet<>();
        VisibilityScanner.BlockAccess map = (x,y,z) -> {
            if (removed.contains(Coordinates.block(x,y,z))) return 0;
            return y == -10 && z == 0 && (x == 0 || (x >= 2 && x <= 10) || (x >= 20 && x <= 25)) ? 0 : 1;
        };
        VisibilitySnapshot before = scan(map, .5, -9.5, .5);
        LongHashSet changed = new LongHashSet(4);
        changed.add(Coordinates.block(1, -10, 0)); changed.add(Coordinates.block(21, -11, 0));
        changed.forEach(removed::add);
        VisibilitySnapshot patch = ConnectedVisibilityScanner.expandChanges(map, .5, -9.5, .5,
                -64, 32, 0, 32, before, changed, 32768);
        VisibilitySnapshot after = before.withRevealed(patch);
        assertTrue(after.isTerrainVisible(10, -11, 0));
        assertFalse(after.isBlockVisible(21, -12, 0));
        assertFalse(after.isConnected(21, -11, 0));
        assertFalse(patch.budgetLimited());
    }

    @Test void urgentExpansionHasReadLimit() {
        boolean[] open = {false};
        VisibilityScanner.BlockAccess map = (x,y,z) -> (x == 0 && y == -10 && z == 0)
                || (open[0] && x >= 1) ? 0 : 1;
        VisibilitySnapshot before = scan(map, .5, -9.5, .5);
        open[0] = true;
        LongHashSet changed = new LongHashSet(1); changed.add(Coordinates.block(1,-10,0));
        AtomicInteger reads = new AtomicInteger();
        VisibilitySnapshot patch = ConnectedVisibilityScanner.expandChanges((x,y,z) -> { reads.incrementAndGet(); return map.blockAt(x,y,z); },
                .5,-9.5,.5,-64,32,0,32,before,changed,100);
        assertTrue(patch.budgetLimited()); assertEquals(100, reads.get());
        assertTrue(before.withRevealed(patch).isTerrainVisible(-1,-10,0));
        assertFalse(scan(map,.5,-9.5,.5).budgetLimited());
    }

    @Test void closingPassageHidesRoom() {
        boolean[] closed = {false};
        VisibilityScanner.BlockAccess map = (x,y,z) -> y == -10 && z == 0 && x >= 0 && x <= 10
                && !(closed[0] && x == 5) ? 0 : 1;
        VisibilitySnapshot open = scan(map,.5,-9.5,.5);
        assertTrue(open.isTerrainVisible(10,-11,0));
        closed[0] = true;
        VisibilitySnapshot sealed = scan(map,.5,-9.5,.5);
        assertFalse(sealed.isConnected(10,-10,0));
        assertFalse(sealed.isTerrainVisible(10,-11,0));
        LongHashSet changes = new LongHashSet(32);
        sealed.forEachChangedBlock(open,changes::add);
        assertTrue(changes.contains(Coordinates.block(10,-11,0)), "client should get the hide correction");
        assertTrue(sealed.isTerrainVisible(5,-10,0));
    }

    @Test void matchesCellQueueFloodOnRandomMaps() {
        Random random = new Random(73041);
        for (int trial = 0; trial < 40; trial++) {
            byte[] cells = new byte[12*12*12];
            for (int i = 0; i < cells.length; i++) cells[i] = (byte)(random.nextInt(10) < 6 ? 0 : random.nextInt(3) + 1);
            cells[6*144+6*12+6] = 0;
            VisibilityScanner.BlockAccess map = (x,y,z) -> x < -6 || x >= 6 || y < -16 || y >= -4 || z < -6 || z >= 6
                    ? -1 : cells[(y+16)*144+(z+6)*12+x+6] == 3 ? -1 : cells[(y+16)*144+(z+6)*12+x+6];
            Set<Long> reached = new HashSet<>(), surface = new HashSet<>();
            ArrayDeque<int[]> queue = new ArrayDeque<>(); queue.add(new int[]{0,-10,0});
            while (!queue.isEmpty()) {
                int[] p = queue.remove(); int type = map.blockAt(p[0],p[1],p[2]);
                if (type < 0 || !reached.add(Coordinates.block(p[0],p[1],p[2]))) continue;
                if (type > 0) surface.add(Coordinates.block(p[0],p[1],p[2]));
                if (type == 1) continue;
                for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1,1}) {
                    int[] n = p.clone(); n[axis] += sign; queue.add(n);
                }
            }
            VisibilitySnapshot actual = scan(map,.5,-9.5,.5);
            Set<Long> actualCells = new HashSet<>(), actualSurfaces = new HashSet<>();
            actual.forEachBlock(actualCells::add); actual.forEachTerrainBlock(actualSurfaces::add);
            assertEquals(reached, actualCells, "trial " + trial); assertEquals(surface, actualSurfaces);
        }
    }
}
