package dev.betavk.lod;

import net.minecraft.src.Block;

/**
 * Downsamples real 16x128x16 chunks (with light, metadata, and biome climate) into level-1 (2x2x2) LodNodes,
 * and mipmaps level 1 -> 2 -> 3 -> 4 cleanly without ballooning terrain silhouettes or losing player builds.
 */
public final class LodDownsampler {
    private LodDownsampler() {
    }

    private static final int KIND_SKIP = 0;
    private static final int KIND_LEAVES = 1;
    private static final int KIND_WATER = 2;
    private static final int KIND_SNOW = 3;
    private static final int KIND_NATURAL = 4;
    private static final int KIND_STRUCTURE = 5;

    private static final byte[] KIND = new byte[256];

    static {
        for (int id = 1; id < 256; id++) {
            Block b = Block.blocksList[id];
            if (b == null) continue;
            if (id == 18) KIND[id] = KIND_LEAVES;
            else if (id == 8 || id == 9) KIND[id] = KIND_WATER;
            else if (id == 78) KIND[id] = KIND_SNOW;
            else if (id == 6 || id == 31 || id == 32 || id == 37 || id == 38 || id == 39 || id == 40
                    || id == 50 || id == 55 || id == 63 || id == 65 || id == 66 || id == 68 || id == 69
                    || id == 70 || id == 72 || id == 75 || id == 76 || id == 77 || id == 83 || id == 93 || id == 94) {
                KIND[id] = KIND_SKIP;
            } else if (id == 1 || id == 2 || id == 3 || id == 7 || id == 10 || id == 11 || id == 12
                    || id == 13 || id == 17 || id == 24 || id == 79 || id == 80 || id == 82 || id == 87 || id == 88) {
                KIND[id] = KIND_NATURAL;
            } else if (b.isOpaqueCube() || id == 20 || id == 44 || id == 53 || id == 67 || id == 81 || id == 59) {
                KIND[id] = KIND_STRUCTURE;
            } else {
                KIND[id] = KIND_SKIP;
            }
        }
    }

    private static int nibble(byte[] arr, int idx) {
        return (arr[idx >>> 1] >>> ((idx & 1) << 2)) & 15;
    }

