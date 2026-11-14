package dev.betavk.hooks.mesh;

import net.minecraft.src.Block;
import net.minecraft.src.BlockGrass;
import net.minecraft.src.BlockTallGrass;
import net.minecraft.src.ChunkCache;
import net.minecraft.src.TileEntity;
import net.minecraft.src.WorldChunkManager;

/** Small hooks that make code shared with the game thread safe on a mesh worker. */
public final class Hooks {
    private Hooks() {
    }

    private static MeshWorker worker() {
        return Thread.currentThread() instanceof MeshWorker w ? w : null;
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

    public static BlockGrass grass() {
        MeshWorker w = worker();
        return w == null ? Block.grass : (BlockGrass) w.blocks[Block.grass.blockID];
    }
}
