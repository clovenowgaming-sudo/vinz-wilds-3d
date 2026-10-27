package com.vinz.wilds;

import java.util.Random;

/** Builds one terrain chunk (mesh data + vegetation) on a worker thread. */
final class ChunkGen {
    private ChunkGen() {}

    /** Grid cells per chunk side for LOD 0..4 (2 m, 4 m, 8 m, 16 m, 32 m spacing). */
    static final int[] CELLS = {64, 32, 16, 8, 4};

    static final class Data {
        int cx, cz, lod;
        long key;
        boolean obj, grass, failed;
        float[] verts;
        float[] water;
        boolean hasWater;
        float[] pines, oaks, rocks, grassI;
        int nPines, nOaks, nRocks, nGrass;
        float[] circles;
        int nCircles;
        float minH, maxH;
    }

    private static void putV(float[] v, int idx, float x, float y, float z, float nx, float ny, float nz) {
        int o = idx * 6;
        v[o] = x;
        v[o + 1] = y;
        v[o + 2] = z;
        v[o + 3] = nx;
        v[o + 4] = ny;
        v[o + 5] = nz;
    }

    private static void copySkirt(float[] v, int dst, int src, float skirt) {
        int s = src * 6, o = dst * 6;
        v[o] = v[s];
        v[o + 1] = v[s + 1] - skirt;
        v[o + 2] = v[s + 2];
        v[o + 3] = v[s + 3];
        v[o + 4] = v[s + 4];
        v[o + 5] = v[s + 5];
    }

