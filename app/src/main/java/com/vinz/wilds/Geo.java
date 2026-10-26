package com.vinz.wilds;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Static mesh in a VAO. Vertex layout: pos(3) nrm(3) col(4) = 40 bytes.
 * Optional per-instance buffer: vec4 (x,y,z,scale) + float rot = 20 bytes.
 */
final class Geo {
    int vao, vbo, ibo, inst;
    int idxCount;
    final int maxInst;
    final boolean instanced;
    private FloatBuffer ib;

    Geo(MeshBuilder b, int maxInstances) {
        instanced = maxInstances > 0;
        maxInst = maxInstances;
        idxCount = b.ic;
        int[] t = new int[1];

        GLES30.glGenVertexArrays(1, t, 0);
        vao = t[0];
        GLES30.glBindVertexArray(vao);

        GLES30.glGenBuffers(1, t, 0);
        vbo = t[0];
        FloatBuffer fb = ByteBuffer.allocateDirect(b.vc * 40).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(b.v, 0, b.vc * 10);
        fb.position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, b.vc * 40, fb, GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 40, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 40, 12);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, 40, 24);

        GLES30.glGenBuffers(1, t, 0);
        ibo = t[0];
        ShortBuffer sb = ByteBuffer.allocateDirect(b.ic * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(b.idx, 0, b.ic);
        sb.position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, b.ic * 2, sb, GLES30.GL_STATIC_DRAW);

        if (instanced) {
            GLES30.glGenBuffers(1, t, 0);
            inst = t[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxInst * 20, null, GLES30.GL_DYNAMIC_DRAW);
            GLES30.glEnableVertexAttribArray(3);
            GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, 20, 0);
            GLES30.glVertexAttribDivisor(3, 1);
            GLES30.glEnableVertexAttribArray(4);
            GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, 20, 16);
            GLES30.glVertexAttribDivisor(4, 1);
            ib = ByteBuffer.allocateDirect(maxInst * 20).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        GLES30.glBindVertexArray(0);
    }

    void setInstances(float[] d, int count) {
        if (!instanced || count <= 0) return;
        if (count > maxInst) count = maxInst;
        ib.clear();
        ib.put(d, 0, count * 5);
        ib.position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, inst);
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 20, ib);
    }

    void draw(int count) {
        if (count <= 0) return;
        if (count > maxInst) count = maxInst;
        GLES30.glBindVertexArray(vao);
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, idxCount, GLES30.GL_UNSIGNED_SHORT, 0, count);
    }

    void drawOne() {
        GLES30.glBindVertexArray(vao);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, idxCount, GLES30.GL_UNSIGNED_SHORT, 0);
    }
}
