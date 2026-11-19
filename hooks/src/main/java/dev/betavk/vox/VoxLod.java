package dev.betavk.vox;

import dev.betavk.render.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.src.Frustrum;
import net.minecraft.src.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Distant terrain from real voxels. Columns of 32x32 voxels at five levels (1, 2, 4, 8, 16 blocks per voxel) are built from the game's generator
 * on worker threads (the level above is made from the four columns below it), meshed into display lists, and drawn by a quadtree selection: a
 * column is drawn at the coarsest level whose minimum distance allows it, otherwise it is split into the four columns below, so every spot is
 * drawn at exactly one level. Level-0 columns inside the loaded chunks are not drawn.
 */
public final class VoxLod {
    private VoxLod() {
    }

    /** Parked: the voxel LOD is not connected to the game for now. 0 = off. */
    public static volatile int distanceChunks;
    public static final int LEVELS = 5;
    /** Nearest distance (blocks) at which a level may be used. */
    private static final float[] START = {0, 192, 320, 560, 960};
    private static final int WORKERS = Integer.getInteger("betavk.voxworkers", 2);
    private static final int APPLY_BUDGET = Integer.getInteger("betavk.voxbudget", 400_000);   // ints recorded per frame

    private static volatile int epoch;
    private static volatile World world;
    private static volatile long seed;
    private static final VoxelStore store = new VoxelStore(null);

    private static final class Col {
        final int level, sx, sz;
        final int[] opaque, trans;
        int meshedMask = -1;
        boolean queued;
        int lastSeen;

        Col(int level, int sx, int sz) {
            this.level = level;
            this.sx = sx;
            this.sz = sz;
            int n = VoxelStore.ySections(level);
            opaque = new int[n];
            trans = new int[n];
            java.util.Arrays.fill(opaque, -1);
            java.util.Arrays.fill(trans, -1);
        }
    }

    private record Job(boolean mesh, int level, int sx, int sz, int prio, int epoch) {
    }

    private record MeshResult(Col col, int epoch, Mesher.Mesh[] meshes, int mask) {
    }

    private static final Map<Long, Col> cols = new HashMap<>();
    private static final Set<Long> selected = ConcurrentHashMap.newKeySet();
    private static final Set<Long> inflight = ConcurrentHashMap.newKeySet();
    private static final PriorityBlockingQueue<Job> jobs = new PriorityBlockingQueue<>(64, java.util.Comparator.comparingInt(Job::prio));
    private static final ConcurrentLinkedQueue<MeshResult> done = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();
    private static final List<Integer> pendingDelete = new ArrayList<>();
    private static boolean started;
    private static int frame;
    private static final AtomicInteger built = new AtomicInteger(), meshed = new AtomicInteger();
    public static int drawnLastFrame, listsRecorded;
    private static final float[] ambient = {1F, 1F, 1F, 1F};

    private static long key(int level, int sx, int sz) {
        return (long) level << 56 | (long) (sx & 0xFFFFFFF) << 28 | (sz & 0xFFFFFFFL);
    }

    // ---- state ----------------------------------------------------------------------------

    public static boolean active(Minecraft mc) {
        World w = mc.theWorld;
        return distanceChunks > 0 && w != null && !dev.betavk.hooks.McCompat.isNether(w) && w == world;
    }

    /** Blocks; 0 when off. */
    public static float farPlane(Minecraft mc) {
        return active(mc) ? distanceChunks * 16.0F : 0.0F;
    }

    /** A new world was created: forget everything. */
    public static void reset(World w) {
        epoch++;
        jobs.clear();
        done.clear();
        for (Col c : cols.values()) for (int i = 0; i < c.opaque.length; i++) {
            if (c.opaque[i] >= 0) pendingDelete.add(c.opaque[i]);
            if (c.trans[i] >= 0) pendingDelete.add(c.trans[i]);
        }
        cols.clear();
        selected.clear();
        inflight.clear();
        for (int l = 0; l < LEVELS; l++) {
        }
        store.clear();
        world = w;
        seed = dev.betavk.hooks.McCompat.worldSeed(w);
    }

