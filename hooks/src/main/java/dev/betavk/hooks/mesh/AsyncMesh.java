package dev.betavk.hooks.mesh;

import dev.betavk.render.Gfx;
import net.minecraft.src.Block;
import net.minecraft.src.EntityLiving;
import net.minecraft.src.RenderGlobal;
import net.minecraft.src.ChunkCache;
import net.minecraft.src.RenderBlocks;
import net.minecraft.src.Tessellator;
import net.minecraft.src.TileEntity;
import net.minecraft.src.TileEntityRenderer;
import net.minecraft.src.WorldRenderer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Far chunk sections are meshed on worker threads. The game thread snapshots the chunks around a section (a ChunkCache), a worker records the section's
 * vertices without touching any graphics state, and the game thread turns the result into display lists. Near sections (block edits) stay synchronous
 * so changes show immediately. A late result for a section that has meanwhile been retargeted or disposed is dropped (the generation counter).
 */
public final class AsyncMesh {
    private static final boolean SPLIT_ALPHA = !"false".equalsIgnoreCase(System.getProperty("betavk.splitAlpha", "true"));
    private static final boolean HAS_IS_CHUNK_LIT = dev.betavk.hooks.McCompat.hasField(WorldRenderer.class, "isChunkLit");
    private static final boolean HAS_FANCY_GRASS = dev.betavk.hooks.McCompat.hasField(RenderBlocks.class, "fancyGrass");
    private static final boolean HAS_IS_BLOCK_CONTAINER = dev.betavk.hooks.McCompat.hasField(Block.class, "isBlockContainer");

    private static final class FancyGrassFlag {
        static boolean isOn() { return RenderBlocks.fancyGrass; }
    }

    private static final class BlockContainerFlag {
        static boolean is(int id) { return Block.isBlockContainer[id]; }
    }

    private AsyncMesh() {
    }

    private static final class State {
        int generation;
        boolean jobInFlight, pendingRebuild, inDeferred;
        int jobGeneration = -1;
    }

    private static final WeakHashMap<WorldRenderer, State> states = new WeakHashMap<>();
    private static final java.util.ArrayDeque<WorldRenderer> deferred = new java.util.ArrayDeque<>();
    private static long budgetEnd;
    private static int farCalls;
    private static boolean syncFallback;   // the next updateRenderer is the fallback after a failed job: it must not be sent to a worker again

    private static State state(WorldRenderer w) {
        return states.computeIfAbsent(w, k -> new State());
    }

    // ---- game thread: per frame ---------------------------------------------------------------------

    /** atStart of RenderGlobal.updateRenderers. */
    public static void frameStart(RenderGlobal rg, EntityLiving viewer, boolean all) {
        MeshPool.applyCompleted(3_000_000L);   // finished chunk meshes from the worker threads
        budgetEnd = System.nanoTime() + 2_000_000L;
        farCalls = 0;
        // sections that did not fit last frame's time budget go first
        while (!deferred.isEmpty() && System.nanoTime() < budgetEnd) {
            WorldRenderer w = deferred.pollFirst();
            State st = states.get(w);
            if (st != null) st.inDeferred = false;
            if (w.worldObj == null || st == null) continue;
            if (st.jobInFlight && st.jobGeneration == st.generation) {
                st.pendingRebuild = true;
                continue;
            }
            submit(w, st);
        }
    }

    /** replaceInt of the far-section count (a constant 2) in RenderGlobal.updateRenderers. */
    public static int farBudget(RenderGlobal rg) {
        return MeshPool.farBudget();
    }

    private static final int MAX_FAR_BUDGET = 48;
    private static final WorldRenderer[] bestWr = new WorldRenderer[MAX_FAR_BUDGET];
    private static final int[] bestSlot = new int[MAX_FAR_BUDGET];
    private static final double[] bestKey = new double[MAX_FAR_BUDGET];
    private static final ArrayList<WorldRenderer> nearWr = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private static final ArrayList<WorldRenderer>[] dirtyBuckets = new ArrayList[17];
    static {
        for (int i = 0; i < dirtyBuckets.length; i++) dirtyBuckets[i] = new ArrayList<>();
    }
    private static List<WorldRenderer> lastDirtyList;
    private static long lastSortNs;
    private static int lastDirtySize;
    private static double sortX, sortY, sortZ;

