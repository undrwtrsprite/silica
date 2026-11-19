package dev.betavk.hooks;

import dev.betavk.platform.Keyboard;
import dev.betavk.render.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.src.EntityRenderer;

/**
 * Field of view setting and zoom. The game's own FOV method is left alone except for scaling its result: the setting scales it (70 = unchanged),
 * and holding the zoom key multiplies it by a factor that eases between 1 and the zoom level. The held item and hand keep the plain 70 degree
 * projection whatever the setting, otherwise they stretch and fly across the screen at a wide FOV.
 */
public final class Zoom {
    private Zoom() {
    }

    private static boolean handPass;
    private static float factor = 1.0F, level = 0.3F;
    private static long lastNs;

    /** Test hook: pretends the zoom key is held. */
    public static boolean testHeld;

    private static boolean held() {
        if (testHeld) return true;
        Minecraft mc = McCompat.minecraft;
        return mc != null && mc.currentScreen == null && mc.inGameHasFocus && Controls.zoom != null && Keyboard.isKeyDown(Controls.zoom.keyCode);
    }

    /** atStart of EntityRenderer.updateCameraAndRender. */
    public static void update(EntityRenderer er, float partial) {
        long now = System.nanoTime();
        float dt = lastNs == 0L ? 0.016F : Math.min(0.1F, (now - lastNs) / 1.0E9F);
        lastNs = now;
        float target = held() ? level : 1.0F;
        factor += (target - factor) * Math.min(1.0F, 14.0F * dt);
        if (Math.abs(target - factor) < 0.002F) factor = target;
    }

    /** True when the wheel was used for zooming (so the hotbar must not change). */
    public static boolean scroll(int wheel) {
        if (!held()) return false;
        level *= wheel > 0 ? 0.85F : 1.0F / 0.85F;
        level = Math.max(0.05F, Math.min(0.9F, level));
        return true;
    }

    /** onReturn of EntityRenderer.getFOVModifier. */
    public static float fov(EntityRenderer er, float value) {
        return handPass ? value : value * (Opts.fov / 70.0F) * factor;
    }

    private static final boolean HAS_MOUSE_SENSITIVITY = McCompat.hasField(net.minecraft.src.GameSettings.class, "mouseSensitivity");

    private static final class Sensitivity {
        static float get(EntityRenderer er) { return er.mc.gameSettings.mouseSensitivity; }
    }

    /** replaceFloat of the 8.0 mouse-turn constant in updateCameraAndRender: slower turning while zoomed. */
    public static float turnScale(EntityRenderer er) {
        float s = HAS_MOUSE_SENSITIVITY ? Sensitivity.get(er) : 0.5F;
        return OptionsScreens.turnConstant(s) * Math.max(0.15F, factor);
    }

    /** atStart of EntityRenderer.func_4135_b (the hand pass): when the FOV is not 70 or a zoom is on, the hand gets its own plain projection. */
    public static void hand(EntityRenderer er, float partial, int pass) {
        if (Opts.fov == 70.0F && factor == 1.0F) return;
        handPass = true;
        Gfx.glMatrixMode(Gfx.GL_PROJECTION);
        Gfx.glLoadIdentity();
        if (er.mc.gameSettings.anaglyph) Gfx.glTranslatef((-(pass * 2 - 1)) * 0.07F, 0.0F, 0.0F);
        Gfx.gluPerspective(McCompat.getFovModifier(er, partial), (float) er.mc.displayWidth / (float) er.mc.displayHeight, 0.05F, er.farPlaneDistance * 2.0F);
        Gfx.glMatrixMode(Gfx.GL_MODELVIEW);
        handPass = false;
    }
}
