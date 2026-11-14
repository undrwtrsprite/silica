package dev.betavk.vox;

import dev.betavk.render.Gfx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Records mesh data (the 8-int vertex layout) into a display list of the graphics layer; game thread only. */
final class ListRecorder {
    private ListRecorder() {
    }

    static int record(int existing, int[] data, int vertexCount) {
        int list = existing >= 0 ? existing : Gfx.glGenLists(1);
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder());
        bb.asIntBuffer().put(data);
        FloatBuffer fb = bb.asFloatBuffer();
        Gfx.glNewList(list, Gfx.GL_COMPILE);
        fb.position(3);
        Gfx.glTexCoordPointer(2, Gfx.GL_FLOAT, 32, fb);
        Gfx.glEnableClientState(Gfx.GL_TEXTURE_COORD_ARRAY);
        bb.position(20);
        Gfx.glColorPointer(4, Gfx.GL_UNSIGNED_BYTE, 32, bb);
        Gfx.glEnableClientState(Gfx.GL_COLOR_ARRAY);
        fb.position(0);
        Gfx.glVertexPointer(3, Gfx.GL_FLOAT, 32, fb);
        Gfx.glEnableClientState(Gfx.GL_VERTEX_ARRAY);
        Gfx.glDrawArrays(Gfx.GL_TRIANGLES, Gfx.GL_POINTS, vertexCount);
        Gfx.glDisableClientState(Gfx.GL_VERTEX_ARRAY);
        Gfx.glDisableClientState(Gfx.GL_TEXTURE_COORD_ARRAY);
        Gfx.glDisableClientState(Gfx.GL_COLOR_ARRAY);
        Gfx.glEndList();
        return list;
    }
}
