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

    private AsyncMesh() {
    }

    private static final class State {
        int generation;
        boolean jobInFlight, pendingRebuild;
        int jobGeneration = -1;
    }

    private static final WeakHashMap<WorldRenderer, State> states = new WeakHashMap<>();
    private static final ArrayList<WorldRenderer> deferred = new ArrayList<>();
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
        for (int i = 0; i < deferred.size() && System.nanoTime() < budgetEnd; ) {
            WorldRenderer w = deferred.remove(i);
            State st = states.get(w);
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

    /** redirectCallNth of WorldRenderer.updateRenderer in the far-section selection of RenderGlobal.updateRenderers. */
    public static void updateFar(WorldRenderer w) {
        if (!MeshPool.canMesh(w.worldObj)) {
            w.updateRenderer();
            return;
        }
        if (++farCalls > 1 && System.nanoTime() > budgetEnd) {   // out of per-frame time (a submit may generate chunks synchronously): later
            State st = state(w);
            if (!deferred.contains(w)) deferred.add(w);
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
                                if (Block.isBlockContainer[id]) res.tileEntityPositions.add(new int[]{x, y, z});
                            }
                            Block b = worker.blocks[id];
                            int p = b.getRenderBlockPass();
                            if (p != pass) {
                                skipped = true;
                                continue;
                            }
                            if (pass == 1 && b != Block.waterMoving && b != Block.waterStill && b != Block.ice) {
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
                            boolean guardGrass = SPLIT_ALPHA && pass == 0 && RenderBlocks.fancyGrass && id == Block.grass.blockID;
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
        return b == Block.snow || b == Block.stairSingle || b == Block.stairCompactCobblestone
                || b == Block.stairCompactPlanks || b == Block.tilledField || b == Block.fence
                || b == Block.pressurePlateStone || b == Block.pressurePlatePlanks || b == Block.button;
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
        w.isChunkLit = r.lit;
        w.isInitialized = true;
        CaveCull.set(w, r.visMask);
    }
}
