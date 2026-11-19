package dev.betavk.compat;

import java.nio.IntBuffer;

/** LWJGL 2's native cursor object. Grabbing is done by dev.betavk.platform.Mouse, so this only has to exist. */
public final class Cursor {
    public Cursor(int width, int height, int xHot, int yHot, int numImages, IntBuffer images, IntBuffer delays) {
    }
}
