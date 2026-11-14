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
        w.worldProvider = real.worldProvider;
        w.seed = seed;
        w.manager = new WorldChunkManager(w);   // reads only the seed through getRandomSeed()
        return w;
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
