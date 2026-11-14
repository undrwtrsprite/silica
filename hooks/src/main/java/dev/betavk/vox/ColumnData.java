package dev.betavk.vox;

import net.minecraft.src.BiomeGenBase;

/** One generated chunk: terrain blocks and metadata (index x << 11 | z << 7 | y), per-column climate, and the surface summary the tree replay needs. */
final class ColumnData {
    final int cx, cz;
    final byte[] blocks, meta;
    final float[] temp = new float[256], hum = new float[256];   // index x * 16 + z
    BiomeGenBase corner;                                          // biome of column (0, 0)
    final short[] hv = new short[256], floor = new short[256];
    final byte[] sid = new byte[256];

    ColumnData(int cx, int cz, byte[] blocks, byte[] meta) {
        this.cx = cx;
        this.cz = cz;
        this.blocks = blocks;
        this.meta = meta;
    }

    /** Fills hv (first y above the topmost non-air block, water included), floor (above the topmost solid), sid (the block at hv - 1). */
    void summarise() {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = x * 16 + z, base = x << 11 | z << 7;
                int y = 127;
                while (y > 0 && blocks[base + y] == 0) y--;
                int fy = y;
                while (fy > 0 && (blocks[base + fy] == 8 || blocks[base + fy] == 9)) fy--;
                hv[col] = (short) (y + 1);
                floor[col] = (short) (fy + 1);
                sid[col] = blocks[base + y];
            }
        }
    }
}
