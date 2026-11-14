package dev.betavk.hooks.world;

import dev.betavk.vox.GenWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.src.Block;
import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkCoordinates;
import net.minecraft.src.ChunkProvider;
import net.minecraft.src.ChunkProviderGenerate;
import net.minecraft.src.World;
import net.minecraft.src.WorldChunkManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Terrain prefetch. Overworld terrain (noise, caves, biome surface, heightmap, sky light) depends only on the world seed and the chunk coordinates, so workers
 * with their own generator instances create chunks ahead of demand, nearest first. When the game asks its generator for a chunk, a finished one is taken (or a
 * running job is waited for, or a queued one is cancelled and the chunk generated inline) and the world-dependent half of the lighting is finished on the
 * game thread. -Dbetavk.genworkers=N (default 2, 0 disables).
 */
public final class GenPool {
    private GenPool() {
    }

    public static final int WORKERS = Integer.getInteger("betavk.genworkers", 2);

    private static final class Job {
        final int x, z, priority, epoch;
        final AtomicInteger state = new AtomicInteger();   // 0 queued, 1 running, 2 done, 3 cancelled/taken inline
        volatile Chunk result;
        final CountDownLatch done = new CountDownLatch(1);

        Job(int x, int z, int priority, int epoch) {
            this.x = x;
            this.z = z;
            this.priority = priority;
            this.epoch = epoch;
        }
    }

    private static final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>(64, Comparator.comparingInt((Job j) -> j.priority));
    private static final ConcurrentHashMap<Long, Job> jobs = new ConcurrentHashMap<>();
    private static volatile ChunkProviderGenerate main;
    private static volatile World world;
    private static volatile long seed;
    private static volatile int epoch;
    private static boolean started;
    private static int tickCounter;
    private static final List<int[]> OFFSETS = new ArrayList<>();
    private static final int MAX_RADIUS = 22;
    private static final int SQUARE = 12;
    public static final AtomicInteger hits = new AtomicInteger(), waited = new AtomicInteger(), cancelled = new AtomicInteger(), misses = new AtomicInteger();

    static {
        for (int dx = -MAX_RADIUS; dx <= MAX_RADIUS; dx++) for (int dz = -MAX_RADIUS; dz <= MAX_RADIUS; dz++) OFFSETS.add(new int[]{dx, dz});
        OFFSETS.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** atEnd of ChunkProviderGenerate's constructor: the overworld's terrain provider was created (a new world). */
    public static void registerMain(ChunkProviderGenerate provider, World w, long s) {
        if (w instanceof GenWorld) return;   // our own worker instances
        epoch++;
        for (Job j : jobs.values()) j.state.set(3);
        jobs.clear();
        queue.clear();
        main = provider;
        world = w;
        seed = s;
    }

    private static void start() {
        if (started) return;
        started = true;
        for (int i = 0; i < WORKERS; i++) {
            Thread t = new Thread(GenPool::workerLoop, "Chunk generator " + i);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
        System.out.println("[betavk] chunk terrain prefetch on " + WORKERS + " worker threads");
    }

    /** What the game's generator does for one chunk, minus the lighting that reaches into neighbouring chunks. */
    private static Chunk generate(ChunkProviderGenerate p, GenWorld gw, World real, int cx, int cz) {
        byte[] blocks = new byte[32768];
        p.rand.setSeed((long) cx * 341873128712L + (long) cz * 132897987541L);
        WorldChunkManager m = gw.manager();
        p.biomesForGeneration = m.loadBlockGeneratorData(p.biomesForGeneration, cx * 16, cz * 16, 16, 16);
        p.generateTerrain(cx, cz, blocks, p.biomesForGeneration, m.temperature);
        p.replaceBlocksForBiome(cx, cz, blocks, p.biomesForGeneration);
        p.field_902_u.func_867_a(p, gw, cx, cz, blocks);   // caves
        Chunk c = new Chunk(real, blocks, cx, cz);
        heightAndSky(c);
        return c;
    }

    /** First half of the game's chunk lighting pass (Chunk.func_1024_c): heightmap and sky light from the chunk's own blocks. Touches no other chunk. */
    static void heightAndSky(Chunk c) {
        int lowest = 127;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int y = 127, base = x << 11 | z << 7;
                while (y > 0 && Block.lightOpacity[c.blocks[base + y - 1] & 255] == 0) y--;
                c.heightMap[z << 4 | x] = (byte) y;
                if (y < lowest) lowest = y;
                if (!c.worldObj.worldProvider.hasNoSky) {
                    int level = 15, yy = 127;
                    do {
                        level -= Block.lightOpacity[c.blocks[base + yy] & 255];
                        if (level > 0) c.skylightMap.setNibble(x, yy, z, level);
                        --yy;
                    } while (yy > 0 && level > 0);
                }
            }
        }
        c.lowestBlockHeight = lowest;
    }

