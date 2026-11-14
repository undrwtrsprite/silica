package dev.betavk.vox;

import net.minecraft.src.BiomeGenBase;
import net.minecraft.src.Block;
import net.minecraft.src.ChunkProviderGenerate;
import net.minecraft.src.ISaveHandler;
import net.minecraft.src.World;
import net.minecraft.src.WorldGenerator;

import java.util.Random;

/**
 * Replays the tree part of the game's chunk population (seed, random sequence, generators, biome rules) on a stand-in world that only knows the
 * terrain surface, so the trees of a distant chunk are the ones that will grow there. Lakes, clay and ores consume the same random numbers they do
 * in the real step (their effects are not needed); dungeons are assumed not to fit, so a chunk that holds a real dungeon can differ.
 */
final class TreePlanter {
    interface Sink {
        void block(int wx, int y, int wz, int id, int meta);
    }

    private static final int W = 48;
    private static final ThreadLocal<Sim> SIM = ThreadLocal.withInitial(() -> {
        Sim s = Sandbox.allocate(Sim.class);
        s.init();
        return s;
    });

    private TreePlanter() {
    }

    /** s = chunks (cx, cz), (cx+1, cz), (cx, cz+1), (cx+1, cz+1). */
    static void plant(ChunkProviderGenerate p, int cx, int cz, ColumnData[] s, Sink sink) {
        Sim w = SIM.get();
        w.begin(cx * 16, cz * 16, s);
        populateTrees(p, cx, cz, w, s);
        w.flush(sink);
    }

    private static boolean isWater(ColumnData[] s, int lx, int y, int lz) {
        ColumnData c = s[0];
        int col = lx * 16 + lz;
        return c.sid[col] == 9 && y >= c.floor[col] && y < c.hv[col];
    }

    private static void lakeRolls(Random r) {
        int n = r.nextInt(4) + 4;
        for (int i = 0; i < n; i++) for (int k = 0; k < 6; k++) r.nextDouble();
    }

    private static void veinRolls(Random r, int blocks) {
        r.nextFloat();
        r.nextInt(3);
        r.nextInt(3);
        for (int i = 0; i <= blocks; i++) r.nextDouble();
    }

    private static void ores(Random r, int count, int height, int blocks) {
        for (int i = 0; i < count; i++) {
            r.nextInt(16);
            r.nextInt(height);
            r.nextInt(16);
            veinRolls(r, blocks);
        }
    }

    private static void populateTrees(ChunkProviderGenerate p, int cx, int cz, World w, ColumnData[] s) {
        BiomeGenBase biome = s[3].corner;
        int x0 = cx * 16, z0 = cz * 16;
        Random r = p.rand;
        long seed = p.worldObj.getRandomSeed();
        r.setSeed(seed);
        long a = r.nextLong() / 2L * 2L + 1L, b = r.nextLong() / 2L * 2L + 1L;
        r.setSeed((long) cx * a + (long) cz * b ^ seed);
        if (r.nextInt(4) == 0) {
            r.nextInt(16);
            r.nextInt(128);
            r.nextInt(16);
            lakeRolls(r);
        }
        if (r.nextInt(8) == 0) {
            r.nextInt(16);
            int y = r.nextInt(r.nextInt(120) + 8);
            r.nextInt(16);
            if (y < 64 || r.nextInt(10) == 0) lakeRolls(r);
        }
        for (int i = 0; i < 8; i++) {
            r.nextInt(16);
            r.nextInt(128);
            r.nextInt(16);
            r.nextInt(2);
            r.nextInt(2);
        }
        for (int i = 0; i < 10; i++) {
            int x = r.nextInt(16), y = r.nextInt(128), z = r.nextInt(16);
            if (isWater(s, x, y, z)) veinRolls(r, 32);
        }
        ores(r, 20, 128, 32);
        ores(r, 10, 128, 32);
        ores(r, 20, 128, 16);
        ores(r, 20, 64, 8);
        ores(r, 2, 32, 8);
        ores(r, 8, 16, 7);
        ores(r, 1, 16, 7);
        r.nextInt(16);
        r.nextInt(16);
        r.nextInt(16);
        r.nextInt(16);
        veinRolls(r, 6);

        double d = 0.5D;
        int noise = (int) ((p.mobSpawnerNoise.func_806_a((double) x0 * d, (double) z0 * d) / 8.0D + r.nextDouble() * 4.0D + 4.0D) / 3.0D);
        int trees = 0;
        if (r.nextInt(10) == 0) trees++;
        if (biome == BiomeGenBase.forest) trees += noise + 5;
        if (biome == BiomeGenBase.rainforest) trees += noise + 5;
        if (biome == BiomeGenBase.seasonalForest) trees += noise + 2;
        if (biome == BiomeGenBase.taiga) trees += noise + 5;
        if (biome == BiomeGenBase.desert) trees -= 20;
        if (biome == BiomeGenBase.tundra) trees -= 20;
        if (biome == BiomeGenBase.plains) trees -= 20;
        for (int i = 0; i < trees; i++) {
            int x = x0 + r.nextInt(16) + 8, z = z0 + r.nextInt(16) + 8;
            WorldGenerator g = biome.getRandomWorldGenForTrees(r);
            g.func_517_a(1.0D, 1.0D, 1.0D);
            g.generate(w, r, x, w.getHeightValue(x, z), z);
        }
    }

