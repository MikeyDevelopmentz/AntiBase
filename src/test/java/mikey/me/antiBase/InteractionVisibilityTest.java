package mikey.me.antiBase;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class InteractionVisibilityTest {
    @Test
    void expiredPinRefreshesOnce() {
        AtomicLong now = new AtomicLong(100);
        InteractionVisibility interactions = new InteractionVisibility(now::get);
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        interactions.pin(player, world, -17, -8, 33);
        LongHashSet refreshed = new LongHashSet(1);
        interactions.drainRefreshes((playerId, id, chunks) -> fail("The pin is still active"));
        now.addAndGet(2_000_000_001L);
        interactions.drainRefreshes((playerId, id, chunks) -> {
            assertEquals(player, playerId);
            assertEquals(world, id);
            chunks.forEach(refreshed::add);
        });
        assertEquals(1, refreshed.size());
        assertTrue(refreshed.contains(Coordinates.block(-17, -8, 33)));
        interactions.drainRefreshes((playerId, id, chunks) -> fail("Already drained"));
    }

    @Test
    void replacingLocalSightRefreshesOldBlocks() {
        AtomicLong now = new AtomicLong(100);
        InteractionVisibility interactions = new InteractionVisibility(now::get);
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        LongHashSet blocks = new LongHashSet(1);
        blocks.add(Coordinates.block(-17, -8, 33));
        interactions.local(player, world, new VisibilitySnapshot(blocks, new LongHashSet(0)));
        LongHashSet next = new LongHashSet(1);
        next.add(Coordinates.block(17, -8, 33));
        interactions.local(player, world, new VisibilitySnapshot(next, new LongHashSet(0)));
        LongHashSet refreshed = new LongHashSet(2);
        interactions.drainRefreshes((playerId, id, chunks) -> chunks.forEach(refreshed::add));
        assertTrue(refreshed.contains(Coordinates.block(-17, -8, 33)));
        assertFalse(refreshed.contains(Coordinates.block(17, -8, 33)));
        now.addAndGet(1_000_000_001L);
        interactions.drainRefreshes((playerId, id, chunks) -> chunks.forEach(refreshed::add));
        assertTrue(refreshed.contains(Coordinates.block(17, -8, 33)));
        assertFalse(interactions.allows(player, world, 17, -8, 33));
    }

    @Test
    void pinIsExactAndExpires() {
        AtomicLong now = new AtomicLong(100);
        InteractionVisibility interactions = new InteractionVisibility(now::get);
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        interactions.pin(player, world, 2, -8, 2);
        assertTrue(interactions.allows(player, world, 2, -8, 2));
        assertFalse(interactions.allows(player, world, 3, -8, 2));
        assertFalse(interactions.allows(player, UUID.randomUUID(), 2, -8, 2));
        now.addAndGet(2_000_000_001L);
        assertFalse(interactions.allows(player, world, 2, -8, 2));
    }

    @Test
    void emptySightExpiryClearsNothing() {
        AtomicLong now = new AtomicLong(100);
        InteractionVisibility interactions = new InteractionVisibility(now::get);
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        VisibilitySnapshot emptySpace = VisibilityScanner.scan((x, y, z) -> 0,
                0.5, -7.5, 0.5, -64, 320, 0, 8, 4096, 100000);
        interactions.local(player, world, emptySpace);
        assertTrue(interactions.allows(player, world, 1, -8, 0));
        now.addAndGet(1_000_000_001L);
        interactions.drainRefreshes((id, worldId, blocks) -> fail("An expired empty sight path needs no AIR clears"));
        assertFalse(interactions.allows(player, world, 1, -8, 0));
    }

    @Test
    void chunkArrivalKeepsMiningPin() {
        InteractionVisibility interactions = new InteractionVisibility();
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        LongHashSet blocks = new LongHashSet(1);
        blocks.add(Coordinates.block(5, -8, 0));
        interactions.local(player, world, new VisibilitySnapshot(blocks, new LongHashSet(0)));
        interactions.pin(player, world, 2, -8, 0);
        interactions.invalidateLocal(player);
        assertFalse(interactions.allows(player, world, 5, -8, 0));
        assertTrue(interactions.isPinned(player, world, 2, -8, 0));
        LongHashSet refreshed = new LongHashSet(1);
        interactions.drainRefreshes((id, worldId, changed) -> changed.forEach(refreshed::add));
        assertEquals(1, refreshed.size());
        assertTrue(refreshed.contains(Coordinates.block(5, -8, 0)));
    }

    @Test
    void localOverlayNeedsLineOfSight() {
        InteractionVisibility interactions = new InteractionVisibility();
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        VisibilitySnapshot local = VisibilityScanner.scan((x, y, z) ->
                y == -8 && z == 0 && x >= 0 && x <= 7 && x != 3 ? 0 : 1,
                0.5, -7.5, 0.5, -64, 320, 0, 8, 4096, 100000);
        interactions.local(player, world, local);
        assertTrue(interactions.allows(player, world, 3, -8, 0));
        assertFalse(interactions.allows(player, world, 4, -8, 0));
        interactions.remove(player);
        assertFalse(interactions.allows(player, world, 3, -8, 0));
    }
}

