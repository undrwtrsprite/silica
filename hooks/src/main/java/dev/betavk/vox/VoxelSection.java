package dev.betavk.vox;

/**
 * A 32x32x32 cube of voxels at one level of detail. A level-L voxel covers 2^L blocks per side, so a section covers 32 << L blocks per side.
 * Coordinates sx, sy, sz are in sections of that level. Index = (y * 32 + z) * 32 + x.
 */
public final class VoxelSection {
    public static final int SIZE = 32, VOLUME = SIZE * SIZE * SIZE;

    public final int level, sx, sy, sz;
    public final int[] voxels = new int[VOLUME];
    public volatile boolean dirty;

    public VoxelSection(int level, int sx, int sy, int sz) {
        this.level = level;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
    }

    public static int index(int x, int y, int z) {
        return (y * SIZE + z) * SIZE + x;
    }

    public int get(int x, int y, int z) {
        return voxels[index(x, y, z)];
    }

    public void set(int x, int y, int z, int v) {
        voxels[index(x, y, z)] = v;
    }

    /** Voxels that are not air. */
    public int nonAirCount() {
        int n = 0;
        for (int v : voxels) if ((v & 255) != 0) n++;
        return n;
    }

    public boolean isAllAir() {
        for (int v : voxels) if ((v & 255) != 0) return false;
        return true;
    }

    /** Blocks per voxel side. */
    public int scale() {
        return 1 << level;
    }

    /** World block coordinate of the section's minimum corner. */
    public long originX() {
        return (long) sx * (SIZE << level);
    }

    public long originY() {
        return (long) sy * (SIZE << level);
    }

    public long originZ() {
        return (long) sz * (SIZE << level);
    }
}
