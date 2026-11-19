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
    private static final boolean HAS_LIGHTING_COUNTER = dev.betavk.hooks.McCompat.hasField(World.class, "lightingUpdatesCounter");
    private static final boolean HAS_LIGHTING_TO_UPDATE = dev.betavk.hooks.McCompat.hasField(World.class, "lightingToUpdate");
    private static int legacyLightingCounter;

    /** replaceBody of World.updatingLighting: preserve 500-item / re-entrancy rules but cap per-frame lighting bursts to a time budget. */
    @SuppressWarnings("unchecked")
    public static boolean updatingLighting(World w) {
        if (!HAS_LIGHTING_TO_UPDATE) return false;
        if (HAS_LIGHTING_COUNTER) {
            if (w.lightingUpdatesCounter >= 50) return false;
            w.lightingUpdatesCounter++;
        } else {
            if (legacyLightingCounter >= 50) return false;
            legacyLightingCounter++;
        }
        try {
            List queue = w.lightingToUpdate;
            boolean inGame = dev.betavk.hooks.McCompat.minecraft == null || dev.betavk.hooks.McCompat.minecraft.thePlayer != null;
            long deadline = (inGame && LIGHTING_BUDGET_NS > 0L) ? System.nanoTime() + LIGHTING_BUDGET_NS : Long.MAX_VALUE;
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
            if (HAS_LIGHTING_COUNTER) w.lightingUpdatesCounter--;
            else legacyLightingCounter--;
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

    private static final java.util.Map<net.minecraft.src.ChunkProviderLoadOrGenerate, IntKeyMap<Chunk>> loadOrGenCaches = new java.util.WeakHashMap<>();

    private static IntKeyMap<Chunk> loadOrGenCache(net.minecraft.src.ChunkProviderLoadOrGenerate cp) {
        return loadOrGenCaches.computeIfAbsent(cp, k -> new IntKeyMap<>());
    }

    /** atStartReturnIfNotNull of ChunkProviderLoadOrGenerate.provideChunk: unbounded O(1) cache avoiding 1024-slot GZIP thrashing in Alpha/early Beta. */
    public static Chunk provideLoadOrGenFast(net.minecraft.src.ChunkProviderLoadOrGenerate cp, int x, int z) {
        return loadOrGenCache(cp).getInt((x & 0xFFFF) | ((z & 0xFFFF) << 16));
    }

    /** onReturn of ChunkProviderLoadOrGenerate.provideChunk: remembers newly loaded/generated chunks in memory. */
    public static Chunk cacheLoadOrGenChunk(net.minecraft.src.ChunkProviderLoadOrGenerate cp, Chunk c) {
        if (c != null && c != cp.blankChunk) {
            loadOrGenCache(cp).put((c.xPosition & 0xFFFF) | ((c.zPosition & 0xFFFF) << 16), c);
        }
        return c;
    }

    /** redirectCall of Chunk.onChunkUnload inside ChunkProviderLoadOrGenerate.provideChunk: chunks stay live in loadOrGenCaches even when their 32x32 ring slot aliases, so never unload the player's or active chunks' entities/tile entities. */
    public static void keepLoadOrGenChunk(Chunk c) {
    }

    /** redirectCall of saveChunk inside ChunkProviderLoadOrGenerate.provideChunk: only save an evicted ring-slot chunk if it actually has unsaved changes. */
    public static void saveLoadOrGenSlotChunk(net.minecraft.src.ChunkProviderLoadOrGenerate cp, Chunk c) {
        if (c != null && c != cp.blankChunk && c.needsSaving(false)) {
            cp.saveChunk(c);
        }
    }

    /** redirectCall of saveExtraChunkData inside ChunkProviderLoadOrGenerate.provideChunk: only save extra data for modified chunks. */
    public static void saveLoadOrGenSlotExtra(net.minecraft.src.ChunkProviderLoadOrGenerate cp, Chunk c) {
        if (c != null && c != cp.blankChunk && c.needsSaving(false)) {
            if (!c.neverSave) cp.saveExtraChunkData(c);
            c.isModified = false;
        }
    }

    /** replaceBody of ChunkProviderLoadOrGenerate.chunkExists: check unbounded cache before the 1024-slot ring that aliases beyond +/-15 chunks. */
    public static boolean loadOrGenExistsFast(net.minecraft.src.ChunkProviderLoadOrGenerate cp, int x, int z) {
        if (x == cp.lastQueriedChunkXPos && z == cp.lastQueriedChunkZPos && cp.lastQueriedChunk != null) return true;
        if (loadOrGenCache(cp).getInt((x & 0xFFFF) | ((z & 0xFFFF) << 16)) != null) return true;
        Chunk slot = cp.chunks[(x & 31) + (z & 31) * 32];
        return slot != null && (slot == cp.blankChunk || slot.isAtLocation(x, z));
    }

    /** replaceBody of ChunkProviderLoadOrGenerate.canChunkExist / func_21111_d: unlock wide render distances past 15 chunks while still bounding cascade population. */
    public static boolean allowChunk(net.minecraft.src.ChunkProviderLoadOrGenerate cp, int x, int z) {
        Minecraft mc = dev.betavk.hooks.McCompat.minecraft;
        int pcx, pcz, r;
        if (mc != null && mc.thePlayer != null && mc.theWorld == cp.worldObj) {
            pcx = ((int) Math.floor(mc.thePlayer.posX)) >> 4;
            pcz = ((int) Math.floor(mc.thePlayer.posZ)) >> 4;
            r = Math.max(15, dev.betavk.hooks.Opts.effectiveChunks(mc.gameSettings) + 4);
        } else {
            pcx = dev.betavk.hooks.McCompat.spawnX(cp.worldObj) >> 4;
            pcz = dev.betavk.hooks.McCompat.spawnZ(cp.worldObj) >> 4;
            r = 15;
        }
        return Math.abs(x - pcx) <= r && Math.abs(z - pcz) <= r;
    }

    /** atStart of ChunkProviderLoadOrGenerate.saveChunks: save modified cached chunks evicted from the 1024-slot ring. */
    public static void flushLoadOrGenDirty(net.minecraft.src.ChunkProviderLoadOrGenerate cp, boolean fullSave, net.minecraft.src.IProgressUpdate prog) {
        if (!fullSave) return;
        IntKeyMap<Chunk> cache = loadOrGenCaches.get(cp);
        if (cache == null) return;
        cache.forEachValue(c -> {
            if (c == cp.blankChunk) return;
            if (cp.chunks[(c.xPosition & 31) + (c.zPosition & 31) * 32] == c) return;
            if (!c.neverSave) cp.saveExtraChunkData(c);
            if (c.needsSaving(true)) {
                cp.saveChunk(c);
                c.isModified = false;
            }
        });
    }

    private static final boolean HAS_CHUNK_PROVIDER = dev.betavk.hooks.McCompat.hasClass("net/minecraft/src/ChunkProvider");

    /** atStart of Minecraft.runTick. */
    public static void tick(Minecraft mc) {
        GenPool.onTick(mc, POPULATE_BUDGET_NS);
        if (HAS_CHUNK_PROVIDER) Unloader.tick(mc);
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
