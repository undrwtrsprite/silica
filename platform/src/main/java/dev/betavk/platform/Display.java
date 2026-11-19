package dev.betavk.platform;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;

import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * LWJGL 2 style Display on top of GLFW + an OpenGL context (macOS gives the legacy 2.1 compatibility profile, which
 * is what b1.7.3's fixed-function pipeline needs). GLFW on macOS requires every call on the first thread: run the JVM
 * with -XstartOnFirstThread and run the game loop on the main thread.
 * <p>
 * getWidth/getHeight are FRAMEBUFFER pixels (Retina aware); window creation uses screen points.
 */
public final class Display {
    /** Registered by the graphics backend: runs once the window's context is current. */
    /** false = create a GLFW_NO_API window (Vulkan): no GL context, no swap interval, presentation by presentHook. */
    public static boolean openGlContext = true;
    /** Registered by a non-GL backend: replaces glfwSwapBuffers at the end of every frame. */
    public static Runnable presentHook;
    public static Runnable onContextCreated;
    /** Registered by the graphics backend: reads the presented framebuffer as tightly packed RGB, bottom row first. */
    public interface FrameGrabber {
        void readRgb(int width, int height, java.nio.ByteBuffer out);
    }
    public static FrameGrabber frameGrabber;

    private static long window = NULL;
    private static String title = "Minecraft";
    private static DisplayMode pendingMode = new DisplayMode(854, 480);
    private static boolean fullscreen;
    private static int fbWidth, fbHeight, winWidth, winHeight;
    private static int savedX, savedY, savedW, savedH;
    private static boolean resized;
    private static boolean active = true;
    private static volatile int monitorRefreshHz = 60;
    private static GLFWErrorCallback errorCallback;

    private Display() {
    }

    public static long handle() {
        return window;
    }

    public static void setParent(java.awt.Canvas ignored) {
    }

    public static void setParent(Object ignored) {
        // No AWT canvas parent: the game owns a native GLFW window.
    }

    public static void setTitle(String t) {
        title = t;
        if (window != NULL) glfwSetWindowTitle(window, t);
    }

    public static void setDisplayMode(DisplayMode mode) {
        pendingMode = mode;
    }

    public static DisplayMode getDisplayMode() {
        return window != NULL ? new DisplayMode(fbWidth, fbHeight) : pendingMode;
    }

    public static DisplayMode getDesktopDisplayMode() {
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");   // may be called before Display.create(); init is idempotent
        GLFWVidMode vm = glfwGetVideoMode(glfwGetPrimaryMonitor());
        return new DisplayMode(vm.width(), vm.height());
    }

    public static boolean isFullscreen() {
        return fullscreen;
    }

    public static void setFullscreen(boolean fs) {
        fullscreen = fs;
        if (window == NULL) return;
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode vm = glfwGetVideoMode(monitor);
        if (fs) {
            try (MemoryStack st = MemoryStack.stackPush()) {
                IntBuffer x = st.mallocInt(1), y = st.mallocInt(1), w = st.mallocInt(1), h = st.mallocInt(1);
                glfwGetWindowPos(window, x, y);
                glfwGetWindowSize(window, w, h);
                savedX = x.get(0);
                savedY = y.get(0);
                savedW = w.get(0);
                savedH = h.get(0);
            }
            glfwSetWindowMonitor(window, monitor, 0, 0, vm.width(), vm.height(), vm.refreshRate());
        } else {
            int w = savedW > 0 ? savedW : pendingMode.getWidth();
            int h = savedH > 0 ? savedH : pendingMode.getHeight();
            glfwSetWindowMonitor(window, NULL, savedX > 0 ? savedX : 100, savedY > 0 ? savedY : 100, w, h, GLFW_DONT_CARE);
        }
        if (openGlContext) glfwSwapInterval(vsync() ? 1 : 0);
        if (!Boolean.getBoolean("betavk.nograb")) {
            glfwShowWindow(window);
            glfwFocusWindow(window);
            active = true;
        }
        refreshSizes();
        updateRefreshRate();
    }

    private static boolean vsync() {
        return Boolean.getBoolean("betavk.vsync");
    }

    public static void create(dev.betavk.compat.PixelFormat fmt) throws LWJGLException {
        create();
    }

    public static void create() throws LWJGLException {
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        errorCallback.set();
        if (!glfwInit()) throw new LWJGLException("glfwInit failed (JVM must run with -XstartOnFirstThread)");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW_TRUE);
        if (!openGlContext) glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        // No version hints: macOS then returns the legacy (2.1) compatibility context.

        long monitor = fullscreen ? glfwGetPrimaryMonitor() : NULL;
        int w = pendingMode.getWidth(), h = pendingMode.getHeight();
        if (fullscreen) {
            GLFWVidMode vm = glfwGetVideoMode(monitor);
            w = vm.width();
            h = vm.height();
        }
        window = glfwCreateWindow(w, h, title, monitor, NULL);
        if (window == NULL) throw new LWJGLException("glfwCreateWindow failed");

        if (!fullscreen && System.getProperty("betavk.monitor") != null) {   // -Dbetavk.monitor=N: open on that monitor (benchmarks)
            org.lwjgl.PointerBuffer mons = glfwGetMonitors();
            int mi = Integer.getInteger("betavk.monitor");
            if (mons != null && mi >= 0 && mi < mons.remaining()) {
                try (MemoryStack st = MemoryStack.stackPush()) {
                    IntBuffer mx = st.mallocInt(1), my = st.mallocInt(1);
                    glfwGetMonitorPos(mons.get(mi), mx, my);
                    glfwSetWindowPos(window, mx.get(0) + 100, my.get(0) + 100);
                }
            }
        }
        if (openGlContext) glfwMakeContextCurrent(window);
        if (onContextCreated != null) onContextCreated.run();   // the backend loads its GL function pointers here
        if (openGlContext) glfwSwapInterval(vsync() ? 1 : 0);

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> Keyboard.onKey(key, action));
        glfwSetCharCallback(window, (win, cp) -> Keyboard.onChar(cp));
        glfwSetCursorPosCallback(window, (win, cx, cy) -> Mouse.onCursor(cx, cy));
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> Mouse.onButton(button, action));
        glfwSetScrollCallback(window, (win, xo, yo) -> Mouse.onScroll(yo));
        glfwSetFramebufferSizeCallback(window, (win, fw, fh) -> {
            resized = true;
            refreshSizes();
            updateRefreshRate();
        });
        glfwSetWindowPosCallback(window, (win, x, y) -> updateRefreshRate());
        glfwSetWindowFocusCallback(window, (win, focused) -> {
            active = focused;
            if (!focused) {
                Keyboard.clear();
                Mouse.clear();
            }
        });
        refreshSizes();
        updateRefreshRate();
        resized = false;
    }

    /** Refresh rate (Hz) of the monitor that currently holds the window (cached, updated on move/resize/fullscreen). */
    public static int getRefreshRate() {
        return monitorRefreshHz;
    }

    private static void updateRefreshRate() {
        if (window == NULL) return;
        long best = glfwGetWindowMonitor(window);
        if (best == NULL) {
            org.lwjgl.PointerBuffer mons = glfwGetMonitors();
            if (mons != null && mons.remaining() > 0) {
                try (MemoryStack st = MemoryStack.stackPush()) {
                    IntBuffer wx = st.mallocInt(1), wy = st.mallocInt(1), ww = st.mallocInt(1), wh = st.mallocInt(1);
                    IntBuffer mx = st.mallocInt(1), my = st.mallocInt(1);
                    glfwGetWindowPos(window, wx, wy);
                    glfwGetWindowSize(window, ww, wh);
                    int x0 = wx.get(0), y0 = wy.get(0), x1 = x0 + ww.get(0), y1 = y0 + wh.get(0);
                    long maxArea = -1L;
                    for (int i = 0; i < mons.remaining(); i++) {
                        long m = mons.get(i);
                        GLFWVidMode vm = glfwGetVideoMode(m);
                        if (vm == null) continue;
                        glfwGetMonitorPos(m, mx, my);
                        int ox = Math.max(0, Math.min(x1, mx.get(0) + vm.width()) - Math.max(x0, mx.get(0)));
                        int oy = Math.max(0, Math.min(y1, my.get(0) + vm.height()) - Math.max(y0, my.get(0)));
                        long area = (long) ox * oy;
                        if (area > maxArea) {
                            maxArea = area;
                            best = m;
                        }
                    }
                }
            }
            if (best == NULL) best = glfwGetPrimaryMonitor();
        }
        GLFWVidMode vm = best != NULL ? glfwGetVideoMode(best) : null;
        if (vm != null && vm.refreshRate() > 0) monitorRefreshHz = vm.refreshRate();
    }

    private static void refreshSizes() {
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer fw = st.mallocInt(1), fh = st.mallocInt(1), ww = st.mallocInt(1), wh = st.mallocInt(1);
            glfwGetFramebufferSize(window, fw, fh);
            glfwGetWindowSize(window, ww, wh);
            fbWidth = Math.max(1, fw.get(0));
            fbHeight = Math.max(1, fh.get(0));
            winWidth = Math.max(1, ww.get(0));
            winHeight = Math.max(1, wh.get(0));
        }
    }

    public static void destroy() {
        if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }
        glfwTerminate();
        if (errorCallback != null) errorCallback.free();
    }

    /** Present the frame and process window/input events (like LWJGL 2's Display.update). */
    public static void update() {
        captureHook();
        swapBuffers();
        glfwPollEvents();
    }

    // ---- test hooks: -Dbetavk.screenshot=/path/out.png  -Dbetavk.screenshotFrame=300  -Dbetavk.exitFrame=310 ----
    private static int frameCounter;

    /** -Dbetavk.windowtest=true: resize, fullscreen on, fullscreen off; a screenshot after each step, then exit. */
    private static void windowTest() {
        String dir = System.getProperty("betavk.windowtest.dir", ".");
        switch (frameCounter) {
            case 60 -> { glfwSetWindowSize(window, 1100, 650); System.out.println("[wt] resize requested 1100x650"); }
            case 150 -> { System.out.println("[wt] after resize: framebuffer " + fbWidth + "x" + fbHeight); captureNext = dir + "/wt-resized.png"; }
            case 170 -> { setFullscreen(true); System.out.println("[wt] fullscreen on"); }
            case 260 -> { System.out.println("[wt] fullscreen: framebuffer " + fbWidth + "x" + fbHeight); captureNext = dir + "/wt-fullscreen.png"; }
            case 280 -> { setFullscreen(false); System.out.println("[wt] fullscreen off"); }
            case 370 -> { System.out.println("[wt] windowed again: framebuffer " + fbWidth + "x" + fbHeight); captureNext = dir + "/wt-back.png"; }
            case 390 -> glfwSetWindowShouldClose(window, true);
            default -> { }
        }
    }
    private static volatile String captureNext;

    /** Save the next presented frame's framebuffer as a PNG. */
    public static void captureNextFrame(String path) {
        captureNext = path;
    }

    private static void captureHook() {
        frameCounter++;
        if (Boolean.getBoolean("betavk.windowtest")) windowTest();
        String path = System.getProperty("betavk.screenshot");
        boolean byFrame = path != null && frameCounter == Integer.getInteger("betavk.screenshotFrame", -1);
        if (captureNext != null) {
            path = captureNext;
            captureNext = null;
            byFrame = true;
        }
        if (byFrame) {
            try {
                java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(fbWidth * fbHeight * 3);
                if (frameGrabber == null) throw new IllegalStateException("no frame grabber registered by the backend");
                frameGrabber.readRgb(fbWidth, fbHeight, buf);
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(fbWidth, fbHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < fbHeight; y++) {
                    for (int x = 0; x < fbWidth; x++) {
                        int i = ((fbHeight - 1 - y) * fbWidth + x) * 3;
                        img.setRGB(x, y, ((buf.get(i) & 0xFF) << 16) | ((buf.get(i + 1) & 0xFF) << 8) | (buf.get(i + 2) & 0xFF));
                    }
                }
                javax.imageio.ImageIO.write(img, "png", new java.io.File(path));
                System.out.println("[betavk] screenshot " + fbWidth + "x" + fbHeight + " -> " + path);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (frameCounter == Integer.getInteger("betavk.exitFrame", -1)) {
            System.out.println("[betavk] exitFrame reached, closing");
            glfwSetWindowShouldClose(window, true);
        }
    }

    public static void swapBuffers() {
        if (presentHook != null) presentHook.run();
        else glfwSwapBuffers(window);
    }

    private static final boolean ALWAYS_ACTIVE = Boolean.getBoolean("betavk.alwaysactive");   // tests: the game sleeps and opens its menu when the window loses focus

    public static boolean isActive() {
        if (ALWAYS_ACTIVE) return true;
        return window != NULL && (active || glfwGetWindowAttrib(window, GLFW_FOCUSED) != 0);
    }

    public static boolean isCloseRequested() {
        return glfwWindowShouldClose(window);
    }

    public static int getWidth() {
        return fbWidth;
    }

    public static int getHeight() {
        return fbHeight;
    }

    /** Framebuffer pixels per window point (2.0 on Retina). */
    static double scaleX() {
        return winWidth == 0 ? 1.0 : (double) fbWidth / winWidth;
    }

    static double scaleY() {
        return winHeight == 0 ? 1.0 : (double) fbHeight / winHeight;
    }

    /** True once after the framebuffer size changed. */
    public static boolean wasResized() {
        boolean r = resized;
        resized = false;
        return r;
    }
}