    /** Downsamples (or copies at level 0) a real chunk snapshot into LodNode level 0 (16x128x16) or level 1 (8x128x8). */
    public static void fromChunk(LodChunkSource.ChunkSnap c, LodNode out) {
        if (out.level == 0) {
            fromChunkL0(c, out);
            return;
        }
        byte[] blocks = c.blocks;
        byte[] meta = c.data;
        byte[] sky = c.skyLight;
        byte[] blk = c.blockLight;
        float[] temp = c.temp;
        float[] hum = c.hum;

        for (int vx = 0; vx < 8; vx++) {
            int bx0 = vx << 1;
            for (int vz = 0; vz < 8; vz++) {
                int bz0 = vz << 1;
                double tSum = 0, hSum = 0;
                for (int dz = 0; dz < 2; dz++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int ti = ((bx0 + dx) << 4) | (bz0 + dz);
                        tSum += temp[ti];
                        hSum += hum[ti];
                    }
                }
                out.colTint[(vx << 3) | vz] = LodNode.packTint(tSum * 0.25, hSum * 0.25);

                for (int y = 0; y < 128; y++) {
                    int leafCnt = 0, waterCnt = 0, snowCnt = 0, natCnt = 0, manCnt = 0;
                    int leafSample = 0, waterSample = 0, snowSample = 0, natSample = 0, manSample = 0;
                    int skyMax = 0, blkMax = 0;
                    boolean hasAirAbove = false;

                    for (int dz = 0; dz < 2; dz++) {
                        int z = bz0 + dz;
                        for (int dx = 0; dx < 2; dx++) {
                            int x = bx0 + dx;
                            int idx = (x << 11) | (z << 7) | y;
                            int id = blocks[idx] & 255;
                            int s = nibble(sky, idx);
                            int bl = nibble(blk, idx);
                            if (s > skyMax) skyMax = s;
                            if (bl > blkMax) blkMax = bl;
                            if (y + 1 >= 128 || KIND[blocks[idx + 1] & 255] == KIND_SKIP) hasAirAbove = true;

                            int k = KIND[id];
                            if (k == KIND_SKIP) continue;
                            int m = nibble(meta, idx);
                            int sample = (id & 0xFF) | (m << 8);
                            switch (k) {
                                case KIND_STRUCTURE -> { manCnt++; manSample = sample; }
                                case KIND_NATURAL -> {
                                    natCnt++;
                                    if ((natSample & 255) != 2 || id == 2) natSample = sample;
                                }
                                case KIND_LEAVES -> { leafCnt++; leafSample = sample; }
                                case KIND_WATER -> { waterCnt++; waterSample = sample; }
                                case KIND_SNOW -> { snowCnt++; snowSample = sample; }
                                default -> { }
                            }
                        }
                    }

                    int win = 0;
                    int flags = 0;
                    if (manCnt >= 1) win = manSample;
                    else if (natCnt >= 2 || (natCnt == 1 && (natCnt + leafCnt + waterCnt) >= 2)) {
                        win = natSample;
                        if ((win & 255) == 2 && hasAirAbove) flags |= LodNode.FLAG_GRASS_SIDE;
                    } else if (waterCnt >= 2) {
                        win = waterSample;
                        if (y + 1 < 128) {
                            boolean aboveWater = false;
                            for (int dz = 0; dz < 2 && !aboveWater; dz++) {
                                for (int dx = 0; dx < 2 && !aboveWater; dx++) {
                                    int above = blocks[((bx0 + dx) << 11) | ((bz0 + dz) << 7) | (y + 1)] & 255;
                                    if (above == 8 || above == 9) aboveWater = true;
                                }
                            }
                            if (aboveWater) flags |= LodNode.FLAG_WATER_FULL;
                        }
                    } else if (leafCnt >= 2) {
                        win = leafSample;
                    } else if (snowCnt >= 2) {
                        win = snowSample;
                    }

                    if (win == 0) out.set(vx, y, vz, LodNode.pack(0, 0, skyMax, blkMax, 0));
                    else out.set(vx, y, vz, LodNode.pack(win & 255, (win >>> 8) & 15, skyMax, blkMax, flags));
                }
            }
        }
        out.dataReady = true;
    }

    /** Full 1x1x1 near-LOD copy preserving every block, light value, metadata, and per-block climate tint. */
    private static void fromChunkL0(LodChunkSource.ChunkSnap c, LodNode out) {
        byte[] blocks = c.blocks;
        byte[] meta = c.data;
        byte[] sky = c.skyLight;
        byte[] blk = c.blockLight;
        float[] temp = c.temp;
        float[] hum = c.hum;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                out.colTint[(x << 4) | z] = LodNode.packTint(temp[(x << 4) | z], hum[(x << 4) | z]);
                int base = (x << 11) | (z << 7);
                for (int y = 0; y < 128; y++) {
                    int idx = base | y;
                    int id = blocks[idx] & 255;
                    int s = nibble(sky, idx);
                    int bl = nibble(blk, idx);
                    if (KIND[id] == KIND_SKIP) {
                        out.set(x, y, z, LodNode.pack(0, 0, s, bl, 0));
                        continue;
                    }
                    int m = nibble(meta, idx);
                    int flags = 0;
                    if (id == 2 && (y + 1 >= 128 || (blocks[idx + 1] & 255) == 0)) {
                        flags |= LodNode.FLAG_GRASS_SIDE;
                    } else if ((id == 8 || id == 9) && y + 1 < 128) {
                        int above = blocks[idx + 1] & 255;
                        if (above == 8 || above == 9) flags |= LodNode.FLAG_WATER_FULL;
                    }
                    out.set(x, y, z, LodNode.pack(id, m, s, bl, flags));
                }
            }
        }
        out.dataReady = true;
    }

    /** Mips 4 child LodNodes (level L-1) into dst (level L, L in 2..4). */
    public static void mipNode(LodNode dst, LodNode[] children) {
        boolean mergeY = dst.level == 2 || dst.level == 3;   // L1(128)->L2(64) and L2(64)->L3(32) halve Y; L3->L4 keeps 32
        int h = dst.h;
        int[] src = new int[mergeY ? 8 : 4];

        for (int vx = 0; vx < 8; vx++) {
            int cx = vx >> 2;
            int sxBase = (vx & 3) << 1;
            for (int vz = 0; vz < 8; vz++) {
                int cz = vz >> 2;
                int szBase = (vz & 3) << 1;
                LodNode child = children[(cz << 1) | cx];
                if (child == null) {
                    dst.colTint[(vx << 3) | vz] = 0;
                    for (int vy = 0; vy < h; vy++) dst.set(vx, vy, vz, LodNode.pack(0, 0, 15, 0, 0));
                    continue;
                }
                double tSum = 0, hSum = 0;
                for (int dz = 0; dz < 2; dz++) {
                    for (int dx = 0; dx < 2; dx++) {
                        short ct = child.colTint[((sxBase + dx) << 3) | (szBase + dz)];
                        tSum += LodNode.tintTemp(ct);
                        hSum += LodNode.tintHum(ct);
                    }
                }
                dst.colTint[(vx << 3) | vz] = LodNode.packTint(tSum * 0.25, hSum * 0.25);

                for (int vy = 0; vy < h; vy++) {
                    int n = 0;
                    int sy0 = mergeY ? (vy << 1) : vy;
                    int dyEnd = mergeY ? 2 : 1;
                    for (int dy = 0; dy < dyEnd; dy++) {
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++) {
                                src[n++] = child.get(sxBase + dx, sy0 + dy, szBase + dz);
                            }
                        }
                    }
                    dst.set(vx, vy, vz, mergeCells(src, n));
                }
                // Recompute FLAG_WATER_FULL cleanly against the merged voxel directly above
                for (int vy = 0; vy < h - 1; vy++) {
                    int v = dst.get(vx, vy, vz);
                    int id = LodNode.id(v);
                    if (id == 8 || id == 9) {
                        int aboveId = LodNode.id(dst.get(vx, vy + 1, vz));
                        if (aboveId == 8 || aboveId == 9) dst.set(vx, vy, vz, v | LodNode.FLAG_WATER_FULL);
                    }
                }
            }
        }
        dst.dataReady = true;
    }

    private static int mergeCells(int[] src, int count) {
        int manCnt = 0, natCnt = 0, waterCnt = 0, leafCnt = 0, snowCnt = 0;
        int manWin = 0, natWin = 0, waterWin = 0, leafWin = 0, snowWin = 0;
        int skyMax = 0, blkMax = 0;

        for (int i = 0; i < count; i++) {
            int v = src[i];
            int s = LodNode.sky(v);
            int bl = LodNode.blk(v);
            if (s > skyMax) skyMax = s;
            if (bl > blkMax) blkMax = bl;
            int id = LodNode.id(v);
            if (id == 0) continue;
            switch (KIND[id]) {
                case KIND_STRUCTURE -> {
                    manCnt++;
                    manWin = v;
                }
                case KIND_NATURAL -> {
                    natCnt++;
                    if (LodNode.id(natWin) != 2 || id == 2 || i >= count / 2) natWin = v;
                }
                case KIND_WATER -> {
                    waterCnt++;
                    waterWin = v;
                }
                case KIND_LEAVES -> {
                    leafCnt++;
                    leafWin = v;
                }
                case KIND_SNOW -> {
                    snowCnt++;
                    snowWin = v;
                }
                default -> {
                }
            }
        }

        int win = 0;
        int threshold = count == 8 ? 3 : 2;
        if (manCnt >= 1) win = manWin;
        else if (natCnt >= threshold) win = natWin;
        else if (waterCnt >= threshold) win = waterWin;
        else if (leafCnt >= (count == 8 ? 3 : 2)) win = leafWin;
        else if (snowCnt >= threshold) win = snowWin;

        if (win == 0) return LodNode.pack(0, 0, skyMax, blkMax, 0);
        int cleaned = win & ~((0xFF << 12) | LodNode.FLAG_WATER_FULL);
        return cleaned | ((skyMax & 0xF) << 12) | ((blkMax & 0xF) << 16);
    }
}
