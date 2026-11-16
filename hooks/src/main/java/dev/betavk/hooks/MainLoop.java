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

    /** replaceBody of Minecraft.func_28001_B: the paid-account check talked to a login server that no longer exists. */
    public static void paidCheck(Minecraft mc) {
        Minecraft.hasPaidCheckTime = 0L;
    }

    // ---- per frame -----------------------------------------------------------------------

    /** Suppresses Beta's automatic fullscreen exit on focus loss. GLFW handles the window's focus. */
    public static void focusLostToggle(Minecraft mc) {
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
        TestHarness.frame(mc);
        if (Display.wasResized()) {
            mc.displayWidth = Math.max(1, Display.getWidth());
            mc.displayHeight = Math.max(1, Display.getHeight());
            mc.resize(mc.displayWidth, mc.displayHeight);
        }
    }

    // ---- input ---------------------------------------------------------------------------

    /** redirect of Keyboard.next() in runTick: F6 toggles the FPS counter. */
    public static boolean nextKey() {
        boolean r = Keyboard.next();
        if (r && Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_F6) {
            Opts.showFps = !Opts.showFps;
            Settings.save();
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
}
