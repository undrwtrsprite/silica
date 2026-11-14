package dev.betavk.compat;

import dev.betavk.render.Gfx;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The handful of LWJGL 2 GL entry points whose shape differs from the plain GL 1.1 names that Gfx uses (LWJGL 2 had shorter names and typed
 * pointer overloads). The class preparation step sends exactly these calls here; everything else goes straight to Gfx.
 */
public final class Lwjgl2Gl {
    private static final int GL_UNSIGNED_BYTE = 0x1401, GL_BYTE = 0x1400, GL_FLOAT = 0x1406;

    private Lwjgl2Gl() {
    }

    public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer pointer) {
        Gfx.glColorPointer(size, unsigned ? GL_UNSIGNED_BYTE : GL_BYTE, stride, pointer);
    }

    public static void glNormalPointer(int stride, ByteBuffer pointer) {
        Gfx.glNormalPointer(GL_BYTE, stride, pointer);
    }

    public static void glTexCoordPointer(int size, int stride, FloatBuffer pointer) {
        Gfx.glTexCoordPointer(size, GL_FLOAT, stride, pointer);
    }

    public static void glVertexPointer(int size, int stride, FloatBuffer pointer) {
        Gfx.glVertexPointer(size, GL_FLOAT, stride, pointer);
    }

    public static void glFog(int pname, FloatBuffer params) {
        Gfx.glFogfv(pname, params);
    }

    public static void glLight(int light, int pname, FloatBuffer params) {
        Gfx.glLightfv(light, pname, params);
    }

    public static void glLightModel(int pname, FloatBuffer params) {
        Gfx.glLightModelfv(pname, params);
    }

    public static void glGetFloat(int pname, FloatBuffer params) {
        Gfx.glGetFloatv(pname, params);
    }

    public static void glGetQueryObjectuARB(int id, int pname, IntBuffer params) {
        Gfx.glGetQueryObjectuiv(id, pname, params);
    }
}
