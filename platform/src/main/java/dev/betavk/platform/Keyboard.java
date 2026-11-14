package dev.betavk.platform;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/**
 * LWJGL 2 style polled/evented keyboard on top of GLFW. Key codes are LWJGL 2's (DirectInput scancodes) so that
 * keybindings saved in options.txt by the original game keep working. All methods run on the main thread.
 */
public final class Keyboard {
    public static final int KEY_ESCAPE = 1;
    public static final int KEY_1 = 2;
    public static final int KEY_S = 31;
    public static final int KEY_LSHIFT = 42;
    public static final int KEY_RSHIFT = 54;
    public static final int KEY_F1 = 59, KEY_F2 = 60, KEY_F3 = 61, KEY_F4 = 62, KEY_F5 = 63, KEY_F6 = 64, KEY_F7 = 65,
            KEY_F8 = 66, KEY_F9 = 67, KEY_F10 = 68, KEY_F11 = 87, KEY_F12 = 88;

    private record Event(int key, boolean down, char ch) {
    }

    private static final Map<Integer, Integer> GLFW_TO_LWJGL2 = new HashMap<>();
    private static final String[] NAMES = new String[256];
    private static final boolean[] down = new boolean[256];
    private static final ArrayDeque<Event> queue = new ArrayDeque<>();
    private static Event current = new Event(0, false, '\0');
    private static boolean repeatEvents;

    private static void map(int glfw, int code, String name) {
        GLFW_TO_LWJGL2.put(glfw, code);
        NAMES[code] = name;
    }

    static {
        map(GLFW_KEY_ESCAPE, 1, "ESCAPE");
        for (int i = 0; i < 9; i++) map(GLFW_KEY_1 + i, 2 + i, String.valueOf(i + 1));
        map(GLFW_KEY_0, 11, "0");
        map(GLFW_KEY_MINUS, 12, "MINUS");
        map(GLFW_KEY_EQUAL, 13, "EQUALS");
        map(GLFW_KEY_BACKSPACE, 14, "BACK");
        map(GLFW_KEY_TAB, 15, "TAB");
        String row1 = "QWERTYUIOP";
        for (int i = 0; i < row1.length(); i++) map(GLFW_KEY_Q + (row1.charAt(i) - 'Q'), 16 + i, String.valueOf(row1.charAt(i)));
        map(GLFW_KEY_LEFT_BRACKET, 26, "LBRACKET");
        map(GLFW_KEY_RIGHT_BRACKET, 27, "RBRACKET");
        map(GLFW_KEY_ENTER, 28, "RETURN");
        map(GLFW_KEY_LEFT_CONTROL, 29, "LCONTROL");
        String row2 = "ASDFGHJKL";
        for (int i = 0; i < row2.length(); i++) map(GLFW_KEY_A + (row2.charAt(i) - 'A'), 30 + i, String.valueOf(row2.charAt(i)));
        map(GLFW_KEY_SEMICOLON, 39, "SEMICOLON");
        map(GLFW_KEY_APOSTROPHE, 40, "APOSTROPHE");
        map(GLFW_KEY_GRAVE_ACCENT, 41, "GRAVE");
        map(GLFW_KEY_LEFT_SHIFT, 42, "LSHIFT");
        map(GLFW_KEY_BACKSLASH, 43, "BACKSLASH");
        String row3 = "ZXCVBNM";
        for (int i = 0; i < row3.length(); i++) map(GLFW_KEY_A + (row3.charAt(i) - 'A'), 44 + i, String.valueOf(row3.charAt(i)));
        map(GLFW_KEY_COMMA, 51, "COMMA");
        map(GLFW_KEY_PERIOD, 52, "PERIOD");
        map(GLFW_KEY_SLASH, 53, "SLASH");
        map(GLFW_KEY_RIGHT_SHIFT, 54, "RSHIFT");
        map(GLFW_KEY_KP_MULTIPLY, 55, "MULTIPLY");
        map(GLFW_KEY_LEFT_ALT, 56, "LMENU");
        map(GLFW_KEY_SPACE, 57, "SPACE");
        map(GLFW_KEY_CAPS_LOCK, 58, "CAPITAL");
        for (int i = 0; i < 10; i++) map(GLFW_KEY_F1 + i, 59 + i, "F" + (i + 1));
        map(GLFW_KEY_NUM_LOCK, 69, "NUMLOCK");
        map(GLFW_KEY_SCROLL_LOCK, 70, "SCROLL");
        map(GLFW_KEY_KP_7, 71, "NUMPAD7");
        map(GLFW_KEY_KP_8, 72, "NUMPAD8");
        map(GLFW_KEY_KP_9, 73, "NUMPAD9");
        map(GLFW_KEY_KP_SUBTRACT, 74, "SUBTRACT");
        map(GLFW_KEY_KP_4, 75, "NUMPAD4");
        map(GLFW_KEY_KP_5, 76, "NUMPAD5");
        map(GLFW_KEY_KP_6, 77, "NUMPAD6");
        map(GLFW_KEY_KP_ADD, 78, "ADD");
        map(GLFW_KEY_KP_1, 79, "NUMPAD1");
        map(GLFW_KEY_KP_2, 80, "NUMPAD2");
        map(GLFW_KEY_KP_3, 81, "NUMPAD3");
        map(GLFW_KEY_KP_0, 82, "NUMPAD0");
        map(GLFW_KEY_KP_DECIMAL, 83, "DECIMAL");
        map(GLFW_KEY_F11, 87, "F11");
        map(GLFW_KEY_F12, 88, "F12");
        map(GLFW_KEY_KP_ENTER, 156, "NUMPADENTER");
        map(GLFW_KEY_RIGHT_CONTROL, 157, "RCONTROL");
        map(GLFW_KEY_KP_DIVIDE, 181, "DIVIDE");
        map(GLFW_KEY_RIGHT_ALT, 184, "RMENU");
        map(GLFW_KEY_HOME, 199, "HOME");
        map(GLFW_KEY_UP, 200, "UP");
        map(GLFW_KEY_PAGE_UP, 201, "PRIOR");
        map(GLFW_KEY_LEFT, 203, "LEFT");
        map(GLFW_KEY_RIGHT, 205, "RIGHT");
        map(GLFW_KEY_END, 207, "END");
        map(GLFW_KEY_DOWN, 208, "DOWN");
        map(GLFW_KEY_PAGE_DOWN, 209, "NEXT");
        map(GLFW_KEY_INSERT, 210, "INSERT");
        map(GLFW_KEY_DELETE, 211, "DELETE");
        map(GLFW_KEY_LEFT_SUPER, 219, "LMETA");
        map(GLFW_KEY_RIGHT_SUPER, 220, "RMETA");
    }

