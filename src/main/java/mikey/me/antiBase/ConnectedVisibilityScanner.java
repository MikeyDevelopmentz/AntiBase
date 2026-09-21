package mikey.me.antiBase;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** 6-connected scanline flood. opaque cells are a wall, never a door */
final class ConnectedVisibilityScanner {
    record Window(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Window around(double x, double y, double z, int minY, int maxY, int radius) {
            int padding = ((radius + 15) >> 4) << 4;
            int cx = VoxelRayTracer.floor(x) & ~15, cy = VoxelRayTracer.floor(y) & ~15, cz = VoxelRayTracer.floor(z) & ~15;
            return new Window(cx - padding, Math.max(minY, cy - padding), cz - padding,
                    cx + 16 + padding, Math.min(maxY, cy + 16 + padding), cz + 16 + padding);
        }
        boolean contains(int x, int y, int z) {
            return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
        }
    }

    static VisibilitySnapshot scan(VisibilityScanner.BlockAccess source, double x, double y, double z,
                                   int minY, int maxY, int hideBelow, int radius) {
        if (!valid(x, y, z, minY, maxY, radius)) return VisibilitySnapshot.EMPTY;
        Work work = new Work(source, Window.around(x, y, z, minY, maxY, radius), hideBelow,
                VisibilitySnapshot.EMPTY, Integer.MAX_VALUE);
        work.offer(VoxelRayTracer.floor(x), VoxelRayTracer.floor(y), VoxelRayTracer.floor(z));
        return work.finish();
    }

    /** at tick end only grow the new space. a full worker scan finishes big openings */
    static VisibilitySnapshot expandChanges(VisibilityScanner.BlockAccess source, double x, double y, double z,
                                            int minY, int maxY, int hideBelow, int radius,
                                            VisibilitySnapshot previous, LongHashSet changed, int maxReads) {
        if (maxReads < 1) throw new IllegalArgumentException("Read limit must be positive");
        if (!valid(x, y, z, minY, maxY, radius)) return VisibilitySnapshot.EMPTY;
        Work work = new Work(source, Window.around(x, y, z, minY, maxY, radius), hideBelow, previous, maxReads);
        changed.forEach(key -> {
            int bx = Coordinates.blockX(key), by = Coordinates.blockY(key), bz = Coordinates.blockZ(key);
            // a blast in a sealed room doesnt magically count as connected
            if (!previous.isConnected(bx, by, bz) && !previous.isConnected(bx - 1, by, bz)
                    && !previous.isConnected(bx + 1, by, bz) && !previous.isConnected(bx, by - 1, bz)
                    && !previous.isConnected(bx, by + 1, bz) && !previous.isConnected(bx, by, bz - 1)
                    && !previous.isConnected(bx, by, bz + 1)) return;
            int type = work.at(bx, by, bz);
            if (type != 2 && type != 3 && type != 5) return;
            work.offer(bx, by, bz);
            // the blast can also remove previously connected transparent cells
            if (previous.isConnected(bx, by, bz)) {
                work.offer(bx - 1, by, bz); work.offer(bx + 1, by, bz);
                work.offer(bx, by - 1, bz); work.offer(bx, by + 1, bz);
                work.offer(bx, by, bz - 1); work.offer(bx, by, bz + 1);
            }
        });
        return work.finish();
    }

