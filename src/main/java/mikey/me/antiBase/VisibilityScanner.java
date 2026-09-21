package mikey.me.antiBase;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

/** find far surfaces first, then fill exposed neighbours with sight checks */
final class VisibilityScanner {
    private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    // eighth step probes catch narrow cave entrances the quarter step grid missed
    private static final double[] DIRECTIONS = {0, -1, 1, -0.5, 0.5, -0.25, 0.25, -0.75, 0.75,
            -0.125, 0.125, -0.375, 0.375, -0.625, 0.625, -0.875, 0.875};
    private static final double[][] FACE_SAMPLES = {
            {0.001, 0.5, 0.5}, {0.999, 0.5, 0.5}, {0.5, 0.001, 0.5},
            {0.5, 0.999, 0.5}, {0.5, 0.5, 0.001}, {0.5, 0.5, 0.999}
    };
    private static final double[] EDGE_SAMPLES = {0.001, 0.999};

    @FunctionalInterface
    interface BlockAccess {
        /** -1 missing 0 air 1 opaque 2 see through non air (glass, water, partials) */
        int blockAt(int x, int y, int z);
    }

    static VisibilitySnapshot scan(BlockAccess blocks, double eyeX, double eyeY, double eyeZ,
                                   int minY, int maxY, int hideBelow, int radius, int budget, int rayBudget) {
        if (radius < 1 || budget < 1 || rayBudget < 1) throw new IllegalArgumentException("Scan limits must be positive");
        if (!Double.isFinite(eyeX) || !Double.isFinite(eyeY) || !Double.isFinite(eyeZ)
                || eyeY < minY || eyeY >= maxY || eyeY - radius >= hideBelow) return VisibilitySnapshot.EMPTY;
        return new Work(blocks, eyeX, eyeY, eyeZ, minY, maxY, hideBelow, radius, budget, rayBudget).run();
    }

