package dev.betavk.hooks;

import dev.betavk.platform.Display;
import dev.betavk.platform.DisplayMode;
import dev.betavk.platform.Keyboard;
import dev.betavk.platform.Mouse;
import dev.betavk.render.Backends;
import dev.betavk.render.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.src.MouseHelper;
import net.minecraft.src.Tessellator;

import java.io.File;

/** Hooks into Minecraft's start-up and main loop: graphics backend, window size, frame limiter, resize, game directory, key and wheel events. */
public final class MainLoop {
    private MainLoop() {
    }

    // ---- start-up ------------------------------------------------------------------------

    /** atStart of Minecraft.startGame: the graphics backend must exist before the window is created. */
    public static void beforeStart(Minecraft mc) {
        McCompat.minecraft = mc;
        Backends.install();
    }

    private static boolean backendSet;

    /** afterCall of Display.create: the backend is created against the window, and the game takes the real (pixel) size. */
    public static void afterDisplayCreate(Minecraft mc) {
        if (!backendSet) {
            backendSet = true;
            Gfx.set(Backends.create());
            if (Backends.vulkan() && !"false".equalsIgnoreCase(System.getProperty("betavk.keepQuads", "true"))) {
                Tessellator.convertQuadsToTriangles = false;
            }
        }
        mc.displayWidth = Display.getWidth();
        mc.displayHeight = Display.getHeight();
    }

    /** redirect of Display.getDisplayMode(): full screen always uses the desktop's mode. */
    public static DisplayMode displayMode() {
        return Display.getDesktopDisplayMode();
    }

    /** atStartReturnIfNotNull of Minecraft.getAppDir: -Dbetavk.gamedir keeps the game's data out of the real ~/Library folder. */
    public static File appDir(String name) {
        String override = System.getProperty("betavk.gamedir");
        if (override == null) return null;
        File f = new File(override);
        f.mkdirs();
        return f;
    }

    /** afterFieldSet of RenderGlobal.renderChunksDeep inside loadRenderers: expand chunk grid for custom chunk distances. */
    public static void applyLoadRenderersSize(net.minecraft.src.RenderGlobal rg) {
        if (Opts.renderChunks >= 2) {
            int diam = Opts.renderChunks * 2 + 1;
            rg.renderChunksWide = diam;
            rg.renderChunksDeep = diam;
        }
    }

    /** afterFieldSet of EntityRenderer.farPlaneDistance inside setupCameraTransform: match far clip & fog to the chunk + LOD sliders. */
    public static void applyFarPlane(net.minecraft.src.EntityRenderer er) {
        float realFar = Opts.effectiveFarPlane(er.mc.gameSettings);
        er.farPlaneDistance = dev.betavk.lod.LodEngine.effectiveFarPlane(er.mc, realFar);
    }

    /** atEnd of EntityRenderer.setupFog(int, float): ease linear fog back when wide render distances or LODs are active. */
    public static void applyFog(net.minecraft.src.EntityRenderer er, int fogMode, float partialTicks) {
        applyFog1(er, fogMode);
    }

    /** atEnd of EntityRenderer.setupFog(int) / func_4140_a(int): Alpha/early-Beta 1-arg fog adjustment. */
    public static void applyFog1(net.minecraft.src.EntityRenderer er, int fogMode) {
        if (!McCompat.isOpenAirFog(er.mc, er, fogMode)) return;
        float far = er.farPlaneDistance;
        if (dev.betavk.lod.LodEngine.isActive(er.mc)) {
            float realFar = Opts.effectiveFarPlane(er.mc.gameSettings);
            dev.betavk.render.Gfx.glFogf(0xB63, Math.max(realFar * 0.65F, far * 0.72F));
            dev.betavk.render.Gfx.glFogf(0xB64, far * 1.02F);
        } else {
            int chunks = Opts.effectiveChunks(er.mc.gameSettings);
            if (chunks > 12) {
                float startRatio = Math.min(0.65F, 0.25F + (chunks - 12) * 0.025F);
                dev.betavk.render.Gfx.glFogf(0xB63, far * startRatio);
                dev.betavk.render.Gfx.glFogf(0xB64, far);
            }
        }
    }

    /** afterFieldSet / replaceBody of Minecraft.func_28001_B: the paid-account check talked to a login server that no longer exists. */
    public static void paidCheck(Minecraft mc) {
        Minecraft.hasPaidCheckTime = 0L;
    }

