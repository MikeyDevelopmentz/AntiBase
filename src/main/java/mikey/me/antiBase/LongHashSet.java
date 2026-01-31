package mikey.me.antiBase;

public class LongHashSet {
    private static final long EMPTY = Long.MIN_VALUE;
    private long[] table;
    private int size;
    private int mask;
    private int resizeThreshold;

    public LongHashSet(int expectedSize) {
        int capacity = tableSizeFor(Math.max(16, expectedSize * 4 / 3));
        table = new long[capacity];
        mask = capacity - 1;
        resizeThreshold = capacity * 3 / 4;
        java.util.Arrays.fill(table, EMPTY);
    }

    public boolean add(long value) {
        if (value == EMPTY) value = Long.MIN_VALUE + 1;
        int idx = mix(value) & mask;
        int firstTombstone = -1;
        while (true) {
            long existing = table[idx];
            if (existing == EMPTY) {
                table[firstTombstone != -1 ? firstTombstone : idx] = value;
                if (++size >= resizeThreshold) grow();
                return true;
            }
            if (existing == TOMBSTONE && firstTombstone == -1) {
                firstTombstone = idx;
            } else if (existing == value) {
                return false;
            }
            idx = (idx + 1) & mask;
        }
    }

    public boolean contains(long value) {
        if (value == EMPTY) value = Long.MIN_VALUE + 1;
        int idx = mix(value) & mask;
        while (true) {
            long existing = table[idx];
            if (existing == EMPTY) return false;
            if (existing == value) return true;
            idx = (idx + 1) & mask;
        }
    }

    private static final long TOMBSTONE = Long.MIN_VALUE + 2;

    public boolean remove(long value) {
        if (value == EMPTY) value = Long.MIN_VALUE + 1;
        int idx = mix(value) & mask;
        while (true) {
            long existing = table[idx];
            if (existing == EMPTY) return false;
            if (existing == value) {
                table[idx] = TOMBSTONE;
                size--;
                return true;
            }
            idx = (idx + 1) & mask;
        }
    }

    public int size() {
        return size;
    }

    public void clear() {
        if (size > 0) {
            java.util.Arrays.fill(table, EMPTY);
            size = 0;
        }
    }

    public void forEach(LongConsumer consumer) {
        for (long v : table) {
            if (v != EMPTY && v != TOMBSTONE) consumer.accept(v);
        }
    }

    @FunctionalInterface
    public interface LongConsumer {
        void accept(long value);
    }

    private void grow() {
        long[] old = table;
        int newCapacity = old.length * 2;
        table = new long[newCapacity];
        mask = newCapacity - 1;
        resizeThreshold = newCapacity * 3 / 4;
        java.util.Arrays.fill(table, EMPTY);
        size = 0;
        for (long v : old) {
            if (v != EMPTY) add(v);
        }
    }

    private static int mix(long key) {
        key ^= (key >>> 33);
        key *= 0xff51afd7ed558ccdL;
        key ^= (key >>> 33);
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= (key >>> 33);
        return (int) key;
    }

    private static int tableSizeFor(int cap) {
        int n = -1 >>> Integer.numberOfLeadingZeros(cap - 1);
        return (n < 16) ? 16 : (n >= (1 << 30)) ? (1 << 30) : n + 1;
    }
}
