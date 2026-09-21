package mikey.me.antiBase;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VoxelRayTracerTest {
    @Test void wallSurfaceDoesNotShowEntityInside() {
        VoxelRayTracer rays = new VoxelRayTracer((x,y,z) -> x == 1 ? 1 : 0,
                .5,-7.5,.5,-64,320,0,100,new LongHashSet(8));
        assertTrue(rays.trace(1.5,-7.5,.5));
        assertFalse(rays.traceOpen(1.5,-7.5,.5));
        assertFalse(rays.traceOpen(2.5,-7.5,.5));
        assertTrue(rays.traceOpen(.75,-7.5,.5));
    }
    @Test
    void touchingWallsDoNotLeak() {
        LongHashSet visible = new LongHashSet(8);
        VoxelRayTracer rays = new VoxelRayTracer((x, y, z) ->
                (x == 1 && z == 0) || (x == 0 && z == 1) ? 1 : 0,
                0.5, -7.5, 0.5, -64, 320, 0, 100, visible);
        assertFalse(rays.trace(2.5, -7.5, 2.5));
        assertFalse(visible.contains(Coordinates.block(1, -8, 1)));
        assertFalse(visible.contains(Coordinates.block(2, -8, 2)));
    }

    @Test
    void diagonalEdgeDoesNotReveal() {
        LongHashSet visible = new LongHashSet(8);
        VoxelRayTracer rays = new VoxelRayTracer((x, y, z) -> x == 1 && z == 0 ? 1 : 0,
                0.5, -7.5, 0.5, -64, 320, 0, 100, visible);
        assertFalse(rays.trace(2.5, -7.5, 2.5));
        assertFalse(visible.contains(Coordinates.block(0, -8, 1)));
        assertFalse(visible.contains(Coordinates.block(1, -8, 1)));
    }

    @Test
    void gridOriginDoesNotSkipWalls() {
        LongHashSet visible = new LongHashSet(8);
        VoxelRayTracer rays = new VoxelRayTracer((x, y, z) -> x == -1 ? 1 : 0,
                0, -7.5, 0.5, -64, 320, 0, 100, visible);
        assertFalse(rays.trace(-5.5, -7.5, 0.5));
        assertTrue(visible.contains(Coordinates.block(-1, -8, 0)));
        assertFalse(visible.contains(Coordinates.block(-2, -8, 0)));
    }

    @Test
    void nothingRevealedBehindFirstHit() {
        LongHashSet visible = new LongHashSet(8);
        VoxelRayTracer rays = new VoxelRayTracer((x, y, z) -> x == 3 ? 1 : 0,
                0.5, -7.5, 0.5, -64, 320, 0, 100, visible);
        assertTrue(rays.trace(3.5, -7.5, 0.5));
        assertFalse(rays.trace(4.5, -7.5, 0.5));
        for (int x = 0; x <= 3; x++) assertTrue(visible.contains(Coordinates.block(x, -8, 0)));
        assertFalse(visible.contains(Coordinates.block(4, -8, 0)));
    }

    @Test
    void verticalLayerBlocksRays() {
        LongHashSet visible = new LongHashSet(8);
        VoxelRayTracer rays = new VoxelRayTracer((x, y, z) -> y == -3 ? 1 : 0,
                0.5, 1.5, 0.5, -64, 320, 0, 100, visible);
        assertFalse(rays.trace(0.5, -8.5, 0.5));
        assertTrue(visible.contains(Coordinates.block(0, -3, 0)));
        assertFalse(visible.contains(Coordinates.block(0, -4, 0)));
    }

    @Test
    void budgetExhaustionStopsReads() {
        AtomicInteger reads = new AtomicInteger();
        LongHashSet visible = new LongHashSet(8);
        VoxelRayTracer rays = new VoxelRayTracer((x, y, z) -> { reads.incrementAndGet(); return 0; },
                0.5, -7.5, 0.5, -64, 320, 0, 3, visible);
        assertFalse(rays.trace(10.5, -7.5, 0.5));
        assertFalse(rays.trace(1.5, -7.5, 0.5));
        assertEquals(3, reads.get());
    }
}
