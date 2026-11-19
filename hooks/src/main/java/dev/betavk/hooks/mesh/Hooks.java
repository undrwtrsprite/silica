package dev.betavk.hooks.mesh;

import dev.betavk.hooks.Opts;
import net.minecraft.src.Block;
import net.minecraft.src.BlockGrass;
import net.minecraft.src.BlockLeavesBase;
import net.minecraft.src.BlockTallGrass;
import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkCache;
import net.minecraft.src.IBlockAccess;
import net.minecraft.src.RenderBlocks;
import net.minecraft.src.Tessellator;
import net.minecraft.src.WorldChunkManager;

/** Small hooks that make code shared with the game thread safe (and faster) during chunk meshing. */
public final class Hooks {
    private Hooks() {
    }

    private static final boolean[] SPECIAL_LIGHT = new boolean[256];
    static {
        SPECIAL_LIGHT[44] = true; // stairSingle
        SPECIAL_LIGHT[60] = true; // tilledField
        SPECIAL_LIGHT[53] = true; // stairCompactPlanks (Alpha 1.1.x+)
        SPECIAL_LIGHT[67] = true; // stairCompactCobblestone (Alpha 1.1.x+)
    }

    private static MeshWorker worker() {
        return Thread.currentThread() instanceof MeshWorker w ? w : null;
    }

    /** replaceBody of ChunkCache.isBlockOpaqueCube: single bounds + chunk-array lookup avoiding virtual Block.isOpaqueCube. */
    public static boolean isOpaque(ChunkCache c, int x, int y, int z) {
        if (y < 0 || y >= 128) return false;
        int cx = (x >> 4) - c.chunkX, cz = (z >> 4) - c.chunkZ;
        Chunk[][] arr = c.chunkArray;
        if (cx < 0 || cx >= arr.length) return false;
        Chunk[] col = arr[cx];
        if (cz < 0 || cz >= col.length) return false;
        Chunk ch = col[cz];
        if (ch == null) return false;
        int id = ch.blocks[((x & 15) << 11) | ((z & 15) << 7) | y] & 255;
        return id == 18 ? !((BlockLeavesBase) Block.leaves).graphicsLevel : Block.opaqueCubeLookup[id];
    }

    private static int lightLevel(ChunkCache c, int x, int y, int z) {
        if (x < -32000000 || z < -32000000 || x >= 32000000 || z > 32000000) return 15;
        if (y < 0) return 0;
        int sub = c.worldObj.skylightSubtracted;
        if (y >= 128) {
            int l = 15 - sub;
            return l < 0 ? 0 : l;
        }
        int cx = (x >> 4) - c.chunkX, cz = (z >> 4) - c.chunkZ;
        Chunk[][] arr = c.chunkArray;
        if (cx < 0 || cx >= arr.length) return 0;
        Chunk[] col = arr[cx];
        if (cz < 0 || cz >= col.length) return 0;
        Chunk ch = col[cz];
        if (ch == null) return 0;
        int idx = ((x & 15) << 11) | ((z & 15) << 7) | y;
        int id = ch.blocks[idx] & 255;
        // Slabs, farmland, and stairs check light from the highest open neighbor instead of their own cell.
        if (SPECIAL_LIGHT[id]) {
            int lv = c.getLightValueExt(x, y, z, true);
            return lv < 0 ? 0 : Math.min(lv, 15);
        }
        int shift = (idx & 1) << 2;
        int bi = idx >>> 1;
        int sky = (ch.skylightMap.data[bi] >>> shift) & 15;
        if (sky > 0) {
            MeshWorker w = worker();
            if (w != null) w.lit = true;
            else Chunk.isLit = true;
        }
        int light = sky - sub;
        int blk = (ch.blocklightMap.data[bi] >>> shift) & 15;
        int lv = Math.max(light, blk);
        return lv < 0 ? 0 : Math.min(lv, 15);
    }

    /** replaceBody of ChunkCache.getBrightness(IIII)F: combines block check + sky/block nibble reads in one lookup. */
    public static float getBrightness(ChunkCache c, int x, int y, int z, int minLight) {
        int lv = lightLevel(c, x, y, z);
        if (lv < minLight) lv = minLight;
        return dev.betavk.hooks.McCompat.lightBrightnessTable(c.worldObj)[lv];
    }

    /** replaceBody of ChunkCache.getLightBrightness(III)F. */
    public static float getLightBrightness(ChunkCache c, int x, int y, int z) {
        return dev.betavk.hooks.McCompat.lightBrightnessTable(c.worldObj)[lightLevel(c, x, y, z)];
    }

