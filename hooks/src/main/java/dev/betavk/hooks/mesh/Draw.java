package dev.betavk.hooks.mesh;

/** One recorded draw of a capturing tessellator: mode, which arrays are present (1 texture, 2 colour, 4 normals), and the vertex ints (8 per vertex). */
public final class Draw {
    public final int mode, flags, vertexCount;
    public final int[] data;

    public Draw(int mode, int flags, int vertexCount, int[] data) {
        this.mode = mode;
        this.flags = flags;
        this.vertexCount = vertexCount;
        this.data = data;
    }
}
