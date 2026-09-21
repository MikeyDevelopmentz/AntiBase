package mikey.me.antiBase;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ClientViewTrackerTest {
    @Test
    void unknownDataIsNotMasked() {
        ClientViewTracker tracker = new ClientViewTracker();
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        tracker.reset(player, world);
        assertFalse(tracker.get(player, world).isKnownMasked(1, -8, 1));
    }

    @Test
    void tracksRealAndMasked() {
        ClientViewTracker tracker = new ClientViewTracker();
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        tracker.reset(player, world);
        ClientViewTracker.View view = tracker.get(player, world);
        LongHashSet real = new LongHashSet(1);
        real.add(Coordinates.block(-1, -8, -1));
        view.chunkSent(-1, -1, real);
        assertTrue(view.wasReal(-1, -8, -1));
        assertFalse(view.isKnownMasked(-1, -8, -1));
        assertTrue(view.isKnownMasked(-2, -8, -1));
        view.blockSent(-1, -8, -1, false);
        assertFalse(view.wasReal(-1, -8, -1));
        assertTrue(view.isKnownMasked(-1, -8, -1));
        view.blockSent(-1, -8, -1, true);
        assertFalse(view.isKnownMasked(-1, -8, -1));
    }

    @Test
    void unloadDiscardsKnowledge() {
        ClientViewTracker tracker = new ClientViewTracker();
        UUID player = UUID.randomUUID(), oldWorld = UUID.randomUUID(), newWorld = UUID.randomUUID();
        tracker.reset(player, oldWorld);
        ClientViewTracker.View old = tracker.get(player, oldWorld);
        old.chunkSent(0, 0, new LongHashSet(0));
        old.unload(0, 0);
        assertFalse(old.isKnownMasked(1, -8, 1));
        tracker.reset(player, newWorld);
        old.chunkSent(0, 0, new LongHashSet(0)); // late callback from the old connection
        assertNull(tracker.get(player, oldWorld));
        assertFalse(old.isKnownMasked(1, -8, 1));
        assertFalse(tracker.get(player, newWorld).isKnownMasked(1, -8, 1));
    }

    @Test
    void singleAirClearDoesntMaskChunk() {
        ClientViewTracker tracker = new ClientViewTracker();
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        tracker.reset(player, world);
        ClientViewTracker.View view = tracker.get(player, world);
        view.blockSent(1, -8, 1, false);
        assertTrue(view.isKnownMasked(1, -8, 1));
        assertFalse(view.isKnownMasked(2, -8, 1));
    }
}
