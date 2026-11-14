package dev.betavk.compat;

/** The game creates LWJGL 2's controller layer at start-up and never uses it; there are no controllers here. */
public final class Controllers {
    private Controllers() {
    }

    public static void create() {
    }

    public static void destroy() {
    }
}