    // ---- per frame -----------------------------------------------------------------------

    /** Suppresses Beta's automatic fullscreen exit on focus loss. GLFW handles the window's focus. */
    public static void focusLostToggle(Minecraft mc) {
    }

    /** Removes pre-b1.6 Thread.sleep(1000) inside toggleFullscreen. */
    public static void noopSleep(long ms) {
    }

    /** Pre-b1.6 toggleFullscreen called setIngameFocus() unconditionally; avoid locking the cursor while a menu is open. */
    public static void fullscreenFocus(Minecraft mc) {
        if (mc.currentScreen == null) mc.setIngameFocus();
    }

    private static long nextFrameNs;

    /**
     * afterCall of Thread.yield in run(): the FPS lock. Sleeps (then spins for the last millisecond) until this frame's slot ends. A frame that
     * overruns its slot is not paid back: the schedule restarts from now, so a slow frame is never followed by a burst of very short ones.
     */
    public static void limitFps(Minecraft mc) {
        int cap = Opts.maxFps;
        if (cap <= 0) {
            nextFrameNs = 0L;
            return;
        }
        long interval = 1_000_000_000L / cap;
        long now = System.nanoTime();
        long target = nextFrameNs + interval;
        if (nextFrameNs == 0L || target < now) target = now;
        nextFrameNs = target;
        long remaining;
        while ((remaining = target - System.nanoTime()) > 0L) {
            if (remaining > 1_500_000L) java.util.concurrent.locks.LockSupport.parkNanos(remaining - 1_000_000L);
            else Thread.onSpinWait();
        }
    }

    /** afterCall of screenshotListener in run(): the window was resized (the original compared an AWT canvas, which we do not have). */
    public static int frameNo;

    public static void afterFrame(Minecraft mc) {
        frameNo++;
        PickBlock.poll(mc);
        if (Bench.ENABLED) Bench.frame(mc);
        if ("boot".equals(System.getProperty("betavk.test"))) McCompat.bootFrame(mc);
        else if ("compat".equals(System.getProperty("betavk.test"))) McCompat.compatFrame(mc);
        else if ("diag".equals(System.getProperty("betavk.test"))) McCompat.diagFrame(mc);
        else if ("walk".equals(System.getProperty("betavk.test"))) WalkTest.frame(mc);
        else if (System.getProperty("betavk.test") != null) TestHarness.frame(mc);
        if (Display.wasResized()) {
            mc.displayWidth = Math.max(1, Display.getWidth());
            mc.displayHeight = Math.max(1, Display.getHeight());
            mc.resize(mc.displayWidth, mc.displayHeight);
        }
    }

    // ---- input ---------------------------------------------------------------------------

    /** redirect of Keyboard.next() in runTick: F6 toggles the FPS counter and b1.8+ tracks toggle-sneak edges. */
    public static boolean nextKey() {
        boolean r = Keyboard.next();
        if (r) {
            int key = Keyboard.getEventKey();
            boolean down = Keyboard.getEventKeyState();
            if (down && key == Keyboard.KEY_F6) {
                Opts.showFps = !Opts.showFps;
                Settings.save();
            }
            Keys.modernToggleSneak(McCompat.minecraft, key, down);
        }
        return r;
    }

    /** redirect of Mouse.getEventDWheel() in runTick: the wheel changes the zoom while the zoom key is held, otherwise the hotbar. */
    public static int wheel() {
        int d = Mouse.getEventDWheel();
        return d != 0 && Zoom.scroll(d) ? 0 : d;
    }

    /** replaceBody of MouseHelper.ungrabMouseCursor (the original centred the cursor in an AWT canvas): releasing the cursor also puts it in the middle of the window. */
    public static void ungrab(MouseHelper helper) {
        Mouse.setGrabbed(false);
    }

    /** replaceBody of legacy Infdev AWT MouseHelper.grabMouseCursor: avoid AWT MouseInfo/Robot in headless macOS mode. */
    public static void grab(MouseHelper helper) {
        Mouse.setGrabbed(true);
        Mouse.getDX();
        Mouse.getDY();
    }

    /** replaceBody of legacy Infdev AWT MouseHelper.mouseXYChange. */
    public static void mouseDelta(MouseHelper helper) {
        helper.deltaX = Mouse.getDX();
        helper.deltaY = Mouse.getDY();
    }
}
