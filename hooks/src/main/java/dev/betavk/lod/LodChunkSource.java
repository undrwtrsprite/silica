package dev.betavk.lod;

import dev.betavk.vox.GenWorld;
import net.minecraft.src.BiomeGenBase;
import net.minecraft.src.Block;
import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkProviderGenerate;
import net.minecraft.src.CompressedStreamTools;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.RegionFileCache;
import net.minecraft.src.SaveHandler;
import net.minecraft.src.World;
import net.minecraft.src.WorldChunkManager;

import java.io.DataInputStream;
import java.io.File;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides real chunk block/data/light/climate snapshots for LOD building, in strict priority order:
 *   1. Live/recently-edited chunk snapshots captured on the game thread
 *   2. Saved McRegion chunks read from disk (including all player builds and edits from earlier sessions)
 *   3. Procedural Beta 1.7.3 terrain + caves + biome trees/snow/ice + skylight generated on LOD workers
 */
public final class LodChunkSource {
    private LodChunkSource() {
    }

    public static final class ChunkSnap {
        public final byte[] blocks;
        public final byte[] data;
        public final byte[] skyLight;
        public final byte[] blockLight;
        public final float[] temp = new float[256];
        public final float[] hum = new float[256];

        ChunkSnap(byte[] blocks, byte[] data, byte[] skyLight, byte[] blockLight) {
            this.blocks = blocks;
            this.data = data;
            this.skyLight = skyLight;
            this.blockLight = blockLight;
        }
    }

    static final class WorkerGen {
        final GenWorld genWorld;
        final ChunkProviderGenerate provider;
        final WorldChunkManager manager;
        final Random rng = new Random();
        final byte[] skyTmp = new byte[32768];
        final byte[] blkTmp = new byte[32768];
        BiomeGenBase[] biomes;

        WorkerGen(World real, long seed) {
            this.genWorld = GenWorld.create(real, seed);
            this.provider = new ChunkProviderGenerate(genWorld, seed);
            this.manager = genWorld.manager();
        }
    }

    private static volatile File saveDir;
    private static final ConcurrentHashMap<Long, ChunkSnap> liveSnaps = new ConcurrentHashMap<>();
    private static final int MAX_LIVE_SNAPS = 2048;

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static void reset(World w) {
        liveSnaps.clear();
        if (w != null && w.saveHandler instanceof SaveHandler sh) {
            saveDir = sh.getSaveDirectory();
        } else {
            saveDir = null;
        }
    }

    /** Game thread: capture an immutable snapshot of a loaded/edited/saved chunk. */
    public static void captureChunk(Chunk c) {
        if (c == null || c.blocks == null || c.blocks.length != 32768) return;
        if (liveSnaps.size() > MAX_LIVE_SNAPS) {
            // Simple bounded clear of older entries when cache overflows; disk & gen remain available.
            liveSnaps.clear();
        }
        byte[] bl = c.blocks.clone();
        byte[] dt = c.data != null && c.data.data != null ? c.data.data.clone() : new byte[16384];
        byte[] sk = c.skylightMap != null && c.skylightMap.data != null ? c.skylightMap.data.clone() : new byte[16384];
        byte[] bk = c.blocklightMap != null && c.blocklightMap.data != null ? c.blocklightMap.data.clone() : new byte[16384];
        liveSnaps.put(key(c.xPosition, c.zPosition), new ChunkSnap(bl, dt, sk, bk));
    }

    /** Worker thread: resolve chunk (cx, cz) from live edits, McRegion disk, or generator fallback. */
    static ChunkSnap fetch(int cx, int cz, WorkerGen wg) {
        wg.biomes = wg.manager.loadBlockGeneratorData(wg.biomes, cx * 16, cz * 16, 16, 16);

        ChunkSnap snap = liveSnaps.get(key(cx, cz));
        if (snap == null) {
            snap = readRegion(saveDir, cx, cz);
        }
        if (snap == null) {
            snap = generateFallback(cx, cz, wg);
        } else {
            // Live/disk snapshots carry exact blocks/light; attach exact biome climate from manager.
            ChunkSnap view = new ChunkSnap(snap.blocks, snap.data, snap.skyLight, snap.blockLight);
            for (int i = 0; i < 256; i++) {
                view.temp[i] = (float) wg.manager.temperature[i];
                view.hum[i] = (float) wg.manager.humidity[i];
            }
            return view;
        }
        return snap;
    }

