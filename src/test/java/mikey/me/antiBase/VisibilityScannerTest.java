package mikey.me.antiBase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VisibilityScannerTest {
    @Test
    void airMovementQueuesNoTerrain() {
        VisibilitySnapshot before = scan((x, y, z) -> 0, 0.5, -7.5, 0.5);
        VisibilitySnapshot after = scan((x, y, z) -> 0, 0.8, -7.5, 0.5);
        assertTrue(before.blockCount() > 1000);
        assertTrue(after.isBlockVisible(2, -8, 0), "sight cells stay available for entity checks");
        assertEquals(0, after.terrainCount());
        after.forEachChangedBlock(before, key -> fail("Moving an empty ray path needs no terrain packet"));
    }

    @Test
    void removedSolidGetsAirUpdate() {
        VisibilitySnapshot before = scan((x, y, z) -> x == 0 && y == -8 && z == 0 ? 0 : 1, 0.5, -7.5, 0.5);
        VisibilitySnapshot after = scan((x, y, z) -> (x == 0 || x == 1) && y == -8 && z == 0 ? 0 : 1, 0.5, -7.5, 0.5);
        LongHashSet deltas = new LongHashSet(16);
        after.forEachChangedBlock(before, deltas::add);
        assertTrue(deltas.contains(Coordinates.block(1, -8, 0)), "solid to air should clear the old block");
        assertTrue(deltas.contains(Coordinates.block(2, -8, 0)));
        assertFalse(deltas.contains(Coordinates.block(0, -8, 0)));
        assertTrue(after.isBlockVisible(1, -8, 0));
        assertFalse(after.isTerrainVisible(1, -8, 0));
    }

    @Test
    void raysPassThroughGlass() {
        VisibilitySnapshot result = scan((x, y, z) -> {
            if (y != -8 || z != 0 || x < 0 || x >= 20) return 1;
            return x == 12 ? 2 : 0;
        }, 0.5, -7.5, 0.5);
        assertTrue(result.isTerrainVisible(12, -8, 0));
        assertTrue(result.isTerrainVisible(20, -8, 0));
        assertFalse(result.isBlockVisible(21, -8, 0));
        assertTrue(result.isBlockVisible(13, -8, 0));
        assertFalse(result.isTerrainVisible(13, -8, 0));
    }

    @Test
    void largeCaveDoesNotWasteBudget() {
        VisibilitySnapshot result = VisibilityScanner.scan((x, y, z) ->
                Math.abs(x) <= 30 && Math.abs(z) <= 30 && y >= -60 && y <= -1 ? 0 : 1,
                0.5, -30.5, 0.5, -64, 320, 0, 64, 50000, 2000000);
        for (int y = -57; y <= -4; y++) for (int z = -27; z <= 27; z++) {
            assertTrue(result.isBlockVisible(31, y, z), "Missing exposed cave wall at 31," + y + "," + z);
            assertTrue(result.isBlockVisible(-31, y, z));
            assertTrue(result.isBlockVisible(z, y, 31));
            assertTrue(result.isBlockVisible(z, y, -31));
            assertFalse(result.isBlockVisible(32, y, z), "layer behind the wall should stay hidden");
        }
        for (int x = -27; x <= 27; x++) for (int z = -27; z <= 27; z++) assertTrue(result.isBlockVisible(x, -61, z));
        assertFalse(result.budgetLimited(), "The normal budget should finish this cave");
    }

    private VisibilitySnapshot scan(VisibilityScanner.BlockAccess world, double x, double y, double z) {
        return VisibilityScanner.scan(world, x, y, z, -64, 320, 0, 32, 50000, 2000000);
    }

    @Test
    void roomBehindWallStaysHidden() {
        VisibilityScanner.BlockAccess world = (x, y, z) ->
                y == -8 && z == 2 && x >= 1 && x <= 14 && x != 6 ? 0 : 1;
        VisibilitySnapshot result = scan(world, 2.5, -7.5, 2.5);
        assertTrue(result.isBlockVisible(5, -8, 2));
        assertTrue(result.isBlockVisible(6, -8, 2), "first wall renders normally");
        assertFalse(result.isBlockVisible(7, -8, 2), "sealed room nearby stays hidden");
        assertFalse(result.isBlockVisible(14, -8, 2));
        assertTrue(result.isSectionVisible(0, -1, 0), "section membership alone shouldnt reveal blocks");
    }

    @Test
    void tunnelCornerIsNotLineOfSight() {
        VisibilityScanner.BlockAccess bentTunnel = (x, y, z) -> y == -8
                && ((z == 0 && x >= 0 && x <= 8) || (x == 8 && z >= 0 && z <= 12)) ? 0 : 1;
        VisibilitySnapshot result = scan(bentTunnel, 0.5, -7.5, 0.5);
        assertTrue(result.isBlockVisible(7, -8, 0));
        assertFalse(result.isBlockVisible(8, -8, 8));
        assertFalse(result.isBlockVisible(8, -8, 12));
    }

    @Test
    void resealingRefreshesBothWays() {
        boolean[] open = {false};
        VisibilityScanner.BlockAccess world = (x, y, z) ->
                y == -8 && z == 2 && x >= 1 && x <= 14 && (x != 6 || open[0]) ? 0 : 1;
        VisibilitySnapshot closed = scan(world, 2.5, -7.5, 2.5);
        open[0] = true;
        VisibilitySnapshot opened = scan(world, 2.5, -7.5, 2.5);
        open[0] = false;
        VisibilitySnapshot resealed = scan(world, 2.5, -7.5, 2.5);
        assertFalse(closed.isBlockVisible(10, -8, 2));
        assertTrue(opened.isBlockVisible(10, -8, 2));
        assertFalse(resealed.isBlockVisible(10, -8, 2));
        assertTrue(opened.changedChunks(closed).contains(Coordinates.chunk(0, 0)));
        assertTrue(resealed.changedChunks(opened).contains(Coordinates.chunk(0, 0)));
    }

    @Test
    void exposedFaceIsRevealed() {
        VisibilityScanner.BlockAccess world = (x, y, z) -> {
            if (y != -8 || x < 0 || x > 6 || z < 0 || z > 4) return 1;
            return x == 3 && z == 1 ? 1 : 0;
        };
        VoxelRayTracer centerOnly = new VoxelRayTracer(world, 1.5, -7.5, 1.5,
                -64, 320, 0, 100, new LongHashSet(10));
        assertFalse(centerOnly.trace(5.5, -7.5, 2.5));
        assertTrue(scan(world, 1.5, -7.5, 1.5).isBlockVisible(5, -8, 2));
    }

    @Test
    void thinOpeningRevealsFarSurface() {
        VisibilityScanner.BlockAccess world = (x, y, z) -> {
            if (x < 0 || x > 20 || y != -8 || z < 0 || z > 2) return 1;
            return x == 8 && z != 1 ? 1 : 0;
        };
        VisibilitySnapshot result = scan(world, 1.5, -7.5, 1.5);
        for (int x = 1; x <= 20; x++) assertTrue(result.isBlockVisible(x, -8, 1), "Missing ray cell at " + x);
        assertTrue(result.isBlockVisible(21, -8, 1));
    }

    @Test
    void eyeHeightMatters() {
        VisibilityScanner.BlockAccess world = (x, y, z) -> {
            if (z != 0 || y < -10 || y > -7 || x < 0 || x > 8) return 1;
            return x == 3 && y != -7 ? 1 : 0;
        };
        VisibilitySnapshot standing = scan(world, 1.5, -6.5, 0.5);
        VisibilitySnapshot crouched = scan(world, 1.5, -8.8, 0.5);
        assertTrue(standing.isBlockVisible(7, -7, 0));
        assertFalse(crouched.isBlockVisible(7, -10, 0));
    }

    @Test
    void scansAllDirections() {
        VisibilitySnapshot result = scan((x, y, z) -> y == -8 && z == 0 ? 0 : 1, 0.5, -7.5, 0.5);
        assertTrue(result.isBlockVisible(10, -8, 0));
        assertTrue(result.isBlockVisible(-10, -8, 0));
    }

    @Test
    void unknownChunkStopsEverything() {
        VisibilitySnapshot result = scan((x, y, z) -> x < 16 ? 0 : -1, 15.5, -7.5, 0.5);
        assertFalse(result.isBlockVisible(16, -8, 0));
        assertFalse(result.isSectionVisible(1, -1, 0));
    }

    @Test
    @Timeout(5)
    void scanRespectsLimits() {
        AtomicInteger reads = new AtomicInteger();
        VisibilitySnapshot result = VisibilityScanner.scan((x, y, z) -> {
            reads.incrementAndGet();
            assertTrue(y >= -64 && y < 320);
            return 0;
        }, 0.5, -7.5, 0.5, -64, 320, 0, 32, 1000, 2000);
        // both candidate classification and ray walking have hard caps
        assertTrue(reads.get() <= 3000);
        assertTrue(result.blockCount() <= 2000);
        assertFalse(result.isBlockVisible(33, -8, 0), "rays shouldnt go past their reach");
    }

    @Test
    void negativeCoordsWork() {
        VisibilitySnapshot result = scan((x, y, z) -> y == -64 && z == -17 ? 0 : 1, -16.5, -63.5, -16.5);
        assertTrue(result.isBlockVisible(-16, -64, -17));
        assertTrue(result.isSectionVisible(-2, -4, -2));
        assertTrue(result.isSectionVisible(-1, -4, -2));
        assertFalse(result.isBlockVisible(-17, -65, -17));
    }

    @Test
    void lookingDownFromAboveCutoff() {
        VisibilitySnapshot result = scan((x, y, z) -> x == 0 && z == 0 ? 0 : 1, 0.5, 5.5, 0.5);
        assertTrue(result.isBlockVisible(0, -8, 0));
        assertFalse(result.isBlockVisible(0, 0, 0), "no visibility entry needed above the cutoff");
    }

    @Test
    void explosionReachesBeyondEditedCells() {
        LongHashSet changed = new LongHashSet(1);
        changed.add(Coordinates.block(12, -8, 0));
        VisibilitySnapshot patch = VisibilityScanner.scanChangedBlocks((x, y, z) ->
                        x >= 0 && x <= 25 && y >= -16 && y < 0 && Math.abs(z) <= 5 ? 0 : 1,
                0.5, -7.5, 0.5, -64, 320, 0, 32, 4096, 200000, changed);
        assertTrue(patch.isBlockVisible(26, -8, 0));
        assertTrue(patch.isBlockVisible(24, -17, 0));
        assertFalse(patch.isBlockVisible(27, -8, 0));
    }

    @Test
    void craterSurfaceOnlyAfterRemoval() {
        LongHashSet changed = new LongHashSet(1);
        changed.add(Coordinates.block(12, -8, 0));
        VisibilitySnapshot before = VisibilityScanner.scanChangedBlocks((x, y, z) ->
                        y == -8 && z == 0 && x >= 0 && x < 12 ? 0 : 1,
                0.5, -7.5, 0.5, -64, 320, 0, 32, 4096, 200000, changed);
        assertTrue(before.isBlockVisible(12, -8, 0));
        assertFalse(before.isBlockVisible(13, -8, 0), "Event coordinates must not pretend the wall is already air");
        VisibilitySnapshot after = VisibilityScanner.scanChangedBlocks((x, y, z) ->
                        y == -8 && z == 0 && x >= 0 && x <= 12 ? 0 : 1,
                0.5, -7.5, 0.5, -64, 320, 0, 32, 4096, 200000, changed);
        assertTrue(after.isBlockVisible(13, -8, 0), "An observer beyond the eight-block mining radius must see the new wall");
        assertFalse(after.isBlockVisible(14, -8, 0));
        assertFalse(after.isBlockVisible(13, -8, 1));
    }

    @Test
    void embeddedEyeDoesNotScan() {
        VisibilitySnapshot result = scan((x, y, z) -> x == 0 && y == -8 && z == 0 ? 1 : 0, 0.5, -7.5, 0.5);
        assertTrue(result.isBlockVisible(0, -8, 0));
        assertFalse(result.isBlockVisible(1, -8, 0));
    }

    @Test
    void invalidEyeReadsNothing() {
        VisibilityScanner.BlockAccess world = (x, y, z) -> { fail("Unexpected read"); return -1; };
        assertEquals(0, scan(world, 0.5, 80, 0.5).blockCount());
        assertEquals(0, scan(world, 0.5, -65, 0.5).blockCount());
        assertEquals(0, scan(world, Double.NaN, -8, 0.5).blockCount());
    }
}
