package dev.betavk.lod;

import dev.betavk.render.Gfx;
import net.minecraft.src.Block;
import net.minecraft.src.ColorizerFoliage;
import net.minecraft.src.ColorizerGrass;
import net.minecraft.src.Tessellator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Greedy-merged, zero-bleed real-block mesher for LodNodes.
 * Outputs Beta's exact 8-int packed vertex layout (x, y, z, u, v, rgba, normal, pad) without GL_LIGHTING,
 * using the world's current skylightSubtracted & lightBrightnessTable, biome foliage/grass colors, snowy grass
 * sides, Fancy/Fast leaf textures, and exact height water/snow surfaces.
 */
public final class LodMesher {
    private LodMesher() {
    }

    public static final class Mesh {
        public int[] opaque = new int[0];
        public int opaqueVerts;
        public int[] trans = new int[0];
        public int transVerts;
        public boolean quads;
    }

    static final class Scratch {
        int[] opaqueBuf = new int[1 << 13];
        int opaqueInts;
        int[] transBuf = new int[1 << 11];
        int transInts;
        final int[] faceTile = new int[16 * 128];
        final int[] faceColor = new int[16 * 128];
        final int[] faceTop = new int[16 * 128];   // encoded voxel height (quantized sub-voxel bits for water/snow/slabs)
        final boolean[] used = new boolean[16 * 128];

        void reset() {
            opaqueInts = 0;
            transInts = 0;
        }

        void pushQuad(boolean trans, float x0, float y0, float z0, float x1, float y1, float z1,
                      float x2, float y2, float z2, float x3, float y3, float z3,
                      float u0, float v0, float u1, float v1, int rgba, boolean asQuads) {
            int verts = asQuads ? 4 : 6;
            int need = verts * 8;
            int[] buf;
            int n;
            if (trans) {
                if (transInts + need > transBuf.length) transBuf = Arrays.copyOf(transBuf, Math.max(transBuf.length * 2, transInts + need));
                buf = transBuf;
                n = transInts;
                transInts += need;
            } else {
                if (opaqueInts + need > opaqueBuf.length) opaqueBuf = Arrays.copyOf(opaqueBuf, Math.max(opaqueBuf.length * 2, opaqueInts + need));
                buf = opaqueBuf;
                n = opaqueInts;
                opaqueInts += need;
            }
            if (asQuads) {
                putVert(buf, n, x0, y0, z0, u0, v0, rgba);
                putVert(buf, n + 8, x1, y1, z1, u1, v0, rgba);
                putVert(buf, n + 16, x2, y2, z2, u1, v1, rgba);
                putVert(buf, n + 24, x3, y3, z3, u0, v1, rgba);
            } else {
                putVert(buf, n, x0, y0, z0, u0, v0, rgba);
                putVert(buf, n + 8, x1, y1, z1, u1, v0, rgba);
                putVert(buf, n + 16, x2, y2, z2, u1, v1, rgba);
                putVert(buf, n + 24, x0, y0, z0, u0, v0, rgba);
                putVert(buf, n + 32, x2, y2, z2, u1, v1, rgba);
                putVert(buf, n + 40, x3, y3, z3, u0, v1, rgba);
            }
        }

