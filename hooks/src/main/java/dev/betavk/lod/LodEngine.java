package dev.betavk.lod;

import dev.betavk.hooks.Opts;
import dev.betavk.render.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.src.Chunk;
import net.minecraft.src.EntityLiving;
import net.minecraft.src.Frustrum;
import net.minecraft.src.RenderGlobal;
import net.minecraft.src.World;
import net.minecraft.src.WorldRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Chunk-aligned real-block LOD quadtree (levels 1..4) covering beyond RenderGlobal's real chunks.
 * Reads player edits & saved McRegion disk chunks first, falling back to procedural seed generation.
 */
public final class LodEngine {
    private LodEngine() {
    }

    private static final int WORKERS = Integer.getInteger("betavk.lodworkers",
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
    private static final int UPLOAD_BUDGET_INTS = Integer.getInteger("betavk.lodbudget", 300_000);

    private record Job(boolean mesh, LodNode node, int prio, int epoch, int version) {
    }

    private record BuiltMesh(LodNode node, int epoch, int version, int lightSub, boolean fancy, int nbMask, LodMesher.Mesh mesh) {
    }

    private static final ConcurrentHashMap<Long, LodNode> nodes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, LodNode> readyNodes = new ConcurrentHashMap<>();
    private static final Set<Long> selectedKeys = new HashSet<>();
    private static final List<LodNode> selectedList = new ArrayList<>();
    private static final List<LodNode> visible = new ArrayList<>();
    private static final List<LodNode> visibleTrans = new ArrayList<>();
    private static final List<Integer> pendingDelete = new ArrayList<>();
    private static final PriorityBlockingQueue<Job> jobs = new PriorityBlockingQueue<>(128, Comparator.comparingInt(Job::prio));
    private static final ConcurrentLinkedQueue<BuiltMesh> done = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Long, Object> buildLocks = new ConcurrentHashMap<>();

    private static volatile int epoch;
    private static volatile World activeWorld;
    private static volatile long worldSeed;
    private static boolean workersStarted;
    private static int frameNo;
    private static int lastRealChunks = -1, lastLodChunks = -1;
    public static int drawnLastFrame;
    private static final Frustrum sharedFrustum = new Frustrum();

    public static int jobsWaiting() {
        return jobs.size() + done.size();
    }

    /** Test helper: true if a built LOD node contains any voxel with the given block ID. */
    public static boolean nodeHasBlock(int level, int gx, int gz, int blockId) {
        LodNode n = readyNodes.get(key(level, gx, gz));
        if (n == null || !n.dataReady) return false;
        int[] v = n.vox;
        for (int i = 0; i < v.length; i++) {
            if (LodNode.id(v[i]) == blockId) return true;
        }
        return false;
    }

    /** Test helper: minimum Chebyshev distance in chunks from (camCX, camCZ) to any drawn LOD node (or MAX_VALUE). */
    public static int minDrawnCheb(int camCX, int camCZ) {
        int min = Integer.MAX_VALUE;
        for (int i = 0, sz = visible.size(); i < sz; i++) {
            LodNode n = visible.get(i);
            int c0x = n.gx * n.span, c1x = c0x + n.span - 1;
            int c0z = n.gz * n.span, c1z = c0z + n.span - 1;
            int dx = camCX < c0x ? c0x - camCX : camCX > c1x ? camCX - c1x : 0;
            int dz = camCZ < c0z ? c0z - camCZ : camCZ > c1z ? camCZ - c1z : 0;
            int d = Math.max(dx, dz);
            if (d < min) min = d;
        }
        return min;
    }

    public static long key(int level, int gx, int gz) {
        return ((long) level << 56) | ((long) (gx & 0xFFFFFFFL) << 28) | (gz & 0xFFFFFFFL);
    }

    public static boolean isActive(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.theWorld != activeWorld) return false;
        if (mc.theWorld.worldProvider.isNether) return false;
        int lod = Opts.effectiveLodChunks(mc.gameSettings);
        int real = Opts.effectiveChunks(mc.gameSettings);
        return lod > real;
    }

    public static float effectiveFarPlane(Minecraft mc, float realFar) {
        if (!isActive(mc)) return realFar;
        return Math.max(realFar, Opts.effectiveLodChunks(mc.gameSettings) * 16.0F);
    }

