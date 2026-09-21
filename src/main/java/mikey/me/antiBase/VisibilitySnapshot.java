package mikey.me.antiBase;

/** eligibility, terrain and connected space each get their own section bitmap */
final class VisibilitySnapshot {
    static final VisibilitySnapshot EMPTY = new VisibilitySnapshot(SectionBits.EMPTY, SectionBits.EMPTY, false);
    private final SectionBits sight;
    private final SectionBits terrain;
    private final boolean budgetLimited;
    private final SectionBits connected;

    // for explicit block sets, sections come from the cells
    VisibilitySnapshot(LongHashSet blocks, LongHashSet ignoredSections) { this(blocks, ignoredSections, false); }

    VisibilitySnapshot(LongHashSet blocks, LongHashSet ignoredSections, boolean budgetLimited) {
        SectionBits.Builder builder = new SectionBits.Builder();
        blocks.forEach(builder::add);
        sight = builder.build();
        terrain = sight;
        this.budgetLimited = budgetLimited;
        connected = SectionBits.EMPTY;
    }

    VisibilitySnapshot(SectionBits sight, SectionBits terrain, boolean budgetLimited) {
        this(sight, terrain, budgetLimited, SectionBits.EMPTY);
    }

    VisibilitySnapshot(SectionBits sight, SectionBits terrain, boolean budgetLimited, SectionBits connected) {
        this.sight = sight;
        this.terrain = terrain;
        this.budgetLimited = budgetLimited;
        this.connected = connected;
    }

    boolean isBlockVisible(int x, int y, int z) { return sight.contains(x, y, z); }
    boolean isTerrainVisible(int x, int y, int z) { return terrain.contains(x, y, z); }
    boolean isConnected(int x, int y, int z) { return connected.contains(x, y, z); }
    boolean isSectionVisible(int x, int y, int z) { return sight.containsSection(x, y, z); }
    int blockCount() { return sight.size(); }
    int terrainCount() { return terrain.size(); }
    int sectionCount() { return sight.sectionCount(); }
    boolean budgetLimited() { return budgetLimited; }
    void forEachBlock(LongHashSet.LongConsumer consumer) { sight.forEach(consumer); }
    void forEachTerrainBlock(LongHashSet.LongConsumer consumer) { terrain.forEach(consumer); }

    VisibilitySnapshot withTerrainPadding(SectionBits padding, boolean limited) {
        return new VisibilitySnapshot(sight.union(padding), terrain.union(padding), budgetLimited || limited, connected);
    }

    /** explosions only remove occluders, so add checked surfaces without wiping old coverage */
    VisibilitySnapshot withRevealed(VisibilitySnapshot patch) {
        return new VisibilitySnapshot(sight.union(patch.sight), terrain.union(patch.terrain), budgetLimited || patch.budgetLimited,
                connected.union(patch.connected));
    }

    void forEachChangedBlock(VisibilitySnapshot previous, LongHashSet.LongConsumer consumer) {
        // air to air in a ray path changes nothing for the client.
        // shown solid turning to air is still a terrain removal and gets an update
        terrain.forEachDifference(previous.terrain, consumer);
    }

    LongHashSet changedChunks(VisibilitySnapshot previous) {
        LongHashSet changed = new LongHashSet(32);
        forEachChangedBlock(previous, key -> changed.add(Coordinates.chunk(Coordinates.blockX(key) >> 4, Coordinates.blockZ(key) >> 4)));
        return changed;
    }
}
