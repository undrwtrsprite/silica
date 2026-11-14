package dev.betavk.hooks.world;

import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkCoordIntPair;
import net.minecraft.src.ChunkProvider;
import net.minecraft.src.IChunkProvider;
import net.minecraft.src.World;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.WeakHashMap;

/**
 * Chunk population (trees, ores, lakes...) is deferred to a queue drained under a per-tick time budget, in the same order the immediate version ran, so the
 * world is the same. Chunks loaded from inside a population are populated at once, as in the original nested order.
 */
public final class Populate {
    private Populate() {
    }

    public static final boolean DEFER = !"0".equals(System.getProperty("betavk.deferpopulate", "1"));
    private static final class State {
        final LinkedHashSet<Long> queue = new LinkedHashSet<>();
        boolean populating;
    }

    private static final WeakHashMap<ChunkProvider, State> states = new WeakHashMap<>();
    public static ChunkProvider active;

    private static State state(ChunkProvider cp) {
        return states.computeIfAbsent(cp, k -> new State());
    }

    /** redirectCall of ChunkProvider.populate inside prepareChunk. */
    public static void request(ChunkProvider cp, IChunkProvider p, int x, int z) {
        State st = state(cp);
        if (!DEFER || st.populating) {
            cp.populate(p, x, z);
        } else {
            st.queue.add(((long) x << 32) | ((long) z & 0xFFFFFFFFL));
            active = cp;
        }
    }

    private static boolean populatable(ChunkProvider cp, int x, int z) {
        Chunk c = (Chunk) cp.chunkMap.get(ChunkCoordIntPair.chunkXZ2Int(x, z));
        return c != null && !c.isTerrainPopulated && cp.chunkExists(x + 1, z + 1) && cp.chunkExists(x, z + 1) && cp.chunkExists(x + 1, z);
    }

    /** Game thread. Populates queued chunks until budgetNanos is spent (always at least one); budgetNanos &lt; 0 drains everything. */
    public static int drain(ChunkProvider cp, long budgetNanos) {
        State st = state(cp);
        long end = System.nanoTime() + budgetNanos;
        int n = 0;
        Iterator<Long> it = st.queue.iterator();
        while (it.hasNext()) {
            long k = it.next();
            it.remove();
            int x = (int) (k >> 32), z = (int) k;
            if (populatable(cp, x, z)) {
                st.populating = true;
                try {
                    cp.populate(cp, x, z);
                } finally {
                    st.populating = false;
                }
                n++;
                if (budgetNanos >= 0 && System.nanoTime() >= end) break;
            }
            it = st.queue.iterator();   // populating may have queued more
        }
        return n;
    }

    public static boolean isFor(ChunkProvider cp, World w) {
        return cp.field_28066_g == w;
    }

    public static int pending(ChunkProvider cp) {
        return state(cp).queue.size();
    }
}
