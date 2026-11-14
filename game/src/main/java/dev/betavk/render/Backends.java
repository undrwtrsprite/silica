package dev.betavk.render;

import dev.betavk.render.vulkan.VulkanRenderer;

/** Selects the graphics backend: -Dbetavk.backend=gl (default) or vulkan. */
public final class Backends {
    private Backends() {
    }

    public static boolean vulkan() {
        return "vulkan".equalsIgnoreCase(System.getProperty("betavk.backend", "gl"));
    }

    /** Before Display.create(). */
    public static void install() {
        if (vulkan()) VulkanRenderer.install();
        else GLBackend.install();
    }

    /** After Display.create(). */
    public static Renderer create() {
        Renderer r = vulkan() ? new VulkanRenderer() : new GLRenderer();
        System.out.println("[betavk] backend " + r.name() + ": occlusionQueries=" + r.supportsOcclusionQueries()
                + " vbo=" + r.supportsVertexBufferObjects() + " fogDistanceNV=" + r.supportsFogDistanceNV());
        return r;
    }
}
