package dev.betavk.hooks.mesh;

import dev.betavk.render.Gfx;
import net.minecraft.src.Tessellator;

/** Tessellator hooks: one tessellator per thread, a replay of captured draws, and two fixes to vertex packing. */
public final class TessHooks {
    private TessHooks() {
    }

    /** everywhereRedirectStaticRead of Tessellator.instance: a mesh worker has its own capturing tessellator, everyone else the game's. */
    public static Tessellator instance() {
        Thread t = Thread.currentThread();
        return t instanceof MeshWorker w ? w.tessellator : Tessellator.instance;
    }

    /** Game thread: issues the draw a capturing tessellator recorded (the same calls Tessellator.draw makes for its own data). */
    static void emit(Draw d) {
        Tessellator t = Tessellator.instance;
        t.intBuffer.clear();
        t.intBuffer.put(d.data, 0, d.data.length);
        t.byteBuffer.position(0);
        t.byteBuffer.limit(d.data.length * 4);
        if ((d.flags & 1) != 0) {
            t.floatBuffer.position(3);
            Gfx.glTexCoordPointer(2, Gfx.GL_FLOAT, 32, t.floatBuffer);
            Gfx.glEnableClientState(Gfx.GL_TEXTURE_COORD_ARRAY);
        }
        if ((d.flags & 2) != 0) {
            t.byteBuffer.position(20);
            Gfx.glColorPointer(4, Gfx.GL_UNSIGNED_BYTE, 32, t.byteBuffer);
            Gfx.glEnableClientState(Gfx.GL_COLOR_ARRAY);
        }
        if ((d.flags & 4) != 0) {
            t.byteBuffer.position(24);
            Gfx.glNormalPointer(Gfx.GL_BYTE, 32, t.byteBuffer);
            Gfx.glEnableClientState(Gfx.GL_NORMAL_ARRAY);
        }
        t.floatBuffer.position(0);
        Gfx.glVertexPointer(3, Gfx.GL_FLOAT, 32, t.floatBuffer);
        Gfx.glEnableClientState(Gfx.GL_VERTEX_ARRAY);
        Gfx.glDrawArrays(d.mode, Gfx.GL_POINTS, d.vertexCount);
        Gfx.glDisableClientState(Gfx.GL_VERTEX_ARRAY);
        if ((d.flags & 1) != 0) Gfx.glDisableClientState(Gfx.GL_TEXTURE_COORD_ARRAY);
        if ((d.flags & 2) != 0) Gfx.glDisableClientState(Gfx.GL_COLOR_ARRAY);
        if ((d.flags & 4) != 0) Gfx.glDisableClientState(Gfx.GL_NORMAL_ARRAY);
    }

    /** replaceBody of Tessellator.addVertex: when a quad becomes two triangles the two duplicated vertices must carry the quad's normal as well. */
    public static void addVertex(Tessellator t, double x, double y, double z) {
        ++t.addedVertices;
        int[] raw = t.rawBuffer;
        if (t.drawMode == 7 && Tessellator.convertQuadsToTriangles && t.addedVertices % 4 == 0) {
            for (int k = 0; k < 2; k++) {
                int back = 8 * (3 - k);
                int i = t.rawBufferIndex;
                if (t.hasTexture) {
                    raw[i + 3] = raw[i - back + 3];
                    raw[i + 4] = raw[i - back + 4];
                }
                if (t.hasColor) raw[i + 5] = raw[i - back + 5];
                if (t.hasNormals) raw[i + 6] = raw[i - back + 6];
                raw[i] = raw[i - back];
                raw[i + 1] = raw[i - back + 1];
                raw[i + 2] = raw[i - back + 2];
                ++t.vertexCount;
                t.rawBufferIndex += 8;
            }
        }
        int i = t.rawBufferIndex;
        if (t.hasTexture) {
            raw[i + 3] = Float.floatToRawIntBits((float) t.textureU);
            raw[i + 4] = Float.floatToRawIntBits((float) t.textureV);
        }
        if (t.hasColor) raw[i + 5] = t.color;
        if (t.hasNormals) raw[i + 6] = t.normal;
        raw[i] = Float.floatToRawIntBits((float) (x + t.xOffset));
        raw[i + 1] = Float.floatToRawIntBits((float) (y + t.yOffset));
        raw[i + 2] = Float.floatToRawIntBits((float) (z + t.zOffset));
        t.rawBufferIndex += 8;
        ++t.vertexCount;
        if (t.vertexCount % 4 == 0 && t.rawBufferIndex >= t.bufferSize - 32) {
            t.draw();
            t.isDrawing = true;
        }
    }

    /** replaceBody of Tessellator.setNormal: each component is masked (sign extension clobbered the other components). */
    public static void setNormal(Tessellator t, float x, float y, float z) {
        t.hasNormals = true;
        byte bx = (byte) ((int) (x * 128.0F)), by = (byte) ((int) (y * 127.0F)), bz = (byte) ((int) (z * 127.0F));
        t.normal = (bx & 255) | (by & 255) << 8 | (bz & 255) << 16;
    }
}