    /** World switched or closed. */
    public static void reset(World w) {
        epoch++;
        jobs.clear();
        done.clear();
        selectedKeys.clear();
        selectedList.clear();
        visible.clear();
        visibleTrans.clear();
        buildLocks.clear();
        readyNodes.clear();
        for (LodNode n : nodes.values()) {
            if (n.opaqueList >= 0) pendingDelete.add(n.opaqueList);
            if (n.transList >= 0) pendingDelete.add(n.transList);
        }
        nodes.clear();
        LodChunkSource.reset(w);
        activeWorld = w;
        worldSeed = w == null ? 0L : w.getRandomSeed();
        lastRealChunks = -1;
        lastLodChunks = -1;
    }

    /** Game thread: a chunk was edited or saved — capture it and rebuild affected L0..L4 nodes. */
    public static void onChunkEdited(World w, Chunk c) {
        if (w == null || c == null || w != activeWorld || w.worldProvider.isNether) return;
        if (!Opts.lodEnabled || Opts.lodChunks <= 0) return;
        LodChunkSource.captureChunk(c);
        invalidateChunk(c.xPosition, c.zPosition);
    }

    public static void invalidateChunk(int cx, int cz) {
        for (int lvl = 0; lvl <= 4; lvl++) {
            int shift = lvl <= 1 ? 0 : (lvl - 1);
            LodNode n = nodes.get(key(lvl, cx >> shift, cz >> shift));
            if (n != null) {
                n.dataDirty = true;
                n.version++;
            }
        }
    }

