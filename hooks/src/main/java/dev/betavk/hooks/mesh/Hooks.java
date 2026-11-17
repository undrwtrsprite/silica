package dev.betavk.hooks.mesh;

import net.minecraft.src.Block;
import net.minecraft.src.BlockGrass;
import net.minecraft.src.BlockLeavesBase;
import net.minecraft.src.BlockTallGrass;
import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkCache;
import net.minecraft.src.IBlockAccess;
import net.minecraft.src.WorldChunkManager;

/** Small hooks that make code shared with the game thread safe (and faster) during chunk meshing. */
public final class Hooks {
    private Hooks() {
    }

    private static final boolean[] SPECIAL_LIGHT = new boolean[256];
    static {
        SPECIAL_LIGHT[Block.stairSingle.blockID] = true;
        SPECIAL_LIGHT[Block.tilledField.blockID] = true;
        SPECIAL_LIGHT[Block.stairCompactPlanks.blockID] = true;
        SPECIAL_LIGHT[Block.stairCompactCobblestone.blockID] = true;
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
        return c.worldObj.worldProvider.lightBrightnessTable[lv];
    }

    /** replaceBody of ChunkCache.getLightBrightness(III)F. */
    public static float getLightBrightness(ChunkCache c, int x, int y, int z) {
        return c.worldObj.worldProvider.lightBrightnessTable[lightLevel(c, x, y, z)];
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
}
