package mikey.me.antiBase;

final class Coordinates {
    private Coordinates() { }

    static long block(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }

    static long section(int x, int y, int z) {
        return ((long) x & 0x3FFFFFL) << 42 | ((long) z & 0x3FFFFFL) << 20 | (y & 0xFFFFFL);
    }

    static int blockX(long key) { return (int) (key >> 38); }
    static int blockY(long key) { return (int) (key << 52 >> 52); }
    static int blockZ(long key) { return (int) (key << 26 >> 38); }

    static int sectionX(long key) { return (int) (key >> 42); }
    static int sectionY(long key) { return (int) (key << 44 >> 44); }
    static int sectionZ(long key) { return (int) (key << 22 >> 42); }
    static long chunk(int x, int z) { return (long) x << 32 | (z & 0xFFFFFFFFL); }
    static int chunkX(long key) { return (int) (key >> 32); }
    static int chunkZ(long key) { return (int) key; }
}
