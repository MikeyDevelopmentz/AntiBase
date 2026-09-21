package mikey.me.antiBase;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/** capped diagnostics. packet threads queue samples, only the server thread writes logs */
final class ConsoleDebug {
    enum Metric { CHUNKS, MASKED_BLOCKS, SUPPRESSED_UPDATES, AIR_CLEARS, REAL_UPDATES,
        INTERACTION_CORRECTIONS, METADATA_DROPPED, MINING, LOCAL_SCANS, SCANS, STALE_SCANS, BUDGET_LIMITS, DELTA_BLOCKS,
        EXPLOSION_BATCHES, PHYSICS_BATCHES, CLIENT_CHUNKS, ENTITY_HIDES, ENTITY_SHOWS, ENTITY_TELEPORTS }
    private static final int SAMPLE_LIMIT = 20;
    private volatile Session active;

    void configure(boolean enabled, UUID filter, String target) {
        // swap sessions so concurrent packet writes dont leak into the new capture
        active = enabled ? new Session(filter, target) : null;
    }

    boolean accepts(UUID player) {
        Session session = active;
        return session != null && session.accepts(player);
    }

    boolean enabled() { return active != null; }
    String status() { Session session = active; return session != null ? "ON (" + session.target + ")" : "OFF"; }

    void count(UUID player, Metric metric, long amount) {
        Session session = active;
        if (session != null && session.accepts(player)) session.counters.get(metric).add(amount);
    }

    void sample(UUID player, String message) { sample(player, message, false); }
    void important(UUID player, String message) { sample(player, message, true); }

    private void sample(UUID player, String message, boolean important) {
        Session session = active;
        if (session == null || !session.accepts(player)) return;
        synchronized (session.samples) {
            if (session.samples.size() >= SAMPLE_LIMIT) {
                session.dropped.increment();
                if (!important) return;
                session.samples.removeFirst();
            }
            session.samples.addLast("player=" + player + " " + message);
        }
    }

    void flush(Logger logger, String queueStatus) {
        Session session = active;
        if (session == null) return;
        StringBuilder summary = new StringBuilder("[debug] target=").append(session.target)
                .append(" mode=AIR+SUPPRESS ").append(queueStatus);
        session.counters.forEach((metric, counter) -> summary.append(' ')
                .append(metric.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(counter.sumThenReset()));
        summary.append(" omittedSamples=").append(session.dropped.sumThenReset());
        String[] samples;
        synchronized (session.samples) {
            samples = session.samples.toArray(String[]::new);
            session.samples.clear();
        }
        logger.info(summary.toString());
        for (String sample : samples) logger.info("[debug] " + sample);
    }

    private static final class Session {
        final UUID filter;
        final String target;
        final Map<Metric, LongAdder> counters = new EnumMap<>(Metric.class);
        final ArrayDeque<String> samples = new ArrayDeque<>();
        final LongAdder dropped = new LongAdder();
        Session(UUID filter, String target) {
            this.filter = filter;
            this.target = target;
            for (Metric metric : Metric.values()) counters.put(metric, new LongAdder());
        }
        boolean accepts(UUID player) { return filter == null || filter.equals(player); }
    }
}