    // ---- workers --------------------------------------------------------------------------

    private static void start() {
        if (started) return;
        started = true;
        for (int i = 0; i < WORKERS; i++) {
            Thread t = new Thread(VoxLod::workerLoop, "Voxel LOD " + i);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
        System.out.println("[betavk] voxel LOD on " + WORKERS + " worker thread(s)");
    }

    private static void workerLoop() {
        ColumnBuilder cb = null;
        int cbEpoch = -1;
        while (true) {
            Job j;
            try {
                j = jobs.take();
            } catch (InterruptedException e) {
                return;
            }
            if (j.epoch != epoch) continue;
            try {
                if (j.mesh) {
                    Mesher.Mesh[] m = new Mesher.Mesh[VoxelStore.ySections(j.level)];
                    int mask = 0;
                    int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int i = 0; i < 4; i++) if (store.columnPresent(j.level, j.sx + nb[i][0], j.sz + nb[i][1])) mask |= 1 << i;
                    for (int sy = 0; sy < m.length; sy++) {
                        VoxelSection s = store.get(j.level, j.sx, sy, j.sz);
                        m[sy] = s == null ? new Mesher.Mesh() : Mesher.mesh(store, s);
                    }
                    Col c = new Col(j.level, j.sx, j.sz);
                    done.add(new MeshResult(c, j.epoch, m, mask));
                    meshed.incrementAndGet();
                } else {
                    if (cb == null || cbEpoch != j.epoch) {
                        cb = new ColumnBuilder(world, seed);
                        cbEpoch = j.epoch;
                    }
                    ensure(cb, j.level, j.sx, j.sz, j.epoch);
                    built.incrementAndGet();
                    inflight.remove(key(j.level, j.sx, j.sz));
                }
            } catch (Throwable t) {
                t.printStackTrace();
                inflight.remove(key(j.level, j.sx, j.sz));
            }
        }
    }

