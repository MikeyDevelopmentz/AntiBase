package mikey.me.antiBase;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** what we actually sent each connection. if unknown, never assume its masked */
final class ClientViewTracker {
    private final Map<UUID, View> viewers = new ConcurrentHashMap<>();

    void reset(UUID player, UUID world) {
        View old = viewers.put(player, new View(world));
        if (old != null) old.close();
    }

    View get(UUID player, UUID world) {
        View view = viewers.get(player);
        return view != null && view.world.equals(world) ? view : null;
    }

    void remove(UUID player) {
        View old = viewers.remove(player);
        if (old != null) old.close();
    }

    void clear() {
        viewers.values().forEach(View::close);
        viewers.clear();
    }

    static final class View {
        private final UUID world;
        private final Map<Long, ChunkKnowledge> chunks = new ConcurrentHashMap<>();
        private volatile boolean active = true;
        View(UUID world) { this.world = world; }

        boolean wasReal(int x, int y, int z) {
            ChunkKnowledge chunk = chunks.get(Coordinates.chunk(x >> 4, z >> 4));
            return active && chunk != null && chunk.real.contains(Coordinates.block(x, y, z));
        }

        boolean isKnownMasked(int x, int y, int z) {
            ChunkKnowledge chunk = chunks.get(Coordinates.chunk(x >> 4, z >> 4));
            return active && chunk != null && (chunk.complete || chunk.masked.contains(Coordinates.block(x, y, z)))
                    && !chunk.real.contains(Coordinates.block(x, y, z));
        }

        synchronized void chunkSent(int cx, int cz, LongHashSet real) {
            if (active) chunks.put(Coordinates.chunk(cx, cz), new ChunkKnowledge(true, real));
        }

        synchronized void blockSent(int x, int y, int z, boolean real) {
            if (!active) return;
            ChunkKnowledge chunk = chunks.computeIfAbsent(Coordinates.chunk(x >> 4, z >> 4),
                    key -> new ChunkKnowledge(false, new LongHashSet(16)));
            long key = Coordinates.block(x, y, z);
            if (real) { chunk.real.add(key); chunk.masked.remove(key); }
            else { chunk.real.remove(key); chunk.masked.add(key); }
        }

        synchronized void unload(int cx, int cz) { chunks.remove(Coordinates.chunk(cx, cz)); }
        private synchronized void close() { active = false; chunks.clear(); }

        private static final class ChunkKnowledge {
            final boolean complete;
            final LongHashSet real;
            final LongHashSet masked = new LongHashSet(0);
            ChunkKnowledge(boolean complete, LongHashSet real) { this.complete = complete; this.real = real; }
        }
    }
}
