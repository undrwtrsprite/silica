package dev.betavk.hooks.world;

import net.minecraft.src.Block;
import net.minecraft.src.Chunk;
import net.minecraft.src.EnumSkyBlock;
import net.minecraft.src.MathHelper;
import net.minecraft.src.MetadataChunkBlock;
import net.minecraft.src.NoiseGeneratorPerlin;
import net.minecraft.src.World;
import net.minecraft.src.WorldGenMinable;

import java.util.Random;

/**
 * Direct-chunk and scalar fast paths for terrain generation, ore population, Perlin noise, and chunk lighting updates.
 */
public final class TerrainFast {
    private TerrainFast() {
    }

    // ---- MetadataChunkBlock.func_4127_a ---------------------------------------------------------

    private static int nibble(byte[] lm, int lx, int y, int lz) {
        int idx = (lx << 11) | (lz << 7) | y;
        return (lm[idx >>> 1] >>> ((idx & 1) << 2)) & 15;
    }

    /** replaceBody of MetadataChunkBlock.func_4127_a: cache column & cardinal chunks instead of 9 World lookups per voxel. */
    public static void lightUpdateFast(MetadataChunkBlock m, World w) {
        int dx = m.field_1302_e - m.field_1298_b + 1;
        int dy = m.field_1301_f - m.field_1304_c + 1;
        int dz = m.field_1300_g - m.field_1303_d + 1;
        if (dx * dy * dz > 32768) {
            System.out.println("Light too large, skipping!");
            return;
        }
        EnumSkyBlock type = m.field_1299_a;
        boolean isSky = type == EnumSkyBlock.Sky;
        boolean isBlock = type == EnumSkyBlock.Block;
        int prevCx = 0, prevCz = 0;
        boolean hasPrev = false, prevValid = false;
        Chunk c0 = null, cXm = null, cXp = null, cZm = null, cZp = null;
        byte[] lm0 = null, lmXm = null, lmXp = null, lmZm = null, lmZp = null;

        for (int x = m.field_1298_b; x <= m.field_1302_e; x++) {
            int cx = x >> 4;
            int lx = x & 15;
            for (int z = m.field_1303_d; z <= m.field_1300_g; z++) {
                int cz = z >> 4;
                int lz = z & 15;
                boolean valid;
                if (hasPrev && cx == prevCx && cz == prevCz) {
                    valid = prevValid;
                } else {
                    valid = dev.betavk.hooks.McCompat.HAS_DO_CHUNKS_NEAR
                            ? w.doChunksNearChunkExist(x, 0, z, 1)
                            : w.checkChunksExist((cx << 4) - 1, 0, (cz << 4) - 1, (cx << 4) + 16, 1, (cz << 4) + 16);
                    if (valid) {
                        c0 = w.getChunkFromChunkCoords(cx, cz);
                        if (dev.betavk.hooks.McCompat.HAS_CHUNK_EMPTY && c0.func_21167_h()) {
                            valid = false;
                        } else {
                            lm0 = isSky ? c0.skylightMap.data : isBlock ? c0.blocklightMap.data : null;
                            cXm = cXp = cZm = cZp = null;
                            lmXm = lmXp = lmZm = lmZp = null;
                        }
                    }
                    prevValid = valid;
                    prevCx = cx;
                    prevCz = cz;
                    hasPrev = true;
                }
                if (!valid) continue;

                if (m.field_1304_c < 0) m.field_1304_c = 0;
                if (m.field_1301_f >= 128) m.field_1301_f = 127;

                if (lm0 == null) {
                    // Fallback if a non-Sky/Block enum were ever used.
                    for (int y = m.field_1304_c; y <= m.field_1301_f; y++) {
                        int oldVal = w.getSavedLightValue(type, x, y, z);
                        if (oldVal != 0) w.setLightValue(type, x, y, z, 0);
                    }
                    continue;
                }

                if (lx == 0 && cXm == null) {
                    cXm = w.getChunkFromChunkCoords(cx - 1, cz);
                    lmXm = isSky ? cXm.skylightMap.data : cXm.blocklightMap.data;
                } else if (lx == 15 && cXp == null) {
                    cXp = w.getChunkFromChunkCoords(cx + 1, cz);
                    lmXp = isSky ? cXp.skylightMap.data : cXp.blocklightMap.data;
                }
                if (lz == 0 && cZm == null) {
                    cZm = w.getChunkFromChunkCoords(cx, cz - 1);
                    lmZm = isSky ? cZm.skylightMap.data : cZm.blocklightMap.data;
                } else if (lz == 15 && cZp == null) {
                    cZp = w.getChunkFromChunkCoords(cx, cz + 1);
                    lmZp = isSky ? cZp.skylightMap.data : cZp.blocklightMap.data;
                }

                byte[] blocks = c0.blocks;
                int colBase = (lx << 11) | (lz << 7);
                int skyY = isSky ? (c0.heightMap[(lz << 4) | lx] & 255) : 128;

                for (int y = m.field_1304_c; y <= m.field_1301_f; y++) {
                    int idx = colBase | y;
                    int oldVal = (lm0[idx >>> 1] >>> ((idx & 1) << 2)) & 15;
                    int id = blocks[idx] & 255;
                    int opacity = Block.lightOpacity[id];
                    if (opacity == 0) opacity = 1;
                    int direct = isSky ? (y >= skyY ? 15 : 0) : Block.lightValue[id];
                    int target;
                    if (opacity >= 15 && direct == 0) {
                        target = 0;
                    } else {
                        int lXm = lx > 0 ? nibble(lm0, lx - 1, y, lz) : nibble(lmXm, 15, y, lz);
                        int lXp = lx < 15 ? nibble(lm0, lx + 1, y, lz) : nibble(lmXp, 0, y, lz);
                        int yM = y <= 0 ? 0 : y - 1;
                        int yP = y >= 127 ? 127 : y + 1;
                        int lYm = (lm0[(colBase | yM) >>> 1] >>> ((yM & 1) << 2)) & 15;
                        int lYp = (lm0[(colBase | yP) >>> 1] >>> ((yP & 1) << 2)) & 15;
                        int lZm = lz > 0 ? nibble(lm0, lx, y, lz - 1) : nibble(lmZm, lx, y, 15);
                        int lZp = lz < 15 ? nibble(lm0, lx, y, lz + 1) : nibble(lmZp, lx, y, 0);
                        int best = lXm;
                        if (lXp > best) best = lXp;
                        if (lYm > best) best = lYm;
                        if (lYp > best) best = lYp;
                        if (lZm > best) best = lZm;
                        if (lZp > best) best = lZp;
                        target = best - opacity;
                        if (target < 0) target = 0;
                        if (direct > target) target = direct;
                    }
                    if (oldVal != target) {
                        w.setLightValue(type, x, y, z, target);
                        int prop = Math.max(0, target - 1);
                        w.neighborLightPropagationChanged(type, x - 1, y, z, prop);
                        w.neighborLightPropagationChanged(type, x, y - 1, z, prop);
                        w.neighborLightPropagationChanged(type, x, y, z - 1, prop);
                        if (x + 1 >= m.field_1302_e) w.neighborLightPropagationChanged(type, x + 1, y, z, prop);
                        if (y + 1 >= m.field_1301_f) w.neighborLightPropagationChanged(type, x, y + 1, z, prop);
                        if (z + 1 >= m.field_1300_g) w.neighborLightPropagationChanged(type, x, y, z + 1, prop);
                    }
                }
            }
        }
    }

