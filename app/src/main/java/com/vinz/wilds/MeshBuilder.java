package com.vinz.wilds;

import java.util.Arrays;

/** CPU-side procedural mesh builder. Vertex = pos(3) nrm(3) col(4: rgb + emissive). */
final class MeshBuilder {
    float[] v = new float[10 * 1024];
    int vc = 0;
    short[] idx = new short[3 * 2048];
    int ic = 0;
    private int seed;
    private final int salt;

    MeshBuilder(int salt) {
        this.salt = salt;
        this.seed = salt * 7919 + 13;
    }

    float rnd() {
        seed = seed * 1103515245 + 12345;
        return ((seed >>> 8) & 0xFFFF) / 65535f;
    }

    private float hj(int i, int j) {
        int h = i * 73856093 ^ j * 19349663 ^ (salt * 83492791);
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= (h >>> 16);
        return (h & 0xFFFF) / 65535f;
    }

    int vert(float x, float y, float z, float nx, float ny, float nz, float r, float g, float b, float a) {
        if ((vc + 1) * 10 > v.length) v = Arrays.copyOf(v, v.length * 2);
        int o = vc * 10;
        v[o] = x; v[o + 1] = y; v[o + 2] = z;
        v[o + 3] = nx; v[o + 4] = ny; v[o + 5] = nz;
        v[o + 6] = r; v[o + 7] = g; v[o + 8] = b; v[o + 9] = a;
        return vc++;
    }

    void tri(int a, int b, int c) {
        if (ic + 3 > idx.length) idx = Arrays.copyOf(idx, idx.length * 2);
        idx[ic++] = (short) a;
        idx[ic++] = (short) b;
        idx[ic++] = (short) c;
    }