    private static ChunkSnap readRegion(File dir, int cx, int cz) {
        if (dir == null) return null;
        File reg = new File(new File(dir, "region"), "r." + (cx >> 5) + "." + (cz >> 5) + ".mcr");
        if (!reg.isFile()) return null;
        try {
            NBTTagCompound root;
            synchronized (RegionFileCache.class) {
                DataInputStream in = RegionFileCache.getChunkInputStream(dir, cx, cz);
                if (in == null) return null;
                try {
                    root = CompressedStreamTools.func_1141_a(in);
                } finally {
                    in.close();
                }
            }
            if (root == null || !root.hasKey("Level")) return null;
            NBTTagCompound level = root.getCompoundTag("Level");
            if (!level.hasKey("Blocks")) return null;
            byte[] blocks = level.getByteArray("Blocks");
            if (blocks == null || blocks.length != 32768) return null;
            byte[] data = level.getByteArray("Data");
            byte[] sky = level.getByteArray("SkyLight");
            byte[] blk = level.getByteArray("BlockLight");
            if (data == null || data.length != 16384) data = new byte[16384];
            if (sky == null || sky.length != 16384) sky = new byte[16384];
            if (blk == null || blk.length != 16384) blk = new byte[16384];
            return new ChunkSnap(blocks, data, sky, blk);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ChunkSnap generateFallback(int cx, int cz, WorkerGen wg) {
        byte[] blocks = new byte[32768];
        byte[] data = new byte[16384];
        byte[] sky = new byte[16384];
        byte[] blk = new byte[16384];

        ChunkProviderGenerate p = wg.provider;
        p.rand.setSeed((long) cx * 341873128712L + (long) cz * 132897987541L);
        p.generateTerrain(cx, cz, blocks, wg.biomes, wg.manager.temperature);
        p.replaceBlocksForBiome(cx, cz, blocks, wg.biomes);
        p.field_902_u.func_867_a(p, wg.genWorld, cx, cz, blocks);

        ChunkSnap snap = new ChunkSnap(blocks, data, sky, blk);
        for (int i = 0; i < 256; i++) {
            snap.temp[i] = (float) wg.manager.temperature[i];
            snap.hum[i] = (float) wg.manager.humidity[i];
        }
        plantFastTrees(cx, cz, blocks, data, wg);
        applySnowAndIce(blocks, snap.temp);
        fillLight(blocks, sky, blk, wg.genWorld.worldProvider.hasNoSky, wg.skyTmp, wg.blkTmp);
        return snap;
    }

    private static void setNibble(byte[] arr, int idx, int val) {
        int bi = idx >>> 1;
        if ((idx & 1) == 0) arr[bi] = (byte) ((arr[bi] & 0xF0) | (val & 15));
        else arr[bi] = (byte) ((arr[bi] & 0x0F) | ((val & 15) << 4));
    }

    private static void plantFastTrees(int cx, int cz, byte[] blocks, byte[] data, WorkerGen wg) {
        BiomeGenBase center = wg.biomes[8 * 16 + 8];
        if (center == BiomeGenBase.desert || center == BiomeGenBase.iceDesert || center == BiomeGenBase.tundra) return;

        Random rng = wg.rng;
        rng.setSeed((long) cx * 0x4F9939F508L ^ (long) cz * 0x1EF1565BD5L ^ wg.genWorld.getRandomSeed());
        double d = wg.provider.mobSpawnerNoise.func_806_a(cx * 8.0, cz * 8.0);
        int base = (int) ((d / 8.0 + rng.nextDouble() * 4.0 + 4.0) / 3.0);
        int count = rng.nextInt(10) == 0 ? 1 : 0;
        if (center == BiomeGenBase.forest || center == BiomeGenBase.rainforest) count += base + 5;
        else if (center == BiomeGenBase.seasonalForest) count += base + 2;
        else if (center == BiomeGenBase.taiga) count += base + 5;
        else if (center == BiomeGenBase.savanna || center == BiomeGenBase.plains) count = rng.nextInt(5) == 0 ? 1 : 0;
        else count += base;

        boolean birchAllowed = center == BiomeGenBase.forest;
        boolean pine = center == BiomeGenBase.taiga;
        for (int t = 0; t < count; t++) {
            int lx = rng.nextInt(16);
            int lz = rng.nextInt(16);
            int baseIdx = (lx << 11) | (lz << 7);
            int y = 120;
            while (y > 40 && blocks[baseIdx | y] == 0) y--;
            int gId = blocks[baseIdx | y] & 255;
            if (gId != 2 && gId != 3) continue;
            int top = y + 1;
            int height = 4 + rng.nextInt(3);
            if (top + height + 2 >= 127) continue;

            int logMeta = pine ? 1 : (birchAllowed && rng.nextInt(5) == 0 ? 2 : 0);
            int leafMeta = pine ? 1 : (logMeta == 2 ? 2 : 0);

            if (pine) {
                int th = height + 2;
                if (top + th + 1 >= 127) continue;
                for (int dy = 0; dy < th; dy++) {
                    int idx = baseIdx | (top + dy);
                    blocks[idx] = 17;
                    setNibble(data, idx, 1);
                }
                int maxR = 2 + rng.nextInt(2);
                for (int dy = 2; dy <= th + 1; dy++) {
                    int r = dy == th + 1 ? 0 : Math.max(1, maxR - ((dy - 2) / 2));
                    int ly = top + dy;
                    for (int oz = -r; oz <= r; oz++) {
                        int az = lz + oz;
                        if (az < 0 || az >= 16) continue;
                        for (int ox = -r; ox <= r; ox++) {
                            int ax = lx + ox;
                            if (ax < 0 || ax >= 16) continue;
                            if (Math.abs(ox) == r && Math.abs(oz) == r && r > 0) continue;
                            int idx = (ax << 11) | (az << 7) | ly;
                            if (blocks[idx] == 0) {
                                blocks[idx] = 18;
                                setNibble(data, idx, leafMeta);
                            }
                        }
                    }
                }
            } else {
                for (int dy = 0; dy < height; dy++) {
                    int idx = baseIdx | (top + dy);
                    blocks[idx] = 17;
                    setNibble(data, idx, logMeta);
                }
                for (int ly = top + height - 3; ly <= top + height; ly++) {
                    int dy = ly - (top + height);
                    int r = 1 - dy / 2;
                    for (int oz = -r; oz <= r; oz++) {
                        int az = lz + oz;
                        if (az < 0 || az >= 16) continue;
                        for (int ox = -r; ox <= r; ox++) {
                            int ax = lx + ox;
                            if (ax < 0 || ax >= 16) continue;
                            if (Math.abs(ox) == r && Math.abs(oz) == r && (rng.nextInt(2) == 0 || dy == 0)) continue;
                            int idx = (ax << 11) | (az << 7) | ly;
                            if (blocks[idx] == 0) {
                                blocks[idx] = 18;
                                setNibble(data, idx, leafMeta);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void applySnowAndIce(byte[] blocks, float[] temp) {
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int base = (lx << 11) | (lz << 7);
                int y = 127;
                while (y > 0) {
                    int id = blocks[base | y] & 255;
                    if (id != 0 && Block.lightOpacity[id] != 0) break;
                    y--;
                }
                int top = y + 1;
                if (top >= 127) continue;
                double adj = temp[(lx << 4) | lz] - ((top - 64) / 64.0) * 0.3;
                if (adj >= 0.5) continue;
                int id = blocks[base | y] & 255;
                if (id != 0 && id != 79 && id != 8 && id != 9 && id != 10 && id != 11
                        && blocks[base | top] == 0 && Block.opaqueCubeLookup[id]) {
                    blocks[base | top] = 78;
                }
            }
        }
    }

    private static void fillLight(byte[] blocks, byte[] sky, byte[] blk, boolean hasNoSky, byte[] st, byte[] bt) {
        boolean anyEmit = false;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int base = (lx << 11) | (lz << 7);
                int level = hasNoSky ? 0 : 15;
                for (int y = 127; y >= 0; y--) {
                    int idx = base | y;
                    int id = blocks[idx] & 255;
                    int op = Block.lightOpacity[id];
                    if (op >= 255) level = 0;
                    else if (level > 0) level = Math.max(0, level - op);
                    st[idx] = (byte) level;
                    int emit = Block.lightValue[id];
                    bt[idx] = (byte) emit;
                    if (emit > 0) anyEmit = true;
                }
            }
        }
        // Soften cave mouths, tree shade, and cliff overhangs with 2 bounded cardinal light sweeps.
        if (!hasNoSky) spreadLight(blocks, st, 2);
        if (anyEmit) spreadLight(blocks, bt, 2);

        for (int i = 0; i < 16384; i++) {
            int j = i << 1;
            sky[i] = (byte) ((st[j] & 15) | ((st[j + 1] & 15) << 4));
            if (anyEmit) blk[i] = (byte) ((bt[j] & 15) | ((bt[j + 1] & 15) << 4));
        }
    }

    private static void spreadLight(byte[] blocks, byte[] light, int passes) {
        for (int p = 0; p < passes; p++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int base = (x << 11) | (z << 7);
                    for (int y = 32; y < 127; y++) {
                        int idx = base | y;
                        int cur = light[idx] & 255;
                        int step = Math.max(1, Block.lightOpacity[blocks[idx] & 255]);
                        if (step >= 255) continue;
                        int n = cur;
                        if (x > 0) n = Math.max(n, (light[idx - 2048] & 255) - step);
                        if (x < 15) n = Math.max(n, (light[idx + 2048] & 255) - step);
                        if (z > 0) n = Math.max(n, (light[idx - 128] & 255) - step);
                        if (z < 15) n = Math.max(n, (light[idx + 128] & 255) - step);
                        n = Math.max(n, (light[idx - 1] & 255) - step);
                        n = Math.max(n, (light[idx + 1] & 255) - step);
                        if (n > cur) light[idx] = (byte) n;
                    }
                    for (int y = 126; y >= 32; y--) {
                        int idx = base | y;
                        int cur = light[idx] & 255;
                        int step = Math.max(1, Block.lightOpacity[blocks[idx] & 255]);
                        if (step >= 255) continue;
                        int n = Math.max(cur, (light[idx + 1] & 255) - step);
                        if (n > cur) light[idx] = (byte) n;
                    }
                }
            }
        }
    }
}