    static Data generate(World w, int cx, int cz, int lod, boolean obj, boolean grass, int Q) {
        Data d = new Data();
        d.cx = cx;
        d.cz = cz;
        d.lod = lod;
        d.key = World.key(cx, cz);
        d.obj = obj;
        d.grass = grass;

        int cells = CELLS[lod];
        int n = cells + 1;
        float step = World.CHUNK / (float) cells;
        float x0 = cx * (float) World.CHUNK, z0 = cz * (float) World.CHUNK;
        int m = cells + 3;
        float[] hg = new float[m * m];
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < m; i++) {
                hg[j * m + i] = w.height(x0 + (i - 1) * step, z0 + (j - 1) * step);
            }
        }

        int nv = n * n + 4 * n;
        float[] v = new float[nv * 6];
        float mn = 1e9f, mx = -1e9f;
        boolean water = false;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float h = hg[(j + 1) * m + i + 1];
                float hl = hg[(j + 1) * m + i], hr = hg[(j + 1) * m + i + 2];
                float hd = hg[j * m + i + 1], hu = hg[(j + 2) * m + i + 1];
                float nx = (hl - hr) / (2f * step), nz = (hd - hu) / (2f * step);
                float l = (float) Math.sqrt(nx * nx + 1f + nz * nz);
                putV(v, j * n + i, i * step, h, j * step, nx / l, 1f / l, nz / l);
                if (h < mn) mn = h;
                if (h > mx) mx = h;
                if (h < 0.4f) water = true;
            }
        }
        float skirt = step * 1.5f + 3f;
        int base = n * n;
        for (int i = 0; i < n; i++) {
            copySkirt(v, base + i, i, skirt);
            copySkirt(v, base + n + i, cells * n + i, skirt);
            copySkirt(v, base + 2 * n + i, i * n, skirt);
            copySkirt(v, base + 3 * n + i, i * n + cells, skirt);
        }
        d.verts = v;
        d.minH = mn;
        d.maxH = mx;
        d.hasWater = water;
        if (water) {
            float[] wv = new float[n * n * 3];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int o = (j * n + i) * 3;
                    wv[o] = i * step;
                    wv[o + 1] = j * step;
                    wv[o + 2] = -hg[(j + 1) * m + i + 1];
                }
            }
            d.water = wv;
        }

        if (obj) genObjects(w, d, x0, z0, grass, Q);
        return d;
    }

    private static void genObjects(World w, Data d, float x0, float z0, boolean grass, int Q) {
        Random r = new Random(((long) d.cx * 73856093L) ^ ((long) d.cz * 19349663L) ^ 0x5DEECE66DL);
        int atP = Q == 2 ? 90 : Q == 1 ? 40 : 16;
        int atO = Q == 2 ? 60 : Q == 1 ? 26 : 10;
        int atR = Q == 2 ? 18 : Q == 1 ? 8 : 3;
        int atG = grass ? (Q == 2 ? 2200 : 800) : 0;
        d.pines = new float[atP * 5];
        d.oaks = new float[atO * 5];
        d.rocks = new float[atR * 5];
        d.grassI = new float[Math.max(1, atG) * 5];
        float[] circ = new float[(atP + atO + atR) * 3];
        int nc = 0;

        for (int t = 0; t < atP; t++) {
            float x = x0 + r.nextFloat() * World.CHUNK, z = z0 + r.nextFloat() * World.CHUNK;
            float rot = r.nextFloat() * 6.283f, sr = r.nextFloat();
            float h = w.height(x, z);
            if (h < 2.5f || h > 190f) continue;
            float f = World.fbm(x * 0.0025f + 3f, z * 0.0025f - 8f, 3);
            float need = h > 30f ? 0.46f : 0.55f;
            if (f < need || w.slope(x, z) > 0.8f) continue;
            float s = (0.8f + sr * 0.9f) * (h > 130f ? 0.7f : 1f);
            int o = d.nPines++ * 5;
            d.pines[o] = x;
            d.pines[o + 1] = h - 0.1f;
            d.pines[o + 2] = z;
            d.pines[o + 3] = s;
            d.pines[o + 4] = rot;
            circ[nc * 3] = x;
            circ[nc * 3 + 1] = z;
            circ[nc * 3 + 2] = 0.5f * s;
            nc++;
        }
        for (int t = 0; t < atO; t++) {
            float x = x0 + r.nextFloat() * World.CHUNK, z = z0 + r.nextFloat() * World.CHUNK;
            float rot = r.nextFloat() * 6.283f, sr = r.nextFloat();
            float h = w.height(x, z);
            if (h < 1.8f || h > 70f) continue;
            float f = World.fbm(x * 0.0035f - 12f, z * 0.0035f + 4f, 3);
            if (f < 0.52f || w.slope(x, z) > 0.55f) continue;
            float s = 0.8f + sr * 0.7f;
            int o = d.nOaks++ * 5;
            d.oaks[o] = x;
            d.oaks[o + 1] = h - 0.1f;
            d.oaks[o + 2] = z;
            d.oaks[o + 3] = s;
            d.oaks[o + 4] = rot;
            circ[nc * 3] = x;
            circ[nc * 3 + 1] = z;
            circ[nc * 3 + 2] = 0.5f * s;
            nc++;
        }
        for (int t = 0; t < atR; t++) {
            float x = x0 + r.nextFloat() * World.CHUNK, z = z0 + r.nextFloat() * World.CHUNK;
            float rot = r.nextFloat() * 6.283f, sr = r.nextFloat(), keep = r.nextFloat();
            float h = w.height(x, z);
            if (h < -0.3f) continue;
            if (w.slope(x, z) < 0.45f && keep > 0.12f) continue;
            float s = 0.5f + sr * sr * 2.6f;
            int o = d.nRocks++ * 5;
            d.rocks[o] = x;
            d.rocks[o + 1] = h - 0.15f * s;
            d.rocks[o + 2] = z;
            d.rocks[o + 3] = s;
            d.rocks[o + 4] = rot;
            circ[nc * 3] = x;
            circ[nc * 3 + 1] = z;
            circ[nc * 3 + 2] = 0.85f * s;
            nc++;
        }
        d.circles = circ;
        d.nCircles = nc;

        for (int t = 0; t < atG; t++) {
            float x = x0 + r.nextFloat() * World.CHUNK, z = z0 + r.nextFloat() * World.CHUNK;
            float rot = r.nextFloat() * 6.283f, sr = r.nextFloat();
            float h = w.height(x, z);
            if (h < 1.2f || h > 120f) continue;
            if (World.fbm(x * 0.02f + 40f, z * 0.02f - 9f, 2) < 0.38f) continue;
            if (w.slope(x, z) > 0.6f) continue;
            int o = d.nGrass++ * 5;
            d.grassI[o] = x;
            d.grassI[o + 1] = h - 0.02f;
            d.grassI[o + 2] = z;
            d.grassI[o + 3] = 0.7f + sr * 0.8f;
            d.grassI[o + 4] = rot;
        }
    }
}
