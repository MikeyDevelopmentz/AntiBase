package mikey.me.antiBase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class LongHashSetTest {
    @Test
    void supportsEveryLong() {
        LongHashSet set = new LongHashSet(0);
        long[] values = {Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MIN_VALUE + 2, Long.MAX_VALUE, -1, 0, 1};
        for (long value : values) assertTrue(set.add(value));
        assertEquals(values.length, set.size());
        Set<Long> actual = new HashSet<>();
        set.forEach(actual::add);
        for (long value : values) {
            assertTrue(actual.contains(value));
            assertTrue(set.remove(value));
            assertFalse(set.contains(value));
        }
        assertEquals(0, set.size());
    }

    @Test
    @Timeout(5)
    void removalChurnKeepsProbesAlive() {
        LongHashSet set = new LongHashSet(0);
        for (long value = 0; value < 100000; value++) {
            assertTrue(set.add(value));
            assertTrue(set.remove(value));
            assertFalse(set.contains(value + 1));
        }
        set.clear();
        assertTrue(set.add(Long.MIN_VALUE));
    }

    @Test
    @Timeout(10)
    void matchesJdkSet() {
        LongHashSet actual = new LongHashSet(0);
        Set<Long> expected = new HashSet<>();
        Random random = new Random(7319);
        for (int i = 0; i < 100000; i++) {
            long value = random.nextBoolean() ? random.nextInt(512) : random.nextLong();
            switch (random.nextInt(3)) {
                case 0 -> assertEquals(expected.add(value), actual.add(value));
                case 1 -> assertEquals(expected.remove(value), actual.remove(value));
                case 2 -> assertEquals(expected.contains(value), actual.contains(value));
            }
            assertEquals(expected.size(), actual.size());
            if (i % 10000 == 0) {
                Set<Long> contents = new HashSet<>();
                actual.forEach(contents::add);
                assertEquals(expected, contents);
                expected.clear();
                actual.clear();
            }
        }
        Set<Long> contents = new HashSet<>();
        actual.forEach(contents::add);
        assertEquals(expected, contents);
    }

    @Test
    void rejectsBadCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LongHashSet(-1));
        assertThrows(IllegalArgumentException.class, () -> new LongHashSet(Integer.MAX_VALUE));
    }
}