    private Keyboard() {
    }

    public static void create() {
    }

    public static void destroy() {
    }

    public static void enableRepeatEvents(boolean enable) {
        repeatEvents = enable;
    }

    public static boolean isKeyDown(int key) {
        return key >= 0 && key < down.length && down[key];
    }

    public static String getKeyName(int key) {
        return key >= 0 && key < NAMES.length && NAMES[key] != null ? NAMES[key] : "NONE";
    }

    /** Advance to the next queued event. */
    public static boolean next() {
        Event e = queue.poll();
        if (e == null) return false;
        current = e;
        return true;
    }

    public static int getEventKey() {
        return current.key();
    }

    public static boolean getEventKeyState() {
        return current.down();
    }

    public static char getEventCharacter() {
        return current.ch();
    }

    // ---- fed by Display's GLFW callbacks -------------------------------------------------

    static void onKey(int glfwKey, int action) {
        Integer code = GLFW_TO_LWJGL2.get(glfwKey);
        if (code == null) return;
        if (action == GLFW_REPEAT) {
            if (repeatEvents) queue.add(new Event(code, true, '\0'));
            return;
        }
        boolean pressed = action == GLFW_PRESS;
        down[code] = pressed;
        queue.add(new Event(code, pressed, '\0'));
    }

    /** GLFW delivers the character after the key press; attach it to the most recent press event without one. */
    static void onChar(int codepoint) {
        if (codepoint > 0xFFFF) return;
        Event last = null;
        for (Event e : queue) last = e;
        if (last != null && last.down() && last.ch() == '\0') {
            queue.removeLast();
            queue.add(new Event(last.key(), true, (char) codepoint));
        }
    }

    static void clear() {
        java.util.Arrays.fill(down, false);
        queue.clear();
    }
}
