package dev.betavk.vox;

import net.minecraft.src.Block;
import net.minecraft.src.ColorizerFoliage;
import net.minecraft.src.ColorizerGrass;

import java.util.Arrays;

/**
 * Turns a voxel section into triangles in the game's 8-int vertex layout (x, y, z, u, v, rgba, normal, pad), as float bits. Faces are generated per
 * voxel and culled against their neighbours (including those in the next section), opaque and translucent faces go to separate buffers. Shading is
 * the game's own face brightness (top 1.0, z 0.8, x 0.6, bottom 0.5) times the light of the air voxel the face looks into.
 */
public final class Mesher {
    private Mesher() {
    }

    // block kinds
    static final int AIR = 0, OPAQUE = 1, LEAVES = 2, WATER = 3, TRANSLUCENT = 4, SNOW = 5;
    private static final byte[] KIND = new byte[256];
    private static final float[] LIGHT = new float[16];

    static {
        for (int i = 0; i < 16; i++) {
            float f = 1.0F - i / 15.0F;
            LIGHT[i] = (1.0F - f) / (f * 3.0F + 1.0F) * 0.95F + 0.05F;
        }
        for (int id = 1; id < 256; id++) {
            Block b = Block.blocksList[id];
            if (b == null) continue;
            KIND[id] = (byte) (id == 18 ? LEAVES : id == 8 || id == 9 ? WATER : id == 79 || id == 20 ? TRANSLUCENT : id == 78 ? SNOW : b.isOpaqueCube() ? OPAQUE : AIR);
        }
    }

    public static final class Mesh {
        public int[] opaque = new int[0], translucent = new int[0];
        public int opaqueVerts, translucentVerts;
    }

    private static final class Buf {
        int[] a = new int[1 << 12];
        int n;

        void vertex(float x, float y, float z, float u, float v, int color) {
            if (n + 8 > a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n] = Float.floatToRawIntBits(x);
            a[n + 1] = Float.floatToRawIntBits(y);
            a[n + 2] = Float.floatToRawIntBits(z);
            a[n + 3] = Float.floatToRawIntBits(u);
            a[n + 4] = Float.floatToRawIntBits(v);
            a[n + 5] = color;
            a[n + 6] = 0;
            a[n + 7] = 0;
            n += 8;
        }

