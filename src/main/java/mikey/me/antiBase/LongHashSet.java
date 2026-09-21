package mikey.me.antiBase;

import java.util.Arrays;

/** long set that takes any long value, incl packed negative coords */
public final class LongHashSet {
    private long[] table;
    private boolean[] occupied;
    private int size;
    private int mask;

    public LongHashSet(int expectedSize) {
        if (expectedSize < 0 || expectedSize > (1 << 28)) {
            throw new IllegalArgumentException("Invalid expected size: " + expectedSize);
        }
        int capacity = 16;
        while (capacity * 3L / 4 < expectedSize) capacity <<= 1;
        table = new long[capacity];
        occupied = new boolean[capacity];
        mask = capacity - 1;
    }

    public synchronized boolean add(long value) {
        int index = find(value);
        if (occupied[index]) return false;
        table[index] = value;
        occupied[index] = true;
        if (++size >= table.length * 3L / 4) grow();
        return true;
    }

    public synchronized boolean contains(long value) {
        return occupied[find(value)];
    }

    public synchronized boolean remove(long value) {
        int index = find(value);
        if (!occupied[index]) return false;
        occupied[index] = false;
        size--;
        // reinsert the cluster after it, no tombstones so probes cant grind to a halt
        for (index = (index + 1) & mask; occupied[index]; index = (index + 1) & mask) {
            long displaced = table[index];
            occupied[index] = false;
            int destination = find(displaced);
            table[destination] = displaced;
            occupied[destination] = true;
        }
        return true;
    }

    public synchronized int size() { return size; }

    public synchronized void clear() {
        Arrays.fill(occupied, false);
        size = 0;
    }

    public synchronized void forEach(LongConsumer consumer) {
        for (int i = 0; i < table.length; i++) {
            if (occupied[i]) consumer.accept(table[i]);
        }
    }

    @FunctionalInterface
    public interface LongConsumer { void accept(long value); }

    private int find(long value) {
        int index = mix(value) & mask;
        while (occupied[index] && table[index] != value) index = (index + 1) & mask;
        return index;
    }

    private void grow() {
        long[] oldTable = table;
        boolean[] oldOccupied = occupied;
        table = new long[oldTable.length << 1];
        occupied = new boolean[table.length];
        mask = table.length - 1;
        for (int i = 0; i < oldTable.length; i++) {
            if (oldOccupied[i]) {
                int index = find(oldTable[i]);
                table[index] = oldTable[i];
                occupied[index] = true;
            }
        }
    }

    private static int mix(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= key >>> 33;
        return (int) (key ^ (key >>> 32));
    }
}