    private static void rebuildNearbyGrid(RenderGlobal rg, double vx, double vy, double vz) {
        WorldRenderer[] wrs = rg.worldRenderers;
        int W = rg.renderChunksWide, T = rg.renderChunksTall, D = rg.renderChunksDeep;
        if (wrs == null || W <= 0 || T <= 0 || D <= 0) return;
        int scx = net.minecraft.src.MathHelper.floor_double(vx) >> 4;
        int scy = net.minecraft.src.MathHelper.floor_double(vy) >> 4;
        int scz = net.minecraft.src.MathHelper.floor_double(vz) >> 4;
        for (int dz = -1; dz <= 1; dz++) {
            int gz = Math.floorMod(scz + dz, D);
            for (int dy = -1; dy <= 1; dy++) {
                int cy = scy + dy;
                if (cy < 0 || cy >= T) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    WorldRenderer w = wrs[(gz * T + cy) * W + Math.floorMod(scx + dx, W)];
                    if (w.needsUpdate) {
                        double ddx = (w.posX + 8) - vx, ddy = (w.posY + 8) - vy, ddz = (w.posZ + 8) - vz;
                        if (ddx * ddx + ddy * ddy + ddz * ddz <= 256.0) {
                            w.updateRenderer();
                            w.needsUpdate = false;
                        }
                    }
                }
            }
        }
    }

    /** Alpha/early-Beta overload where RenderGlobal.updateRenderers takes EntityPlayer. */
    public static boolean updateRenderersPlayer(RenderGlobal rg, net.minecraft.src.EntityPlayer viewer, boolean limit) {
        return updateRenderers(rg, (EntityLiving) viewer, limit);
    }

    /** replaceBody of RenderGlobal.updateRenderers: periodic-sort + O(budget) tail-pop for large dirty queues, O(n) single-pass when small. */
    @SuppressWarnings("unchecked")
    public static boolean updateRenderers(RenderGlobal rg, EntityLiving viewer, boolean limit) {
        frameStart(rg, viewer, limit);
        List<WorldRenderer> dirty = rg.worldRenderersToUpdate;
        int n = dirty.size();
        if (n == 0) return true;
        double vx = viewer.posX, vy = viewer.posY, vz = viewer.posZ;
        int budget = Math.max(2, Math.min(MAX_FAR_BUDGET, farBudget(rg)));
        if (n > 128 && !limit) {
            rebuildNearbyGrid(rg, vx, vy, vz);
            long now = System.nanoTime();
            if (dirty != lastDirtyList || lastSortNs == 0L || now - lastSortNs >= 150_000_000L
                    || n > lastDirtySize + 512
                    || Math.abs(vx - sortX) + Math.abs(vy - sortY) + Math.abs(vz - sortZ) > 16.0) {
                for (ArrayList<WorldRenderer> b : dirtyBuckets) b.clear();
                for (int i = 0; i < n; i++) {
                    WorldRenderer w = dirty.get(i);
                    if (w == null || !w.needsUpdate) continue;
                    if (!w.isInFrustum) {
                        dirtyBuckets[0].add(w);
                    } else {
                        double dx = (w.posX + 8) - vx, dy = (w.posY + 8) - vy, dz = (w.posZ + 8) - vz;
                        int d2 = (int) Math.min(1_000_000_000.0, dx * dx + dy * dy + dz * dz);
                        int log = 31 - Integer.numberOfLeadingZeros(Math.max(256, d2));
                        dirtyBuckets[16 - Math.max(0, Math.min(15, log - 8))].add(w);
                    }
                }
                dirty.clear();
                for (ArrayList<WorldRenderer> b : dirtyBuckets) {
                    if (!b.isEmpty()) dirty.addAll(b);
                }
                if (dirty.isEmpty()) {
                    lastDirtyList = dirty;
                    lastDirtySize = 0;
                    return true;
                }
                sortX = vx; sortY = vy; sortZ = vz;
                // Sort only the closest <= 256 tail slice exactly; the rest is bucket-ordered in O(N).
                int total = dirty.size();
                int tailStart = Math.max(dirtyBuckets[0].size(), total - 256);
                if (total - tailStart > 1) {
                    dirty.subList(tailStart, total).sort((a, b) -> {
                        double dax = (a.posX + 8) - sortX, day = (a.posY + 8) - sortY, daz = (a.posZ + 8) - sortZ;
                        double dbx = (b.posX + 8) - sortX, dby = (b.posY + 8) - sortY, dbz = (b.posZ + 8) - sortZ;
                        int c = Double.compare(dbx * dbx + dby * dby + dbz * dbz, dax * dax + day * day + daz * daz);
                        return c != 0 ? c : Integer.compare(b.chunkIndex, a.chunkIndex);
                    });
                }
                for (ArrayList<WorldRenderer> b : dirtyBuckets) b.clear();
                lastSortNs = now;
                lastDirtyList = dirty;
                lastDirtySize = total;
            }
            int submittedFar = 0;
            while (!dirty.isEmpty() && submittedFar < budget) {
                WorldRenderer w = dirty.remove(dirty.size() - 1);
                if (w == null || !w.needsUpdate) continue;
                if (!w.isInFrustum && submittedFar > 0) {
                    dirty.add(w);
                    break;
                }
                double dx = (w.posX + 8) - vx, dy = (w.posY + 8) - vy, dz = (w.posZ + 8) - vz;
                if (dx * dx + dy * dy + dz * dz <= 256.0) {
                    w.updateRenderer();
                    w.needsUpdate = false;
                } else {
                    updateFar(w);
                    w.needsUpdate = false;
                    submittedFar++;
                }
            }
            lastDirtySize = Math.min(lastDirtySize, dirty.size());
            return dirty.isEmpty();
        }
        lastSortNs = 0L;
        lastDirtyList = dirty;
        int bestCount = 0;
        nearWr.clear();
        for (int i = 0; i < n; i++) {
            WorldRenderer w = dirty.get(i);
            if (w == null) continue;
            if (limit) {
                if (w.isInFrustum) {
                    nearWr.add(w);
                    dirty.set(i, null);
                }
                continue;
            }
            double dx = (w.posX + 8) - vx, dy = (w.posY + 8) - vy, dz = (w.posZ + 8) - vz;
            float d2 = (float) (dx * dx + dy * dy + dz * dz);
            if (d2 <= 256.0F) {
                nearWr.add(w);
                dirty.set(i, null);
                continue;
            }
            double key = (w.isInFrustum ? 0.0 : 1e18) + d2;
            if (bestCount == budget && key >= bestKey[budget - 1]) continue;
            int pos = bestCount < budget ? bestCount++ : budget - 1;
            while (pos > 0 && key < bestKey[pos - 1]) {
                bestWr[pos] = bestWr[pos - 1];
                bestSlot[pos] = bestSlot[pos - 1];
                bestKey[pos] = bestKey[pos - 1];
                pos--;
            }
            bestWr[pos] = w;
            bestSlot[pos] = i;
            bestKey[pos] = key;
        }
        int nearN = nearWr.size();
        if (nearN > 1) {
            nearWr.sort((a, b) -> {
                if (a.isInFrustum != b.isInFrustum) return a.isInFrustum ? -1 : 1;
                int c = Float.compare(a.distanceToEntitySquared(viewer), b.distanceToEntitySquared(viewer));
                return c != 0 ? c : Integer.compare(a.chunkIndex, b.chunkIndex);
            });
        }
        for (int i = 0; i < nearN; i++) {
            WorldRenderer w = nearWr.get(i);
            w.updateRenderer();
            w.needsUpdate = false;
        }
        nearWr.clear();
        for (int k = 0; k < bestCount; k++) {
            WorldRenderer w = bestWr[k];
            if (!w.isInFrustum && k > 0) break;
            dirty.set(bestSlot[k], null);
            updateFar(w);
            w.needsUpdate = false;
        }
        for (int k = 0; k < bestCount; k++) bestWr[k] = null;
        int total = dirty.size();
        int write = 0;
        for (int i = 0; i < total; i++) {
            WorldRenderer w = dirty.get(i);
            if (w != null) {
                if (write != i) dirty.set(write, w);
                write++;
            }
        }
        for (int i = total - 1; i >= write; i--) dirty.remove(i);
        return write == 0;
    }

    /** redirectCall of List.contains in RenderGlobal.sortAndRender: skip 15,000-entry O(n) scans while dirty queue is large. */
    public static boolean alreadyQueued(List<?> dirty, Object wr) {
        return dirty.size() > 256 || dirty.contains(wr);
    }

    /** redirectCallNth / helper for submitting a far section to MeshPool workers. */
    public static void updateFar(WorldRenderer w) {
        if (!MeshPool.canMesh(w.worldObj)) {
            w.updateRenderer();
            return;
        }
        if (++farCalls > 1 && System.nanoTime() > budgetEnd) {   // out of per-frame time (a submit may generate chunks synchronously): later
            State st = state(w);
            if (!st.inDeferred) {
                st.inDeferred = true;
                deferred.addLast(w);
            }
            return;
        }
        State st = state(w);
        if (st.jobInFlight && st.jobGeneration == st.generation) {
            st.pendingRebuild = true;   // a job for this very target is running: rebuild once more when it lands
            return;
        }
        submit(w, st);
    }

    /** atStart of WorldRenderer.setDontDraw: the renderer is retargeted or disposed. */
    public static void retarget(WorldRenderer w) {
        state(w).generation++;
        CaveCull.reset(w);
    }

    private static void submit(WorldRenderer w, State st) {
        byte b = 1;
        ChunkCache cache = new ChunkCache(w.worldObj, w.posX - b, w.posY - b, w.posZ - b, w.posX + w.sizeWidth + b, w.posY + w.sizeHeight + b, w.posZ + w.sizeDepth + b);
        st.jobInFlight = true;
        st.jobGeneration = st.generation;
        st.pendingRebuild = false;
        MeshPool.submit(new MeshWorker.Job(w, cache, st.generation));
    }

    /** Game thread: a worker finished (or failed) meshing a renderer. */
    static void finish(MeshResult r) {
        WorldRenderer w = r.target;
        State st = state(w);
        if (r.generation != st.generation || w.worldObj == null) {
            MeshPool.droppedStale++;
            if (r.generation == st.jobGeneration) {
                // the last job submitted was for an old target and no newer one exists: the current target still needs meshing
                st.jobInFlight = false;
                st.pendingRebuild = false;
                if (w.worldObj != null) submit(w, st);
            }
            return;
        }
        st.jobInFlight = false;
        if (r.failure != null) {
            w.needsUpdate = true;
            w.updateRenderer();   // fall back to the synchronous path
        } else {
            apply(w, r);
        }
        if (st.pendingRebuild) {
            st.pendingRebuild = false;
            submit(w, st);
        }
    }

    // ---- worker thread -----------------------------------------------------------------------------

    /** Pure meshing: reads a ChunkCache snapshot and records vertices; touches no graphics state. */
    static MeshResult buildMesh(WorldRenderer w, ChunkCache cache, MeshWorker worker, int generation) {
        MeshResult res = new MeshResult(w, generation);
        int x0 = w.posX, y0 = w.posY, z0 = w.posZ, x1 = w.posX + w.sizeWidth, y1 = w.posY + w.sizeHeight, z1 = w.posZ + w.sizeDepth;
        RenderBlocks rb = new RenderBlocks(cache);
        CapturingTessellator primaryCap = worker.primaryTess;
        CapturingTessellator cutoutCap = worker.cutoutTess;
        CaveCull.VisGraph vg = worker.visGraph;
        if (CaveCull.ENABLED) vg.reset();
        worker.tessellator = primaryCap;
        try {
            for (int pass = 0; pass < 2; pass++) {
                boolean skipped = false, rendered = false, started = false, cutoutStarted = false;
                for (int y = y0; y < y1; y++) {
                    for (int z = z0; z < z1; z++) {
                        for (int x = x0; x < x1; x++) {
                            int id = cache.getBlockId(x, y, z);
                            if (id <= 0) continue;
                            if (pass == 0) {
                                if (CaveCull.ENABLED && Block.opaqueCubeLookup[id]) vg.markOpaque(x - x0, y - y0, z - z0);
                                if (HAS_IS_BLOCK_CONTAINER ? BlockContainerFlag.is(id) : (Block.blocksList[id] instanceof net.minecraft.src.BlockContainer)) {
                                    res.tileEntityPositions.add(new int[]{x, y, z});
                                }
                            }
                            Block b = worker.blocks[id];
                            if (b == null) continue;
                            int p = b.getRenderBlockPass();
                            if (p != pass) {
                                skipped = true;
                                continue;
                            }
                            if (pass == 1 && id != 8 && id != 9 && id != 79) {
                                res.pass1Alpha = true;
                            }
                            boolean splitCutout = SPLIT_ALPHA && pass == 0 && !b.isOpaqueCube() && !isOpaqueNonCube(b);
                            CapturingTessellator cap = splitCutout ? cutoutCap : primaryCap;
                            worker.tessellator = cap;
                            if (splitCutout) {
                                if (!cutoutStarted) {
                                    cutoutStarted = true;
                                    cap.startDrawingQuads();
                                    cap.setTranslationD(-w.posX, -w.posY, -w.posZ);
                                }
                            } else if (!started) {
                                started = true;
                                cap.startDrawingQuads();
                                cap.setTranslationD(-w.posX, -w.posY, -w.posZ);
                            }
                            boolean guardGrass = SPLIT_ALPHA && pass == 0 && HAS_FANCY_GRASS && FancyGrassFlag.isOn() && id == Block.grass.blockID;
                            if (guardGrass && cap.rawBufferIndex + 512 >= cap.bufferSize - 32) {
                                cap.draw();
                                cap.isDrawing = true;
                            }
                            int prevIdx = cap.rawBufferIndex;
                            rendered |= rb.renderBlockByRenderType(b, x, y, z);
                            if (guardGrass && cap.rawBufferIndex > prevIdx) {
                                cutoutStarted |= moveGrassOverlay(cap, prevIdx, cutoutCap, cutoutStarted, w.posX, w.posY, w.posZ);
                            }
                        }
                    }
                }
                if (started) {
                    primaryCap.draw();
                    res.draws[pass].addAll(primaryCap.captured);
                    primaryCap.captured.clear();
                    primaryCap.setTranslationD(0.0D, 0.0D, 0.0D);
                }
                if (cutoutStarted) {
                    cutoutCap.draw();
                    res.cutoutDraws.addAll(cutoutCap.captured);
                    cutoutCap.captured.clear();
                    cutoutCap.setTranslationD(0.0D, 0.0D, 0.0D);
                }
                if (!started && !cutoutStarted) rendered = false;
                res.started[pass] = started || cutoutStarted;
                res.rendered[pass] = rendered;
                if (!skipped) break;
            }
        } finally {
            worker.tessellator = primaryCap;
        }
        res.lit = worker.lit;
        res.visMask = CaveCull.ENABLED ? vg.computeVisMask() : CaveCull.ALL_VISIBLE;
        return res;
    }

    private static boolean isOpaqueNonCube(Block b) {
        int id = b.blockID;
        return id == 78 || id == 44 || id == 67 || id == 53 || id == 60 || id == 85 || id == 70 || id == 72 || id == 77;
    }

    /** Split fancy grass side overlay quads (terrain.png tile 38) from opaque grass blocks into the alpha-tested cutout buffer. */
    private static boolean moveGrassOverlay(CapturingTessellator src, int prevIdx, CapturingTessellator dst, boolean dstStarted, int wx, int wy, int wz) {
        int[] raw = src.rawBuffer;
        int stride = Tessellator.convertQuadsToTriangles ? 48 : 32;
        int vertsPerQuad = Tessellator.convertQuadsToTriangles ? 6 : 4;
        int write = prevIdx;
        for (int q = prevIdx; q < src.rawBufferIndex; q += stride) {
            float u = (Float.intBitsToFloat(raw[q + 3]) + Float.intBitsToFloat(raw[q + 19])) * 0.5F;
            float v = (Float.intBitsToFloat(raw[q + 4]) + Float.intBitsToFloat(raw[q + 20])) * 0.5F;
            int tile = ((int) (v * 16.0F) << 4) | (int) (u * 16.0F);
            if (tile == 38) {
                if (!dstStarted) {
                    dstStarted = true;
                    dst.startDrawingQuads();
                    dst.setTranslationD(-wx, -wy, -wz);
                }
                dst.hasTexture |= src.hasTexture;
                dst.hasColor |= src.hasColor;
                dst.hasNormals |= src.hasNormals;
                if (dst.rawBufferIndex + stride >= dst.bufferSize - 32) {
                    dst.draw();
                    dst.isDrawing = true;
                }
                System.arraycopy(raw, q, dst.rawBuffer, dst.rawBufferIndex, stride);
                dst.rawBufferIndex += stride;
                dst.vertexCount += vertsPerQuad;
                dst.addedVertices += 4;
                src.vertexCount -= vertsPerQuad;
                src.addedVertices -= 4;
            } else {
                if (write != q) System.arraycopy(raw, q, raw, write, stride);
                write += stride;
            }
        }
        src.rawBufferIndex = write;
        return dstStarted;
    }

    // ---- game thread: result to display lists ------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void apply(WorldRenderer w, MeshResult r) {
        WorldRenderer.chunksUpdated++;
        for (int i = 0; i < 2; i++) w.skipRenderPass[i] = true;
        HashSet old = new HashSet(w.tileEntityRenderers);
        w.tileEntityRenderers.clear();
        for (int pass = 0; pass < 2; pass++) {
            if (r.started[pass]) {
                Gfx.glNewList(w.glRenderList + pass, Gfx.GL_COMPILE);
                Gfx.glPushMatrix();
                w.setupGLTranslation();
                float s = 1.000001F;
                Gfx.glTranslatef((float) (-w.sizeDepth) / 2.0F, (float) (-w.sizeHeight) / 2.0F, (float) (-w.sizeDepth) / 2.0F);
                Gfx.glScalef(s, s, s);
                Gfx.glTranslatef((float) w.sizeDepth / 2.0F, (float) w.sizeHeight / 2.0F, (float) w.sizeDepth / 2.0F);
                if (pass == 0 && SPLIT_ALPHA) {
                    if (!r.draws[0].isEmpty()) {
                        Gfx.glDisable(Gfx.GL_ALPHA_TEST);
                        for (Draw d : r.draws[0]) TessHooks.emit(d);
                        Gfx.glEnable(Gfx.GL_ALPHA_TEST);
                    }
                    if (!r.cutoutDraws.isEmpty()) {
                        for (Draw d : r.cutoutDraws) TessHooks.emit(d);
                    }
                } else if (pass == 1 && SPLIT_ALPHA && !r.pass1Alpha && !r.draws[1].isEmpty()) {
                    Gfx.glDisable(Gfx.GL_ALPHA_TEST);
                    for (Draw d : r.draws[1]) TessHooks.emit(d);
                    Gfx.glEnable(Gfx.GL_ALPHA_TEST);
                } else {
                    for (Draw d : r.draws[pass]) TessHooks.emit(d);
                }
                Gfx.glPopMatrix();
                Gfx.glEndList();
            }
            if (r.rendered[pass]) w.skipRenderPass[pass] = false;
        }
        for (int[] p : r.tileEntityPositions) {
            TileEntity te = w.worldObj.getBlockTileEntity(p[0], p[1], p[2]);
            if (TileEntityRenderer.instance.hasSpecialRenderer(te)) w.tileEntityRenderers.add(te);
        }
        HashSet added = new HashSet(w.tileEntityRenderers);
        added.removeAll(old);
        w.tileEntities.addAll(added);
        old.removeAll(w.tileEntityRenderers);
        w.tileEntities.removeAll(old);
        if (HAS_IS_CHUNK_LIT) w.isChunkLit = r.lit;
        w.isInitialized = true;
        CaveCull.set(w, r.visMask);
    }
}