        /** Quad a b c d (counter-clockwise from outside) as two triangles: abc, acd. */
        void quad(float[] p, float u0, float v0, float u1, float v1, int color) {
            float[] us = {u0, u1, u1, u0}, vs = {v0, v0, v1, v1};
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int k : order) vertex(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], us[k], vs[k], color);
        }
    }

    private static int packColor(int r, int g, int b) {
        return 255 << 24 | b << 16 | g << 8 | r;   // little-endian RGBA, as the game's tessellator packs it
    }

    /** Biome colour of a voxel for tinted blocks, as 0xRRGGBB; 0xFFFFFF when untinted. */
    static int tintRgb(int id, int meta, int tintIdx, int side) {
        if (id == 2 && side == 1) return ColorizerGrass.getGrassColor(Vox.tintTemperature(tintIdx), Vox.tintHumidity(tintIdx)) & 0xFFFFFF;
        if (id == 18) {
            int t = meta & 3;
            if (t == 1) return ColorizerFoliage.getFoliageColorPine() & 0xFFFFFF;
            if (t == 2) return ColorizerFoliage.getFoliageColorBirch() & 0xFFFFFF;
            return ColorizerFoliage.getFoliageColor(Vox.tintTemperature(tintIdx), Vox.tintHumidity(tintIdx)) & 0xFFFFFF;
        }
        return 0xFFFFFF;
    }

    private static int voxel(VoxelStore store, VoxelSection s, int x, int y, int z) {
        VoxelSection t = s;
        int sx = s.sx, sy = s.sy, sz = s.sz;
        if (x < 0) {
            sx--;
            x += 32;
        } else if (x >= 32) {
            sx++;
            x -= 32;
        }
        if (z < 0) {
            sz--;
            z += 32;
        } else if (z >= 32) {
            sz++;
            z -= 32;
        }
        if (y < 0) {
            sy--;
            y += 32;
        } else if (y >= 32) {
            sy++;
            y -= 32;
        }
        if (sy < 0) return Vox.make(1, 0, 0, 0, 0);   // below the world: solid, nothing to see
        if (sy >= VoxelStore.ySections(s.level)) return Vox.make(0, 0, 15, 0, 0);   // above the world: open sky
        if (sx != s.sx || sy != s.sy || sz != s.sz) {
            t = store.get(s.level, sx, sy, sz);
            if (t == null) return Vox.make(0, 0, 15, 0, 0);   // not loaded (yet): looks like open air, so the face is drawn
        }
        return t.get(x, y, z);
    }

    static boolean hides(int selfKind, int selfId, int nVox) {
        int nId = Vox.id(nVox);
        int nk = KIND[nId];
        if (nk == AIR || nk == SNOW) return false;
        if (nk == OPAQUE || nk == LEAVES) return true;
        if (nk == WATER) return selfKind == WATER;
        return nId == selfId;   // translucent neighbour: hides only the same kind
    }

    // face tables: direction d = 0 -y, 1 +y, 2 -z, 3 +z, 4 -x, 5 +x (the game's side numbers)
    private static final int[][] DIR = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    private static final float[] SHADE = {0.5F, 1.0F, 0.8F, 0.8F, 0.6F, 0.6F};

    private static void face(float[] p, int d, float x0, float y0, float z0, float x1, float y1, float z1) {
        float[][] q;
        switch (d) {
            case 1 -> q = new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            case 0 -> q = new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
            case 5 -> q = new float[][]{{x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}};
            case 4 -> q = new float[][]{{x0, y1, z1}, {x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}};
            case 3 -> q = new float[][]{{x1, y1, z1}, {x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}};
            default -> q = new float[][]{{x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}};
        }
        for (int i = 0; i < 4; i++) {
            p[i * 3] = q[i][0];
            p[i * 3 + 1] = q[i][1];
            p[i * 3 + 2] = q[i][2];
        }
    }

    private static final boolean DEBUG_LEVELS = Boolean.getBoolean("betavk.vox.debuglevels");
    private static final int[] LEVEL_TINT = {0xA0A0FF, 0xFF9090, 0x90FF90, 0x9090FF, 0xFFFF70};

    public static Mesh mesh(VoxelStore store, VoxelSection s) {
        Buf opaque = new Buf(), trans = new Buf();
        float sc = 1 << s.level;
        float[] p = new float[12];
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int v = s.get(x, y, z);
                    int id = Vox.id(v);
                    if (id == 0) continue;
                    int kind = KIND[id];
                    if (kind == AIR) continue;
                    Block blk = Block.blocksList[id];
                    float x0 = x * sc, y0 = y * sc, z0 = z * sc, x1 = x0 + sc, y1 = y0 + sc, z1 = z0 + sc;
                    // Surfaces sit where the game puts them at every level: water at 63.889 (a ninth below the top of its block) and snow an eighth of a
                    // block thick; scaling them with the voxel made the sea level drop and the snow thicken with distance, a visible step between levels.
                    if (kind == WATER) {
                        float worldTop = s.sy * 32 * sc + y1;
                        if (worldTop > 63.889F) y1 -= worldTop - 63.889F;
                    }
                    if (kind == SNOW) y1 = y0 + 0.125F;
                    for (int d = 0; d < 6; d++) {
                        int[] o = DIR[d];
                        int nv = voxel(store, s, x + o[0], y + o[1], z + o[2]);
                        if (kind == WATER && d != 1) continue;   // only the surface of water is drawn
                        if (kind == SNOW && d == 0) continue;
                        if (hides(kind, id, nv) && !(kind == SNOW && d == 1)) continue;
                        if (d == 1 && kind != SNOW && KIND[Vox.id(nv)] == SNOW) continue;   // snow lies on it
                        int light = Math.max(Vox.sky(nv), Vox.blockLight(nv));
                        if (KIND[Vox.id(nv)] != AIR && KIND[Vox.id(nv)] != SNOW) light = Math.max(Vox.sky(v), Vox.blockLight(v));
                        float sh = SHADE[d] * LIGHT[light];
                        int tile = kind == SNOW ? 66 : id == 18 ? ((Vox.meta(v) & 3) == 1 ? 133 : 53) : blk.getBlockTextureFromSideAndMetadata(d, Vox.meta(v));   // leaves: the opaque ('fast') textures
                        int rgb = tintRgb(id, Vox.meta(v), Vox.tint(v), d);
                        if (DEBUG_LEVELS) rgb = ((rgb >> 16 & 255) * (LEVEL_TINT[s.level] >> 16 & 255) / 255) << 16 | ((rgb >> 8 & 255) * (LEVEL_TINT[s.level] >> 8 & 255) / 255) << 8 | (rgb & 255) * (LEVEL_TINT[s.level] & 255) / 255;
                        int color = packColor((int) (((rgb >> 16) & 255) * sh), (int) (((rgb >> 8) & 255) * sh), (int) ((rgb & 255) * sh));
                        float u0 = (tile % 16) / 16.0F, v0 = (tile / 16) / 16.0F;
                        face(p, d, x0, y0, z0, x1, y1, z1);
                        (kind == WATER || kind == TRANSLUCENT ? trans : opaque).quad(p, u0, v0, u0 + 1 / 16.0F, v0 + 1 / 16.0F, color);
                    }
                }
            }
        }
        Mesh m = new Mesh();
        m.opaque = Arrays.copyOf(opaque.a, opaque.n);
        m.opaqueVerts = opaque.n / 8;
        m.translucent = Arrays.copyOf(trans.a, trans.n);
        m.translucentVerts = trans.n / 8;
        return m;
    }
}
