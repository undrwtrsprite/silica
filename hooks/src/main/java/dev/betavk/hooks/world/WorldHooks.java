package dev.betavk.hooks.world;

import net.minecraft.client.Minecraft;
import net.minecraft.src.ChunkProvider;
import net.minecraft.src.World;

/** Hooks into Minecraft for the world code: tick-time population and prefetch, the spawn area before the first frame. */
public final class WorldHooks {
    private WorldHooks() {
    }

    private static final long POPULATE_BUDGET_NS = Long.getLong("betavk.populatebudgetus", 3000L) * 1000L;

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
