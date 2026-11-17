package dev.betavk.hooks.mesh;

import net.minecraft.src.Block;
import net.minecraft.src.ChunkCache;
import net.minecraft.src.Tessellator;
import net.minecraft.src.World;
import net.minecraft.src.WorldChunkManager;
import net.minecraft.src.WorldRenderer;

/**
 * A chunk-meshing thread. Everything the meshing code mutates is private to the worker, so no state is shared with the game thread or other workers:
 * its own capturing Tessellator, its own copies of every Block (the renderer rewrites a Block's bounds while rendering), its own WorldChunkManager
 * (biome lookups reuse scratch arrays) and its own "chunk lit" flag.
 */
public final class MeshWorker extends Thread {
    public final CapturingTessellator primaryTess = new CapturingTessellator();
    public final CapturingTessellator cutoutTess = new CapturingTessellator();
    public final CaveCull.VisGraph visGraph = new CaveCull.VisGraph();
    public Tessellator tessellator = primaryTess;
    public final Block[] blocks = new Block[Block.blocksList.length];
    public boolean lit;
    private World cmWorld;
    private WorldChunkManager cm;

    public static final class Job {
        public final WorldRenderer renderer;
        public final ChunkCache cache;
        public final int generation;

        public Job(WorldRenderer renderer, ChunkCache cache, int generation) {
            this.renderer = renderer;
            this.cache = cache;
            this.generation = generation;
        }
    }

    MeshWorker(int index) {
        super("Chunk mesher " + index);
        setDaemon(true);
        setPriority(Integer.getInteger("betavk.meshprio", Thread.MIN_PRIORITY));
        for (int i = 0; i < blocks.length; i++) blocks[i] = Block.blocksList[i] == null ? null : ShadowCopy.of(Block.blocksList[i]);
    }

    public WorldChunkManager chunkManager(World world) {
        if (world != cmWorld) {
            cm = new WorldChunkManager(world);
            cmWorld = world;
        }
        return cm;
    }

    @Override public void run() {
        while (true) {
            Job job;
            try {
                job = MeshPool.jobs.take();
            } catch (InterruptedException e) {
                return;
            }
            long t0 = System.nanoTime();
            MeshResult res;
            try {
                lit = false;
                if (blocks[Block.leaves.blockID] instanceof net.minecraft.src.BlockLeaves bl) {
                    bl.setGraphicsLevel(Block.leaves.graphicsLevel);
                }
                res = AsyncMesh.buildMesh(job.renderer, job.cache, this, job.generation);
            } catch (Throwable t) {
                res = new MeshResult(job.renderer, job.generation);
                res.failure = t;
            }
            res.meshNanos = System.nanoTime() - t0;
            MeshPool.done.add(res);
        }
    }
}