    /** A World that is only a block lookup: the terrain surface of four chunks plus whatever the tree generators place. */
    static final class Sim extends World {
        private int ox, oz;
        private ColumnData[] s;
        private byte[] ids, meta;
        private int[] over, touched;
        private int touchedN;

        private Sim() {
            super((ISaveHandler) null, null, 0L);
        }

        void init() {
            ids = new byte[W * W * 128];
            meta = new byte[W * W * 128];
            over = new int[W * W];
            touched = new int[W * W * 128 / 4];
        }

        void begin(int ox, int oz, ColumnData[] s) {
            this.ox = ox;
            this.oz = oz;
            this.s = s;
            touchedN = 0;
        }

        void flush(Sink sink) {
            for (int i = 0; i < touchedN; i++) {
                int idx = touched[i], id = ids[idx];
                if (id != 0) {
                    int y = idx & 127, c = idx >> 7;
                    sink.block(ox + c / W, y, oz + c % W, id & 255, meta[idx]);
                }
                ids[idx] = 0;
                meta[idx] = 0;
                over[idx >> 7] = 0;
            }
            touchedN = 0;
        }

        @Override public int getBlockId(int x, int y, int z) {
            int lx = x - ox, lz = z - oz;
            if (lx < 0 || lz < 0 || lx >= W || lz >= W || y < 0 || y >= 128) return 0;
            int idx = ((lx * W) + lz) * 128 + y;
            int id = ids[idx] & 255;
            if (id != 0) return id;
            if (lx >= 32 || lz >= 32) return 0;
            ColumnData c = s[(lz >> 4) * 2 + (lx >> 4)];
            int col = (lx & 15) * 16 + (lz & 15), hv = c.hv[col];
            if (y == hv - 1) return c.sid[col] & 255;
            return y >= hv ? 0 : 1;
        }

        @Override public int getHeightValue(int x, int z) {
            int lx = x - ox, lz = z - oz;
            if (lx < 0 || lz < 0 || lx >= 32 || lz >= 32) return 0;
            ColumnData c = s[(lz >> 4) * 2 + (lx >> 4)];
            return Math.max(c.hv[(lx & 15) * 16 + (lz & 15)], over[lx * W + lz]);
        }

        private boolean put(int x, int y, int z, int id, int m) {
            int lx = x - ox, lz = z - oz;
            if (lx < 0 || lz < 0 || lx >= W || lz >= W || y < 0 || y >= 128) return false;
            int idx = ((lx * W) + lz) * 128 + y;
            if (ids[idx] == 0 && touchedN < touched.length) touched[touchedN++] = idx;
            ids[idx] = (byte) id;
            meta[idx] = (byte) m;
            if (id != 0 && y + 1 > over[lx * W + lz]) over[lx * W + lz] = y + 1;
            return true;
        }

        @Override public boolean setBlock(int x, int y, int z, int id) {
            return put(x, y, z, id, 0);
        }

        @Override public boolean setBlockAndMetadata(int x, int y, int z, int id, int m) {
            return put(x, y, z, id, m);
        }
    }
}
