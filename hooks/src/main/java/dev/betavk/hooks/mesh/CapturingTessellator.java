package dev.betavk.hooks.mesh;

import net.minecraft.src.Tessellator;

import java.util.ArrayList;

/** A Tessellator for a mesh worker: draw() records the vertices instead of issuing graphics calls, so nothing touches the graphics layer off the game thread. */
public final class CapturingTessellator extends Tessellator {
    public final ArrayList<Draw> captured = new ArrayList<>();

    public CapturingTessellator() {
        super(2097152);
        // the graphics buffers the base class allocates are never used here; its own private reset() (called without virtual dispatch) still clears one
        byteBuffer = java.nio.ByteBuffer.allocate(16);
        intBuffer = null;
        floatBuffer = null;
        useVBO = false;
    }

    @Override public void draw() {
        if (!isDrawing) throw new IllegalStateException("Not tesselating!");
        isDrawing = false;
        if (vertexCount > 0) {
            captured.add(new Draw(drawMode == 7 && convertQuadsToTriangles ? 4 : drawMode, (hasTexture ? 1 : 0) | (hasColor ? 2 : 0) | (hasNormals ? 4 : 0), vertexCount,
                    java.util.Arrays.copyOf(rawBuffer, rawBufferIndex)));
        }
        reset();
    }

    @Override public void reset() {
        vertexCount = 0;
        rawBufferIndex = 0;
        addedVertices = 0;
    }
}
