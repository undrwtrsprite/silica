package dev.betavk.hooks.world;

import net.minecraft.client.Minecraft;
import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkCoordIntPair;
import net.minecraft.src.ChunkProvider;

/**
 * Chunk unloader. Beta 1.7.3 single player never unloads a chunk (nothing ever puts one in the provider's drop set), so memory grows by about 70 KB for every
 * chunk the player has ever seen. Every half second this marks the loaded chunks that are far outside the view as dropped; the game's own per-tick
 * unload (100 at a time) then saves them (asynchronously, see RegionWriter) and frees them, and anything that asks for one again loads it from disk.
 * <p>
 * Off by default (vanilla keeps every visited chunk loaded); -Dbetavk.unload=true turns it on, -Dbetavk.unload.margin=N sets the distance in chunks beyond the view range (default 10).
 */
public final class Unloader {
    private Unloader() {
    }

    public static final boolean ENABLED = "true".equals(System.getProperty("betavk.unload", "false"));
    private static final int MARGIN = Integer.getInteger("betavk.unload.margin", 10);
    public static long unloaded, passes, passNanos, passMaxNanos;
    public static int lastRadius, lastLoaded;
    private static int ticks;

    private static final int PER_TICK = Integer.getInteger("betavk.unload.pertick", 5);
    private static final int TICK_SCAN = 4000;
    private static net.minecraft.src.NextTickListEntry cursor;

    /** Called every game tick (from WorldHooks.tick). Saving a chunk costs a fraction of a millisecond on the game thread, so only a few go per tick (the game's own
     *  unload takes them from the drop set, 100 at a time, and would otherwise do a whole backlog in one frame); the scan for far chunks is a cheap loop. */
    public static void tick(Minecraft mc) {
        if (!ENABLED) return;
        ChunkProvider cp = Populate.active;
        if (cp == null || mc.theWorld == null || mc.thePlayer == null || !Populate.isFor(cp, mc.theWorld)) return;
        long t0 = System.nanoTime();
        int view = dev.betavk.hooks.Opts.effectiveChunks(mc.gameSettings) + 1;
        int radius = view + MARGIN;
        int pcx = (int) Math.floor(mc.thePlayer.posX) >> 4, pcz = (int) Math.floor(mc.thePlayer.posZ) >> 4;
        passes++;
        lastRadius = radius;
        lastLoaded = cp.chunkList.size();
        int marked = 0;
        if (cp.droppedChunksSet.isEmpty()) {
            for (int i = 0; i < cp.chunkList.size() && marked < PER_TICK; i++) {
                Chunk c = (Chunk) cp.chunkList.get(i);
                if (Math.abs(c.xPosition - pcx) > radius || Math.abs(c.zPosition - pcz) > radius) {
                    cp.droppedChunksSet.add(ChunkCoordIntPair.chunkXZ2Int(c.xPosition, c.zPosition));
                    marked++;
                }
            }
        }
        unloaded += marked;
        purgeTicks(mc.theWorld, pcx, pcz, radius);
        long dt = System.nanoTime() - t0;
        passNanos += dt;
        passMaxNanos = Math.max(passMaxNanos, dt);
    }

    public static String stats() {
        return String.format("unloader: %d ticks, %d chunks dropped, %d scheduled ticks purged, %.3f ms per tick avg, %.2f ms max", passes, unloaded, ticksPurged, passes == 0 ? 0 : passNanos / 1e6 / passes, passMaxNanos / 1e6);
    }

    public static long ticksPurged;

    /** Scheduled block updates (water and lava spreading, ...) for places that are no longer loaded: the game drops such an entry unticked when it comes due,
     *  but only handles 1000 a tick, and chunk generation schedules far more than that while flying, so they pile up (a million entries after a few minutes).
     *  A slice of the queue is checked each tick, resuming where the last one stopped. */
    private static void purgeTicks(net.minecraft.src.World w, int pcx, int pcz, int radius) {
        var tree = w.scheduledTickTreeSet;
        if (tree.size() < 2000) {
            cursor = null;
            return;
        }
        var it = (cursor == null ? tree : tree.tailSet(cursor, false)).iterator();
        int n = 0;
        net.minecraft.src.NextTickListEntry last = null;
        while (it.hasNext() && n++ < TICK_SCAN) {
            net.minecraft.src.NextTickListEntry e = (net.minecraft.src.NextTickListEntry) it.next();
            last = e;
            if (Math.abs((e.xCoord >> 4) - pcx) > radius || Math.abs((e.zCoord >> 4) - pcz) > radius) {
                it.remove();
                w.scheduledTickSet.remove(e);
                ticksPurged++;
            }
        }
        cursor = it.hasNext() ? last : null;   // at the end: start over from the front next time
    }
}
