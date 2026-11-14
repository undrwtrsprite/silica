package dev.betavk.compat;

import dev.betavk.render.Gfx;

/** LWJGL 2's GLContext, answered by the active graphics backend. */
public final class GLContext {
    private static ContextCapabilities caps;

    private GLContext() {
    }

    public static synchronized ContextCapabilities getCapabilities() {
        if (caps == null) {
            ContextCapabilities c = new ContextCapabilities();
            c.GL_ARB_occlusion_query = Gfx.supportsOcclusionQueries();
            c.GL_NV_fog_distance = Gfx.supportsFogDistanceNV();
            c.GL_ARB_vertex_buffer_object = Gfx.supportsVertexBufferObjects();
            caps = c;
        }
        return caps;
    }
}
