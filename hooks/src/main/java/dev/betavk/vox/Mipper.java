package dev.betavk.vox;

import net.minecraft.src.Block;

/**
 * Builds a coarser section from the eight finer ones below it. Each output voxel stands for a 2x2x2 group: the non-air voxel that is most
 * opaque wins (full cubes over translucent ones such as water and leaves), the higher one on a tie so that tops of things are kept. A group that
 * is all air stays air and takes the average light of its eight voxels (sky light rounded up).
 */
public final class Mipper {
    private Mipper() {
    }

    /** Rank of a voxel for winning a group: 0 for air. */
    static int rank(int v) {
        int id = Vox.id(v);
        if (id == 0) return 0;
        int op = Block.lightOpacity[id];
        return op >= 255 ? 4 : op > 0 ? 3 : Block.opaqueCubeLookup[id] ? 4 : 2;
    }

    /** Fills out (a section at level L+1) from children[dx + 2*dz + 4*dy] (sections at level L, null = nothing there). */
    public static void build(VoxelSection out, VoxelSection[] children) {
        int[] src = new int[8];
        for (int oy = 0; oy < 32; oy++) {
            for (int oz = 0; oz < 32; oz++) {
                for (int ox = 0; ox < 32; ox++) {
                    VoxelSection c = children[(ox >> 4) + 2 * (oz >> 4) + 4 * (oy >> 4)];
                    if (c == null) {
                        out.set(ox, oy, oz, 0);
                        continue;
                    }
                    int bx = (ox & 15) * 2, by = (oy & 15) * 2, bz = (oz & 15) * 2;
                    int n = 0;
                    for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) src[n++] = c.get(bx + dx, by + dy, bz + dz);
                    out.set(ox, oy, oz, pick(src));
                }
            }
        }
        out.dirty = true;
    }

    /** src holds the eight voxels with index dx + 2*dz + 4*dy. */
    public static int pick(int[] src) {
        int best = -1, bestScore = 0;
        for (int i = 0; i < 8; i++) {
            int r = rank(src[i]);
            if (r == 0) continue;
            int score = r * 8 + (i >> 2) * 4 + (i & 3);   // rank first, then the upper layer, then a fixed corner order
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best >= 0) return src[best];
        int sky = 0, blk = 0;
        for (int i = 0; i < 8; i++) {
            sky += Vox.sky(src[i]);
            blk += Vox.blockLight(src[i]);
        }
        return Vox.make(0, 0, (sky + 7) / 8, blk / 8, Vox.tint(src[7]));
    }
}
