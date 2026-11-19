package dev.betavk.platform;

import java.util.ArrayDeque;

import static org.lwjgl.glfw.GLFW.*;

/**
 * LWJGL 2 style mouse on top of GLFW. Coordinates are framebuffer pixels with the origin at the BOTTOM-left,
 * as in LWJGL 2. All methods run on the main thread.
 */
public final class Mouse {
    private record Event(int button, boolean down, int dwheel, int x, int y) {
    }

    private static final boolean[] buttons = new boolean[8];
    private static final ArrayDeque<Event> queue = new ArrayDeque<>();
    private static Event current = new Event(-1, false, 0, 0, 0);
    private static int x, y;
    private static int dx, dy;
    private static double lastCx, lastCy;
    private static boolean haveLast;
    private static boolean grabbed;

    private Mouse() {
    }

    public static void create() {
    }

    public static void destroy() {
    }

    public static dev.betavk.compat.Cursor setNativeCursor(dev.betavk.compat.Cursor c) {
        return null;
    }

    public static boolean next() {
        Event e = queue.poll();
        if (e == null) return false;
        current = e;
        return true;
    }

    public static int getEventButton() {
        return current.button();
    }

    public static boolean getEventButtonState() {
        return current.down();
    }

    public static int getEventDWheel() {
        return current.dwheel();
    }

    public static int getEventX() {
        return current.x();
    }

    public static int getEventY() {
        return current.y();
    }

    public static int getX() {
        return x;
    }

    public static int getY() {
        return y;
    }

    public static boolean isButtonDown(int button) {
        return button >= 0 && button < buttons.length && buttons[button];
    }

    /** Movement since the last call, in pixels; dy is positive upwards. */
    public static int getDX() {
        int v = dx;
        dx = 0;
        return v;
    }

    public static int getDY() {
        int v = dy;
        dy = 0;
        return v;
    }

    /** After the cursor is captured, movement is ignored for this long: anything still in flight from before the capture must not turn the camera. */
    private static final long QUIET_NS = 80_000_000L;
    private static long quietUntil;

    public static void setGrabbed(boolean grab) {
        boolean was = grabbed;
        grabbed = grab;
        boolean real = !Boolean.getBoolean("betavk.nograb");   // unattended runs must not capture or move the user's cursor; the bookkeeping below still applies
        if (real) glfwSetInputMode(Display.handle(), GLFW_CURSOR, grab ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        haveLast = false;
        dx = 0;
        dy = 0;
        if (grab) {
            // Closing a screen: the camera must start from rest. Moving the mouse in the same instant used to reach the game as one large jump.
            quietUntil = System.nanoTime() + QUIET_NS;
        } else if (was) {
            // Opening a screen: GLFW puts the cursor back where it was when it was captured (often off to one side); it belongs in the middle.
            if (real) glfwSetCursorPos(Display.handle(), Display.getWidth() / Display.scaleX() / 2.0, Display.getHeight() / Display.scaleY() / 2.0);
            x = Display.getWidth() / 2;
            y = Display.getHeight() / 2;
        }
    }

    public static boolean isGrabbed() {
        return grabbed;
    }

    /** Position in framebuffer pixels, origin bottom-left. */
    public static void setCursorPosition(int px, int py) {
        double sx = Display.scaleX(), sy = Display.scaleY();
        glfwSetCursorPos(Display.handle(), px / sx, (Display.getHeight() - py) / sy);
        haveLast = false;
    }

    // ---- fed by Display's GLFW callbacks -------------------------------------------------

    static void onCursor(double cx, double cy) {
        if (haveLast && !(grabbed && System.nanoTime() < quietUntil)) {
            dx += (int) Math.round((cx - lastCx) * Display.scaleX());
            dy -= (int) Math.round((cy - lastCy) * Display.scaleY());
        }
        lastCx = cx;
        lastCy = cy;
        haveLast = true;
        x = (int) Math.round(cx * Display.scaleX());
        y = Display.getHeight() - 1 - (int) Math.round(cy * Display.scaleY());
        if (!grabbed) queue.add(new Event(-1, false, 0, x, y));
    }

    static void onButton(int glfwButton, int action) {
        // GLFW: 0 left, 1 right, 2 middle: the same order LWJGL 2 uses.
        if (glfwButton < 0 || glfwButton >= buttons.length) return;
        boolean pressed = action == GLFW_PRESS;
        buttons[glfwButton] = pressed;
        queue.add(new Event(glfwButton, pressed, 0, x, y));
    }

    static void onScroll(double yoffset) {
        int notches = (int) Math.round(yoffset * 120.0);   // LWJGL 2 reports 120 per wheel notch
        if (notches != 0) queue.add(new Event(-1, false, notches, x, y));
    }

    static void clear() {
        java.util.Arrays.fill(buttons, false);
        queue.clear();
    }
}
