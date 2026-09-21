package mikey.me.antiBase;

/** preload a shell of solid blocks. never seed a flood with it */
final class TerrainPadding {
    static VisibilitySnapshot add(VisibilityScanner.BlockAccess source, VisibilitySnapshot visible,
                                  ConnectedVisibilityScanner.Window bounds, int hideBelow, int layers, int maxReads) {
        if (layers == 0 || visible.terrainCount() == 0) return visible;
        if (layers < 0 || layers > 4 || maxReads < 1) throw new IllegalArgumentException("Invalid terrain padding budget");
        Work work = new Work(source, visible, bounds, hideBelow, maxReads);
        LongHashSet frontier = new LongHashSet(visible.terrainCount());
        visible.forEachTerrainBlock(frontier::add);
        for (int layer = 0; layer < layers && frontier.size() > 0 && !work.limited; layer++) {
            LongHashSet next = new LongHashSet(frontier.size());
            frontier.forEach(key -> {
                int x = Coordinates.blockX(key), y = Coordinates.blockY(key), z = Coordinates.blockZ(key);
                work.offer(x - 1, y, z, next); work.offer(x + 1, y, z, next);
                work.offer(x, y - 1, z, next); work.offer(x, y + 1, z, next);
                work.offer(x, y, z - 1, next); work.offer(x, y, z + 1, next);
            });
            frontier = next;
        }
        return visible.withTerrainPadding(work.padding.build(), work.limited);
    }

    private static final class Work {
        final VisibilityScanner.BlockAccess source;
        final VisibilitySnapshot visible;
        final ConnectedVisibilityScanner.Window bounds;
        final int hideBelow, maxReads;
        final SectionBits.Builder examined = new SectionBits.Builder(), padding = new SectionBits.Builder();
        int reads;
        boolean limited;

        Work(VisibilityScanner.BlockAccess source, VisibilitySnapshot visible,
             ConnectedVisibilityScanner.Window bounds, int hideBelow, int maxReads) {
            this.source = source; this.visible = visible; this.bounds = bounds;
            this.hideBelow = hideBelow; this.maxReads = maxReads;
        }

        void offer(int x, int y, int z, LongHashSet next) {
            if (limited || y >= hideBelow || !bounds.contains(x, y, z) || visible.isBlockVisible(x, y, z)
                    || !examined.add(x, y, z)) return;
            if (reads >= maxReads || Thread.currentThread().isInterrupted()) { limited = true; return; }
            reads++;
            // stop at air, fluids, partial blocks, missing chunks. padding must not jump
            // into a sealed room and reveal it or grow the connected component
            if (source.blockAt(x, y, z) != 1) return;
            padding.add(x, y, z);
            next.add(Coordinates.block(x, y, z));
        }
    }
}
