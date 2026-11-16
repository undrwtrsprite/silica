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
    public static final long ALL_VISIBLE = 0xFFFFFFFFFL;   // 6x6 face bits all connected

    // Faces: 0=-Y, 1=+Y, 2=-Z, 3=+Z, 4=-X, 5=+X
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private static long[] visMasks = new long[0];
    private static final VisGraph syncGraph = new VisGraph();

    // Game-thread BFS scratch buffers (stamp-indexed: zero Arrays.fill per frame)
    private static int[] visStamp = new int[0];
    private static int[] frustumStamp = new int[0];   // +frameId = in, -frameId = out
    private static byte[] cellState = new byte[0];    // bits 0..5 = queued faces, bit 6 (0x40) = inQueue
    private static byte[] expandedMask = new byte[0];
    private static int[] lastVisible = new int[0];
    private static int[] bfsQueue = new int[0];
    private static int frameId = 1;
    private static int lastVisibleCount;
    private static boolean wasFallback;

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
        lastVisibleCount = 0;
        wasFallback = false;
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
        if (visStamp.length < n) {
            visStamp = new int[n];
            frustumStamp = new int[n];
            cellState = new byte[n];
            expandedMask = new byte[n];
            lastVisible = new int[n];
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

    private static boolean inFrustum(WorldRenderer w, int idx, ICamera cam, Frustrum fr) {
        int st = frustumStamp[idx];
        if (st == frameId) return true;
        if (st == -frameId) return false;
        boolean inside;
        if (fr != null) {
            float[][] frPlanes = fr.clippingHelper.frustum;
            double x0 = w.posX - 6.0 - fr.xPosition;
            double y0 = w.posY - 6.0 - fr.yPosition;
            double z0 = w.posZ - 6.0 - fr.zPosition;
            double x1 = x0 + w.sizeWidth + 12.0;
            double y1 = y0 + w.sizeHeight + 12.0;
            double z1 = z0 + w.sizeDepth + 12.0;
            inside = true;
            for (int i = 0; i < 6; i++) {
                float[] p = frPlanes[i];
                float a = p[0], b = p[1], c = p[2];
                if (a * (a >= 0f ? x1 : x0) + b * (b >= 0f ? y1 : y0) + c * (c >= 0f ? z1 : z0) + p[3] <= 0.0) {
                    inside = false;
                    break;
                }
            }
        } else {
            inside = cam.isBoundingBoxInFrustum(w.rendererBoundingBox);
        }
        frustumStamp[idx] = inside ? frameId : -frameId;
        return inside;
    }

    private static void seed(WorldRenderer[] wrs, int idx, int inFaceBit) {
        if (visStamp[idx] != frameId) {
            visStamp[idx] = frameId;
            cellState[idx] = (byte) inFaceBit;
            expandedMask[idx] = 0;
            WorldRenderer w = wrs[idx];
            if (!w.skipAllRenderPasses()) {
                w.isInFrustum = true;
                lastVisible[lastVisibleCount++] = idx;
            }
        } else {
            cellState[idx] |= (byte) inFaceBit;
        }
    }

    /** replaceBody of RenderGlobal.clipRenderersByFrustrum: frustum + cave culling on reachable sections only. */
    public static void clipAndCull(RenderGlobal rg, ICamera cam, float pt) {
        WorldRenderer[] wrs = rg.worldRenderers;
        if (wrs == null) return;
        int N = wrs.length;
        EntityLiving ent = rg.mc.renderViewEntity;
        if (!ENABLED || rg.mc.gameSettings.thirdPersonView || ent == null) {
            wasFallback = true;
            for (int i = 0; i < N; i++) {
                WorldRenderer w = wrs[i];
                if (!w.skipAllRenderPasses() && (!w.isInFrustum || ((i + rg.frustrumCheckOffset) & 15) == 0)) {
                    w.updateInFrustrum(cam);
                }
            }
            rg.frustrumCheckOffset++;
            return;
        }
        int W = rg.renderChunksWide, T = rg.renderChunksTall, D = rg.renderChunksDeep;
        if (N == 0 || W <= 0 || T <= 0 || D <= 0) return;
        ensureScratch(N);
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

        Frustrum fr = (cam instanceof Frustrum) ? (Frustrum) cam : null;
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
                        seed(wrs, idx, 63);
                        if ((cellState[idx] & 0x40) == 0) {
                            cellState[idx] |= 0x40;
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
                    if (inFrustum(w, idx, cam, fr)) {
                        seed(wrs, idx, inBit);
                        if ((cellState[idx] & 0x40) == 0 && tail < bfsQueue.length) {
                            cellState[idx] |= 0x40;
                            bfsQueue[tail++] = idx;
                        }
                    }
                }
            }
        }

        while (head < tail) {
            int idx = bfsQueue[head++];
            int cs = cellState[idx];
            cellState[idx] = (byte) (cs & 63);
            int qm = cs & 63;
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
                if (!inFrustum(nw, nIdx, cam, fr)) continue;
                int oppBit = 1 << (dir ^ 1);
                if (visStamp[nIdx] != frameId || (cellState[nIdx] & oppBit) == 0) {
                    seed(wrs, nIdx, oppBit);
                    if ((cellState[nIdx] & 0x40) == 0 && tail < bfsQueue.length) {
                        cellState[nIdx] |= 0x40;
                        bfsQueue[tail++] = nIdx;
                    }
                }
            }
        }
    }
}