    static VisibilitySnapshot scanChangedBlocks(BlockAccess blocks, double eyeX, double eyeY, double eyeZ,
                                                int minY, int maxY, int hideBelow, int radius,
                                                int budget, int rayBudget, LongHashSet changed) {
        if (radius < 1 || budget < 1 || rayBudget < 1) throw new IllegalArgumentException("Scan limits must be positive");
        if (!Double.isFinite(eyeX) || !Double.isFinite(eyeY) || !Double.isFinite(eyeZ)
                || eyeY < minY || eyeY >= maxY) return VisibilitySnapshot.EMPTY;
        Work work = new Work(blocks, eyeX, eyeY, eyeZ, minY, maxY, hideBelow, radius, budget, rayBudget);
        changed.forEach(key -> {
            if (work.rays.exhausted()) return;
            // leave budget for filling the surfaces the first rays found
            if (work.rays.remainingSteps() <= rayBudget / 2) { work.limited = true; return; }
            int x = Coordinates.blockX(key), y = Coordinates.blockY(key), z = Coordinates.blockZ(key);
            double dx = x + 0.5 - eyeX, dy = y + 0.5 - eyeY, dz = z + 0.5 - eyeZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > radius) return;
            // keep going through the edited cell, a blast can open onto a far wall.
            // the committed world still stops the ray if nothing got removed
            if (distance > 1.0e-9) work.probe(dx * radius / distance, dy * radius / distance, dz * radius / distance);
            else for (int[] offset : NEIGHBORS) work.probe(offset[0] * radius, offset[1] * radius, offset[2] * radius);
            for (int[] offset : NEIGHBORS) work.offer(x + offset[0], y + offset[1], z + offset[2]);
        });
        return work.finish();
    }

    private static final class Work {
        private final BlockAccess source;
        private final double eyeX, eyeY, eyeZ;
        private final int minY, maxY, radius, budget, maxReads;
        private final Long2ObjectOpenHashMap<byte[]> cache = new Long2ObjectOpenHashMap<>();
        private long cachedSection;
        private byte[] cachedCells;
        private int reads;
        private final SectionBits.Builder offered = new SectionBits.Builder();
        private final SectionBits.Builder visible = new SectionBits.Builder();
        private final SectionBits.Builder terrain = new SectionBits.Builder();
        private final LongArrayFIFOQueue frontier = new LongArrayFIFOQueue();
        private final VoxelRayTracer rays;
        private boolean limited;

        Work(BlockAccess source, double eyeX, double eyeY, double eyeZ, int minY, int maxY,
             int hideBelow, int radius, int budget, int rayBudget) {
            this.source = source;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.minY = minY;
            this.maxY = maxY;
            this.radius = radius;
            this.budget = budget;
            maxReads = (int) Math.min(Integer.MAX_VALUE, (long) budget + rayBudget);
            rays = new VoxelRayTracer(this::at, eyeX, eyeY, eyeZ, minY, maxY, hideBelow, rayBudget, (x, y, z, type) -> {
                boolean fresh = visible.add(x, y, z);
                if (type > 0) {
                    terrain.add(x, y, z);
                    if (fresh) offer(x, y, z);
                }
            });
        }

        VisibilitySnapshot run() {
            offer(VoxelRayTracer.floor(eyeX), VoxelRayTracer.floor(eyeY), VoxelRayTracer.floor(eyeZ));
            // long rays hit cave walls without flood filling all the empty air.
            // interleave the 6 directions so a small budget doesnt die in one hemisphere
            for (double a : DIRECTIONS) for (double b : DIRECTIONS) {
                if (rays.exhausted()) break;
                double length = radius / Math.sqrt(1 + a * a + b * b);
                for (int sign : new int[]{-1, 1}) {
                    probe(sign * length, a * length, b * length);
                    probe(a * length, sign * length, b * length);
                    probe(a * length, b * length, sign * length);
                }
            }
            return finish();
        }

        VisibilitySnapshot finish() {
            while (!frontier.isEmpty() && !rays.exhausted()) {
                long key = frontier.dequeueLong();
                int x = Coordinates.blockX(key), y = Coordinates.blockY(key), z = Coordinates.blockZ(key);
                int type = at(x, y, z);
                if (type < 0) continue;
                if (!visible.contains(x, y, z)) revealCandidate(rays, x, y, z);
                if (!visible.contains(x, y, z)) continue;
                if (type > 0) {
                    // diagonals too, fills stair stepped cave walls
                    for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dy != 0 || dz != 0) offer(x + dx, y + dy, z + dz);
                    }
                } else {
                    // dense coverage near the eye keeps narrow gaps + partials stable
                    for (int[] offset : NEIGHBORS) offer(x + offset[0], y + offset[1], z + offset[2]);
                }
            }
            return new VisibilitySnapshot(visible.build(), terrain.build(), limited || rays.exhausted() || !frontier.isEmpty());
        }

        private void probe(double x, double y, double z) {
            if (!rays.exhausted()) rays.trace(eyeX + x, eyeY + y, eyeZ + z);
        }

        private int at(int x, int y, int z) {
            if (y < minY || y >= maxY) return -1;
            long section = Coordinates.section(x >> 4, y >> 4, z >> 4);
            if (cachedCells == null || section != cachedSection) {
                byte[] cells = cache.get(section);
                if (cells == null) {
                    if (reads >= maxReads) { limited = true; return -1; }
                    cells = new byte[4096];
                    cache.put(section, cells);
                }
                cachedSection = section;
                cachedCells = cells;
            }
            int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            byte known = cachedCells[index];
            if (known != 0) return known - 2;
            if (reads >= maxReads) { limited = true; return -1; }
            byte value = (byte) source.blockAt(x, y, z);
            cachedCells[index] = (byte) (value + 2);
            reads++;
            return value;
        }

        private void offer(int x, int y, int z) {
            if (y < minY || y >= maxY) return;
            double dx = x + 0.5 - eyeX, dy = y + 0.5 - eyeY, dz = z + 0.5 - eyeZ;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance > (double) radius * radius) return;
            long key = Coordinates.block(x, y, z);
            if (offered.contains(x, y, z)) return;
            int type = at(x, y, z);
            if (type < 0 || (type == 0 && distance > 64)) return;
            if (type == 1 && !exposed(x, y, z)) return;
            if (offered.size() >= budget) { limited = true; return; }
            offered.add(x, y, z);
            frontier.enqueue(key);
        }

        private boolean exposed(int x, int y, int z) {
            for (int[] offset : NEIGHBORS) {
                int type = at(x + offset[0], y + offset[1], z + offset[2]);
                if (type == 0 || type == 2) return true;
            }
            return false;
        }
    }

    private static void revealCandidate(VoxelRayTracer rays, int x, int y, int z) {
        if (rays.trace(x + 0.5, y + 0.5, z + 0.5)) return;
        for (double[] sample : FACE_SAMPLES) {
            if (rays.exhausted() || rays.trace(x + sample[0], y + sample[1], z + sample[2])) return;
        }
        for (double dx : EDGE_SAMPLES) for (double dy : EDGE_SAMPLES) for (double dz : EDGE_SAMPLES) {
            if (rays.exhausted() || rays.trace(x + dx, y + dy, z + dz)) return;
        }
    }
}
