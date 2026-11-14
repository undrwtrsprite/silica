package dev.betavk.hooks;

import dev.betavk.vox.VoxLod;
import net.minecraft.client.Minecraft;
import net.minecraft.src.EntityLiving;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EntityRenderer;
import net.minecraft.src.Frustrum;
import net.minecraft.src.World;

/** Connects the voxel LOD to the game: drawn after the terrain pass, far plane pushed out, reset when a world is created. */
public final class VoxHooks {
    private VoxHooks() {
    }

    private static int lastFrame = -1;

    /** afterCall of RenderGlobal.sortAndRender in renderWorld: the first call of a frame is the opaque terrain pass. */
    public static void afterTerrain(EntityRenderer er) {
        if (lastFrame == MainLoop.frameNo) return;
        lastFrame = MainLoop.frameNo;
        Minecraft mc = er.mc;
        EntityLiving e = mc.renderViewEntity;
        if (e == null) return;
        float p = mc.timer.renderPartialTicks;
        double x = e.lastTickPosX + (e.posX - e.lastTickPosX) * p, y = e.lastTickPosY + (e.posY - e.lastTickPosY) * p, z = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * p;
        Frustrum f = new Frustrum();
        f.setPosition(x, y, z);
        VoxLod.render(mc, f, x, y, z);
    }

    /** afterFieldSet of EntityRenderer.farPlaneDistance in setupCameraTransform: the far plane and fog reach the LOD distance. */
    public static void farPlane(EntityRenderer er) {
        er.farPlaneDistance = Math.max(er.farPlaneDistance, VoxLod.farPlane(er.mc));
    }

    /** atStart of Minecraft.changeWorld. */
    public static void worldChanged(Minecraft mc, World w, String name, EntityPlayer player) {
        VoxLod.reset(w);
    }
}
