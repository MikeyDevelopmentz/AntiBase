package mikey.me.antiBase;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** immutable 4096 bit section maps. lookups need no locks or boxed coords */
final class SectionBits {
    static final SectionBits EMPTY = new Builder().build();
    private final Long2ObjectOpenHashMap<long[]> sections;
    private final int size;

    private SectionBits(Long2ObjectOpenHashMap<long[]> sections, int size) {
        this.sections = sections;
        this.size = size;
    }

    int size() { return size; }
    int sectionCount() { return sections.size(); }
    boolean containsSection(int x, int y, int z) { return sections.containsKey(Coordinates.section(x, y, z)); }

    boolean contains(int x, int y, int z) {
        long[] bits = sections.get(Coordinates.section(x >> 4, y >> 4, z >> 4));
        int index = index(x, y, z);
        return bits != null && (bits[index >>> 6] & (1L << index)) != 0;
    }

    void forEach(LongHashSet.LongConsumer consumer) {
        var entries = sections.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            for (int word = 0; word < 64; word++) emit(entry.getLongKey(), word, entry.getValue()[word], consumer);
        }
    }

    void forEachDifference(SectionBits previous, LongHashSet.LongConsumer consumer) {
        if (this == previous) return;
        var entries = sections.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            long[] bits = entry.getValue(), old = previous.sections.get(entry.getLongKey());
            if (bits == old) continue;
            for (int word = 0; word < 64; word++) emit(entry.getLongKey(), word, bits[word] ^ (old == null ? 0 : old[word]), consumer);
        }
        entries = previous.sections.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (sections.containsKey(entry.getLongKey())) continue;
            for (int word = 0; word < 64; word++) emit(entry.getLongKey(), word, entry.getValue()[word], consumer);
        }
    }

    SectionBits union(SectionBits other) {
        if (other.size == 0 || this == other) return this;
        if (size == 0) return other;
        var merged = new Long2ObjectOpenHashMap<long[]>(sections);
        int count = size;
        var entries = other.sections.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            long[] right = entry.getValue(), left = sections.get(entry.getLongKey());
            if (left == right) continue;
            if (left == null) {
                merged.put(entry.getLongKey(), right); // arrays are immutable, sharing is fine
                for (long word : right) count += Long.bitCount(word);
                continue;
            }
            long[] combined = null;
            for (int word = 0; word < 64; word++) {
                long added = right[word] & ~left[word];
                if (added == 0) continue;
                if (combined == null) combined = left.clone();
                combined[word] |= added;
                count += Long.bitCount(added);
            }
            if (combined != null) merged.put(entry.getLongKey(), combined);
        }
        return new SectionBits(merged, count);
    }

    private static void emit(long section, int word, long bits, LongHashSet.LongConsumer consumer) {
        if (bits == 0) return;
        int x = Coordinates.sectionX(section) << 4, y = Coordinates.sectionY(section) << 4, z = Coordinates.sectionZ(section) << 4;
        while (bits != 0) {
            int index = (word << 6) + Long.numberOfTrailingZeros(bits);
            consumer.accept(Coordinates.block(x + (index & 15), y + (index >>> 8), z + ((index >>> 4) & 15)));
            bits &= bits - 1;
        }
    }

    private static int index(int x, int y, int z) { return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15); }

    /** one scan only, ownership moves once at build() */
    static final class Builder {
        private Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();
        private int size;
        private long lastSection;
        private long[] lastBits;

        int size() { return size; }

        boolean add(int x, int y, int z) {
            if (sections == null) throw new IllegalStateException("Already published");
            long section = Coordinates.section(x >> 4, y >> 4, z >> 4);
            if (lastBits == null || section != lastSection) {
                lastBits = sections.computeIfAbsent(section, ignored -> new long[64]);
                lastSection = section;
            }
            int index = index(x, y, z);
            long mask = 1L << index;
            if ((lastBits[index >>> 6] & mask) != 0) return false;
            lastBits[index >>> 6] |= mask;
            size++;
            return true;
        }

        void add(long key) { add(Coordinates.blockX(key), Coordinates.blockY(key), Coordinates.blockZ(key)); }

        boolean contains(int x, int y, int z) {
            if (sections == null) throw new IllegalStateException("Already published");
            long section = Coordinates.section(x >> 4, y >> 4, z >> 4);
            long[] bits = lastBits != null && section == lastSection ? lastBits : sections.get(section);
            int index = index(x, y, z);
            return bits != null && (bits[index >>> 6] & (1L << index)) != 0;
        }

        SectionBits build() {
            if (sections == null) throw new IllegalStateException("Already published");
            SectionBits result = new SectionBits(sections, size);
            sections = null;
            lastBits = null;
            return result;
        }
    }
}