    /** atStartCancelable of ChunkCache.getBlockTileEntity: tile-entity maps belong to the game thread; mesh jobs record positions instead. */
    public static boolean noTileEntities(ChunkCache c, int x, int y, int z) {
        return worker() != null;
    }

    /** atStartReturnIfNotNull of ChunkCache.getWorldChunkManager: the game thread's manager reuses scratch arrays. */
    public static WorldChunkManager chunkManager(ChunkCache c) {
        MeshWorker w = worker();
        return w == null ? null : w.chunkManager(c.worldObj);
    }

    /** redirectStaticWrite of Chunk.isLit in getBlockLightValue: a lit block seen while rendering goes to the worker's own flag. */
    public static void markLit(boolean v) {
        MeshWorker w = worker();
        if (w != null) w.lit = v;
        else net.minecraft.src.Chunk.isLit = v;
    }

    /** redirectStaticRead of Block.tallGrass / Block.grass in the renderer: compared with the Block it was given, which is a worker's private copy. */
    public static BlockTallGrass tallGrass() {
        MeshWorker w = worker();
        return w == null ? Block.tallGrass : (BlockTallGrass) w.blocks[Block.tallGrass.blockID];
    }

    private static int cachedBlockId(ChunkCache c, int x, int y, int z) {
        if (y < 0 || y >= 128) return 0;
        int cx = (x >> 4) - c.chunkX, cz = (z >> 4) - c.chunkZ;
        Chunk[][] arr = c.chunkArray;
        if (cx < 0 || cx >= arr.length) return -1;
        Chunk[] col = arr[cx];
        if (cz < 0 || cz >= col.length) return -1;
        Chunk ch = col[cz];
        return ch == null ? 0 : ch.blocks[((x & 15) << 11) | ((z & 15) << 7) | y] & 255;
    }

    /**
     * replaceBody of BlockLeavesBase.shouldSideBeRendered: direct ChunkCache lookup without virtual dispatch,
     * preserving all internal leaf faces in Fancy graphics (only culling leaf-to-leaf in Fast mode).
     */
    public static boolean shouldLeafSideBeRendered(BlockLeavesBase self, IBlockAccess access, int x, int y, int z, int side) {
        if (access instanceof ChunkCache cc) {
            int id = cachedBlockId(cc, x, y, z);
            if (id == self.blockID) return self.graphicsLevel;
            return id <= 0 || (id == 18 ? !Block.leaves.graphicsLevel : !Block.opaqueCubeLookup[id]);
        }
        int id = access.getBlockId(x, y, z);
        if (!self.graphicsLevel && id == self.blockID) return false;
        return !access.isBlockOpaqueCube(x, y, z);
    }

    public static BlockGrass grass() {
        MeshWorker w = worker();
        return w == null ? Block.grass : (BlockGrass) w.blocks[Block.grass.blockID];
    }

    public static boolean betterEastFace(RenderBlocks rb, Block b, double x, double y, double z, int tex) {
        return betterGrassFace(rb, b, x, y, z, tex, 2);
    }

    public static boolean betterWestFace(RenderBlocks rb, Block b, double x, double y, double z, int tex) {
        return betterGrassFace(rb, b, x, y, z, tex, 3);
    }

    public static boolean betterNorthFace(RenderBlocks rb, Block b, double x, double y, double z, int tex) {
        return betterGrassFace(rb, b, x, y, z, tex, 4);
    }

    public static boolean betterSouthFace(RenderBlocks rb, Block b, double x, double y, double z, int tex) {
        return betterGrassFace(rb, b, x, y, z, tex, 5);
    }

    private static int fastBlockId(IBlockAccess access, int x, int y, int z) {
        if (access instanceof ChunkCache cc) {
            int id = cachedBlockId(cc, x, y, z);
            if (id >= 0) return id;
        }
        return access.getBlockId(x, y, z);
    }

    private static void drawSideFace(RenderBlocks rb, int side, Block b, double x, double y, double z, int tex) {
        switch (side) {
            case 2 -> rb.renderEastFace(b, x, y, z, tex);
            case 3 -> rb.renderWestFace(b, x, y, z, tex);
            case 4 -> rb.renderNorthFace(b, x, y, z, tex);
            default -> rb.renderSouthFace(b, x, y, z, tex);
        }
    }