    private static void startWorkers() {
        if (workersStarted) return;
        workersStarted = true;
        for (int i = 0; i < WORKERS; i++) {
            Thread t = new Thread(LodEngine::workerLoop, "LOD builder " + i);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
    }

    /** Samples a neighbor voxel across LOD levels (L0..L4) or live captured chunks so level transitions and real-chunk seams never emit underground cliff curtains. */
    static int sampleCrossLevel(LodNode node, int vx, int vy, int vz) {
        int wx = node.gx * node.span * 16 + vx * node.scaleXZ + (node.scaleXZ >> 1);
        int wy = Math.max(0, Math.min(127, vy * node.scaleY + (node.scaleY >> 1)));
        int wz = node.gz * node.span * 16 + vz * node.scaleXZ + (node.scaleXZ >> 1);
        int cx = wx >> 4, cz = wz >> 4;

        for (int lvl = 0; lvl <= 4; lvl++) {
            int shift = lvl <= 1 ? 0 : (lvl - 1);
            LodNode other = readyNodes.get(key(lvl, cx >> shift, cz >> shift));
            if (other != null && other.dataReady) {
                int ox = Math.floorDiv(wx, other.scaleXZ) - (other.gx * other.dim);
                int oz = Math.floorDiv(wz, other.scaleXZ) - (other.gz * other.dim);
                int oy = Math.min(other.h - 1, wy / other.scaleY);
                if (ox >= 0 && ox < other.dim && oz >= 0 && oz < other.dim) {
                    return other.get(ox, oy, oz);
                }
            }
        }
        LodChunkSource.ChunkSnap snap = LodChunkSource.peekSnap(cx, cz);
        if (snap != null) {
            int id = snap.blocks[((wx & 15) << 11) | ((wz & 15) << 7) | wy] & 255;
            if (id != 0) return LodNode.pack(id, 0, 15, 0, 0);
            return LodNode.pack(0, 0, 15, 0, 0);
        }
        // Unloaded outer horizon edge: hide underground skirts below sea level so the world edge doesn't hang stone walls into the void.
        if (wy < 62) return LodNode.pack(1, 0, 0, 0, 0);
        return LodNode.pack(0, 0, 15, 0, 0);
    }

    private static int neighborMask(LodNode n) {
        int mask = 0;
        int c0x = n.gx * n.span, c1x = c0x + n.span - 1;
        int c0z = n.gz * n.span, c1z = c0z + n.span - 1;
        int[][] probes = {{c0x - 1, c0z}, {c1x + 1, c0z}, {c0x, c0z - 1}, {c0x, c1z + 1}};
        for (int k = 0; k < 4; k++) {
            int px = probes[k][0], pz = probes[k][1];
            boolean found = LodChunkSource.peekSnap(px, pz) != null;
            for (int lvl = 0; lvl <= 4 && !found; lvl++) {
                int shift = lvl <= 1 ? 0 : (lvl - 1);
                LodNode other = readyNodes.get(key(lvl, px >> shift, pz >> shift));
                if (other != null && other.dataReady) found = true;
            }
            if (found) mask |= 1 << k;
        }
        return mask;
    }

    private static void workerLoop() {
        LodChunkSource.WorkerGen wg = null;
        int wgEpoch = -1;
        LodMesher.Scratch scratch = new LodMesher.Scratch();

        while (true) {
            Job j;
            try {
                j = jobs.take();
            } catch (InterruptedException e) {
                return;
            }
            LodNode node = j.node;
            if (j.epoch != epoch || activeWorld == null) {
                if (j.mesh) node.queuedMesh = false;
                else node.queuedData = false;
                continue;
            }
            World w = activeWorld;

            try {
                if (wg == null || wgEpoch != j.epoch) {
                    wg = new LodChunkSource.WorkerGen(w, worldSeed);
                    wgEpoch = j.epoch;
                }
                if (j.mesh) {
                    if (!node.dataReady || node.version != j.version) continue;
                    LodNode[] nb = {
                            readyNodes.get(key(node.level, node.gx - 1, node.gz)),
                            readyNodes.get(key(node.level, node.gx + 1, node.gz)),
                            readyNodes.get(key(node.level, node.gx, node.gz - 1)),
                            readyNodes.get(key(node.level, node.gx, node.gz + 1))
                    };
                    int nbMask = neighborMask(node);
                    float[] table = w.worldProvider.lightBrightnessTable;
                    int skySub = w.skylightSubtracted;
                    boolean fancy = Minecraft.isFancyGraphicsEnabled();
                    LodMesher.Mesh m = LodMesher.mesh(node, nb, table, skySub, fancy, scratch);
                    done.add(new BuiltMesh(node, j.epoch, node.version, skySub, fancy, nbMask, m));
                } else {
                    ensureData(wg, node.level, node.gx, node.gz, j.epoch);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                if (j.mesh) node.queuedMesh = false;
                else node.queuedData = false;
            }
        }
    }

    private static LodNode ensureData(LodChunkSource.WorkerGen wg, int level, int gx, int gz, int ep) {
        long k = key(level, gx, gz);
        LodNode n = readyNodes.get(k);
        if (n != null && n.dataReady && !n.dataDirty) return n;

        Object lock = buildLocks.computeIfAbsent(k, kk -> new Object());
        synchronized (lock) {
            if (ep != epoch) return null;
            n = nodes.get(k);
            if (n == null) return null;
            if (n.dataReady && !n.dataDirty) return n;

            if (level <= 1) {
                LodChunkSource.ChunkSnap snap = LodChunkSource.fetch(gx, gz, wg);
                LodDownsampler.fromChunk(snap, n);
            } else {
                LodNode[] ch = new LodNode[4];
                int cLvl = level - 1;
                for (int dz = 0; dz < 2; dz++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int cgx = (gx << 1) + dx, cgz = (gz << 1) + dz;
                        long ck = key(cLvl, cgx, cgz);
                        LodNode child = readyNodes.get(ck);
                        if (child == null) {
                            child = new LodNode(cLvl, cgx, cgz);
                            // Transient child if not tracked in active quadtree
                            if (cLvl == 1) {
                                LodDownsampler.fromChunk(LodChunkSource.fetch(cgx, cgz, wg), child);
                            } else {
                                child = buildTransient(wg, cLvl, cgx, cgz, ep);
                            }
                        } else if (!child.dataReady || child.dataDirty) {
                            if (cLvl == 1) {
                                LodDownsampler.fromChunk(LodChunkSource.fetch(cgx, cgz, wg), child);
                                child.dataDirty = false;
                                readyNodes.put(ck, child);
                            } else {
                                child = ensureData(wg, cLvl, cgx, cgz, ep);
                            }
                        }
                        ch[(dz << 1) | dx] = child;
                    }
                }
                if (ep != epoch) return null;
                LodDownsampler.mipNode(n, ch);
            }
            n.dataDirty = false;
            readyNodes.put(k, n);
        }
        buildLocks.remove(k);
        return n;
    }

    private static LodNode buildTransient(LodChunkSource.WorkerGen wg, int level, int gx, int gz, int ep) {
        if (ep != epoch) return null;
        if (level == 1) {
            LodNode n = new LodNode(1, gx, gz);
            LodDownsampler.fromChunk(LodChunkSource.fetch(gx, gz, wg), n);
            return n;
        }
        LodNode[] ch = new LodNode[4];
        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 2; dx++) {
                int cgx = (gx << 1) + dx, cgz = (gz << 1) + dz;
                LodNode cached = readyNodes.get(key(level - 1, cgx, cgz));
                ch[(dz << 1) | dx] = (cached != null && cached.dataReady && !cached.dataDirty)
                        ? cached : buildTransient(wg, level - 1, cgx, cgz, ep);
            }
        }
        LodNode out = new LodNode(level, gx, gz);
        LodDownsampler.mipNode(out, ch);
        return out;
    }

    /**
     * True if chunk (cx, cz) has its active in-frustum RenderGlobal sections built (ignoring sealed underground cy=0
     * sections culled by CaveCull so surface chunks in front of the player are never overwritten by LOD1).
     */
    private static boolean isRealChunkCovered(RenderGlobal rg, int cx, int cz) {
        WorldRenderer[] wrs = rg.worldRenderers;
        int W = rg.renderChunksWide, T = rg.renderChunksTall, D = rg.renderChunksDeep;
        if (wrs == null || W <= 0 || T <= 0 || D <= 0) return false;
        int gx = Math.floorMod(cx, W), gz = Math.floorMod(cz, D);
        int wx = cx << 4, wz = cz << 4;
        WorldRenderer w0 = wrs[gz * T * W + gx];
        if (w0.posX != wx || w0.posZ != wz) return false;
        boolean anyInit = false;
        boolean groundInit = false;
        for (int cy = 0; cy < T; cy++) {
            WorldRenderer w = wrs[(gz * T + cy) * W + gx];
            if (w.isInitialized) {
                anyInit = true;
                if (cy >= 1 && cy <= 5) groundInit = true;
            } else if (w.isInFrustum && cy >= 1 && cy <= 4) {
                return false;
            }
        }
        return groundInit || anyInit;
    }

    private static void selectNode(RenderGlobal rg, int level, int gx, int gz, int camCX, int camCZ, int innerSafe,
                                   double camX, double camZ, double maxBlocks, int[] outMaxDist) {
        double size = level == 0 ? 16.0 : 16.0 * (1 << (level - 1));
        double minX = gx * size, minZ = gz * size;
        double dx = Math.max(Math.max(minX - camX, camX - (minX + size)), 0.0);
        double dz = Math.max(Math.max(minZ - camZ, camZ - (minZ + size)), 0.0);
        double d = Math.hypot(dx, dz);
        if (d > maxBlocks) return;

        // Subdivide into finer level if closer than child's range threshold
        if (level > 1 && d < outMaxDist[level - 1]) {
            for (int cz = 0; cz < 2; cz++) {
                for (int cx = 0; cx < 2; cx++) {
                    selectNode(rg, level - 1, (gx << 1) + cx, (gz << 1) + cz, camCX, camCZ, innerSafe, camX, camZ, maxBlocks, outMaxDist);
                }
            }
            return;
        }

        // At 1x1-chunk span: enforce inner real-chunk exclusion & covered-border exclusion, then pick L0 (1x1x1) or L1 (2x1x2).
        int chosenLevel = level;
        if (level == 1) {
            int cheb = Math.max(Math.abs(gx - camCX), Math.abs(gz - camCZ));
            if (cheb <= innerSafe || isRealChunkCovered(rg, gx, gz)) return;
            if (outMaxDist[0] > 0 && d < outMaxDist[0]) chosenLevel = 0;
        }

        long k = key(chosenLevel, gx, gz);
        selectedKeys.add(k);
        LodNode node = nodes.get(k);
        if (node == null) {
            node = new LodNode(chosenLevel, gx, gz);
            nodes.put(k, node);
        }
        node.lastSeenFrame = frameNo;
        selectedList.add(node);

        if ((!node.dataReady || node.dataDirty) && !node.queuedData && jobs.size() < 256) {
            node.queuedData = true;
            jobs.add(new Job(false, node, (int) d + chosenLevel * 8, epoch, node.version));
        }
    }

    private static void flushUploads() {
        BuiltMesh r;
        int budget = UPLOAD_BUDGET_INTS;
        while (budget > 0 && (r = done.poll()) != null) {
            if (r.epoch != epoch || r.version != r.node.version) continue;
            LodNode n = r.node;
            if (r.mesh.opaqueVerts > 0) {
                n.opaqueList = LodMesher.recordList(n.opaqueList, r.mesh.opaque, r.mesh.opaqueVerts, r.mesh.quads);
            } else if (n.opaqueList >= 0) {
                pendingDelete.add(n.opaqueList);
                n.opaqueList = -1;
            }
            if (r.mesh.transVerts > 0) {
                n.transList = LodMesher.recordList(n.transList, r.mesh.trans, r.mesh.transVerts, r.mesh.quads);
            } else if (n.transList >= 0) {
                pendingDelete.add(n.transList);
                n.transList = -1;
            }
            n.bakedVersion = r.version;
            n.bakedLightSub = r.lightSub;
            n.bakedFancy = r.fancy;
            n.bakedNeighborMask = r.nbMask;
            budget -= r.mesh.opaque.length + r.mesh.trans.length + 500;
        }
    }

    private static void updateQuadtree(Minecraft mc, double camX, double camZ) {
        int real = Opts.effectiveChunks(mc.gameSettings);
        int lod = Opts.effectiveLodChunks(mc.gameSettings);
        double realBlocks = real * 16.0;
        double maxBlocks = lod * 16.0;

        int q = Opts.lodQuality;
        int l0Cut = q == 2 ? (int) (realBlocks + 128.0) : (q == 1 ? (int) (realBlocks + 80.0) : 0);
        int l1Cut = (int) Math.max(realBlocks * 1.5 + 160.0, realBlocks + (q == 2 ? 352.0 : (q == 1 ? 288.0 : 224.0)));
        int l2Cut = (int) (realBlocks * 1.5 + (q == 2 ? 640.0 : 512.0));
        int l3Cut = (int) (realBlocks * 1.5 + (q == 2 ? 1200.0 : 1024.0));
        int[] cutoffs = {l0Cut, l1Cut, l2Cut, l3Cut, (int) maxBlocks};

        selectedKeys.clear();
        selectedList.clear();
        int camCX = (int) Math.floor(camX) >> 4;
        int camCZ = (int) Math.floor(camZ) >> 4;
        int innerSafe = Math.max(1, real - 2);
        double rootSize = 128.0;   // level 4 = 8x8 chunks = 128 blocks
        int spanR = (int) Math.ceil(maxBlocks / rootSize) + 1;
        int rx0 = (int) Math.floor(camX / rootSize), rz0 = (int) Math.floor(camZ / rootSize);
        RenderGlobal rg = mc.renderGlobal;
        for (int dz = -spanR; dz <= spanR; dz++) {
            for (int dx = -spanR; dx <= spanR; dx++) {
                selectNode(rg, 4, rx0 + dx, rz0 + dz, camCX, camCZ, innerSafe, camX, camZ, maxBlocks, cutoffs);
            }
        }

        // Prune stale nodes out of range
        if (frameNo % 120 == 0 || real != lastRealChunks || lod != lastLodChunks) {
            lastRealChunks = real;
            lastLodChunks = lod;
            Iterator<Map.Entry<Long, LodNode>> it = nodes.entrySet().iterator();
            while (it.hasNext()) {
                LodNode n = it.next().getValue();
                if (selectedKeys.contains(key(n.level, n.gx, n.gz))) continue;
                if (frameNo - n.lastSeenFrame < 300) continue;
                if (n.opaqueList >= 0) pendingDelete.add(n.opaqueList);
                if (n.transList >= 0) pendingDelete.add(n.transList);
                readyNodes.remove(key(n.level, n.gx, n.gz));
                it.remove();
            }
        }
    }

    /** Pass 0 (opaque + alpha cutout leaves). Called immediately after RenderGlobal pass 0. */
    public static void renderPass0(Minecraft mc, EntityLiving ent, float pt) {
        if (!pendingDelete.isEmpty()) {
            for (int l : pendingDelete) Gfx.glDeleteLists(l, 1);
            pendingDelete.clear();
        }
        visible.clear();
        visibleTrans.clear();
        if (!isActive(mc) || ent == null) return;
        startWorkers();
        flushUploads();

        double camX = ent.lastTickPosX + (ent.posX - ent.lastTickPosX) * pt;
        double camY = ent.lastTickPosY + (ent.posY - ent.lastTickPosY) * pt;
        double camZ = ent.lastTickPosZ + (ent.posZ - ent.lastTickPosZ) * pt;

        frameNo++;
        if (frameNo == 1 || frameNo % 8 == 0
                || Opts.effectiveChunks(mc.gameSettings) != lastRealChunks
                || Opts.effectiveLodChunks(mc.gameSettings) != lastLodChunks) {
            updateQuadtree(mc, camX, camZ);
        }

        sharedFrustum.setPosition(camX, camY, camZ);
        RenderGlobal rg = mc.renderGlobal;
        int camCX = (int) Math.floor(camX) >> 4;
        int camCZ = (int) Math.floor(camZ) >> 4;
        int innerSafe = Math.max(1, Opts.effectiveChunks(mc.gameSettings) - 2);
        int skySub = mc.theWorld.skylightSubtracted;
        boolean fancy = mc.gameSettings.fancyGraphics;

        int meshQueued = 0;
        for (int i = 0, sz = selectedList.size(); i < sz; i++) {
            LodNode n = selectedList.get(i);
            if (!n.dataReady) continue;
            if (n.level <= 1) {
                int cheb = Math.max(Math.abs(n.gx - camCX), Math.abs(n.gz - camCZ));
                if (cheb <= innerSafe || isRealChunkCovered(rg, n.gx, n.gz)) continue;
            }

            double size = n.span * 16.0;
            double ox = n.gx * size, oz = n.gz * size;
            if (!sharedFrustum.isBoxInFrustum(ox, 0.0, oz, ox + size, 128.0, oz + size)) continue;

            int nbMask = neighborMask(n);

            boolean needsMesh = n.bakedVersion != n.version
                    || n.bakedLightSub != skySub
                    || n.bakedFancy != fancy
                    || (nbMask & ~n.bakedNeighborMask) != 0;
            if (needsMesh && !n.queuedMesh && meshQueued < 24 && jobs.size() < 256) {
                n.queuedMesh = true;
                double cx = ox + size * 0.5 - camX, cz = oz + size * 0.5 - camZ;
                int dist = (int) Math.hypot(cx, cz);
                jobs.add(new Job(true, n, dist - 50000, epoch, n.version));
                meshQueued++;
            }

            if (n.opaqueList >= 0) visible.add(n);
            if (n.transList >= 0) visibleTrans.add(n);
        }

        if (visible.isEmpty()) {
            drawnLastFrame = 0;
            return;
        }
        int sz = visible.size();
        drawnLastFrame = sz;
        for (int i = 0; i < sz; i++) {
            LodNode n = visible.get(i);
            double size = n.span * 16.0;
            Gfx.glPushMatrix();
            Gfx.glTranslatef((float) (n.gx * size - camX), (float) (-camY), (float) (n.gz * size - camZ));
            Gfx.glCallList(n.opaqueList);
            Gfx.glPopMatrix();
        }
    }

    public static boolean hasPass1() {
        return !visibleTrans.isEmpty();
    }

    /** Pass 1 (translucent water/ice/glass). Called during Pass 1 terrain rendering. */
    public static void renderPass1(Minecraft mc, EntityLiving ent, float pt) {
        if (visibleTrans.isEmpty() || ent == null || !isActive(mc)) return;
        double camX = ent.lastTickPosX + (ent.posX - ent.lastTickPosX) * pt;
        double camY = ent.lastTickPosY + (ent.posY - ent.lastTickPosY) * pt;
        double camZ = ent.lastTickPosZ + (ent.posZ - ent.lastTickPosZ) * pt;

        for (int i = 0, sz = visibleTrans.size(); i < sz; i++) {
            LodNode n = visibleTrans.get(i);
            double size = n.span * 16.0;
            Gfx.glPushMatrix();
            Gfx.glTranslatef((float) (n.gx * size - camX), (float) (-camY), (float) (n.gz * size - camZ));
            Gfx.glCallList(n.transList);
            Gfx.glPopMatrix();
        }
    }
}
