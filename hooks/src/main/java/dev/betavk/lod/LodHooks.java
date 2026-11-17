package dev.betavk.lod;

import net.minecraft.src.EntityLiving;
import net.minecraft.src.RenderGlobal;
import net.minecraft.src.World;

/** Bytecode hook targets connecting LodEngine to EntityRenderer, World, and block edits. */
public final class LodHooks {
    private LodHooks() {
    }

    /** redirectCall of RenderGlobal.sortAndRender inside EntityRenderer.renderWorld. */
    public static int sortAndRender(RenderGlobal rg, EntityLiving ent, int pass, double pt) {
        int r = rg.sortAndRender(ent, pass, pt);
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
            LodEngine.renderPass1(rg.mc, rg.mc.renderViewEntity, (float) pt);
        }
    }

    /** Track player/world block updates cleanly when real WorldRenderer rebuilds or world saves. */
    public static void onBlockMarked(RenderGlobal rg, int x, int y, int z) {
        World w = rg.mc != null ? rg.mc.theWorld : null;
        if (w != null && dev.betavk.hooks.Opts.lodChunks > 0) {
            LodEngine.onChunkEdited(w, w.getChunkFromChunkCoords(x >> 4, z >> 4));
        }
    }
}
