package mikey.me.antiBase;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/** opt in synthetic benchmark, no wall clock asserts in the test suite */
public final class VisibilityBenchmark {
    private static volatile int sink;

    public static void main(String[] args) {
        run("large-cave", (x, y, z) -> Math.abs(x) <= 30 && Math.abs(z) <= 30 && y >= -60 && y <= -1 ? 0 : 1);
        run("open-air", (x, y, z) -> 0);
        run("tunnel", (x, y, z) -> y >= -32 && y <= -29 && Math.abs(z) <= 2 ? 0 : 1);
    }

    private static void run(String name, VisibilityScanner.BlockAccess world) {
        for (int i = 0; i < 12; i++) measure(world);
        long[][] values = new long[15][];
        for (int i = 0; i < values.length; i++) values[i] = measure(world);
        long[] times = Arrays.stream(values).mapToLong(value -> value[0]).sorted().toArray();
        long[] bytes = Arrays.stream(values).mapToLong(value -> value[1]).sorted().toArray();
        long[] result = values[values.length - 1];
        System.out.printf(java.util.Locale.ROOT,
                "%s: twoScansMedianMs=%.3f allocatedKiB=%.1f visible=%d deltaRecords=%d airOnlyDeltas=%d%n",
                name, times[times.length / 2] / 1_000_000.0, bytes[bytes.length / 2] / 1024.0,
                result[2], result[3], result[4]);
    }

    private static long[] measure(VisibilityScanner.BlockAccess world) {
        var memory = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long beforeBytes = memory.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long before = System.nanoTime();
        VisibilitySnapshot first = VisibilityScanner.scan(world, 0.5, -30.5, 0.5, -64, 320, 0, 64, 50000, 2000000);
        VisibilitySnapshot second = VisibilityScanner.scan(world, 0.8, -30.5, 0.5, -64, 320, 0, 64, 50000, 2000000);
        int[] deltas = {0, 0};
        second.forEachChangedBlock(first, key -> {
            deltas[0]++;
            if (world.blockAt(Coordinates.blockX(key), Coordinates.blockY(key), Coordinates.blockZ(key)) == 0) deltas[1]++;
        });
        sink = second.blockCount() + deltas[0];
        return new long[]{System.nanoTime() - before,
                memory.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeBytes,
                second.blockCount(), deltas[0], deltas[1]};
    }
}
