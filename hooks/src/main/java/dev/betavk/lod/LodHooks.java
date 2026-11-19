package dev.betavk.lod;

import net.minecraft.src.EntityLiving;
import net.minecraft.src.RenderGlobal;
import net.minecraft.src.World;

/** Bytecode hook targets connecting LodEngine to EntityRenderer, World, and block edits. */
public final class LodHooks {
    private LodHooks() {
    }

    private static java.lang.invoke.MethodHandle playerSortAndRender;

    /** Alpha/early-Beta overload where RenderGlobal.sortAndRender takes EntityPlayer. */
    public static int sortAndRenderPlayer(RenderGlobal rg, net.minecraft.src.EntityPlayer ent, int pass, double pt) {
        int r;
        try {
            java.lang.invoke.MethodHandle mh = playerSortAndRender;
            if (mh == null) {
                mh = java.lang.invoke.MethodHandles.publicLookup().findVirtual(
                        RenderGlobal.class, "sortAndRender",
                        java.lang.invoke.MethodType.methodType(int.class, net.minecraft.src.EntityPlayer.class, int.class, double.class));
                playerSortAndRender = mh;
            }
            r = (int) mh.invoke(rg, ent, pass, pt);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return finishSortAndRender(rg, ent, pass, pt, r);
    }

    /** redirectCall of RenderGlobal.sortAndRender inside EntityRenderer.renderWorld. */
    public static int sortAndRender(RenderGlobal rg, EntityLiving ent, int pass, double pt) {
        int r = rg.sortAndRender(ent, pass, pt);
        return finishSortAndRender(rg, ent, pass, pt, r);
    }

    private static int finishSortAndRender(RenderGlobal rg, EntityLiving ent, int pass, double pt, int r) {
        if (pass == 0) {
            LodEngine.renderPass0(rg.mc, ent, (float) pt);
            return r;
        }
        if (pass == 1) {
            if (!rg.mc.gameSettings.fancyGraphics) {
                LodEngine.renderPass1(rg.mc, ent, (float) pt);
                return r;
            }
            // Fancy graphics calls sortAndRender(pass=1) with colorMask(false) for depth pre-pass,
            // then only calls renderAllRenderLists(1, pt) with colorMask(true) if r > 0.
            LodEngine.renderPass1(rg.mc, ent, (float) pt);
            return r > 0 ? r : (LodEngine.hasPass1() ? 1 : 0);
        }
        return r;
    }

    /** redirectCall of RenderGlobal.renderAllRenderLists inside EntityRenderer.renderWorld (fancy pass 1 color). */
    public static void renderAllRenderLists(RenderGlobal rg, int pass, double pt) {
        rg.renderAllRenderLists(pass, pt);
        if (pass == 1) {
            LodEngine.renderPass1(rg.mc, dev.betavk.hooks.McCompat.renderViewEntity(rg.mc), (float) pt);
        }
    }

    /** Track player/world block edits cleanly once a chunk is populated (ignoring unpopulated chunkgen/lighting storms). */
    public static void onBlockMarked(RenderGlobal rg, int x, int y, int z) {
        World w = rg.mc != null ? rg.mc.theWorld : null;
        if (w != null && dev.betavk.hooks.Opts.lodChunks > 0) {
            net.minecraft.src.Chunk c = w.getChunkFromChunkCoords(x >> 4, z >> 4);
            if (c != null && c.isTerrainPopulated) {
                LodEngine.onBlockEdited(w, c);
            }
        }
    }
}
