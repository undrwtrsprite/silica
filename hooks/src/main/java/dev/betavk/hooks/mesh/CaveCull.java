package dev.betavk.hooks.mesh;

import net.minecraft.src.Block;
import net.minecraft.src.EntityLiving;
import net.minecraft.src.ICamera;
import net.minecraft.src.MathHelper;
import net.minecraft.src.RenderGlobal;
import net.minecraft.src.World;
import net.minecraft.src.WorldRenderer;

import java.util.Arrays;

/**
 * 6-face subchunk visibility graph ("cave culling"): flood-fills non-opaque voxels per 16x16x16 section
 * during chunk meshing, then runs a monotone BFS from the camera subchunk inside clipRenderersByFrustrum
 * to skip sealed underground and occluded sections before rendering.
 */
public final class CaveCull {
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("betavk.caveCull", "true"));
    public static final long ALL_VISIBLE = 0xFFFFFFFFFL;   // 6x6 face bits all connected

    // Faces: 0=-Y, 1=+Y, 2=-Z, 3=+Z, 4=-X, 5=+X
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private static long[] visMasks = new long[0];
    private static final VisGraph syncGraph = new VisGraph();

    // Game-thread BFS scratch buffers
    private static boolean[] bfsVisited = new boolean[0];
    private static byte[] queuedMask = new byte[0];
    private static byte[] expandedMask = new byte[0];
    private static byte[] frustumCache = new byte[0];   // 0=unknown, 1=out, 2=in (for empty chunks)
    private static boolean[] inQueue = new boolean[0];
    private static int[] bfsQueue = new int[0];

    private CaveCull() {
    }

    /** Per-section 4096-voxel flood fill computing which of the 6 boundary faces can see each other. */
    public static final class VisGraph {
        private final long[] visited = new long[64];
        private final short[] queue = new short[4096];
        private int opaqueCount;

        public void reset() {
            Arrays.fill(visited, 0L);
            opaqueCount = 0;
        }

        public void markOpaque(int lx, int ly, int lz) {
            int idx = (ly << 8) | (lz << 4) | lx;
            visited[idx >>> 6] |= 1L << (idx & 63);
            opaqueCount++;
        }

        private boolean tryVisit(int idx) {
            int word = idx >>> 6;
            long bit = 1L << (idx & 63);
            if ((visited[word] & bit) != 0L) return false;
            visited[word] |= bit;
            return true;
        }

        public long computeVisMask() {
            if (opaqueCount < 256) return ALL_VISIBLE;
            long vis = 0L;
            for (int v0 = 0; v0 < 4096; v0++) {
                int lx = v0 & 15, lz = (v0 >>> 4) & 15, ly = v0 >>> 8;
                if (lx != 0 && lx != 15 && ly != 0 && ly != 15 && lz != 0 && lz != 15) continue;
                if (!tryVisit(v0)) continue;
                int head = 0, tail = 0;
                queue[tail++] = (short) v0;
                int faceMask = 0;
                while (head < tail) {
                    int v = queue[head++] & 0xFFF;
                    int vx = v & 15, vz = (v >>> 4) & 15, vy = v >>> 8;
                    if (vy == 0) faceMask |= 1; else if (tryVisit(v - 256)) queue[tail++] = (short) (v - 256);
                    if (vy == 15) faceMask |= 2; else if (tryVisit(v + 256)) queue[tail++] = (short) (v + 256);
                    if (vz == 0) faceMask |= 4; else if (tryVisit(v - 16)) queue[tail++] = (short) (v - 16);
                    if (vz == 15) faceMask |= 8; else if (tryVisit(v + 16)) queue[tail++] = (short) (v + 16);
                    if (vx == 0) faceMask |= 16; else if (tryVisit(v - 1)) queue[tail++] = (short) (v - 1);
                    if (vx == 15) faceMask |= 32; else if (tryVisit(v + 1)) queue[tail++] = (short) (v + 1);
                }
                for (int f = 0; f < 6; f++) {
                    if ((faceMask & (1 << f)) != 0) vis |= ((long) faceMask) << (f * 6);
                }
                if (vis == ALL_VISIBLE) return ALL_VISIBLE;
            }
            return vis;
        }

        public long computeFromWorld(World world, int x0, int y0, int z0) {
            reset();
            for (int ly = 0; ly < 16; ly++) {
                int y = y0 + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int z = z0 + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        int id = world.getBlockId(x0 + lx, y, z);
                        if (id > 0 && Block.opaqueCubeLookup[id]) markOpaque(lx, ly, lz);
                    }
                }
            }
            return computeVisMask();
        }
    }

    /** atEnd of RenderGlobal.loadRenderers. */
    public static void onLoadRenderers(RenderGlobal rg) {
        if (rg.worldRenderers == null) return;
        int n = rg.worldRenderers.length;
        if (visMasks.length != n) visMasks = new long[n];
        Arrays.fill(visMasks, ALL_VISIBLE);
    }

    public static void reset(WorldRenderer w) {
        int idx = w.chunkIndex;
        if (idx >= 0 && idx < visMasks.length) visMasks[idx] = ALL_VISIBLE;
    }

    public static void set(WorldRenderer w, long mask) {
        int idx = w.chunkIndex;
        if (idx >= 0 && idx < visMasks.length) visMasks[idx] = mask;
    }

    /** atEnd of WorldRenderer.updateRenderer: keep synchronous near edits accurate. */
    public static void onSyncRebuild(WorldRenderer w) {
        if (!ENABLED || w.worldObj == null) {
            reset(w);
            return;
        }
        set(w, w.skipAllRenderPasses() ? ALL_VISIBLE : syncGraph.computeFromWorld(w.worldObj, w.posX, w.posY, w.posZ));
    }

    private static void ensureScratch(int n) {
        if (bfsVisited.length < n) {
            bfsVisited = new boolean[n];
            queuedMask = new byte[n];
            expandedMask = new byte[n];
            frustumCache = new byte[n];
            inQueue = new boolean[n];
            bfsQueue = new int[n * 4];
        }
    }

    private static boolean inFrustum(WorldRenderer w, int idx, ICamera cam) {
        if (!w.skipAllRenderPasses()) return w.isInFrustum;
        byte cached = frustumCache[idx];
        if (cached != 0) return cached == 2;
        boolean inside = cam.isBoundingBoxInFrustum(w.rendererBoundingBox);
        frustumCache[idx] = (byte) (inside ? 2 : 1);
        return inside;
    }

    private static void seed(int idx, int inFaceBit) {
        if (!bfsVisited[idx]) {
            bfsVisited[idx] = true;
            queuedMask[idx] = 0;
            expandedMask[idx] = 0;
            inQueue[idx] = false;
        }
        if ((queuedMask[idx] & inFaceBit) != inFaceBit) {
            queuedMask[idx] |= (byte) inFaceBit;
        }
    }

    /** atEnd of RenderGlobal.clipRenderersByFrustrum: keep only subchunks reachable by air from camera. */
    public static void cull(RenderGlobal rg, ICamera cam, float pt) {
        if (!ENABLED || rg.worldRenderers == null || rg.mc.gameSettings.thirdPersonView) return;
        EntityLiving ent = rg.mc.renderViewEntity;
        if (ent == null) return;
        WorldRenderer[] wrs = rg.worldRenderers;
        int W = rg.renderChunksWide, T = rg.renderChunksTall, D = rg.renderChunksDeep, N = wrs.length;
        if (N == 0 || W <= 0 || T <= 0 || D <= 0) return;
        ensureScratch(N);
        Arrays.fill(bfsVisited, 0, N, false);
        Arrays.fill(frustumCache, 0, N, (byte) 0);

        float eyeOff = ent.yOffset - 1.62F;
        double camX = ent.prevPosX + (ent.posX - ent.prevPosX) * pt;
        double camY = ent.prevPosY + (ent.posY - ent.prevPosY) * pt - eyeOff;
        double camZ = ent.prevPosZ + (ent.posZ - ent.prevPosZ) * pt;
        int scx = MathHelper.floor_double(camX) >> 4;
        int scy = MathHelper.floor_double(camY) >> 4;
        int scz = MathHelper.floor_double(camZ) >> 4;

        int head = 0, tail = 0;

        int cx0 = MathHelper.floor_double(camX - 1.5D) >> 4, cx1 = MathHelper.floor_double(camX + 1.5D) >> 4;
        int cy0 = Math.max(0, MathHelper.floor_double(camY - 1.5D) >> 4), cy1 = Math.min(T - 1, MathHelper.floor_double(camY + 1.5D) >> 4);
        int cz0 = MathHelper.floor_double(camZ - 1.5D) >> 4, cz1 = MathHelper.floor_double(camZ + 1.5D) >> 4;
        for (int cz = cz0; cz <= cz1; cz++) {
            int gz = Math.floorMod(cz, D);
            for (int cy = cy0; cy <= cy1; cy++) {
                for (int cx = cx0; cx <= cx1; cx++) {
                    int idx = (gz * T + cy) * W + Math.floorMod(cx, W);
                    WorldRenderer w = wrs[idx];
                    if (w.posX == (cx << 4) && w.posY == (cy << 4) && w.posZ == (cz << 4)) {
                        seed(idx, 63);
                        if (!inQueue[idx]) {
                            inQueue[idx] = true;
                            bfsQueue[tail++] = idx;
                        }
                    }
                }
            }
        }

        if (scy >= T || scy < 0) {
            int edgeY = scy >= T ? T - 1 : 0;
            int inBit = scy >= T ? 2 : 1;   // entered from +Y sky or -Y void
            for (int gz = 0; gz < D; gz++) {
                for (int gx = 0; gx < W; gx++) {
                    int idx = (gz * T + edgeY) * W + gx;
                    WorldRenderer w = wrs[idx];
                    if (inFrustum(w, idx, cam)) {
                        seed(idx, inBit);
                        if (!inQueue[idx] && tail < bfsQueue.length) {
                            inQueue[idx] = true;
                            bfsQueue[tail++] = idx;
                        }
                    }
                }
            }
        }

        if (tail == 0) return;

        while (head < tail) {
            int idx = bfsQueue[head++];
            inQueue[idx] = false;
            int qm = queuedMask[idx] & 63;
            int em = expandedMask[idx] & 63;
            int todo = qm & ~em;
            if (todo == 0) continue;
            expandedMask[idx] = (byte) (em | todo);

            WorldRenderer w = wrs[idx];
            int exitMask;
            if (todo == 63 || w.chunkIndex < 0 || w.chunkIndex >= visMasks.length) {
                exitMask = 63;
            } else {
                long vis = visMasks[w.chunkIndex];
                exitMask = 0;
                for (int f = 0; f < 6; f++) {
                    if ((todo & (1 << f)) != 0) exitMask |= (int) ((vis >>> (f * 6)) & 63L);
                }
            }

            int cx = w.posX >> 4, cy = w.posY >> 4, cz = w.posZ >> 4;
            if (cy > scy) exitMask &= ~1;
            else if (cy < scy) exitMask &= ~2;
            if (cz > scz) exitMask &= ~4;
            else if (cz < scz) exitMask &= ~8;
            if (cx > scx) exitMask &= ~16;
            else if (cx < scx) exitMask &= ~32;

            for (int dir = 0; dir < 6; dir++) {
                if ((exitMask & (1 << dir)) == 0) continue;
                int ny = cy + DY[dir];
                if (ny < 0 || ny >= T) continue;
                int nx = cx + DX[dir], nz = cz + DZ[dir];
                int nIdx = (Math.floorMod(nz, D) * T + ny) * W + Math.floorMod(nx, W);
                WorldRenderer nw = wrs[nIdx];
                if (nw.posX != (nx << 4) || nw.posY != (ny << 4) || nw.posZ != (nz << 4)) continue;
                if (!inFrustum(nw, nIdx, cam)) continue;
                int oppBit = 1 << (dir ^ 1);
                if (!bfsVisited[nIdx] || (queuedMask[nIdx] & oppBit) == 0) {
                    seed(nIdx, oppBit);
                    if (!inQueue[nIdx] && tail < bfsQueue.length) {
                        inQueue[nIdx] = true;
                        bfsQueue[tail++] = nIdx;
                    }
                }
            }
        }

        for (int i = 0; i < N; i++) {
            WorldRenderer w = wrs[i];
            if (w.isInFrustum && !bfsVisited[i]) w.isInFrustum = false;
        }
    }
}
