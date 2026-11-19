package dev.betavk.lod;

import dev.betavk.vox.GenWorld;
import net.minecraft.src.Block;
import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkProviderGenerate;
import net.minecraft.src.CompressedStreamTools;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.RegionFileCache;
import net.minecraft.src.SaveHandler;
import net.minecraft.src.World;

import java.io.DataInputStream;
import java.io.File;
import java.util.Arrays;
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

    static final class SurfCell {
        long key = Long.MIN_VALUE;
        final byte[] topY = new byte[256];
        final byte[] topId = new byte[256];
        Object centerBiome;
    }

    static final class WorkerGen {
        final GenWorld genWorld;
        final ChunkProviderGenerate provider;
        final Object manager;
        final Random rng = new Random();
        final byte[] genBlocks = new byte[32768];
        final byte[] genData = new byte[16384];
        final byte[] genSky = new byte[16384];
        final byte[] genBlk = new byte[16384];
        final ChunkSnap genSnap = new ChunkSnap(genBlocks, genData, genSky, genBlk);
        final byte[] surfBlocks = new byte[32768];
        final SurfCell[] surfCache = new SurfCell[256];
        Object[] biomes;
        Object[] surfBiomes;

        WorkerGen(World real, long seed) {
            this.genWorld = GenWorld.create(real, seed);
            this.provider = new ChunkProviderGenerate(genWorld, seed);
            this.manager = dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER ? genWorld.manager() : null;
            for (int i = 0; i < surfCache.length; i++) surfCache[i] = new SurfCell();
        }
    }

    private static volatile File saveDir;
    private static final ConcurrentHashMap<Long, ChunkSnap> liveSnaps = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> chunkHashes = new ConcurrentHashMap<>();
    private static final int MAX_LIVE_SNAPS = 512;
    private static final int MAX_CHUNK_HASHES = 8192;

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    static File saveDirectory() {
        return saveDir;
    }

    public static void reset(World w) {
        liveSnaps.clear();
        chunkHashes.clear();
        saveDir = dev.betavk.hooks.McCompat.saveDirectory(w);
    }

    private static long blockDataHash(byte[] blocks, byte[] data) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < blocks.length; i += 4) {
            int v = (blocks[i] & 255)
                    | ((blocks[i + 1] & 255) << 8)
                    | ((blocks[i + 2] & 255) << 16)
                    | ((blocks[i + 3] & 255) << 24);
            h = (h ^ v) * 0x100000001B3L;
        }
        if (data != null) {
            for (int i = 0; i < data.length; i += 4) {
                int v = (data[i] & 255)
                        | ((data[i + 1] & 255) << 8)
                        | ((data[i + 2] & 255) << 16)
                        | ((data[i + 3] & 255) << 24);
                h = (h ^ v) * 0x100000001B3L;
            }
        }
        return h;
    }

    /** Game thread: capture a chunk only when its block/data contents actually differ from the last known snapshot. */
    public static boolean captureIfChanged(Chunk c) {
        if (c == null || c.blocks == null || c.blocks.length != 32768) return false;
        long k = key(c.xPosition, c.zPosition);
        byte[] dtSrc = c.data != null && c.data.data != null && c.data.data.length == 16384 ? c.data.data : null;
        long h = blockDataHash(c.blocks, dtSrc);
        Long prev = chunkHashes.put(k, h);
        if (prev != null && prev == h) return false;
        if (chunkHashes.size() > MAX_CHUNK_HASHES) {
            int drop = MAX_CHUNK_HASHES / 4;
            var it = chunkHashes.keySet().iterator();
            while (it.hasNext() && drop-- > 0) {
                it.next();
                it.remove();
            }
        }
        if (liveSnaps.size() > MAX_LIVE_SNAPS) {
            int drop = MAX_LIVE_SNAPS / 4;
            var it = liveSnaps.keySet().iterator();
            while (it.hasNext() && drop-- > 0) {
                it.next();
                it.remove();
            }
        }
        byte[] bl = c.blocks.clone();
        byte[] dt = dtSrc != null ? dtSrc.clone() : new byte[16384];
        byte[] sk = c.skylightMap != null && c.skylightMap.data != null ? c.skylightMap.data.clone() : new byte[16384];
        byte[] bk = c.blocklightMap != null && c.blocklightMap.data != null ? c.blocklightMap.data.clone() : new byte[16384];
        liveSnaps.put(k, new ChunkSnap(bl, dt, sk, bk));
        return true;
    }

    /** Game thread: capture an immutable snapshot of a loaded/edited/saved chunk. */
    public static void captureChunk(Chunk c) {
        captureIfChanged(c);
    }

    /** Lock-free lookup of a cached live chunk snapshot (returns null if not captured). */
    static ChunkSnap peekSnap(int cx, int cz) {
        return liveSnaps.get(key(cx, cz));
    }

    /** Fast check whether any chunk in [cx0..cx0+span-1] x [cz0..cz0+span-1] exists in live snaps or on disk. */
    static boolean hasAnySavedChunk(int cx0, int cz0, int span) {
        if (!liveSnaps.isEmpty()) {
            for (int dx = 0; dx < span; dx++) {
                for (int dz = 0; dz < span; dz++) {
                    if (liveSnaps.containsKey(key(cx0 + dx, cz0 + dz))) return true;
                }
            }
        }
        File dir = saveDir;
        if (dir == null) return false;
        File regDir = new File(dir, "region");
        if (!regDir.isDirectory()) return false;
        int rx0 = cx0 >> 5, rx1 = (cx0 + span - 1) >> 5;
        int rz0 = cz0 >> 5, rz1 = (cz0 + span - 1) >> 5;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                if (new File(regDir, "r." + rx + "." + rz + ".mcr").isFile()) return true;
            }
        }
        return false;
    }

    /** Worker thread: resolve chunk (cx, cz) from live edits, McRegion disk, or generator fallback. */
    static ChunkSnap fetch(int cx, int cz, WorkerGen wg) {
        return fetch(cx, cz, wg, true);
    }

    /** Worker thread: resolve chunk (cx, cz), optionally including cross-chunk border tree canopies. */
    static ChunkSnap fetch(int cx, int cz, WorkerGen wg, boolean borderTrees) {
        if (dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER) BiomeSupport.loadBiomes(wg, cx, cz);

        ChunkSnap snap = liveSnaps.get(key(cx, cz));
        if (snap == null) {
            snap = readRegion(saveDir, cx, cz);
        }
        if (snap == null) {
            snap = generateFallback(cx, cz, wg, borderTrees);
        } else {
            // Live/disk snapshots carry exact blocks/light; attach exact biome climate from manager.
            ChunkSnap view = new ChunkSnap(snap.blocks, snap.data, snap.skyLight, snap.blockLight);
            if (dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER) {
                BiomeSupport.fillClimate(wg, view);
            } else {
                Arrays.fill(view.temp, 0.5F);
                Arrays.fill(view.hum, 0.5F);
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
            chunkHashes.putIfAbsent(key(cx, cz), blockDataHash(blocks, data));
            return new ChunkSnap(blocks, data, sky, blk);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ChunkSnap generateFallback(int cx, int cz, WorkerGen wg, boolean borderTrees) {
        byte[] blocks = wg.genBlocks;
        byte[] data = wg.genData;
        byte[] sky = wg.genSky;
        byte[] blk = wg.genBlk;
        Arrays.fill(blocks, (byte) 0);
        Arrays.fill(data, (byte) 0);
        Arrays.fill(blk, (byte) 0);

        ChunkProviderGenerate p = wg.provider;
        p.rand.setSeed((long) cx * 341873128712L + (long) cz * 132897987541L);
        ChunkSnap snap = wg.genSnap;
        Object centerBiome;
        if (dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER) {
            centerBiome = BiomeSupport.generateAndReplace(wg, cx, cz, blocks, false);
            BiomeSupport.fillClimate(wg, snap);
        } else {
            LegacyGen.generateAndReplace(p, cx, cz, blocks);
            Arrays.fill(snap.temp, 0.5F);
            Arrays.fill(snap.hum, 0.5F);
            centerBiome = null;
        }
        SurfCell selfSurf = storeSurface(cx, cz, blocks, centerBiome, wg);
        plantFastTrees(cx, cz, blocks, data, selfSurf, borderTrees, wg);
        applySnowAndIce(blocks, snap.temp);
        fillLightFast(blocks, sky, dev.betavk.hooks.McCompat.isNether(wg.genWorld));
        return snap;
    }

    private static SurfCell storeSurface(int cx, int cz, byte[] blocks, Object centerBiome, WorkerGen wg) {
        long k = key(cx, cz);
        int slot = ((cx * 73856093) ^ (cz * 19349663)) & 255;
        SurfCell cell = wg.surfCache[slot];
        cell.key = k;
        cell.centerBiome = centerBiome;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int baseIdx = (lx << 11) | (lz << 7);
                int y = 120;
                while (y > 40 && blocks[baseIdx | y] == 0) y--;
                int col = (lx << 4) | lz;
                cell.topY[col] = (byte) y;
                cell.topId[col] = blocks[baseIdx | y];
            }
        }
        return cell;
    }

    private static SurfCell getSurface(int cx, int cz, WorkerGen wg) {
        long k = key(cx, cz);
        int slot = ((cx * 73856093) ^ (cz * 19349663)) & 255;
        SurfCell cell = wg.surfCache[slot];
        if (cell.key == k) return cell;

        ChunkSnap live = liveSnaps.get(k);
        if (live != null) {
            Object b = dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER ? BiomeSupport.getCenterBiome(wg, cx, cz) : null;
            return storeSurface(cx, cz, live.blocks, b, wg);
        }

        byte[] sb = wg.surfBlocks;
        Arrays.fill(sb, (byte) 0);
        ChunkProviderGenerate p = wg.provider;
        p.rand.setSeed((long) cx * 341873128712L + (long) cz * 132897987541L);
        Object centerBiome;
        if (dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER) {
            centerBiome = BiomeSupport.generateAndReplace(wg, cx, cz, sb, true);
        } else {
            LegacyGen.generateAndReplace(p, cx, cz, sb);
            centerBiome = null;
        }
        return storeSurface(cx, cz, sb, centerBiome, wg);
    }

    private static void setNibble(byte[] arr, int idx, int val) {
        int bi = idx >>> 1;
        if ((idx & 1) == 0) arr[bi] = (byte) ((arr[bi] & 0xF0) | (val & 15));
        else arr[bi] = (byte) ((arr[bi] & 0x0F) | ((val & 15) << 4));
    }

    private static void plantFastTrees(int cx, int cz, byte[] blocks, byte[] data, SurfCell selfSurf, boolean borderTrees, WorkerGen wg) {
        plantChunkTreesInto(cx, cz, cx, cz, blocks, data, selfSurf, wg);
        if (!borderTrees) return;

        for (int dcx = -1; dcx <= 1; dcx++) {
            for (int dcz = -1; dcz <= 1; dcz++) {
                if (dcx == 0 && dcz == 0) continue;
                int tcx = cx + dcx, tcz = cz + dcz;
                long k = key(tcx, tcz);
                int slot = ((tcx * 73856093) ^ (tcz * 19349663)) & 255;
                SurfCell nb = wg.surfCache[slot].key == k ? wg.surfCache[slot] : null;
                Object center = !dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER ? null
                        : (nb != null ? nb.centerBiome : BiomeSupport.getCenterBiome(wg, tcx, tcz));
                if (dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER && BiomeSupport.isDry(center)) continue;
                int count = computeTreeCount(tcx, tcz, center, wg);
                if (count <= 0) continue;
                if (nb == null) nb = getSurface(tcx, tcz, wg);
                plantChunkTreesInto(tcx, tcz, cx, cz, blocks, data, nb, wg);
            }
        }
    }

    private static int computeTreeCount(int cx, int cz, Object center, WorkerGen wg) {
        Random rng = wg.rng;
        rng.setSeed((long) cx * 0x4F9939F508L ^ (long) cz * 0x1EF1565BD5L ^ dev.betavk.hooks.McCompat.worldSeed(wg.genWorld));
        double d = wg.provider.mobSpawnerNoise.func_806_a(cx * 8.0, cz * 8.0);
        int base = (int) ((d / 8.0 + rng.nextDouble() * 4.0 + 4.0) / 3.0);
        int count = rng.nextInt(10) == 0 ? 1 : 0;
        if (dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER) {
            return BiomeSupport.adjustTreeCount(center, count, base, rng);
        }
        return count + base;
    }

    private static void plantChunkTreesInto(int treeCX, int treeCZ, int targetCX, int targetCZ,
                                            byte[] blocks, byte[] data, SurfCell surf, WorkerGen wg) {
        Object center = surf.centerBiome;
        if (dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER && BiomeSupport.isDry(center)) return;
        int count = computeTreeCount(treeCX, treeCZ, center, wg);
        if (count <= 0) return;

        Random rng = wg.rng;
        boolean sameChunk = (treeCX == targetCX && treeCZ == targetCZ);
        int offX = (treeCX - targetCX) << 4;
        int offZ = (treeCZ - targetCZ) << 4;
        boolean birchAllowed = dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER && BiomeSupport.isForest(center);
        boolean pine = dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER && BiomeSupport.isTaiga(center);

        for (int t = 0; t < count; t++) {
            int lx = rng.nextInt(16);
            int lz = rng.nextInt(16);
            int col = (lx << 4) | lz;
            int y = surf.topY[col] & 255;
            int gId = surf.topId[col] & 255;
            if (gId != 2 && gId != 3) continue;
            int top = y + 1;
            int height = 4 + rng.nextInt(3);
            if (top + height + 2 >= 127) continue;

            int logMeta = pine ? 1 : (birchAllowed && rng.nextInt(5) == 0 ? 2 : 0);
            int leafMeta = pine ? 1 : (logMeta == 2 ? 2 : 0);
            int tx = lx + offX;
            int tz = lz + offZ;

            if (pine) {
                int th = height + 2;
                if (top + th + 1 >= 127) continue;
                int maxR = 2 + rng.nextInt(2);
                if (!sameChunk && (tx + maxR < 0 || tx - maxR >= 16 || tz + maxR < 0 || tz - maxR >= 16)) continue;
                if (sameChunk) {
                    int baseIdx = (lx << 11) | (lz << 7);
                    for (int dy = 0; dy < th; dy++) {
                        int idx = baseIdx | (top + dy);
                        blocks[idx] = 17;
                        setNibble(data, idx, 1);
                    }
                }
                for (int dy = 2; dy <= th + 1; dy++) {
                    int r = dy == th + 1 ? 0 : Math.max(1, maxR - ((dy - 2) / 2));
                    int ly = top + dy;
                    for (int oz = -r; oz <= r; oz++) {
                        int az = tz + oz;
                        if (az < 0 || az >= 16) continue;
                        for (int ox = -r; ox <= r; ox++) {
                            int ax = tx + ox;
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
                if (sameChunk) {
                    int baseIdx = (lx << 11) | (lz << 7);
                    for (int dy = 0; dy < height; dy++) {
                        int idx = baseIdx | (top + dy);
                        blocks[idx] = 17;
                        setNibble(data, idx, logMeta);
                    }
                }
                boolean touches = sameChunk || !(tx + 2 < 0 || tx - 2 >= 16 || tz + 2 < 0 || tz - 2 >= 16);
                for (int ly = top + height - 3; ly <= top + height; ly++) {
                    int dy = ly - (top + height);
                    int r = 1 - dy / 2;
                    for (int oz = -r; oz <= r; oz++) {
                        int az = tz + oz;
                        for (int ox = -r; ox <= r; ox++) {
                            boolean cornerSkip = Math.abs(ox) == r && Math.abs(oz) == r && (rng.nextInt(2) == 0 || dy == 0);
                            if (cornerSkip || !touches) continue;
                            int ax = tx + ox;
                            if (ax < 0 || ax >= 16 || az < 0 || az >= 16) continue;
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

    private static void fillLightFast(byte[] blocks, byte[] sky, boolean hasNoSky) {
        if (hasNoSky) {
            Arrays.fill(sky, (byte) 0);
            return;
        }
        int[] opTable = Block.lightOpacity;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int base = (lx << 11) | (lz << 7);
                int level = 15;
                for (int y = 127; y >= 1; y -= 2) {
                    int idxOdd = base | y;
                    int idOdd = blocks[idxOdd] & 255;
                    int opOdd = opTable[idOdd];
                    if (opOdd >= 255) level = 0;
                    else if (level > 0 && opOdd > 0) level = Math.max(0, level - opOdd);
                    int sOdd = level;

                    int idxEven = idxOdd - 1;
                    int idEven = blocks[idxEven] & 255;
                    int opEven = opTable[idEven];
                    if (opEven >= 255) level = 0;
                    else if (level > 0 && opEven > 0) level = Math.max(0, level - opEven);
                    int sEven = level;

                    sky[idxEven >>> 1] = (byte) ((sEven & 15) | ((sOdd & 15) << 4));
                }
            }
        }
    }

    private static final class BiomeSupport {
        static void loadBiomes(WorkerGen wg, int cx, int cz) {
            net.minecraft.src.WorldChunkManager mgr = (net.minecraft.src.WorldChunkManager) wg.manager;
            wg.biomes = mgr.loadBlockGeneratorData((net.minecraft.src.BiomeGenBase[]) wg.biomes, cx * 16, cz * 16, 16, 16);
        }

        static void fillClimate(WorkerGen wg, ChunkSnap snap) {
            net.minecraft.src.WorldChunkManager mgr = (net.minecraft.src.WorldChunkManager) wg.manager;
            for (int i = 0; i < 256; i++) {
                snap.temp[i] = (float) mgr.temperature[i];
                snap.hum[i] = (float) mgr.humidity[i];
            }
        }

        static Object generateAndReplace(WorkerGen wg, int cx, int cz, byte[] blocks, boolean surf) {
            net.minecraft.src.WorldChunkManager mgr = (net.minecraft.src.WorldChunkManager) wg.manager;
            net.minecraft.src.BiomeGenBase[] arr;
            if (surf) {
                arr = mgr.loadBlockGeneratorData((net.minecraft.src.BiomeGenBase[]) wg.surfBiomes, cx * 16, cz * 16, 16, 16);
                wg.surfBiomes = arr;
            } else {
                arr = (net.minecraft.src.BiomeGenBase[]) wg.biomes;
            }
            wg.provider.generateTerrain(cx, cz, blocks, arr, mgr.temperature);
            wg.provider.replaceBlocksForBiome(cx, cz, blocks, arr);
            return arr[8 * 16 + 8];
        }

        static Object getCenterBiome(WorkerGen wg, int cx, int cz) {
            return ((net.minecraft.src.WorldChunkManager) wg.manager).getBiomeGenAt(cx * 16 + 8, cz * 16 + 8);
        }

        static boolean isDry(Object center) {
            return center == net.minecraft.src.BiomeGenBase.desert
                    || center == net.minecraft.src.BiomeGenBase.iceDesert
                    || center == net.minecraft.src.BiomeGenBase.tundra;
        }

        static int adjustTreeCount(Object center, int count, int base, Random rng) {
            if (center == net.minecraft.src.BiomeGenBase.forest || center == net.minecraft.src.BiomeGenBase.rainforest) return count + base + 5;
            if (center == net.minecraft.src.BiomeGenBase.seasonalForest) return count + base + 2;
            if (center == net.minecraft.src.BiomeGenBase.taiga) return count + base + 5;
            if (center == net.minecraft.src.BiomeGenBase.savanna || center == net.minecraft.src.BiomeGenBase.plains) return rng.nextInt(5) == 0 ? 1 : 0;
            return count + base;
        }

        static boolean isForest(Object center) {
            return center == net.minecraft.src.BiomeGenBase.forest;
        }

        static boolean isTaiga(Object center) {
            return center == net.minecraft.src.BiomeGenBase.taiga;
        }
    }

    private static final class LegacyGen {
        private static final java.lang.invoke.MethodHandle GEN;
        private static final java.lang.invoke.MethodHandle REPL;
        static {
            java.lang.invoke.MethodHandle g = null, r = null;
            try {
                var lookup = java.lang.invoke.MethodHandles.publicLookup();
                var mt = java.lang.invoke.MethodType.methodType(void.class, int.class, int.class, byte[].class);
                g = lookup.findVirtual(ChunkProviderGenerate.class, "generateTerrain", mt);
                r = lookup.findVirtual(ChunkProviderGenerate.class, "replaceSurfaceBlocks", mt);
            } catch (Throwable ignored) {}
            GEN = g;
            REPL = r;
        }

        static void generateAndReplace(ChunkProviderGenerate p, int cx, int cz, byte[] blocks) {
            try {
                if (GEN != null) GEN.invokeExact(p, cx, cz, blocks);
                if (REPL != null) REPL.invokeExact(p, cx, cz, blocks);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }
}
