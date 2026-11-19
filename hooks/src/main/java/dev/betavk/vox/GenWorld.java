package dev.betavk.vox;

import net.minecraft.src.ISaveHandler;
import net.minecraft.src.WorldChunkManager;
import net.minecraft.src.World;

/**
 * A World that is only enough for terrain generation on a worker thread: it hands out a private WorldChunkManager (the real one keeps shared
 * scratch arrays, so it cannot be used from two threads) and the seed. Built without running World's constructor.
 */
public final class GenWorld extends World {
    private WorldChunkManager manager;
    private long seed;

    private GenWorld() {
        super((ISaveHandler) null, null, 0L);   // never run: instances come from Sandbox.allocate
    }

    public static GenWorld create(World real, long seed) {
        GenWorld w = Sandbox.allocate(GenWorld.class);
        if (dev.betavk.hooks.McCompat.HAS_WORLD_PROVIDER) ProviderCopy.copy(w, real);
        w.seed = seed;
        if (!dev.betavk.hooks.McCompat.HAS_WORLD_INFO) LegacySeed.set(w, seed);
        if (dev.betavk.hooks.McCompat.HAS_WORLD_CHUNK_MANAGER) w.manager = ManagerInit.create(w);
        return w;
    }

    private static final class ProviderCopy {
        static void copy(GenWorld dst, World src) { dst.worldProvider = src.worldProvider; }
    }

    private static final class LegacySeed {
        private static final java.lang.reflect.Field SEED_FIELD;
        static {
            java.lang.reflect.Field f = null;
            try { f = World.class.getField("randomSeed"); f.setAccessible(true); } catch (Throwable ignored) {}
            SEED_FIELD = f;
        }
        static void set(GenWorld w, long seed) {
            if (SEED_FIELD != null) try { SEED_FIELD.setLong(w, seed); } catch (Throwable ignored) {}
        }
    }

    private static final class ManagerInit {
        static WorldChunkManager create(GenWorld w) { return new WorldChunkManager(w); }
    }

    public WorldChunkManager manager() {
        return manager;
    }

    @Override public WorldChunkManager getWorldChunkManager() {
        return manager;
    }

    @Override public long getRandomSeed() {
        return seed;
    }
}