    /** Flat-shaded triangle; normal oriented away from (ox,oy,oz). */
    void triFlat(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                 float ox, float oy, float oz, float r, float g, float b, float a) {
        float ux = x1 - x0, uy = y1 - y0, uz = z1 - z0;
        float wx = x2 - x0, wy = y2 - y0, wz = z2 - z0;
        float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-6f) return;
        nx /= l; ny /= l; nz /= l;
        float cx = (x0 + x1 + x2) / 3f - ox, cy = (y0 + y1 + y2) / 3f - oy, cz = (z0 + z1 + z2) / 3f - oz;
        if (nx * cx + ny * cy + nz * cz < 0f) { nx = -nx; ny = -ny; nz = -nz; }
        int a0 = vert(x0, y0, z0, nx, ny, nz, r, g, b, a);
        int a1 = vert(x1, y1, z1, nx, ny, nz, r, g, b, a);
        int a2 = vert(x2, y2, z2, nx, ny, nz, r, g, b, a);
        tri(a0, a1, a2);
    }

    void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
              float x3, float y3, float z3, float nx, float ny, float nz, float r, float g, float b, float a) {
        int a0 = vert(x0, y0, z0, nx, ny, nz, r, g, b, a);
        int a1 = vert(x1, y1, z1, nx, ny, nz, r, g, b, a);
        int a2 = vert(x2, y2, z2, nx, ny, nz, r, g, b, a);
        int a3 = vert(x3, y3, z3, nx, ny, nz, r, g, b, a);
        tri(a0, a1, a2);
        tri(a0, a2, a3);
    }

    /** Axis aligned box, hx/hy/hz are half extents. */
    void box(float cx, float cy, float cz, float hx, float hy, float hz, float r, float g, float b, float a) {
        float x0 = cx - hx, x1 = cx + hx, y0 = cy - hy, y1 = cy + hy, z0 = cz - hz, z1 = cz + hz;
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, r, g, b, a);
        quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, r, g, b, a);
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, r, g, b, a);
        quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, r, g, b, a);
        quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, r, g, b, a);
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, r, g, b, a);
    }

    /** Cone with smooth normals. */
    void cone(float cx, float y0, float cz, float r, float h, int seg, float cr, float cg, float cb, float jit) {
        float len = (float) Math.sqrt(h * h + r * r);
        int first = vc;
        for (int i = 0; i <= seg; i++) {
            float a = (float) (i * 2.0 * Math.PI / seg);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float nx = ca * h / len, ny = r / len, nz = sa * h / len;
            float sh = 1f + (rnd() - 0.5f) * jit;
            vert(cx + ca * r, y0, cz + sa * r, nx, ny, nz, cr * sh * 0.8f, cg * sh * 0.8f, cb * sh * 0.8f, 0f);
            vert(cx, y0 + h, cz, nx, ny, nz, cr * sh * 1.2f, cg * sh * 1.2f, cb * sh * 1.2f, 0f);
        }
        for (int i = 0; i < seg; i++) {
            int b0 = first + i * 2;
            int a0 = b0 + 1;
            int b1 = first + (i + 1) * 2;
            tri(b0, a0, b1);
        }
    }

    /** Tapered cylinder, colour gradient bottom -> top. */
    void cyl(float cx, float y0, float cz, float r0, float r1, float h, int seg,
             float r0c, float g0c, float b0c, float r1c, float g1c, float b1c) {
        int first = vc;
        float slope = (r0 - r1) / h;
        for (int i = 0; i <= seg; i++) {
            float a = (float) (i * 2.0 * Math.PI / seg);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float l = (float) Math.sqrt(1f + slope * slope);
            float nx = ca / l, ny = slope / l, nz = sa / l;
            float sh = 0.88f + 0.24f * hj(i % seg, 3);
            vert(cx + ca * r0, y0, cz + sa * r0, nx, ny, nz, r0c * sh, g0c * sh, b0c * sh, 0f);
            vert(cx + ca * r1, y0 + h, cz + sa * r1, nx, ny, nz, r1c * sh, g1c * sh, b1c * sh, 0f);
        }
        for (int i = 0; i < seg; i++) {
            int a = first + i * 2, b = a + 1, c = a + 2, d = a + 3;
            tri(a, b, c);
            tri(c, b, d);
        }
    }

    /** Smooth jittered ellipsoid (leaf blobs, heads, bolts). */
    void blob(float cx, float cy, float cz, float rx, float ry, float rz, int lat, int lon,
              float r, float g, float b, float jit, float emis) {
        int first = vc;
        for (int i = 0; i <= lat; i++) {
            float th = (float) (Math.PI * i / lat);
            float ny = (float) Math.cos(th), sr = (float) Math.sin(th);
            for (int j = 0; j <= lon; j++) {
                float ph = (float) (2.0 * Math.PI * j / lon);
                float jx = hj(i, j % lon);
                float k = (i == 0 || i == lat) ? 1f : 1f + (jx - 0.5f) * jit;
                float nx = sr * (float) Math.cos(ph), nz = sr * (float) Math.sin(ph);
                float sh = 0.72f + 0.5f * (ny * 0.5f + 0.5f) + (jx - 0.5f) * 0.3f * jit;
                vert(cx + nx * rx * k, cy + ny * ry * k, cz + nz * rz * k, nx, ny, nz, r * sh, g * sh, b * sh, emis);
            }
        }
        for (int i = 0; i < lat; i++) {
            for (int j = 0; j < lon; j++) {
                int a = first + i * (lon + 1) + j;
                int c = a + lon + 1;
                tri(a, c, a + 1);
                tri(a + 1, c, c + 1);
            }
        }
    }

    /** Low-poly faceted rock, centred at the origin, flattened bottom. */
    void rock(float rx, float ry, float rz, int lat, int lon, float r, float g, float b) {
        int w = lon + 1;
        float[][] p = new float[(lat + 1) * w][3];
        for (int i = 0; i <= lat; i++) {
            float th = (float) (Math.PI * i / lat);
            float ny = (float) Math.cos(th), sr = (float) Math.sin(th);
            for (int j = 0; j <= lon; j++) {
                float ph = (float) (2.0 * Math.PI * j / lon);
                float k = 0.72f + 0.55f * hj(i, j % lon);
                if (i == 0 || i == lat) k = 0.9f;
                float yy = ny < 0f ? ny * 0.45f : ny;
                float[] q = p[i * w + j];
                q[0] = sr * (float) Math.cos(ph) * rx * k;
                q[1] = yy * ry * k;
                q[2] = sr * (float) Math.sin(ph) * rz * k;
            }
        }
        for (int i = 0; i < lat; i++) {
            for (int j = 0; j < lon; j++) {
                float[] a = p[i * w + j], bb = p[(i + 1) * w + j], c = p[i * w + j + 1], d = p[(i + 1) * w + j + 1];
                float sh = 0.7f + 0.55f * rnd();
                triFlat(a[0], a[1], a[2], bb[0], bb[1], bb[2], c[0], c[1], c[2], 0, 0, 0, r * sh, g * sh, b * sh, 0f);
                sh = 0.7f + 0.55f * rnd();
                triFlat(c[0], c[1], c[2], bb[0], bb[1], bb[2], d[0], d[1], d[2], 0, 0, 0, r * sh, g * sh, b * sh, 0f);
            }
        }
    }

    /** Elongated bipyramid crystal (emissive). */
    void crystalMesh(float h, float r, int seg, float cr, float cg, float cb) {
        float botY = -h * 0.55f;
        for (int i = 0; i < seg; i++) {
            float a0 = (float) (i * 2.0 * Math.PI / seg), a1 = (float) ((i + 1) * 2.0 * Math.PI / seg);
            float x0 = r * (float) Math.cos(a0), z0 = r * (float) Math.sin(a0);
            float x1 = r * (float) Math.cos(a1), z1 = r * (float) Math.sin(a1);
            float sh = 0.65f + 0.5f * rnd();
            triFlat(0, h, 0, x0, 0.1f * h, z0, x1, 0.1f * h, z1, 0, 0, 0, cr * sh, cg * sh, cb * sh, 0.9f);
            sh = 0.5f + 0.4f * rnd();
            triFlat(0, botY, 0, x1, 0.1f * h, z1, x0, 0.1f * h, z0, 0, 0, 0, cr * sh, cg * sh, cb * sh, 0.9f);
        }
    }

    /** One tapered, bent grass blade. */
    void blade(float bx, float bz, float ang, float h, float w) {
        float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
        float[] ts = {0f, 0.4f, 0.75f};
        int first = vc;
        float nx = sa * 0.25f, nz = ca;
        for (int i = 0; i < 3; i++) {
            float t = ts[i];
            float y = h * t;
            float wd = w * (1f - t * 0.8f);
            float zo = h * 0.35f * t * t;
            float lx = -wd, rx = wd;
            float cr = 0.035f + 0.26f * t, cg = 0.09f + 0.40f * t, cb = 0.02f + 0.08f * t;
            vert(bx + lx * ca + zo * sa, y, bz - lx * sa + zo * ca, nx, 0.4f, nz, cr, cg, cb, 0f);
            vert(bx + rx * ca + zo * sa, y, bz - rx * sa + zo * ca, nx, 0.4f, nz, cr, cg, cb, 0f);
        }
        float zt = h * 0.35f;
        int tip = vert(bx + zt * sa, h, bz + zt * ca, nx, 0.4f, nz, 0.30f, 0.50f, 0.10f, 0f);
        tri(first, first + 1, first + 2);
        tri(first + 1, first + 3, first + 2);
        tri(first + 2, first + 3, first + 4);
        tri(first + 3, first + 5, first + 4);
        tri(first + 4, first + 5, tip);
    }
}