    private static void ensure(ColumnBuilder cb, int level, int sx, int sz, int ep) {
        if (ep != epoch || store.columnPresent(level, sx, sz)) return;
        Object lock = locks.computeIfAbsent(key(level, sx, sz), k -> new Object());
        synchronized (lock) {
            if (ep != epoch || store.columnPresent(level, sx, sz)) return;
            if (level == 0) {
                cb.buildL0(store, sx, sz);
                return;
            }
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) ensure(cb, level - 1, sx * 2 + dx, sz * 2 + dz, ep);
            if (ep != epoch) return;
            store.mipUp(level - 1, sx * 2, sz * 2);
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    int cx = sx * 2 + dx, cz = sz * 2 + dz;
                    if (!selected.contains(key(level - 1, cx, cz))) store.dropColumn(level - 1, cx, cz);   // only what is drawn is kept
                }
            }
        }
        locks.remove(key(level, sx, sz));
    }

    // ---- selection ------------------------------------------------------------------------

    private static void select(int level, int sx, int sz, double camX, double camZ, double radius, double vanillaR, List<long[]> out) {
        double size = 32.0 * (1 << level);
        double minX = sx * size, minZ = sz * size;
        double dx = Math.max(Math.max(minX - camX, camX - (minX + size)), 0), dz = Math.max(Math.max(minZ - camZ, camZ - (minZ + size)), 0);
        double d = Math.hypot(dx, dz);
        if (d > radius) return;
        if (level > 0 && d < START[level]) {
            for (int cz = 0; cz < 2; cz++) for (int cx = 0; cx < 2; cx++) select(level - 1, sx * 2 + cx, sz * 2 + cz, camX, camZ, radius, vanillaR, out);
            return;
        }
        if (level == 0) {
            double fx = Math.max(Math.abs(minX - camX), Math.abs(minX + size - camX)), fz = Math.max(Math.abs(minZ - camZ), Math.abs(minZ + size - camZ));
            if (Math.hypot(fx, fz) < vanillaR) return;   // wholly inside the loaded chunks
        }
        out.add(new long[]{level, sx, sz, (long) d});
    }

    private static void schedule(Minecraft mc, double camX, double camZ) {
        double radius = distanceChunks * 16.0;
        double vanillaR = Math.max(32, 256 >> mc.gameSettings.renderDistance) - 16;
        List<long[]> out = new ArrayList<>();
        double rootSize = 32.0 * (1 << (LEVELS - 1));
        int r = (int) Math.ceil(radius / rootSize) + 1;
        int px = (int) Math.floor(camX / rootSize), pz = (int) Math.floor(camZ / rootSize);
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) select(LEVELS - 1, px + dx, pz + dz, camX, camZ, radius, vanillaR, out);
        out.sort((a, b) -> Long.compare(a[3], b[3]));
        selected.clear();
        for (long[] o : out) selected.add(key((int) o[0], (int) o[1], (int) o[2]));
        int requested = 0;
        for (long[] o : out) {
            int level = (int) o[0], sx = (int) o[1], sz = (int) o[2];
            long k = key(level, sx, sz);
            Col c = cols.computeIfAbsent(k, kk -> new Col(level, sx, sz));
            c.lastSeen = frame;
            if (!store.columnPresent(level, sx, sz)) {
                if (!inflight.contains(k) && inflight.size() < 48 && requested < 16) {
                    inflight.add(k);
                    jobs.add(new Job(false, level, sx, sz, (int) o[3], epoch));
                    requested++;
                }
                continue;
            }
            int mask = 0;
            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int i = 0; i < 4; i++) if (store.columnPresent(level, sx + nb[i][0], sz + nb[i][1])) mask |= 1 << i;
            if ((c.meshedMask == -1 || (mask & ~c.meshedMask) != 0 && selected.contains(k)) && !c.queued) {
                c.queued = true;
                jobs.add(new Job(true, level, sx, sz, (int) o[3] - 100000, epoch));
            }
        }
        // forget columns that are no longer selected
        var it = cols.entrySet().iterator();
        while (it.hasNext()) {
            Col c = it.next().getValue();
            if (selected.contains(key(c.level, c.sx, c.sz))) continue;
            if (frame - c.lastSeen < 240) continue;
            for (int i = 0; i < c.opaque.length; i++) {
                if (c.opaque[i] >= 0) pendingDelete.add(c.opaque[i]);
                if (c.trans[i] >= 0) pendingDelete.add(c.trans[i]);
            }
            if (c.level == 0) store.dropColumn(0, c.sx, c.sz);
            it.remove();
        }
    }

    // ---- drawing --------------------------------------------------------------------------

    /** Game thread, once per frame after the terrain pass. */
    public static void render(Minecraft mc, Frustrum fr, double camX, double camY, double camZ) {
        World w = mc.theWorld;
        if (!pendingDelete.isEmpty()) {
            for (int l : pendingDelete) Gfx.glDeleteLists(l, 1);
            pendingDelete.clear();
        }
        if (!active(mc)) {
            drawnLastFrame = 0;
            return;
        }
        start();
        if (frame++ % 12 == 0) schedule(mc, camX, camZ);
        MeshResult r;
        int budget = APPLY_BUDGET;
        while (budget > 0 && (r = done.poll()) != null) {
            if (r.epoch != epoch) continue;
            Col c = cols.get(key(r.col.level, r.col.sx, r.col.sz));
            if (c == null) continue;
            for (int sy = 0; sy < r.meshes.length; sy++) {
                Mesher.Mesh m = r.meshes[sy];
                if (m.opaqueVerts > 0) c.opaque[sy] = ListRecorder.record(c.opaque[sy], m.opaque, m.opaqueVerts);
                else if (c.opaque[sy] >= 0) {
                    pendingDelete.add(c.opaque[sy]);
                    c.opaque[sy] = -1;
                }
                if (m.translucentVerts > 0) c.trans[sy] = ListRecorder.record(c.trans[sy], m.translucent, m.translucentVerts);
                else if (c.trans[sy] >= 0) {
                    pendingDelete.add(c.trans[sy]);
                    c.trans[sy] = -1;
                }
                budget -= m.opaque.length + m.translucent.length + 500;
            }
            c.meshedMask = r.mask;
            c.queued = false;
            listsRecorded++;
        }
        float bright = w.worldProvider.lightBrightnessTable[Math.max(0, 15 - w.skylightSubtracted)];
        ambient[0] = ambient[1] = ambient[2] = bright;
        Gfx.glBindTexture(Gfx.GL_TEXTURE_2D, mc.renderEngine.getTexture("/terrain.png"));
        Gfx.glEnable(Gfx.GL_TEXTURE_2D);
        Gfx.glEnable(Gfx.GL_CULL_FACE);
        Gfx.glEnable(Gfx.GL_LIGHTING);
        Gfx.glDisable(Gfx.GL_LIGHT0);
        Gfx.glDisable(Gfx.GL_LIGHT1);
        Gfx.glLightModelfv(Gfx.GL_LIGHT_MODEL_AMBIENT, ambient);
        Gfx.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        Gfx.glEnable(Gfx.GL_POLYGON_OFFSET_FILL);   // where a column overlaps loaded chunks (it is drawn whole), the real chunks win
        Gfx.glPolygonOffset(1.0F, 4.0F);
        int drawn = 0;
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                Gfx.glEnable(Gfx.GL_BLEND);
                Gfx.glBlendFunc(Gfx.GL_SRC_ALPHA, Gfx.GL_ONE_MINUS_SRC_ALPHA);
            }
            for (Col c : cols.values()) {
                if (!selected.contains(key(c.level, c.sx, c.sz)) || c.meshedMask == -1) continue;
                double size = 32.0 * (1 << c.level);
                double ox = c.sx * size, oz = c.sz * size;
                for (int sy = 0; sy < c.opaque.length; sy++) {
                    int list = pass == 0 ? c.opaque[sy] : c.trans[sy];
                    if (list < 0) continue;
                    double oy = sy * size;
                    if (!fr.isBoxInFrustum(ox, oy, oz, ox + size, oy + size, oz + size)) continue;
                    Gfx.glPushMatrix();
                    Gfx.glTranslatef((float) (ox - camX), (float) (oy - camY), (float) (oz - camZ));
                    Gfx.glCallList(list);
                    Gfx.glPopMatrix();
                    drawn++;
                }
            }
        }
        Gfx.glDisable(Gfx.GL_BLEND);
        Gfx.glDisable(Gfx.GL_POLYGON_OFFSET_FILL);
        drawnLastFrame = drawn;
        Gfx.glDisable(Gfx.GL_LIGHTING);
    }

    /** Test: builds one level-0 column (sx, sz) in a private store and returns an accessor over its blocks (x, y, z in the column's 32 x 128 x 32). */
    public static Column testBuild(World w, long seed, int sx, int sz) {
        VoxelStore st = new VoxelStore(null);
        new ColumnBuilder(w, seed).buildL0(st, sx, sz);
        return (x, y, z) -> st.get(0, sx, y >> 5, sz).get(x, y & 31, z);
    }

    public interface Column {
        int get(int x, int y, int z);
    }

    public static int jobsWaiting() {
        return jobs.size() + done.size();
    }

    public static String stats() {
        int[] byLevel = new int[LEVELS];
        for (Col c : cols.values()) if (selected.contains(key(c.level, c.sx, c.sz)) && c.meshedMask != -1) byLevel[c.level]++;
        return String.format("voxel LOD: %d jobs waiting, %d columns built, %d meshed, drawn columns by level %s, %d lists drawn last frame, %d sections in memory",
                jobs.size(), built.get(), meshed.get(), java.util.Arrays.toString(byLevel), drawnLastFrame, store.sectionCount());
    }
}