    /** Second half (game thread): reads neighbouring chunks through the world and schedules lighting updates. */
    private static void neighbourLighting(Chunk c) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) c.func_996_c(x, z);
        c.isModified = true;
    }

    /** Test: a chunk made the way a worker makes it (without the neighbour lighting). */
    public static Chunk testGenerate(World real, long seed, int cx, int cz) {
        GenWorld gw = GenWorld.create(real, seed);
        return generate(new ChunkProviderGenerate(gw, seed), gw, real, cx, cz);
    }

    private static void workerLoop() {
        ChunkProviderGenerate provider = null;
        GenWorld gw = null;
        int providerEpoch = -1;
        while (true) {
            Job j;
            try {
                j = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            if (j.epoch != epoch || !j.state.compareAndSet(0, 1)) continue;   // stale world, or the game thread already took it
            try {
                if (provider == null || providerEpoch != j.epoch) {
                    gw = GenWorld.create(world, seed);
                    provider = new ChunkProviderGenerate(gw, seed);
                    providerEpoch = j.epoch;
                }
                j.result = generate(provider, gw, world, j.x, j.z);
            } catch (Throwable t) {
                j.result = null;   // the game thread falls back to generating it inline
            }
            j.state.set(2);
            j.done.countDown();
        }
    }

    /** atStartReturnIfNotNull of ChunkProviderGenerate.provideChunk (game thread): a prefetched chunk, or null (the game generates it inline). */
    public static Chunk take(ChunkProviderGenerate caller, int x, int z) {
        if (WORKERS <= 0 || caller != main) return null;
        Job j = jobs.remove(key(x, z));
        if (j == null || j.epoch != epoch) {
            misses.incrementAndGet();
            return null;
        }
        if (j.state.compareAndSet(0, 3)) {
            cancelled.incrementAndGet();   // queued but not started: cheaper to generate inline than to wait in the queue
            return null;
        }
        if (j.state.get() == 1) waited.incrementAndGet();
        try {
            j.done.await();
        } catch (InterruptedException e) {
            return null;
        }
        if (j.result == null) return null;
        hits.incrementAndGet();
        neighbourLighting(j.result);   // the half of finalisation that needs the world
        return j.result;
    }

    private static boolean enqueue(World w, int x, int z, int priority) {
        long k = key(x, z);
        if (jobs.containsKey(k) || w.chunkProvider.chunkExists(x, z)) return false;
        Job j = new Job(x, z, priority, epoch);
        jobs.put(k, j);
        queue.add(j);
        return true;
    }

    private static int dist2(Job j, int cx, int cz) {
        int dx = j.x - cx, dz = j.z - cz;
        return dx * dx + dz * dz;
    }

    /** atStart of Minecraft.runTick: populate what is queued under a time budget, and keep chunks around the player generated ahead of demand. */
    public static void onTick(Minecraft mc, long populateBudgetNs) {
        ChunkProvider cp = Populate.active;
        if (cp != null && mc.theWorld != null && Populate.isFor(cp, mc.theWorld)) {
            int pend = Populate.pending(cp);
            if (pend > 0) Populate.drain(cp, pend > 12 ? populateBudgetNs * 2 : populateBudgetNs);
        }
        if (WORKERS <= 0) return;
        World w = mc.theWorld;
        if (w == null || w != world || main == null || mc.thePlayer == null) return;
        start();
        int radius = Math.min(MAX_RADIUS, 3 + switch (mc.gameSettings.renderDistance) { case 0 -> 17; case 1 -> 9; case 2 -> 5; default -> 3; });   // 3 chunks beyond what the game loads
        int cx = (int) Math.floor(mc.thePlayer.posX) >> 4, cz = (int) Math.floor(mc.thePlayer.posZ) >> 4;
        if (jobs.size() < 160) {
            int added = 0;
            for (int[] o : OFFSETS) {
                int d2 = o[0] * o[0] + o[1] * o[1];
                if (d2 > 2 * MAX_RADIUS * MAX_RADIUS) break;
                // the game keeps a SQUARE of about +-10 chunks loaded around the player whatever the render distance, so prefetch the square too
                if (d2 > radius * radius && Math.max(Math.abs(o[0]), Math.abs(o[1])) > SQUARE) continue;
                if (enqueue(w, cx + o[0], cz + o[1], d2) && ++added >= 24) break;
            }
        }
        if (++tickCounter % 40 == 0) {   // drop finished chunks that fell far behind the player
            int keep = (Math.max(radius, 15) + 3) * (Math.max(radius, 15) + 3);
            jobs.entrySet().removeIf(e -> e.getValue().state.get() == 2 && dist2(e.getValue(), cx, cz) > keep);
        }
    }

    /** At world creation: start generating the spawn area before the loading screen asks for it. */
    public static void prefetchSpawn(World w) {
        if (WORKERS <= 0 || w != world || main == null) return;
        start();
        ChunkCoordinates sp = w.getSpawnPoint();
        int cx = sp.x >> 4, cz = sp.z >> 4;
        for (int[] o : OFFSETS) {
            if (o[0] * o[0] + o[1] * o[1] > 12 * 12) break;
            enqueue(w, cx + o[0], cz + o[1], o[0] * o[0] + o[1] * o[1]);
        }
    }

    public static String stats() {
        return "gen prefetch: hits " + hits + ", waited-on-running " + waited + ", cancelled-queued " + cancelled + ", misses " + misses;
    }
}
