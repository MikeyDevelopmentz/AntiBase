package mikey.me.antiBase;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SectionBitsTest {
    @Test
    void matchesPlainSets() {
        Random random = new Random(438921);
        SectionBits.Builder left = new SectionBits.Builder(), right = new SectionBits.Builder();
        Set<Long> expectedLeft = new HashSet<>(), expectedRight = new HashSet<>();
        for (int i = 0; i < 12000; i++) {
            int x = random.nextInt(97) - 48, y = random.nextInt(97) - 64, z = random.nextInt(97) - 48;
            long key = Coordinates.block(x, y, z);
            if (i % 3 != 0) { left.add(x, y, z); expectedLeft.add(key); }
            if (i % 3 != 1) { right.add(x, y, z); expectedRight.add(key); }
        }
        SectionBits a = left.build(), b = right.build();
        assertEquals(expectedLeft.size(), a.size());
        Set<Long> actual = new HashSet<>();
        a.forEach(actual::add);
        assertEquals(expectedLeft, actual);
        expectedRight.forEach(key -> assertEquals(expectedLeft.contains(key),
                a.contains(Coordinates.blockX(key), Coordinates.blockY(key), Coordinates.blockZ(key))));
        Set<Long> expectedUnion = new HashSet<>(expectedLeft);
        expectedUnion.addAll(expectedRight);
        SectionBits union = a.union(b);
        actual.clear();
        union.forEach(actual::add);
        assertEquals(expectedUnion, actual);
        assertEquals(expectedUnion.size(), union.size());
        Set<Long> expectedDifference = new HashSet<>(expectedUnion);
        expectedDifference.removeIf(key -> expectedLeft.contains(key) && expectedRight.contains(key));
        actual.clear();
        a.forEachDifference(b, key -> assertTrue(actual.add(key), "delta should be emitted once"));
        assertEquals(expectedDifference, actual);
        assertEquals(expectedLeft.size(), a.size());
        assertEquals(expectedRight.size(), b.size());
        assertThrows(IllegalStateException.class, () -> left.add(1, 1, 1));
        assertThrows(IllegalStateException.class, left::build);
    }

    @Test
    void everyBitRoundTrips() {
        SectionBits.Builder builder = new SectionBits.Builder();
        for (int y = -64; y < -48; y++) for (int z = -16; z < 0; z++) for (int x = -16; x < 0; x++) {
            assertTrue(builder.add(x, y, z));
            assertFalse(builder.add(x, y, z));
            assertTrue(builder.contains(x, y, z));
        }
        builder.add(-33554432, -2048, 33554431);
        builder.add(33554431, 2047, -33554432);
        SectionBits bits = builder.build();
        assertEquals(4098, bits.size());
        assertTrue(bits.contains(-33554432, -2048, 33554431));
        assertTrue(bits.contains(33554431, 2047, -33554432));
        Set<Long> seen = new HashSet<>();
        bits.forEach(key -> {
            assertTrue(seen.add(key));
            assertTrue(bits.contains(Coordinates.blockX(key), Coordinates.blockY(key), Coordinates.blockZ(key)));
        });
        assertEquals(bits.size(), seen.size());
        assertFalse(bits.contains(0, -64, -1));
        assertFalse(bits.contains(-1, -48, -1));
        assertTrue(bits.containsSection(-1, -4, -1));
        assertEquals(3, bits.sectionCount());
    }
}
