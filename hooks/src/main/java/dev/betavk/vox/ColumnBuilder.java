package dev.betavk.vox;

import net.minecraft.src.Block;
import net.minecraft.src.World;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds level-0 voxel columns (32 x 32 blocks, 128 high: four sections) from the game's generator: terrain and caves of 2x2 chunks, the trees the
 * population step will plant (replayed), snow and ice as the population step lays them, and per-voxel light. One per worker thread.
 */
final class ColumnBuilder {
    private final ChunkGen gen;
    private final LinkedHashMap<Long, ColumnData> cache = new LinkedHashMap<>(256, 0.75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, ColumnData> e) {
            return size() > 400;
        }
    };

    ColumnBuilder(World world, long seed) {
        gen = new ChunkGen(world, seed);
    }

    private ColumnData base(int cx, int cz) {
        long k = (long) cx << 32 ^ (cz & 0xFFFFFFFFL);
        ColumnData d = cache.get(k);
        if (d == null) {
            d = gen.generate(cx, cz);
            cache.put(k, d);
        }
        return d;
    }

    /** Creates the four sections of level-0 column (sx, sz) in the store and marks it present. */
    void buildL0(VoxelStore store, int sx, int sz) {
        int cx0 = sx * 2, cz0 = sz * 2;
        ColumnData[] work = new ColumnData[4];
        for (int i = 0; i < 4; i++) {
            ColumnData b = base(cx0 + (i & 1), cz0 + (i >> 1));
            ColumnData w = new ColumnData(b.cx, b.cz, b.blocks.clone(), b.meta.clone());
            System.arraycopy(b.temp, 0, w.temp, 0, 256);
            System.arraycopy(b.hum, 0, w.hum, 0, 256);
            w.corner = b.corner;
            work[i] = w;
        }
        TreePlanter.Sink sink = (wx, y, wz, id, meta) -> {
            int cx = wx >> 4, cz = wz >> 4;
            int ci = (cx - cx0) + 2 * (cz - cz0);
            if (ci < 0 || ci > 3 || (cx - cx0) < 0 || (cx - cx0) > 1 || (cz - cz0) < 0 || (cz - cz0) > 1) return;
            ColumnData w = work[ci];
            int idx = (wx & 15) << 11 | (wz & 15) << 7 | y;
            int cur = w.blocks[idx] & 255;
            if (cur == 0 || cur == 18) {
                w.blocks[idx] = (byte) id;
                w.meta[idx] = (byte) meta;
            }
        };
        for (int pz = cz0 - 1; pz <= cz0 + 1; pz++) {
            for (int px = cx0 - 1; px <= cx0 + 1; px++) {
                ColumnData[] s4 = {base(px, pz), base(px + 1, pz), base(px, pz + 1), base(px + 1, pz + 1)};
                TreePlanter.plant(gen.provider, px, pz, s4, sink);
            }
        }
        for (ColumnData w : work) coverWithSnow(w);
        for (int sy = 0; sy < 4; sy++) {
            VoxelSection sec = store.getOrCreate(0, sx, sy, sz);
            for (int ci = 0; ci < 4; ci++) {
                ColumnData w = work[ci];
                int ox = (ci & 1) * 16, oz = (ci >> 1) * 16;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int col = lx * 16 + lz, base = lx << 11 | lz << 7;
                        int tint = Vox.tintOf(w.temp[col], w.hum[col]);
                        int level = 15;
                        // light above this section's top
                        for (int y = 127; y >= sy * 32; y--) {
                            int id = w.blocks[base + y] & 255;
                            int op = Block.lightOpacity[id];
                            if (op >= 255) level = 0;
                            else level = Math.max(0, level - op);
                            if (y < sy * 32 + 32) {
                                int emit = Block.lightValue[id];
                                sec.set(ox + lx, y - sy * 32, oz + lz, Vox.make(id, w.meta[base + y], level, emit, tint));
                            }
                        }
                    }
                }
            }
            sec.dirty = true;
        }
        store.markColumnPresent(0, sx, sz);
    }

    /** The population step's snow and ice: where it is cold enough, a snow layer on top of solid ground and ice over the water surface. */
    private static void coverWithSnow(ColumnData w) {
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int col = lx * 16 + lz, base = lx << 11 | lz << 7;
                int y = 127;
                while (y > 0 && (w.blocks[base + y] == 0 || Block.lightOpacity[w.blocks[base + y] & 255] == 0 && w.blocks[base + y] != 0)) y--;
                int top = y + 1;
                if (top >= 127) continue;
                double adj = w.temp[col] - ((top - 64) / 64.0) * 0.3;
                if (adj >= 0.5) continue;
                int id = w.blocks[base + y] & 255;
                if (id == 9 || id == 8) {
                    w.blocks[base + y] = 79;   // ice over the water surface
                } else if (id != 0 && id != 79 && id != 10 && id != 11 && w.blocks[base + top] == 0 && Block.opaqueCubeLookup[id]) {
                    w.blocks[base + top] = 78;   // snow layer
                }
            }
        }
    }
}
