package dev.betavk.hooks.world;

import net.minecraft.client.Minecraft;
import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkCoordIntPair;
import net.minecraft.src.ChunkProvider;
import net.minecraft.src.MetadataChunkBlock;
import net.minecraft.src.World;

import java.util.List;

/** Hooks into Minecraft for the world code: tick-time population, budgeted lighting, prefetch, the spawn area before the first frame. */
public final class WorldHooks {
    private WorldHooks() {
    }

    private static final long POPULATE_BUDGET_NS = Long.getLong("betavk.populatebudgetus", 2000L) * 1000L;
    private static final long LIGHTING_BUDGET_NS = Long.getLong("betavk.lightingbudgetus", 800L) * 1000L;

    /** replaceBody of World.updatingLighting: preserve 500-item / re-entrancy rules but cap per-frame lighting bursts to a time budget. */
    @SuppressWarnings("unchecked")
    public static boolean updatingLighting(World w) {
        if (w.lightingUpdatesCounter >= 50) return false;
        w.lightingUpdatesCounter++;
        try {
            List queue = w.lightingToUpdate;
            long deadline = LIGHTING_BUDGET_NS > 0L ? System.nanoTime() + LIGHTING_BUDGET_NS : Long.MAX_VALUE;
            int count = 500;
            int done = 0;
            int sz;
            while ((sz = queue.size()) > 0) {
                if (--count <= 0) return true;
                if (done >= 16 && (done & 15) == 0 && System.nanoTime() >= deadline) return true;
                ((MetadataChunkBlock) queue.remove(sz - 1)).func_4127_a(w);
                done++;
            }
            return false;
        } finally {
            w.lightingUpdatesCounter--;
        }
    }

    /** atStartReturnIfNotNull of ChunkProvider.provideChunk: avoid boxing and Map dispatch on hits. */
    @SuppressWarnings("unchecked")
    public static Chunk provideChunkFast(ChunkProvider cp, int x, int z) {
        if (cp.chunkMap instanceof IntKeyMap m) {
            return ((IntKeyMap<Chunk>) m).getInt(ChunkCoordIntPair.chunkXZ2Int(x, z));
        }
        return null;
    }

    /** replaceBody of ChunkProvider.chunkExists: avoid boxing when backed by IntKeyMap. */
    public static boolean chunkExistsFast(ChunkProvider cp, int x, int z) {
        int key = ChunkCoordIntPair.chunkXZ2Int(x, z);
        if (cp.chunkMap instanceof IntKeyMap<?> m) return m.getInt(key) != null;
        return cp.chunkMap.containsKey(key);
    }

    /** atStart of Minecraft.runTick. */
    public static void tick(Minecraft mc) {
        GenPool.onTick(mc, POPULATE_BUDGET_NS);
        Unloader.tick(mc);
        dev.betavk.hooks.Worlds.tick(mc);
    }

    /** atStart of Minecraft.changeWorld2 (a new world): start generating the spawn area. */
    public static void newWorld(Minecraft mc, World w, String name) {
        GenPool.prefetchSpawn(w);
        dev.betavk.lod.LodEngine.reset(w);
    }

    /** afterFieldSet of Minecraft.renderViewEntity inside changeWorld: the spawn area is populated before the first frame. */
    public static void worldReady(Minecraft mc) {
        if (mc.renderViewEntity == null) return;   // this fires for the assignment of null at the start of changeWorld too, with the old world still current
        ChunkProvider cp = Populate.active;
        if (cp != null && mc.theWorld != null && Populate.isFor(cp, mc.theWorld)) Populate.drain(cp, -1);
    }
}
