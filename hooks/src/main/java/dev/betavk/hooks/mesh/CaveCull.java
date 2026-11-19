package dev.betavk.hooks.mesh;

import net.minecraft.src.Block;
import net.minecraft.src.ClippingHelper;
import net.minecraft.src.EntityLiving;
import net.minecraft.src.Frustrum;
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
    private static final boolean HAS_RENDERER_COUNTS = dev.betavk.hooks.McCompat.hasField(RenderGlobal.class, "renderersLoaded");
    public static final long ALL_VISIBLE = 0xFFFFFFFFFL;   // 6x6 face bits all connected

    // Faces: 0=-Y, 1=+Y, 2=-Z, 3=+Z, 4=-X, 5=+X
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private static long[] visMasks = new long[0];
    private static byte[] passFlags = new byte[0];
    private static int[] wrX = new int[0], wrY = new int[0], wrZ = new int[0];
    private static int[] wrChunkIdx = new int[0], chunkToGrid = new int[0];
    private static final VisGraph syncGraph = new VisGraph();

    // Game-thread BFS scratch buffers (stamp-indexed: zero Arrays.fill per frame)
    private static int[] visStamp = new int[0];
    private static int[] frustumStamp = new int[0];   // +frameId = in, -frameId = out
    private static byte[] cellState = new byte[0];    // bits 0..5 = queued faces, bit 6 (0x40) = inQueue
    private static byte[] expandedMask = new byte[0];
    private static int[] lastVisible = new int[0];
    private static int[] pass1Visible = new int[0];
    private static int[] bfsQueue = new int[0];
    private static int frameId = 1;
    private static int lastVisibleCount, pass1Count;
    private static boolean wasFallback, pass0UsedFast;
    private static int visVersion, cachedVisVersion = -1, cachedN;
    private static double cachedCamX, cachedCamY, cachedCamZ, cachedFarPlane;
    private static float f0a, f0b, f0c, f0d, f1a, f1b, f1c, f1d, f2a, f2b, f2c, f2d;
    private static float f3a, f3b, f3c, f3d, f4a, f4b, f4c, f4d, f5a, f5b, f5c, f5d;
    private static int radialFogState;   // 0 = unknown, 1 = on, -1 = off

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
        if (rg.allRenderLists == null || rg.allRenderLists.length < 9) {
            net.minecraft.src.RenderList[] prev = rg.allRenderLists;
            rg.allRenderLists = new net.minecraft.src.RenderList[9];
            int copied = prev == null ? 0 : Math.min(prev.length, 9);
            if (copied > 0) System.arraycopy(prev, 0, rg.allRenderLists, 0, copied);
            for (int i = copied; i < 9; i++) rg.allRenderLists[i] = new net.minecraft.src.RenderList();
        }
        WorldRenderer[] wrs = rg.worldRenderers;
        if (wrs == null) return;
        int n = wrs.length;
        if (regArr[0].length < n) {
            for (int r = 0; r < regArr.length; r++) regArr[r] = new int[n];
        }
        if (rg.glRenderLists instanceof java.util.ArrayList<?> list) list.ensureCapacity(n);
        if (visMasks.length != n) {
            visMasks = new long[n];
            passFlags = new byte[n];
            wrX = new int[n];
            wrY = new int[n];
            wrZ = new int[n];
            wrChunkIdx = new int[n];
            chunkToGrid = new int[n];
        }
        Arrays.fill(visMasks, ALL_VISIBLE);
        Arrays.fill(passFlags, (byte) 3);
        boolean assignIdx = n > 1 && wrs[0].chunkIndex == wrs[1].chunkIndex;
        for (int i = 0; i < n; i++) {
            WorldRenderer w = wrs[i];
            if (assignIdx) w.chunkIndex = i;
            wrX[i] = w.posX;
            wrY[i] = w.posY;
            wrZ[i] = w.posZ;
            int ci = w.chunkIndex;
            wrChunkIdx[i] = ci;
            if (ci >= 0 && ci < n) chunkToGrid[ci] = i;
        }
        lastVisibleCount = 0;
        pass1Count = 0;
        wasFallback = false;
        pass0UsedFast = false;
        visVersion++;
    }

    public static void invalidate() {
        visVersion++;
    }

    /** atEnd of WorldRenderer.setPosition: keep flat position arrays synced without object loads during BFS. */
    public static void onSetPosition(WorldRenderer w, int x, int y, int z) {
        int ci = w.chunkIndex;
        if (ci >= 0 && ci < chunkToGrid.length) {
            int gi = chunkToGrid[ci];
            wrX[gi] = w.posX;
            wrY[gi] = w.posY;
            wrZ[gi] = w.posZ;
            passFlags[gi] = 3;
            visVersion++;
        }
    }

    public static void reset(WorldRenderer w) {
        int idx = w.chunkIndex;
        if (idx >= 0 && idx < visMasks.length) {
            visMasks[idx] = ALL_VISIBLE;
            if (idx < chunkToGrid.length) passFlags[chunkToGrid[idx]] = 3;
        }
        visVersion++;
    }

    public static void set(WorldRenderer w, long mask) {
        int idx = w.chunkIndex;
        if (idx >= 0 && idx < visMasks.length) {
            visMasks[idx] = mask;
            if (idx < chunkToGrid.length) {
                boolean[] skip = w.skipRenderPass;
                passFlags[chunkToGrid[idx]] = w.isInitialized ? (byte) ((skip[0] ? 0 : 1) | (skip[1] ? 0 : 2)) : (byte) 3;
            }
        }
        visVersion++;
    }

    /** atEnd of WorldRenderer.updateRenderer: keep synchronous near edits accurate. */
    public static void onSyncRebuild(WorldRenderer w) {
        if (!ENABLED || w.worldObj == null) {
            reset(w);
            return;
        }
        boolean empty = dev.betavk.hooks.McCompat.HAS_SKIP_ALL_RENDER_PASSES && w.skipAllRenderPasses();
        set(w, empty ? ALL_VISIBLE : syncGraph.computeFromWorld(w.worldObj, w.posX, w.posY, w.posZ));
    }

    private static void ensureScratch(int n) {
        if (visStamp.length < n) {
            visStamp = new int[n];
            frustumStamp = new int[n];
            cellState = new byte[n];
            expandedMask = new byte[n];
            lastVisible = new int[n];
            pass1Visible = new int[n];
            bfsQueue = new int[n * 4];
            frameId = 1;
        }
    }

    /** Exact 6-plane p-vertex AABB frustum check replacing ClippingHelper.isBoxInFrustum (8 corners -> 1 per plane). */
    public static boolean fastBoxInFrustum(ClippingHelper h, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        float[][] fr = h.frustum;
        for (int i = 0; i < 6; i++) {
            float[] p = fr[i];
            float a = p[0], b = p[1], c = p[2];
            double x = a >= 0f ? maxX : minX;
            double y = b >= 0f ? maxY : minY;
            double z = c >= 0f ? maxZ : minZ;
            if (a * x + b * y + c * z + p[3] <= 0.0) return false;
        }
        return true;
    }

    private static boolean hasRadialFog() {
        if (dev.betavk.render.Backends.vulkan()) return true;
        int s = radialFogState;
        if (s == 0) {
            try {
                s = dev.betavk.compat.GLContext.getCapabilities().GL_NV_fog_distance ? 1 : -1;
            } catch (Throwable t) {
                s = -1;
            }
            radialFogState = s;
        }
        return s > 0;
    }

    private static boolean planesMatch(float[][] fr) {
        float[] p = fr[0];
        if (p[0] != f0a || p[1] != f0b || p[2] != f0c || p[3] != f0d) return false;
        p = fr[1];
        if (p[0] != f1a || p[1] != f1b || p[2] != f1c || p[3] != f1d) return false;
        p = fr[2];
        if (p[0] != f2a || p[1] != f2b || p[2] != f2c || p[3] != f2d) return false;
        p = fr[3];
        if (p[0] != f3a || p[1] != f3b || p[2] != f3c || p[3] != f3d) return false;
        p = fr[4];
        if (p[0] != f4a || p[1] != f4b || p[2] != f4c || p[3] != f4d) return false;
        p = fr[5];
        return p[0] == f5a && p[1] == f5b && p[2] == f5c && p[3] == f5d;
    }

    private static void loadPlanes(float[][] fr) {
        float[] p = fr[0]; f0a = p[0]; f0b = p[1]; f0c = p[2]; f0d = p[3];
        p = fr[1]; f1a = p[0]; f1b = p[1]; f1c = p[2]; f1d = p[3];
        p = fr[2]; f2a = p[0]; f2b = p[1]; f2c = p[2]; f2d = p[3];
        p = fr[3]; f3a = p[0]; f3b = p[1]; f3c = p[2]; f3d = p[3];
        p = fr[4]; f4a = p[0]; f4b = p[1]; f4c = p[2]; f4d = p[3];
        p = fr[5]; f5a = p[0]; f5b = p[1]; f5c = p[2]; f5d = p[3];
    }

    private static boolean inFrustumCoords(WorldRenderer[] wrs, int idx, int px, int py, int pz, ICamera cam, Frustrum fr, double baseX, double baseY, double baseZ) {
        int st = frustumStamp[idx];
        if (st == frameId) return true;
        if (st == -frameId) return false;
        boolean inside;
        if (fr != null) {
            double x0 = px + baseX, y0 = py + baseY, z0 = pz + baseZ;
            double x1 = x0 + 28.0, y1 = y0 + 28.0, z1 = z0 + 28.0;
            inside = f0a * (f0a >= 0f ? x1 : x0) + f0b * (f0b >= 0f ? y1 : y0) + f0c * (f0c >= 0f ? z1 : z0) + f0d > 0.0
                  && f1a * (f1a >= 0f ? x1 : x0) + f1b * (f1b >= 0f ? y1 : y0) + f1c * (f1c >= 0f ? z1 : z0) + f1d > 0.0
                  && f2a * (f2a >= 0f ? x1 : x0) + f2b * (f2b >= 0f ? y1 : y0) + f2c * (f2c >= 0f ? z1 : z0) + f2d > 0.0
                  && f3a * (f3a >= 0f ? x1 : x0) + f3b * (f3b >= 0f ? y1 : y0) + f3c * (f3c >= 0f ? z1 : z0) + f3d > 0.0
                  && f4a * (f4a >= 0f ? x1 : x0) + f4b * (f4b >= 0f ? y1 : y0) + f4c * (f4c >= 0f ? z1 : z0) + f4d > 0.0
                  && f5a * (f5a >= 0f ? x1 : x0) + f5b * (f5b >= 0f ? y1 : y0) + f5c * (f5c >= 0f ? z1 : z0) + f5d > 0.0;
        } else {
            inside = cam.isBoundingBoxInFrustum(wrs[idx].rendererBoundingBox);
        }
        frustumStamp[idx] = inside ? frameId : -frameId;
        return inside;
    }

    private static void seed(WorldRenderer[] wrs, int idx, int inFaceBit) {
        if (visStamp[idx] != frameId) {
            visStamp[idx] = frameId;
            cellState[idx] = (byte) inFaceBit;
            expandedMask[idx] = 0;
            if (passFlags[idx] != 0) {
                wrs[idx].isInFrustum = true;
                lastVisible[lastVisibleCount++] = idx;
            }
        } else {
            cellState[idx] |= (byte) inFaceBit;
        }
    }

    private static int fallbackFrustrumOffset;

    private static final class FrustrumOffset {
        static int get(RenderGlobal rg) { return rg.frustrumCheckOffset; }
        static void inc(RenderGlobal rg) { rg.frustrumCheckOffset++; }
    }

    /** replaceBody of RenderGlobal.clipRenderersByFrustrum: frustum + cave culling on reachable sections only. */
    public static void clipAndCull(RenderGlobal rg, ICamera cam, float pt) {
        WorldRenderer[] wrs = rg.worldRenderers;
        if (wrs == null) return;
        int N = wrs.length;
        EntityLiving ent = dev.betavk.hooks.McCompat.renderViewEntity(rg.mc);
        if (!ENABLED || rg.mc.gameSettings.thirdPersonView || ent == null) {
            wasFallback = true;
            int offset = dev.betavk.hooks.McCompat.HAS_FRUSTRUM_CHECK_OFFSET ? FrustrumOffset.get(rg) : fallbackFrustrumOffset;
            for (int i = 0; i < N; i++) {
                WorldRenderer w = wrs[i];
                if (!w.skipAllRenderPasses() && (!w.isInFrustum || ((i + offset) & 15) == 0)) {
                    w.updateInFrustrum(cam);
                }
            }
            if (dev.betavk.hooks.McCompat.HAS_FRUSTRUM_CHECK_OFFSET) FrustrumOffset.inc(rg);
            else fallbackFrustrumOffset++;
            return;
        }
        int W = rg.renderChunksWide, T = rg.renderChunksTall, D = rg.renderChunksDeep;
        if (N == 0 || W <= 0 || T <= 0 || D <= 0) return;
        ensureScratch(N);

        Frustrum fr = (cam instanceof Frustrum) ? (Frustrum) cam : null;
        float eyeOff = ent.yOffset - 1.62F;
        double camX = ent.prevPosX + (ent.posX - ent.prevPosX) * pt;
        double camY = ent.prevPosY + (ent.posY - ent.prevPosY) * pt - eyeOff;
        double camZ = ent.prevPosZ + (ent.posZ - ent.prevPosZ) * pt;
        float farPlane = dev.betavk.lod.LodEngine.effectiveFarPlane(rg.mc, dev.betavk.hooks.Opts.effectiveFarPlane(rg.mc.gameSettings));
        if (!wasFallback && fr != null && visVersion == cachedVisVersion && N == cachedN && farPlane == cachedFarPlane
                && camX == cachedCamX && camY == cachedCamY && camZ == cachedCamZ
                && rg.worldRenderersToUpdate.isEmpty() && planesMatch(fr.clippingHelper.frustum)) {
            return;
        }
        if (fr != null) loadPlanes(fr.clippingHelper.frustum);

        if (wasFallback) {
            for (int i = 0; i < N; i++) wrs[i].isInFrustum = false;
            wasFallback = false;
        } else {
            for (int i = 0; i < lastVisibleCount; i++) wrs[lastVisible[i]].isInFrustum = false;
        }
        lastVisibleCount = 0;
        if (++frameId >= Integer.MAX_VALUE - 1) {
            Arrays.fill(visStamp, 0);
            Arrays.fill(frustumStamp, 0);
            frameId = 1;
        }

        double baseX = fr != null ? -6.0 - fr.xPosition : 0.0;
        double baseY = fr != null ? -6.0 - fr.yPosition : 0.0;
        double baseZ = fr != null ? -6.0 - fr.zPosition : 0.0;
        double maxDistSq = (hasRadialFog() && farPlane >= 64.0F) ? (farPlane + 18.0D) * (farPlane + 18.0D) : Double.POSITIVE_INFINITY;
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
                    int gx = Math.floorMod(cx, W);
                    int idx = (gz * T + cy) * W + gx;
                    if (wrX[idx] == (cx << 4) && wrY[idx] == (cy << 4) && wrZ[idx] == (cz << 4)) {
                        seed(wrs, idx, 63);
                        if ((cellState[idx] & 0x40) == 0) {
                            cellState[idx] |= 0x40;
                            bfsQueue[tail++] = (gz << 25) | (gx << 18) | idx;
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
                    int px = wrX[idx], py = wrY[idx], pz = wrZ[idx];
                    double rdx = px + 8.0 - camX, rdy = py + 8.0 - camY, rdz = pz + 8.0 - camZ;
                    if (rdx * rdx + rdy * rdy + rdz * rdz <= maxDistSq && inFrustumCoords(wrs, idx, px, py, pz, cam, fr, baseX, baseY, baseZ)) {
                        seed(wrs, idx, inBit);
                        if ((cellState[idx] & 0x40) == 0 && tail < bfsQueue.length) {
                            cellState[idx] |= 0x40;
                            bfsQueue[tail++] = (gz << 25) | (gx << 18) | idx;
                        }
                    }
                }
            }
        }

        while (head < tail) {
            int packed = bfsQueue[head++];
            int idx = packed & 0x3FFFF;
            int gx = (packed >>> 18) & 0x7F;
            int gz = packed >>> 25;
            int cs = cellState[idx];
            cellState[idx] = (byte) (cs & 63);
            int qm = cs & 63;
            int em = expandedMask[idx] & 63;
            int todo = qm & ~em;
            if (todo == 0) continue;
            expandedMask[idx] = (byte) (em | todo);

            int cIdx = wrChunkIdx[idx];
            int exitMask;
            if (todo == 63 || cIdx < 0 || cIdx >= visMasks.length) {
                exitMask = 63;
            } else {
                long vis = visMasks[cIdx];
                exitMask = 0;
                for (int f = 0; f < 6; f++) {
                    if ((todo & (1 << f)) != 0) exitMask |= (int) ((vis >>> (f * 6)) & 63L);
                }
            }

            int cx = wrX[idx] >> 4, cy = wrY[idx] >> 4, cz = wrZ[idx] >> 4;
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
                int ngx = gx + DX[dir];
                if (ngx < 0) ngx += W;
                else if (ngx >= W) ngx -= W;
                int ngz = gz + DZ[dir];
                if (ngz < 0) ngz += D;
                else if (ngz >= D) ngz -= D;
                int nIdx = (ngz * T + ny) * W + ngx;
                int oppBit = 1 << (dir ^ 1);
                if (visStamp[nIdx] == frameId) {
                    int ncs = cellState[nIdx];
                    if ((ncs & oppBit) != 0) continue;
                    cellState[nIdx] = (byte) (ncs | oppBit | 0x40);
                    if ((ncs & 0x40) == 0 && tail < bfsQueue.length) {
                        bfsQueue[tail++] = (ngz << 25) | (ngx << 18) | nIdx;
                    }
                    continue;
                }
                int nx16 = nx << 4, ny16 = ny << 4, nz16 = nz << 4;
                if (wrX[nIdx] != nx16 || wrY[nIdx] != ny16 || wrZ[nIdx] != nz16) continue;
                double rdx = nx16 + 8.0 - camX, rdy = ny16 + 8.0 - camY, rdz = nz16 + 8.0 - camZ;
                if (rdx * rdx + rdy * rdy + rdz * rdz > maxDistSq) continue;
                if (!inFrustumCoords(wrs, nIdx, nx16, ny16, nz16, cam, fr, baseX, baseY, baseZ)) continue;
                seed(wrs, nIdx, oppBit);
                if ((cellState[nIdx] & 0x40) == 0 && tail < bfsQueue.length) {
                    cellState[nIdx] |= 0x40;
                    bfsQueue[tail++] = (ngz << 25) | (ngx << 18) | nIdx;
                }
            }
        }
        if (fr != null && rg.worldRenderersToUpdate.isEmpty()) {
            cachedVisVersion = visVersion;
            cachedN = N;
            cachedFarPlane = farPlane;
            cachedCamX = camX;
            cachedCamY = camY;
            cachedCamZ = camZ;
        } else {
            cachedVisVersion = -1;
        }
    }

    private static final int[] regX = new int[9], regY = new int[9], regZ = new int[9], regCnt = new int[9];
    private static final int[] regMap = new int[16];
    private static int[][] regArr = new int[9][4096];
    private static int regActive;

    private static void resetRegions() {
        regActive = 0;
        Arrays.fill(regMap, -1);
    }

    private static void appendRegion(WorldRenderer w, int glList) {
        int wx = w.posX & ~1023, wz = w.posZ & ~1023;
        int r = regMap[(((wx >> 10) & 3) << 2) | ((wz >> 10) & 3)];
        if (r >= 0 && regX[r] == wx && regZ[r] == wz) {
            int[] arr = regArr[r];
            int c = regCnt[r];
            if (c < arr.length) {
                arr[c] = glList;
                regCnt[r] = c + 1;
                return;
            }
        }
        appendRegionSlow(w, glList);
    }

    private static void appendRegionSlow(WorldRenderer w, int glList) {
        int wx = w.posX & ~1023, wy = 0, wz = w.posZ & ~1023;
        int r = -1;
        for (int k = 0; k < regActive; k++) {
            if (regX[k] == wx && regZ[k] == wz && regY[k] == wy) {
                r = k;
                break;
            }
        }
        if (r < 0) {
            r = regActive++;
            regX[r] = wx;
            regY[r] = wy;
            regZ[r] = wz;
            regCnt[r] = 0;
        }
        if (wy == 0) regMap[(((wx >> 10) & 3) << 2) | ((wz >> 10) & 3)] = r;
        int[] arr = regArr[r];
        int cnt = regCnt[r];
        if (cnt == arr.length) arr = regArr[r] = Arrays.copyOf(arr, arr.length * 2);
        arr[cnt] = glList;
        regCnt[r] = cnt + 1;
    }

    private static void flushRegions(RenderGlobal rg, double camX, double camY, double camZ) {
        net.minecraft.src.RenderList[] rls = rg.allRenderLists;
        for (int r = 0; r < regActive; r++) {
            int cnt = regCnt[r];
            if (cnt == 0) continue;
            net.minecraft.src.RenderList rl = rls[r];
            rl.func_861_a(regX[r], regY[r], regZ[r], camX, camY, camZ);
            if (cnt <= rl.field_1236_g.remaining()) {
                rl.field_1236_g.put(regArr[r], 0, cnt);
            } else {
                int[] arr = regArr[r];
                for (int i = 0; i < cnt; i++) rl.func_858_a(arr[i]);
            }
        }
    }

    /** replaceBody of RenderGlobal.renderSortedRenderers: iterate only CaveCull's visible list with O(1) region mapping + bulk RenderList packing. */
    public static int renderSortedRenderers(RenderGlobal rg, int start, int end, int pass, double pt) {
        net.minecraft.src.RenderList[] rls = rg.allRenderLists;
        for (net.minecraft.src.RenderList rl : rls) rl.func_859_b();
        EntityLiving ent = dev.betavk.hooks.McCompat.renderViewEntity(rg.mc);
        double camX = ent.lastTickPosX + (ent.posX - ent.lastTickPosX) * pt;
        double camY = ent.lastTickPosY + (ent.posY - ent.lastTickPosY) * pt;
        double camZ = ent.lastTickPosZ + (ent.posZ - ent.lastTickPosZ) * pt;
        WorldRenderer[] wrs = rg.worldRenderers;
        WorldRenderer[] sorted = rg.sortedWorldRenderers;
        boolean occActive = dev.betavk.hooks.McCompat.HAS_ADVANCED_OPENGL && rg.occlusionEnabled && rg.mc.gameSettings.advancedOpengl;
        boolean fast = ENABLED && !wasFallback && wrs != null && sorted != null
                && start == 0 && end == sorted.length && !occActive
                && !rg.mc.gameSettings.thirdPersonView && (pass == 0 || (pass == 1 && pass0UsedFast));
        int count = 0;
        if (fast) {
            resetRegions();
            if (pass == 0) {
                pass0UsedFast = true;
                int p1 = 0;
                for (int i = 0, n = lastVisibleCount; i < n; i++) {
                    int idx = lastVisible[i];
                    WorldRenderer w = wrs[idx];
                    boolean[] skip = w.skipRenderPass;
                    if (!skip[1]) pass1Visible[p1++] = idx;
                    if (skip[0]) continue;
                    int gl = w.glRenderList;
                    if (gl < 0) continue;
                    appendRegion(w, gl);
                    count++;
                }
                pass1Count = p1;
                if (HAS_RENDERER_COUNTS) {
                    rg.renderersLoaded = wrs.length;
                    rg.renderersBeingClipped = wrs.length - lastVisibleCount;
                    rg.renderersBeingOccluded = 0;
                    rg.renderersBeingRendered = count;
                    rg.renderersSkippingRenderPass = lastVisibleCount - count;
                }
            } else {
                if (pass1Count == 0) return 0;
                for (int i = 0, n = pass1Count; i < n; i++) {
                    WorldRenderer w = wrs[pass1Visible[i]];
                    int gl = w.glRenderList + 1;
                    if (gl < 0) continue;
                    appendRegion(w, gl);
                    count++;
                }
            }
            flushRegions(rg, camX, camY, camZ);
            rg.renderAllRenderLists(pass, pt);
            return count;
        }
        if (pass == 0) pass0UsedFast = false;
        resetRegions();
        for (int i = start; i < end; i++) {
            WorldRenderer w = sorted[i];
            if (pass == 0 && HAS_RENDERER_COUNTS) {
                rg.renderersLoaded++;
                if (w.skipRenderPass[0]) rg.renderersSkippingRenderPass++;
                else if (!w.isInFrustum) rg.renderersBeingClipped++;
                else if (occActive && !w.isVisible) rg.renderersBeingOccluded++;
                else rg.renderersBeingRendered++;
            }
            if (!w.skipRenderPass[pass] && w.isInFrustum && (!occActive || w.isVisible)) {
                int gl = w.glRenderList + pass;
                if (gl >= 0) {
                    appendRegion(w, gl);
                    count++;
                }
            }
        }
        flushRegions(rg, camX, camY, camZ);
        rg.renderAllRenderLists(pass, pt);
        return count;
    }
}
