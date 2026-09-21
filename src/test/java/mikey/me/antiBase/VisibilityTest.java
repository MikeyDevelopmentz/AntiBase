package mikey.me.antiBase;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VisibilityTest {
    @Test
    void sectionCoordsRoundTrip() {
        for (int x : new int[]{-1875000, -1, 0, 1, 1875000}) {
            for (int y : new int[]{-128, -4, -1, 0, 19, 127, 256}) {
                for (int z : new int[]{-1875000, -1, 0, 1, 1875000}) {
                    long key = Coordinates.section(x, y, z);
                    assertEquals(x, Coordinates.sectionX(key));
                    assertEquals(y, Coordinates.sectionY(key));
                    assertEquals(z, Coordinates.sectionZ(key));
                }
            }
        }
    }

    @Test
    void borderBlocksDoNotAlias() {
        LongHashSet blocks = new LongHashSet(4);
        assertTrue(blocks.add(Coordinates.block(-33554432, 0, 0)));
        assertTrue(blocks.add(Coordinates.block(-33554432, 1, 0)));
        assertTrue(blocks.add(Coordinates.block(-33554432, 2, 0)));
        assertEquals(3, blocks.size());
    }

    @Test
    void blockChangesStillRefresh() {
        LongHashSet oldSections = new LongHashSet(4);
        oldSections.add(Coordinates.section(-1, -4, 2));
        oldSections.add(Coordinates.section(-1, -3, 2));
        oldSections.add(Coordinates.section(8, -2, 2));
        LongHashSet newSections = new LongHashSet(4);
        newSections.add(Coordinates.section(-1, -2, 2));
        newSections.add(Coordinates.section(8, -2, 2));
        newSections.add(Coordinates.section(9, -2, 2));
        LongHashSet oldBlocks = new LongHashSet(4);
        oldBlocks.add(Coordinates.block(-1, -8, 32));
        oldBlocks.add(Coordinates.block(128, -8, 32));
        LongHashSet newBlocks = new LongHashSet(4);
        newBlocks.add(Coordinates.block(-2, -8, 32));
        newBlocks.add(Coordinates.block(128, -8, 32));
        newBlocks.add(Coordinates.block(144, -8, 32));
        VisibilitySnapshot oldState = new VisibilitySnapshot(oldBlocks, oldSections);
        VisibilitySnapshot newState = new VisibilitySnapshot(newBlocks, newSections);
        LongHashSet changed = newState.changedChunks(oldState);
        assertEquals(2, changed.size());
        assertTrue(changed.contains(Coordinates.chunk(-1, 2)));
        assertTrue(changed.contains(Coordinates.chunk(9, 2)));
        assertEquals(0, newState.changedChunks(newState).size());
        LongHashSet deltas = new LongHashSet(4);
        newState.forEachChangedBlock(oldState, deltas::add);
        assertEquals(3, deltas.size(), "Only the newly visible and newly hidden blocks need updates");
        assertTrue(deltas.contains(Coordinates.block(-1, -8, 32)));
        assertTrue(deltas.contains(Coordinates.block(-2, -8, 32)));
        assertTrue(deltas.contains(Coordinates.block(144, -8, 32)));
        assertFalse(deltas.contains(Coordinates.block(128, -8, 32)));
        newState.forEachChangedBlock(newState, key -> fail("Unchanged visibility must send no deltas"));
    }

    @Test
    void onlyVisibleBlocksAreExempt() {
        LongHashSet sections = new LongHashSet(1);
        sections.add(Coordinates.section(4, -1, 0));
        LongHashSet blocks = new LongHashSet(1);
        blocks.add(Coordinates.block(64, -8, 0));
        ViewerState state = new ViewerState(UUID.randomUUID(), -64, true, 0, -8, 0,
                new VisibilitySnapshot(blocks, sections));
        assertFalse(state.shouldHide(64, -8, 0, 0));
        assertTrue(state.shouldHide(65, -8, 0, 0));
        assertTrue(state.shouldHide(1, -8, 0, 0));
        assertTrue(state.shouldHide(17, -8, 0, 0));
        assertFalse(state.shouldHide(100, 0, 0, 0));
        assertTrue(state.shouldHide(100, -1, 0, 0));
    }

    @Test
    void patchesDoNotMutateSnapshots() {
        LongHashSet oldBlocks = new LongHashSet(1), newBlocks = new LongHashSet(1);
        LongHashSet oldSections = new LongHashSet(1), newSections = new LongHashSet(1);
        oldBlocks.add(Coordinates.block(0, -8, 0));
        oldSections.add(Coordinates.section(0, -1, 0));
        newBlocks.add(Coordinates.block(17, -8, 0));
        newSections.add(Coordinates.section(1, -1, 0));
        VisibilitySnapshot old = new VisibilitySnapshot(oldBlocks, oldSections);
        VisibilitySnapshot patch = new VisibilitySnapshot(newBlocks, newSections, true);
        VisibilitySnapshot merged = old.withRevealed(patch);
        assertEquals(2, merged.blockCount());
        assertTrue(merged.isBlockVisible(0, -8, 0));
        assertTrue(merged.isBlockVisible(17, -8, 0));
        assertTrue(merged.isSectionVisible(0, -1, 0));
        assertTrue(merged.isSectionVisible(1, -1, 0));
        assertTrue(merged.budgetLimited());
        assertEquals(1, old.blockCount());
        assertEquals(1, patch.blockCount());
        assertFalse(old.isBlockVisible(17, -8, 0));
        assertFalse(patch.isBlockVisible(0, -8, 0));
        assertEquals(2, merged.withRevealed(old).blockCount(), "An older full result must not erase a newer explosion reveal");
    }

    @Test
    void blacklistedWorldIsUntouched() {
        ViewerState state = new ViewerState(UUID.randomUUID(), -64, false, 0, 64, 0, VisibilitySnapshot.EMPTY);
        assertFalse(state.shouldHide(1000, -64, 1000, 0));
    }
}
