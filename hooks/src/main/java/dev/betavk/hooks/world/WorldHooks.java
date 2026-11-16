package dev.betavk.hooks.world;

import net.minecraft.client.Minecraft;
import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkCoordIntPair;
import net.minecraft.src.ChunkProvider;
import net.minecraft.src.World;

/** Hooks into Minecraft for the world code: tick-time population and prefetch, the spawn area before the first frame. */
public final class WorldHooks {
    private WorldHooks() {
    }

    private static final long POPULATE_BUDGET_NS = Long.getLong("betavk.populatebudgetus", 3000L) * 1000L;

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
    }

    /** afterFieldSet of Minecraft.renderViewEntity inside changeWorld: the spawn area is populated before the first frame. */
    public static void worldReady(Minecraft mc) {
        if (mc.renderViewEntity == null) return;   // this fires for the assignment of null at the start of changeWorld too, with the old world still current
        ChunkProvider cp = Populate.active;
        if (cp != null && mc.theWorld != null && Populate.isFor(cp, mc.theWorld)) Populate.drain(cp, -1);
    }
}
