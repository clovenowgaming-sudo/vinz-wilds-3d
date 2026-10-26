package com.vinz.wilds;

import android.opengl.GLES30;

import java.util.HashMap;

/** Small helper around a GLSL program with cached uniform lookups. */
final class Prog {
    final int id;
    private final HashMap<String, Integer> locs = new HashMap<>();

    Prog(String vs, String fs) {
        int v = compile(GLES30.GL_VERTEX_SHADER, vs);
        int f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
        id = GLES30.glCreateProgram();
        GLES30.glAttachShader(id, v);
        GLES30.glAttachShader(id, f);
        GLES30.glLinkProgram(id);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(id);
            GLES30.glDeleteProgram(id);
            throw new RuntimeException("Program link failed: " + log);
        }
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
    }

    private static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            GLES30.glDeleteShader(s);
            String kind = type == GLES30.GL_VERTEX_SHADER ? "VERTEX" : "FRAGMENT";
            throw new RuntimeException(kind + " shader failed: " + log);
        }
        return s;
    }

    void use() {
        GLES30.glUseProgram(id);
    }

    int u(String n) {
        Integer v = locs.get(n);
        if (v == null) {
            v = GLES30.glGetUniformLocation(id, n);
            locs.put(n, v);
        }
        return v;
    }

    void f1(String n, float a) {
        GLES30.glUniform1f(u(n), a);
    }

    void f2(String n, float a, float b) {
        GLES30.glUniform2f(u(n), a, b);
    }

    void f3(String n, float a, float b, float c) {
        GLES30.glUniform3f(u(n), a, b, c);
    }

    void f3v(String n, float[] v) {
        GLES30.glUniform3f(u(n), v[0], v[1], v[2]);
    }

    void i1(String n, int a) {
        GLES30.glUniform1i(u(n), a);
    }

    void mat4(String n, float[] m) {
        GLES30.glUniformMatrix4fv(u(n), 1, false, m, 0);
    }
}
