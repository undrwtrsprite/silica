package dev.betavk.render;

import dev.betavk.platform.Display;
import org.lwjgl.opengl.GL;

/** Wires the OpenGL backend into the window layer (hand-written; the rest of this package is generated). */
public final class GLBackend {
    private GLBackend() {
    }

    /** Call before Display.create(). */
    public static void install() {
        Display.onContextCreated = GL::createCapabilities;
        Display.frameGrabber = (w, h, out) -> {
            Gfx.glPixelStorei(Gfx.GL_PACK_ALIGNMENT, 1);
            Gfx.glReadPixels(0, 0, w, h, Gfx.GL_RGB, Gfx.GL_UNSIGNED_BYTE, out);
        };
    }
}
