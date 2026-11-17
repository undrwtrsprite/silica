package dev.betavk.lod;

/**
 * One chunk-aligned LOD node.
 *   level 0: 1x1 chunks (16x128x16), 16x128x16 voxels (1x1x1 full real blocks)
 *   level 1: 1x1 chunks (16x128x16), 8x128x8 voxels (2x1x2 blocks)
 *   level 2: 2x2 chunks (32x128x32), 8x64x8 voxels (4x2x4 blocks)
 *   level 3: 4x4 chunks (64x128x64), 8x32x8 voxels (8x4x8 blocks)
 *   level 4: 8x8 chunks (128x128x128), 8x32x8 voxels (16x4x16 blocks)
 */
public final class LodNode {
    public static final int FLAG_GRASS_SIDE = 1 << 20;
    public static final int FLAG_WATER_FULL = 1 << 21;

    public final int level;   // 0..4
    public final int gx, gz;  // node coords in level units (chunk coords = gx * span, gz * span)
    public final int span;    // 1, 1, 2, 4, 8 chunks
    public final int dim, dimShift;
    public final int h, hShift;
    public final int scaleXZ, scaleY;

    public final int[] vox;
    public final short[] colTint;   // (tempByte << 8) | humByte per (vx, vz)

    // Lifecycle & GPU display lists (game-thread owned)
    volatile boolean dataReady;
    volatile boolean dataDirty;
    volatile boolean queuedData;
    volatile boolean queuedMesh;
    volatile int version;
    int lastSeenFrame;
    int opaqueList = -1, transList = -1;
    int bakedVersion = -1;
    int bakedLightSub = -1;
    boolean bakedFancy;
    int bakedBetterGrass = -1;
    int bakedNeighborMask = -1;

    public LodNode(int level, int gx, int gz) {
        this.level = level;
        this.gx = gx;
        this.gz = gz;
        this.span = level == 0 ? 1 : (1 << (level - 1));
        this.dimShift = level == 0 ? 4 : 3;
        this.dim = 1 << dimShift;
        this.hShift = level <= 1 ? 7 : (level == 2 ? 6 : 5);
        this.h = 1 << hShift;
        this.scaleXZ = 1 << level;        // 1, 2, 4, 8, 16
        this.scaleY = level <= 1 ? 1 : (level == 2 ? 2 : 4);
        this.vox = new int[dim * dim * h];
        this.colTint = new short[dim * dim];
    }

    public int get(int vx, int vy, int vz) {
        return vox[(((vx << dimShift) | vz) << hShift) | vy];
    }

    public void set(int vx, int vy, int vz, int val) {
        vox[(((vx << dimShift) | vz) << hShift) | vy] = val;
    }

    public static int pack(int id, int meta, int sky, int blk, int flags) {
        return (id & 0xFF) | ((meta & 0xF) << 8) | ((sky & 0xF) << 12) | ((blk & 0xF) << 16) | flags;
    }

    public static int id(int v) {
        return v & 0xFF;
    }

    public static int meta(int v) {
        return (v >>> 8) & 0xF;
    }

    public static int sky(int v) {
        return (v >>> 12) & 0xF;
    }

    public static int blk(int v) {
        return (v >>> 16) & 0xF;
    }

    public static boolean isGrassSide(int v) {
        return (v & FLAG_GRASS_SIDE) != 0;
    }

    public static boolean isWaterFull(int v) {
        return (v & FLAG_WATER_FULL) != 0;
    }

    public static short packTint(double temp, double hum) {
        int t = Math.max(0, Math.min(255, (int) Math.round(temp * 255.0)));
        int hu = Math.max(0, Math.min(255, (int) Math.round(hum * 255.0)));
        return (short) ((t << 8) | hu);
    }

    public static double tintTemp(short s) {
        return ((s >>> 8) & 255) / 255.0;
    }

    public static double tintHum(short s) {
        return (s & 255) / 255.0;
    }
}