    // ---- WorldGenMinable.generate ---------------------------------------------------------------

    /** replaceBody of WorldGenMinable.generate: preserve exact RNG & ellipsoid math, read/set Chunk arrays directly. */
    public static boolean generateMinableFast(WorldGenMinable m, World w, Random rand, int bx, int by, int bz) {
        int num = m.numberOfBlocks;
        int blockId = m.minableBlockId;
        int stoneId = Block.stone.blockID;
        float angle = rand.nextFloat() * 3.1415927F;
        double sSin = MathHelper.sin(angle) * num / 8.0F;
        double sCos = MathHelper.cos(angle) * num / 8.0F;
        double x0 = (bx + 8) + sSin;
        double x1 = (bx + 8) - sSin;
        double z0 = (bz + 8) + sCos;
        double z1 = (bz + 8) - sCos;
        double y0 = by + rand.nextInt(3) + 2;
        double y1 = by + rand.nextInt(3) + 2;

        Chunk cached = null;
        int cachedCx = Integer.MIN_VALUE, cachedCz = Integer.MIN_VALUE;

        for (int i = 0; i <= num; i++) {
            double t = (double) i / (double) num;
            double cxPos = x0 + (x1 - x0) * t;
            double cyPos = y0 + (y1 - y0) * t;
            double czPos = z0 + (z1 - z0) * t;
            double rad = rand.nextDouble() * num / 16.0;
            double sinVal = (MathHelper.sin((float) i * 3.1415927F / (float) num) + 1.0F) * rad + 1.0;
            double halfXZ = sinVal / 2.0;
            double halfY = sinVal / 2.0;

            int minX = MathHelper.floor_double(cxPos - halfXZ);
            int minY = Math.max(0, MathHelper.floor_double(cyPos - halfY));
            int minZ = MathHelper.floor_double(czPos - halfXZ);
            int maxX = MathHelper.floor_double(cxPos + halfXZ);
            int maxY = Math.min(127, MathHelper.floor_double(cyPos + halfY));
            int maxZ = MathHelper.floor_double(czPos + halfXZ);

            for (int x = minX; x <= maxX; x++) {
                double nx = (x + 0.5 - cxPos) / halfXZ;
                double nx2 = nx * nx;
                if (nx2 >= 1.0) continue;
                int chunkX = x >> 4;
                int lx = x & 15;
                for (int y = minY; y <= maxY; y++) {
                    double ny = (y + 0.5 - cyPos) / halfY;
                    double nxy2 = nx2 + ny * ny;
                    if (nxy2 >= 1.0) continue;
                    for (int z = minZ; z <= maxZ; z++) {
                        double nz = (z + 0.5 - czPos) / halfXZ;
                        if (nxy2 + nz * nz < 1.0) {
                            int chunkZ = z >> 4;
                            if (chunkX != cachedCx || chunkZ != cachedCz) {
                                cached = w.getChunkFromChunkCoords(chunkX, chunkZ);
                                cachedCx = chunkX;
                                cachedCz = chunkZ;
                            }
                            int lz = z & 15;
                            if ((cached.blocks[(lx << 11) | (lz << 7) | y] & 255) == stoneId) {
                                cached.setBlockID(lx, y, lz, blockId);
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    // ---- NoiseGeneratorPerlin.func_805_a --------------------------------------------------------

    private static double grad2(int hash, double x, double z) {
        return switch (hash & 15) {
            case 0, 2, 12 -> x;
            case 1, 3, 14 -> -x;
            case 4, 6 -> x + z;
            case 5, 7 -> -x + z;
            case 8, 9, 13 -> z;
            case 10, 11, 15 -> -z;
            default -> 0.0;
        };
    }

    private static double grad3(int hash, double x, double y, double z) {
        return switch (hash & 15) {
            case 0 -> x + y;
            case 1 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4 -> x + z;
            case 5 -> -x + z;
            case 6 -> x - z;
            case 7 -> -x - z;
            case 8 -> y + z;
            case 9 -> -y + z;
            case 10 -> y - z;
            case 11 -> -y - z;
            case 12 -> y + x;
            case 13 -> -y + z;
            case 14 -> y - x;
            case 15 -> -y - z;
            default -> 0.0;
        };
    }

    /** replaceBody of NoiseGeneratorPerlin.func_805_a: hoist permutations array and inline gradient/lerp math identically. */
    public static void perlinNoiseFast(NoiseGeneratorPerlin n, double[] out, double xOff, double yOff, double zOff,
                                       int sx, int sy, int sz, double fx, double fy, double fz, double amp) {
        int[] perm = n.permutations;
        double xCoord = n.xCoord, yCoord = n.yCoord, zCoord = n.zCoord;
        double invAmp = 1.0 / amp;

        if (sy == 1) {
            int outIdx = 0;
            for (int ix = 0; ix < sx; ix++) {
                double dx = (xOff + ix) * fx + xCoord;
                int floorX = (int) dx;
                if (dx < floorX) floorX--;
                int px = floorX & 255;
                dx -= floorX;
                double u = dx * dx * dx * (dx * (dx * 6.0 - 15.0) + 10.0);

                int a = perm[px];
                int aa = perm[a];
                int b = perm[px + 1];
                int ba = perm[b];

                for (int iz = 0; iz < sz; iz++) {
                    double dz = (zOff + iz) * fz + zCoord;
                    int floorZ = (int) dz;
                    if (dz < floorZ) floorZ--;
                    int pz = floorZ & 255;
                    dz -= floorZ;
                    double w = dz * dz * dz * (dz * (dz * 6.0 - 15.0) + 10.0);

                    int k1 = aa + pz;
                    int k2 = ba + pz;
                    double x1 = grad2(perm[k1], dx, dz);
                    x1 += u * (grad3(perm[k2], dx - 1.0, 0.0, dz) - x1);
                    double x2 = grad3(perm[k1 + 1], dx, 0.0, dz - 1.0);
                    x2 += u * (grad3(perm[k2 + 1], dx - 1.0, 0.0, dz - 1.0) - x2);
                    double res = x1 + w * (x2 - x1);
                    out[outIdx++] += res * invAmp;
                }
            }
            return;
        }

        int outIdx = 0;
        int prevY = -1;
        int k00 = 0, k01 = 0, k10 = 0, k11 = 0;
        double y1 = 0.0, y2 = 0.0, y3 = 0.0, y4 = 0.0;

        for (int ix = 0; ix < sx; ix++) {
            double dx = (xOff + ix) * fx + xCoord;
            int floorX = (int) dx;
            if (dx < floorX) floorX--;
            int px = floorX & 255;
            dx -= floorX;
            double u = dx * dx * dx * (dx * (dx * 6.0 - 15.0) + 10.0);

            for (int iz = 0; iz < sz; iz++) {
                double dz = (zOff + iz) * fz + zCoord;
                int floorZ = (int) dz;
                if (dz < floorZ) floorZ--;
                int pz = floorZ & 255;
                dz -= floorZ;
                double w = dz * dz * dz * (dz * (dz * 6.0 - 15.0) + 10.0);

                for (int iy = 0; iy < sy; iy++) {
                    double dy = (yOff + iy) * fy + yCoord;
                    int floorY = (int) dy;
                    if (dy < floorY) floorY--;
                    int py = floorY & 255;
                    dy -= floorY;
                    double v = dy * dy * dy * (dy * (dy * 6.0 - 15.0) + 10.0);

                    if (iy == 0 || py != prevY) {
                        prevY = py;
                        int a = perm[px] + py;
                        k00 = perm[a] + pz;
                        k01 = perm[a + 1] + pz;
                        int b = perm[px + 1] + py;
                        k10 = perm[b] + pz;
                        k11 = perm[b + 1] + pz;
                        double g0 = grad3(perm[k00], dx, dy, dz);
                        y1 = g0 + u * (grad3(perm[k10], dx - 1.0, dy, dz) - g0);
                        double g1 = grad3(perm[k01], dx, dy - 1.0, dz);
                        y2 = g1 + u * (grad3(perm[k11], dx - 1.0, dy - 1.0, dz) - g1);
                        double g2 = grad3(perm[k00 + 1], dx, dy, dz - 1.0);
                        y3 = g2 + u * (grad3(perm[k10 + 1], dx - 1.0, dy, dz - 1.0) - g2);
                        double g3 = grad3(perm[k01 + 1], dx, dy - 1.0, dz - 1.0);
                        y4 = g3 + u * (grad3(perm[k11 + 1], dx - 1.0, dy - 1.0, dz - 1.0) - g3);
                    }
                    double r1 = y1 + v * (y2 - y1);
                    double r2 = y3 + v * (y4 - y3);
                    out[outIdx++] += (r1 + w * (r2 - r1)) * invAmp;
                }
            }
        }
    }
}