    private static boolean valid(double x, double y, double z, int minY, int maxY, int radius) {
        if (radius < 1 || radius > 96) throw new IllegalArgumentException("Flood radius must be 1..96");
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) < 30_000_001 && Math.abs(z) < 30_000_001 && y >= minY && y < maxY;
    }

    private static final class Work {
        final VisibilityScanner.BlockAccess source;
        final Window window;
        final int width, depth, layer, hideBelow, maxReads;
        final VisibilitySnapshot previous;
        // 0 unknown 1 opaque 2 air 3 transparent 4 missing 5 connected
        final byte[] cells;
        final Long2ObjectOpenHashMap<byte[]> sparse;
        long lastSection;
        byte[] lastCells;
        final IntArrayFIFOQueue spans = new IntArrayFIFOQueue();
        final SectionBits.Builder connected = new SectionBits.Builder();
        final SectionBits.Builder terrain = new SectionBits.Builder();
        int reads;
        boolean limited;

        Work(VisibilityScanner.BlockAccess source, Window window, int hideBelow, VisibilitySnapshot previous, int maxReads) {
            this.source = source; this.window = window; this.hideBelow = hideBelow;
            this.previous = previous; this.maxReads = maxReads;
            width = window.maxX - window.minX; depth = window.maxZ - window.minZ; layer = width * depth;
            cells = maxReads == Integer.MAX_VALUE ? new byte[layer * (window.maxY - window.minY)] : null;
            sparse = cells == null ? new Long2ObjectOpenHashMap<>() : null;
        }

        int index(int x, int y, int z) { return (y - window.minY) * layer + (z - window.minZ) * width + x - window.minX; }

        byte[] section(int x, int y, int z) {
            long key = Coordinates.section(x >> 4, y >> 4, z >> 4);
            if (lastCells == null || lastSection != key) {
                lastSection = key;
                lastCells = sparse.computeIfAbsent(key, ignored -> new byte[4096]);
            }
            return lastCells;
        }
        int localIndex(int x, int y, int z) { return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15); }
        int cached(int x, int y, int z) {
            return cells != null ? cells[index(x,y,z)] : section(x,y,z)[localIndex(x,y,z)];
        }
        void cache(int x, int y, int z, int type) {
            if (cells != null) cells[index(x,y,z)] = (byte) type;
            else section(x,y,z)[localIndex(x,y,z)] = (byte) type;
        }

        int at(int x, int y, int z) {
            if (!window.contains(x, y, z)) return 4;
            int type = cached(x, y, z);
            if (type != 0) return type;
            if (reads >= maxReads || Thread.currentThread().isInterrupted()) { limited = true; return 4; }
            reads++;
            int block = source.blockAt(x, y, z);
            type = block < 0 ? 4 : block == 1 ? 1 : block == 0 ? 2 : 3;
            // surface checks still read the committed world even for already known cells
            if (type == 1 || type == 3) {
                if (y < hideBelow) terrain.add(x, y, z);
            }
            if ((type == 2 || type == 3) && previous.isConnected(x, y, z)) type = 5;
            cache(x, y, z, type);
            return type;
        }

        int offer(int x, int y, int z) {
            int type = at(x, y, z);
            if (type != 2 && type != 3) return x;
            mark(x, y, z);
            int left = x, right = x;
            while (open(at(left - 1, y, z))) mark(--left, y, z);
            while (open(at(right + 1, y, z))) mark(++right, y, z);
            spans.enqueue(index(left, y, z));
            spans.enqueue(right - left);
            return right;
        }

        boolean open(int type) { return type == 2 || type == 3; }
        void mark(int x, int y, int z) {
            cache(x, y, z, 5);
            // keep connected AIR above the cutoff too, paths can leave and re-enter
            connected.add(x, y, z);
        }

        void row(int left, int right, int y, int z) {
            for (int x = left; x <= right && !limited; x++) x = offer(x, y, z);
        }

        VisibilitySnapshot finish() {
            while (!spans.isEmpty() && !limited) {
                if (Thread.currentThread().isInterrupted()) { limited = true; break; }
                int start = spans.dequeueInt(), length = spans.dequeueInt();
                int y = start / layer + window.minY, z = start % layer / width + window.minZ;
                int left = start % width + window.minX, right = left + length;
                row(left, right, y - 1, z); row(left, right, y + 1, z);
                row(left, right, y, z - 1); row(left, right, y, z + 1);
            }
            SectionBits space = connected.build(), surfaces = terrain.build();
            return new VisibilitySnapshot(space.union(surfaces), surfaces, limited, space);
        }
    }
}
