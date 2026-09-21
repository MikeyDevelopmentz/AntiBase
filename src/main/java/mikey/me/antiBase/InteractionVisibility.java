package mikey.me.antiBase;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** short lived pins for trusted interactions + the latest local sight scan */
final class InteractionVisibility {
    private static final long PIN_NANOS = 2_000_000_000L;
    private static final long LOCAL_NANOS = 1_000_000_000L;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    InteractionVisibility() { this(System::nanoTime); }
    InteractionVisibility(LongSupplier clock) { this.clock = clock; }

    void pin(UUID player, UUID world, int x, int y, int z) {
        Window window = windows.compute(player, (id, old) -> old != null && old.world.equals(world) ? old : new Window(world));
        long now = clock.getAsLong();
        expirePins(window, now);
        long key = Coordinates.block(x, y, z);
        if (window.pins.containsKey(key) || window.pins.size() < 128) window.pins.put(key, now + PIN_NANOS);
    }

    VisibilitySnapshot local(UUID player, UUID world, VisibilitySnapshot visibility) {
        Window window = windows.compute(player, (id, old) -> old != null && old.world.equals(world) ? old : new Window(world));
        long now = clock.getAsLong();
        VisibilitySnapshot previous = window.local != null && window.local.expires > now ? window.local.visibility : VisibilitySnapshot.EMPTY;
        if (window.local != null) window.local.visibility.forEachTerrainBlock(window.changedBlocks::add);
        window.local = new Local(visibility, now + LOCAL_NANOS);
        return previous;
    }

    /** server thread drain. when a reveal expires the client state has to go too */
    void drainRefreshes(RefreshConsumer refresh) {
        long now = clock.getAsLong();
        for (var entry : windows.entrySet()) {
            Window window = entry.getValue();
            expirePins(window, now);
            Local local = window.local;
            if (local != null && local.expires <= now) {
                local.visibility.forEachTerrainBlock(window.changedBlocks::add);
                window.local = null;
            }
            if (window.changedBlocks.size() > 0) {
                refresh.accept(entry.getKey(), window.world, window.changedBlocks);
                window.changedBlocks.clear();
            }
        }
    }

    private static void expirePins(Window window, long now) {
        window.pins.entrySet().removeIf(entry -> {
            if (entry.getValue() > now) return false;
            window.changedBlocks.add(entry.getKey());
            return true;
        });
    }

    @FunctionalInterface
    interface RefreshConsumer { void accept(UUID player, UUID world, LongHashSet blocks); }

    boolean allows(UUID player, UUID world, int x, int y, int z) {
        Window window = windows.get(player);
        if (window == null || !window.world.equals(world)) return false;
        long now = clock.getAsLong();
        Long expires = window.pins.get(Coordinates.block(x, y, z));
        if (expires != null && expires > now) return true;
        Local local = window.local;
        return local != null && local.expires > now && local.visibility.isBlockVisible(x, y, z);
    }

    boolean isPinned(UUID player, UUID world, int x, int y, int z) {
        Window window = windows.get(player);
        if (window == null || !window.world.equals(world)) return false;
        Long expires = window.pins.get(Coordinates.block(x, y, z));
        return expires != null && expires > clock.getAsLong();
    }

    void remove(UUID player) { windows.remove(player); }

    void invalidateLocal(UUID player) {
        Window window = windows.get(player);
        if (window != null && window.local != null) {
            window.local.visibility.forEachTerrainBlock(window.changedBlocks::add);
            window.local = null;
        }
    }
    void clear() { windows.clear(); }

    private static final class Window {
        final UUID world;
        final Map<Long, Long> pins = new ConcurrentHashMap<>();
        final LongHashSet changedBlocks = new LongHashSet(16);
        volatile Local local;
        Window(UUID world) { this.world = world; }
    }
    private record Local(VisibilitySnapshot visibility, long expires) { }
}