        private static void putVert(int[] buf, int off, float x, float y, float z, float u, float v, int rgba) {
            buf[off] = Float.floatToRawIntBits(x);
            buf[off + 1] = Float.floatToRawIntBits(y);
            buf[off + 2] = Float.floatToRawIntBits(z);
            buf[off + 3] = Float.floatToRawIntBits(u);
            buf[off + 4] = Float.floatToRawIntBits(v);
            buf[off + 5] = rgba;
            buf[off + 6] = 0;
            buf[off + 7] = 0;
        }
    }

    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};
    private static final float[] SHADE = {0.50F, 1.00F, 0.80F, 0.80F, 0.60F, 0.60F};
    private static final float UV_TILE = 1.0F / 16.0F;
    private static final float UV_EPS = 0.5F / 256.0F;

    private static int neighbor(LodNode node, LodNode[] nb, int vx, int vy, int vz) {
        int dim = node.dim;
        if (vy < 0) return LodNode.pack(1, 0, 0, 0, 0);
        if (vy >= node.h) return LodNode.pack(0, 0, 15, 0, 0);
        if (vx >= 0 && vx < dim && vz >= 0 && vz < dim) return node.get(vx, vy, vz);
        int ni = -1;
        int nx = vx, nz = vz;
        if (nx < 0 && nz >= 0 && nz < dim) { ni = 0; nx += dim; }
        else if (nx >= dim && nz >= 0 && nz < dim) { ni = 1; nx -= dim; }
        else if (nz < 0 && nx >= 0 && nx < dim) { ni = 2; nz += dim; }
        else if (nz >= dim && nx >= 0 && nx < dim) { ni = 3; nz -= dim; }
        LodNode n = ni >= 0 ? nb[ni] : null;
        if (n != null && n.dataReady && n.dim == dim && n.h == node.h) return n.get(nx, vy, nz);
        return LodEngine.sampleCrossLevel(node, vx, vy, vz);
    }

    private static boolean isWater(int id) {
        return id == 8 || id == 9;
    }

    private static boolean isOpaqueSolid(int id) {
        if (id == 0 || id == 18 || isWater(id) || id == 78 || id == 79 || id == 20) return false;
        Block b = Block.blocksList[id];
        return b != null && b.isOpaqueCube();
    }

    private static boolean hides(int selfId, int nId, boolean fancy) {
        if (nId == 0 || nId == 78) return false;
        if (isOpaqueSolid(nId)) return true;
        if (nId == 18) return !fancy && selfId != 78;
        if (isWater(selfId) && nId == 79) return true;   // ice hides water directly beneath it (matches BlockFluid)
        if (isWater(nId)) return isWater(selfId);
        return nId == selfId;   // ice / glass
    }

    private static int packRgba(int r, int g, int b) {
        return 0xFF000000 | ((b & 255) << 16) | ((g & 255) << 8) | (r & 255);
    }

    private static int tintRgb(int id, int meta, short colTint, int side, boolean grassSlope) {
        if (id == 2 && (side == 1 || (side >= 2 && grassSlope))) {
            return ColorizerGrass.getGrassColor(LodNode.tintTemp(colTint), LodNode.tintHum(colTint)) & 0xFFFFFF;
        }
        if (id == 18) {
            int t = meta & 3;
            if (t == 1) return ColorizerFoliage.getFoliageColorPine() & 0xFFFFFF;
            if (t == 2) return ColorizerFoliage.getFoliageColorBirch() & 0xFFFFFF;
            return ColorizerFoliage.getFoliageColor(LodNode.tintTemp(colTint), LodNode.tintHum(colTint)) & 0xFFFFFF;
        }
        return 0xFFFFFF;
    }

    /** nb order: [0]=-X, [1]=+X, [2]=-Z, [3]=+Z. */
    public static Mesh mesh(LodNode node, LodNode[] nb, float[] lightTable, int skySub, boolean fancy, Scratch s) {
        s.reset();
        boolean asQuads = !Tessellator.convertQuadsToTriangles;
        int h = node.h;
        int dim = node.dim;
        float sx = node.scaleXZ, sy = node.scaleY;
        int maxMerge = node.level == 0 ? 16 : (node.level == 1 ? 4 : 8);

        for (int d = 0; d < 6; d++) {
            int dx = DX[d], dy = DY[d], dz = DZ[d];
            boolean horizontalSlice = (d <= 1);
            int slices = horizontalSlice ? h : dim;
            int uMax = dim;
            int vMax = horizontalSlice ? dim : h;
            float shade = SHADE[d];

            for (int sl = 0; sl < slices; sl++) {
                Arrays.fill(s.faceTile, 0, uMax * vMax, 0);
                Arrays.fill(s.used, 0, uMax * vMax, false);
                boolean anyFace = false;

                for (int u = 0; u < uMax; u++) {
                    for (int v = 0; v < vMax; v++) {
                        int vx, vy, vz;
                        if (horizontalSlice) {
                            vx = u; vz = v; vy = sl;
                        } else if (d >= 4) {
                            vx = sl; vy = v; vz = u;
                        } else {
                            vx = u; vy = v; vz = sl;
                        }
                        int cell = node.get(vx, vy, vz);
                        int id = LodNode.id(cell);
                        if (id == 0) continue;
                        if (id == 78 && d == 0) continue;   // bottom of thin snow layer covered by ground

                        int nv = neighbor(node, nb, vx + dx, vy + dy, vz + dz);
                        int nId = LodNode.id(nv);
                        boolean aboveSnow = false;
                        int aboveCell = vy + 1 < h ? node.get(vx, vy + 1, vz) : 0;
                        int aboveId = LodNode.id(aboveCell);
                        if (d == 1 && id != 78 && aboveId == 78) continue;
                        if (hides(id, nId, fancy) && !(id == 78 && d == 1)) continue;
                        if (id == 2 && (aboveId == 78 || aboveId == 80)) aboveSnow = true;

                        boolean water = isWater(id);
                        boolean iceGlass = (id == 79 || id == 20);
                        boolean trans = water || iceGlass;
                        int meta = LodNode.meta(cell);
                        int lightV = !isOpaqueSolid(nId) ? nv : cell;
                        int blk = Math.max(LodNode.blk(lightV), Block.lightValue[id]);
                        int eff = Math.max(0, Math.min(15, Math.max(LodNode.sky(lightV) - skySub, blk)));
                        float sh = shade * lightTable[eff];

                        boolean grassSlope = false;
                        if (id == 2 && d >= 2 && !aboveSnow && LodNode.isGrassSide(cell)) {
                            // Keep rolling slopes green; sheer cliffs (air below neighbor) stay dirt.
                            if (vy == 0 || LodNode.id(neighbor(node, nb, vx + dx, vy - 1, vz + dz)) != 0) {
                                grassSlope = true;
                            }
                        }

                        int tile;
                        if (id == 78) {
                            tile = 66;
                        } else if (id == 18) {
                            int t = meta & 3;
                            tile = fancy ? (t == 1 ? 132 : 52) : (t == 1 ? 133 : 53);
                        } else if (id == 2) {
                            tile = d == 1 ? 0 : d == 0 ? 2 : aboveSnow ? 68 : grassSlope ? 0 : 3;
                        } else {
                            Block b = Block.blocksList[id];
                            if (b == null) continue;
                            tile = b.getBlockTextureFromSideAndMetadata(d, meta);
                        }

                        int rgb = tintRgb(id, meta, node.colTint[(vx << node.dimShift) | vz], d, grassSlope);
                        int cr = (int) (((rgb >>> 16) & 255) * sh);
                        int cg = (int) (((rgb >>> 8) & 255) * sh);
                        int cb = (int) ((rgb & 255) * sh);

                        // Top encoding: 0 = full voxel height, 1 = water surface (-0.111m), 2 = snow layer, 3 = half-slab (0.5m)
                        int topMode = 0;
                        if (water && !LodNode.isWaterFull(cell)) topMode = 1;
                        else if (id == 78) topMode = 2;
                        else if (id == 44 && node.scaleY == 1) topMode = 3;

                        int slot = u * vMax + v;
                        s.faceTile[slot] = ((tile + 1) << 1) | (trans ? 1 : 0);
                        s.faceColor[slot] = packRgba(cr, cg, cb);
                        s.faceTop[slot] = topMode;
                        anyFace = true;
                    }
                }
                if (!anyFace) continue;

                // Greedy rectangle merge along (u, v)
                for (int u = 0; u < uMax; u++) {
                    for (int v = 0; v < vMax; v++) {
                        int slot = u * vMax + v;
                        int tk = s.faceTile[slot];
                        if (tk == 0 || s.used[slot]) continue;
                        int col = s.faceColor[slot];
                        int top = s.faceTop[slot];

                        int hSpan = 1;
                        while (v + hSpan < vMax && hSpan < maxMerge) {
                            int ns = u * vMax + (v + hSpan);
                            if (s.used[ns] || s.faceTile[ns] != tk || s.faceColor[ns] != col || s.faceTop[ns] != top) break;
                            hSpan++;
                        }
                        int wSpan = 1;
                        outer:
                        while (u + wSpan < uMax && wSpan < maxMerge) {
                            for (int k = 0; k < hSpan; k++) {
                                int ns = (u + wSpan) * vMax + (v + k);
                                if (s.used[ns] || s.faceTile[ns] != tk || s.faceColor[ns] != col || s.faceTop[ns] != top) break outer;
                            }
                            wSpan++;
                        }
                        for (int du = 0; du < wSpan; du++) {
                            for (int dv = 0; dv < hSpan; dv++) {
                                s.used[(u + du) * vMax + (v + dv)] = true;
                            }
                        }

                        boolean trans = (tk & 1) != 0;
                        int tile = (tk >>> 1) - 1;
                        float u0 = (tile & 15) * UV_TILE + UV_EPS;
                        float v0 = (tile >>> 4) * UV_TILE + UV_EPS;
                        float u1 = u0 + UV_TILE - 2.0F * UV_EPS;
                        float v1 = v0 + UV_TILE - 2.0F * UV_EPS;

                        float x0, y0, z0, x1, y1, z1;
                        if (horizontalSlice) {
                            x0 = u * sx;
                            x1 = (u + wSpan) * sx;
                            z0 = v * sx;
                            z1 = (v + hSpan) * sx;
                            y0 = sl * sy;
                            y1 = top == 1 ? (sl + 1) * sy - 0.1111F
                                    : top == 2 ? y0 + Math.min(0.25F * sy, 1.0F)
                                    : top == 3 ? y0 + 0.5F
                                    : (sl + 1) * sy;
                        } else if (d >= 4) {
                            x0 = sl * sx;
                            x1 = (sl + 1) * sx;
                            z0 = u * sx;
                            z1 = (u + wSpan) * sx;
                            y0 = v * sy;
                            y1 = (v + hSpan) * sy;
                            if (hSpan == 1) {
                                if (top == 1) y1 -= 0.1111F;
                                else if (top == 2) y1 = y0 + Math.min(0.25F * sy, 1.0F);
                                else if (top == 3) y1 = y0 + 0.5F;
                            }
                        } else {
                            x0 = u * sx;
                            x1 = (u + wSpan) * sx;
                            z0 = sl * sx;
                            z1 = (sl + 1) * sx;
                            y0 = v * sy;
                            y1 = (v + hSpan) * sy;
                            if (hSpan == 1) {
                                if (top == 1) y1 -= 0.1111F;
                                else if (top == 2) y1 = y0 + Math.min(0.25F * sy, 1.0F);
                                else if (top == 3) y1 = y0 + 0.5F;
                            }
                        }

                        switch (d) {
                            case 1 -> s.pushQuad(trans, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, u0, v0, u1, v1, col, asQuads);
                            case 0 -> s.pushQuad(trans, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, u0, v0, u1, v1, col, asQuads);
                            case 5 -> s.pushQuad(trans, x1, y1, z0, x1, y1, z1, x1, y0, z1, x1, y0, z0, u0, v0, u1, v1, col, asQuads);
                            case 4 -> s.pushQuad(trans, x0, y1, z1, x0, y1, z0, x0, y0, z0, x0, y0, z1, u0, v0, u1, v1, col, asQuads);
                            case 3 -> s.pushQuad(trans, x1, y1, z1, x0, y1, z1, x0, y0, z1, x1, y0, z1, u0, v0, u1, v1, col, asQuads);
                            default -> s.pushQuad(trans, x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0, u0, v0, u1, v1, col, asQuads);
                        }
                    }
                }
            }
        }

        Mesh m = new Mesh();
        m.quads = asQuads;
        m.opaque = Arrays.copyOf(s.opaqueBuf, s.opaqueInts);
        m.opaqueVerts = s.opaqueInts / 8;
        m.trans = Arrays.copyOf(s.transBuf, s.transInts);
        m.transVerts = s.transInts / 8;
        return m;
    }

    // Reusable direct upload buffer (game thread only — zero per-chunk direct buffer allocations)
    private static ByteBuffer uploadByteBuf = ByteBuffer.allocateDirect(1 << 18).order(ByteOrder.nativeOrder());
    private static IntBuffer uploadIntBuf = uploadByteBuf.asIntBuffer();
    private static FloatBuffer uploadFloatBuf = uploadByteBuf.asFloatBuffer();

    /** Game thread: record packed vertex buffer into a Gfx display list cleanly. */
    public static int recordList(int existing, int[] data, int vertexCount, boolean quads) {
        int list = existing >= 0 ? existing : Gfx.glGenLists(1);
        int bytes = data.length * 4;
        if (bytes > uploadByteBuf.capacity()) {
            int cap = Integer.highestOneBit(bytes) << 1;
            uploadByteBuf = ByteBuffer.allocateDirect(cap).order(ByteOrder.nativeOrder());
            uploadIntBuf = uploadByteBuf.asIntBuffer();
            uploadFloatBuf = uploadByteBuf.asFloatBuffer();
        }
        uploadIntBuf.clear();
        uploadIntBuf.put(data);
        uploadByteBuf.position(0).limit(bytes);
        uploadFloatBuf.clear();

        Gfx.glNewList(list, Gfx.GL_COMPILE);
        uploadFloatBuf.position(3);
        Gfx.glTexCoordPointer(2, Gfx.GL_FLOAT, 32, uploadFloatBuf);
        Gfx.glEnableClientState(Gfx.GL_TEXTURE_COORD_ARRAY);
        uploadByteBuf.position(20);
        Gfx.glColorPointer(4, Gfx.GL_UNSIGNED_BYTE, 32, uploadByteBuf);
        Gfx.glEnableClientState(Gfx.GL_COLOR_ARRAY);
        uploadFloatBuf.position(0);
        Gfx.glVertexPointer(3, Gfx.GL_FLOAT, 32, uploadFloatBuf);
        Gfx.glEnableClientState(Gfx.GL_VERTEX_ARRAY);
        Gfx.glDrawArrays(quads ? 7 /* GL_QUADS */ : Gfx.GL_TRIANGLES, 0, vertexCount);
        Gfx.glDisableClientState(Gfx.GL_VERTEX_ARRAY);
        Gfx.glDisableClientState(Gfx.GL_TEXTURE_COORD_ARRAY);
        Gfx.glDisableClientState(Gfx.GL_COLOR_ARRAY);
        Gfx.glEndList();
        return list;
    }
}