    private static final boolean HAS_FANCY_GRASS = dev.betavk.hooks.McCompat.hasField(RenderBlocks.class, "fancyGrass");
    private static final boolean HAS_AO = dev.betavk.hooks.McCompat.hasField(RenderBlocks.class, "enableAO");

    private static final class FancyGrassFlag {
        static boolean isOn() { return RenderBlocks.fancyGrass; }
    }

    private static final class AoGrass {
        static boolean isEnabled(RenderBlocks rb) { return rb.enableAO; }
        static void draw(RenderBlocks rb, Block b, double x, double y, double z, int side, float cr, float cg, float cb) {
            float rTL = rb.colorRedTopLeft, rBL = rb.colorRedBottomLeft, rBR = rb.colorRedBottomRight, rTR = rb.colorRedTopRight;
            float gTL = rb.colorGreenTopLeft, gBL = rb.colorGreenBottomLeft, gBR = rb.colorGreenBottomRight, gTR = rb.colorGreenTopRight;
            float bTL = rb.colorBlueTopLeft, bBL = rb.colorBlueBottomLeft, bBR = rb.colorBlueBottomRight, bTR = rb.colorBlueTopRight;
            rb.colorRedTopLeft *= cr; rb.colorRedBottomLeft *= cr; rb.colorRedBottomRight *= cr; rb.colorRedTopRight *= cr;
            rb.colorGreenTopLeft *= cg; rb.colorGreenBottomLeft *= cg; rb.colorGreenBottomRight *= cg; rb.colorGreenTopRight *= cg;
            rb.colorBlueTopLeft *= cb; rb.colorBlueBottomLeft *= cb; rb.colorBlueBottomRight *= cb; rb.colorBlueTopRight *= cb;
            drawSideFace(rb, side, b, x, y, z, 0);
            rb.colorRedTopLeft = rTL; rb.colorRedBottomLeft = rBL; rb.colorRedBottomRight = rBR; rb.colorRedTopRight = rTR;
            rb.colorGreenTopLeft = gTL; rb.colorGreenBottomLeft = gBL; rb.colorGreenBottomRight = gBR; rb.colorGreenTopRight = gTR;
            rb.colorBlueTopLeft = bTL; rb.colorBlueBottomLeft = bBL; rb.colorBlueBottomRight = bBR; rb.colorBlueTopRight = bTR;
        }
    }

    private static boolean betterGrassFace(RenderBlocks rb, Block b, double x, double y, double z, int tex, int side) {
        int mode = Opts.betterGrass;
        if (mode == 0 || (tex != 3 && tex != 38 && tex != 68) || b.blockID != 2 || rb.overrideBlockTexture >= 0 || rb.blockAccess == null) {
            return false;
        }
        int dx = side == 4 ? -1 : (side == 5 ? 1 : 0);
        int dz = side == 2 ? -1 : (side == 3 ? 1 : 0);
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);

        if (tex == 68) {
            boolean snow = mode == 1;
            if (!snow) {
                int adj = fastBlockId(rb.blockAccess, bx + dx, by, bz + dz);
                snow = fastBlockId(rb.blockAccess, bx + dx, by - 1, bz + dz) == 2 && (adj == 78 || adj == 80);
            }
            if (!snow) return false;
            drawSideFace(rb, side, b, x, y, z, 66);
            return true;
        }

        if (mode != 1 && fastBlockId(rb.blockAccess, bx + dx, by - 1, bz + dz) != 2) {
            return false;
        }
        if (HAS_FANCY_GRASS && FancyGrassFlag.isOn()) {
            if (tex == 3) return true;   // skip dirt face 3; Beta immediately follows with tinted side overlay 38
            drawSideFace(rb, side, b, x, y, z, 0);
            return true;
        }
        if (tex == 38) return false;

        int col = dev.betavk.hooks.McCompat.blockColorMultiplier(b, rb.blockAccess, bx, by, bz);
        float cr = ((col >>> 16) & 255) / 255.0F;
        float cg = ((col >>> 8) & 255) / 255.0F;
        float cb = (col & 255) / 255.0F;
        if (HAS_AO && AoGrass.isEnabled(rb)) {
            AoGrass.draw(rb, b, x, y, z, side, cr, cg, cb);
        } else {
            float shade = side <= 3 ? 0.8F : 0.6F;
            float br = b.getBlockBrightness(rb.blockAccess, bx + dx, by, bz + dz) * shade;
            Tessellator.instance.setColorOpaque_F(cr * br, cg * br, cb * br);
            drawSideFace(rb, side, b, x, y, z, 0);
        }
        return true;
    }
}
