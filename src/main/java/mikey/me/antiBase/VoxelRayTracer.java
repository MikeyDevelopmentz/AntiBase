package mikey.me.antiBase;

/** voxel walk. reveals the ray path + the first opaque surface, nothing more */
final class VoxelRayTracer {
    private static final double EPSILON = 1.0e-9;
    private final VisibilityScanner.BlockAccess world;
    private final double eyeX, eyeY, eyeZ;
    private final int minY, maxY, hideBelow;
    private final CellVisitor visitor;
    private int remainingSteps;

    VoxelRayTracer(VisibilityScanner.BlockAccess world, double eyeX, double eyeY, double eyeZ,
                   int minY, int maxY, int hideBelow, int steps, LongHashSet visible) {
        this(world, eyeX, eyeY, eyeZ, minY, maxY, hideBelow, steps, visible, null);
    }

    VoxelRayTracer(VisibilityScanner.BlockAccess world, double eyeX, double eyeY, double eyeZ,
                   int minY, int maxY, int hideBelow, int steps, LongHashSet visible, LongHashSet.LongConsumer firstSurface) {
        this(world, eyeX, eyeY, eyeZ, minY, maxY, hideBelow, steps, (x, y, z, type) -> {
            long key = Coordinates.block(x, y, z);
            if (visible.add(key) && type > 0 && firstSurface != null) firstSurface.accept(key);
        });
    }

    VoxelRayTracer(VisibilityScanner.BlockAccess world, double eyeX, double eyeY, double eyeZ,
                   int minY, int maxY, int hideBelow, int steps, CellVisitor visitor) {
        this.world = world;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.minY = minY;
        this.maxY = maxY;
        this.hideBelow = hideBelow;
        this.remainingSteps = steps;
        this.visitor = visitor;
    }

    @FunctionalInterface
    interface CellVisitor { void visit(int x, int y, int z, int type); }

    boolean exhausted() { return remainingSteps <= 0 || Thread.currentThread().isInterrupted(); }
    int remainingSteps() { return remainingSteps; }

    boolean trace(double targetX, double targetY, double targetZ) {
        return trace(targetX, targetY, targetZ, true);
    }

    /** an entity sitting inside an opaque block is not a visible surface */
    boolean traceOpen(double targetX, double targetY, double targetZ) {
        return trace(targetX, targetY, targetZ, false);
    }

    private boolean trace(double targetX, double targetY, double targetZ, boolean allowOpaqueTarget) {
        int x = floor(eyeX), y = floor(eyeY), z = floor(eyeZ);
        int endX = floor(targetX), endY = floor(targetY), endZ = floor(targetZ);
        double dx = targetX - eyeX, dy = targetY - eyeY, dz = targetZ - eyeZ;
        int stepX = Double.compare(dx, 0), stepY = Double.compare(dy, 0), stepZ = Double.compare(dz, 0);
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double nextX = firstBoundary(eyeX, x, dx, stepX);
        double nextY = firstBoundary(eyeY, y, dy, stepY);
        double nextZ = firstBoundary(eyeZ, z, dz, stepZ);
        while (!exhausted()) {
            int type = visit(x, y, z, true);
            if (type < 0) return false;
            if (x == endX && y == endY && z == endZ) return allowOpaqueTarget || type != 1;
            if (type == 1) return false;
            double next = Math.min(nextX, Math.min(nextY, nextZ));
            if (next > 1 || !Double.isFinite(next)) return false;
            int axes = (nextX <= next + EPSILON ? 1 : 0)
                    | (nextY <= next + EPSILON ? 2 : 0)
                    | (nextZ <= next + EPSILON ? 4 : 0);
            // a diagonal ray must not slip between two opaque voxels touching at a corner.
            // check the side cells before stepping diagonally
            boolean blocked = false;
            for (int subset = 1; subset < 8; subset++) {
                if (subset == axes || (subset & axes) != subset) continue;
                int side = visit(x + ((subset & 1) != 0 ? stepX : 0),
                        y + ((subset & 2) != 0 ? stepY : 0),
                        z + ((subset & 4) != 0 ? stepZ : 0), false);
                if (side < 0 || side == 1) blocked = true;
            }
            if (blocked) return false;
            if ((axes & 1) != 0) { x += stepX; nextX += deltaX; }
            if ((axes & 2) != 0) { y += stepY; nextY += deltaY; }
            if ((axes & 4) != 0) { z += stepZ; nextZ += deltaZ; }
        }
        return false;
    }

    private int visit(int x, int y, int z, boolean reveal) {
        if (exhausted() || y < minY || y >= maxY) return -1;
        remainingSteps--;
        int type = world.blockAt(x, y, z);
        // touching a side cell on a zero area edge doesnt reveal it
        if (reveal && type >= 0 && y < hideBelow) {
            visitor.visit(x, y, z, type);
        }
        return type;
    }

    private static double firstBoundary(double origin, int cell, double direction, int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        return ((step > 0 ? cell + 1.0 : cell) - origin) / direction;
    }

    static int floor(double coordinate) { return (int) Math.floor(coordinate); }
}
