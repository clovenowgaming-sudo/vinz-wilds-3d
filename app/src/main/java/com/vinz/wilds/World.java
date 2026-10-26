package com.vinz.wilds;

import java.util.ArrayList;
import java.util.Random;

/** Procedural island: heightfield, vegetation placement, crystals and tree collision. */
final class World {
    static final float SIZE = 640f;
    static final float HALF = 320f;
    static final int NC = 12;
    private static final int CELL = 16;
    private static final int GRID = (int) (SIZE / CELL);

    final int quality;
    float[] pines, oaks, rocks, grass;
    int nPines, nOaks, nRocks, nGrass;
    final float[] crystals = new float[NC * 3];
    final float spawnX = 0f, spawnZ = 0f;

    @SuppressWarnings("unchecked")
    private final ArrayList<float[]>[] cells = (ArrayList<float[]>[]) new ArrayList[GRID * GRID];

    // ---------------------------------------------------------------- noise
    private static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263 + 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= (h >>> 16);
        return (h & 0xFFFFFF) / 16777215f;
    }

    static float vnoise(float x, float y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float xf = x - xi, yf = y - yi;
        float u = xf * xf * (3f - 2f * xf), v = yf * yf * (3f - 2f * yf);
        float a = hash(xi, yi), b = hash(xi + 1, yi), c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
        float ab = a + (b - a) * u, cd = c + (d - c) * u;
        return ab + (cd - ab) * v;
    }

    static float fbm(float x, float y, int oct) {
        float s = 0f, a = 0.5f, n = 0f;
        for (int i = 0; i < oct; i++) {
            s += a * vnoise(x, y);
            n += a;
            x = x * 2.03f + 17.3f;
            y = y * 2.03f + 9.1f;
            a *= 0.5f;
        }
        return s / n;
    }

    static float smooth(float a, float b, float x) {
        float t = (x - a) / (b - a);
        t = t < 0f ? 0f : t > 1f ? 1f : t;
        return t * t * (3f - 2f * t);
    }

    float height(float x, float z) {
        float r = (float) Math.sqrt(x * x + z * z);
        float d = r / HALF;
        float base = fbm(x * 0.0045f + 10f, z * 0.0045f + 10f, 5);
        float h = (base - 0.45f) * 78f;
        float mtn = fbm(x * 0.0022f - 30f, z * 0.0022f + 50f, 3);
        float rg = 1f - Math.abs(2f * fbm(x * 0.008f + 5f, z * 0.008f - 7f, 4) - 1f);
        h += smooth(0.50f, 0.70f, mtn) * rg * rg * 75f;
        float e = smooth(0.50f, 1.0f, d);
        h = h * (1f - e * 0.6f) - e * 42f;
        float c = 1f - smooth(0f, 60f, r);
        h = h * (1f - c) + (4.5f + (base - 0.45f) * 14f) * c;
        return h;
    }

    float slope(float x, float z) {
        float e = 1.5f;
        float dx = height(x + e, z) - height(x - e, z);
        float dz = height(x, z + e) - height(x, z - e);
        return (float) Math.sqrt(dx * dx + dz * dz) / (2f * e);
    }

    // ----------------------------------------------------------- generation
    World(int q) {
        quality = q;
        Random r = new Random(20261025L);
        int tp = q == 2 ? 2200 : q == 1 ? 900 : 350;
        int to = q == 2 ? 1200 : q == 1 ? 500 : 200;
        int tr = q == 2 ? 500 : q == 1 ? 250 : 120;
        int tg = q == 2 ? 26000 : q == 1 ? 9000 : 0;
        pines = new float[Math.max(1, tp) * 5];
        oaks = new float[Math.max(1, to) * 5];
        rocks = new float[Math.max(1, tr) * 5];
        grass = new float[Math.max(1, tg) * 5];

        int att = 0;
        while (nPines < tp && att < tp * 60) {
            att++;
            float x = (r.nextFloat() * 2f - 1f) * (HALF - 20f);
            float z = (r.nextFloat() * 2f - 1f) * (HALF - 20f);
            float h = height(x, z);
            if (h < 2.0f || h > 52f) continue;
            if (x * x + z * z < 14f * 14f) continue;
            float dens = fbm(x * 0.015f + 3f, z * 0.015f - 8f, 3);
            float pref = smooth(8f, 22f, h);
            if (dens < 0.42f + (1f - pref) * 0.16f) continue;
            if (slope(x, z) > 0.75f) continue;
            float s = 0.8f + r.nextFloat() * 0.9f;
            put(pines, nPines++, x, h - 0.1f, z, s, r.nextFloat() * 6.283f);
            addCircle(x, z, 0.5f * s);
        }

        att = 0;
        while (nOaks < to && att < to * 60) {
            att++;
            float x = (r.nextFloat() * 2f - 1f) * (HALF - 20f);
            float z = (r.nextFloat() * 2f - 1f) * (HALF - 20f);
            float h = height(x, z);
            if (h < 1.5f || h > 22f) continue;
            if (x * x + z * z < 14f * 14f) continue;
            float dens = fbm(x * 0.02f - 12f, z * 0.02f + 4f, 3);
            if (dens < 0.50f) continue;
            if (slope(x, z) > 0.55f) continue;
            float s = 0.8f + r.nextFloat() * 0.7f;
            put(oaks, nOaks++, x, h - 0.1f, z, s, r.nextFloat() * 6.283f);
            addCircle(x, z, 0.5f * s);
        }

        att = 0;
        while (nRocks < tr && att < tr * 40) {
            att++;
            float x = (r.nextFloat() * 2f - 1f) * (HALF - 20f);
            float z = (r.nextFloat() * 2f - 1f) * (HALF - 20f);
            float h = height(x, z);
            if (h < -0.5f) continue;
            if (x * x + z * z < 10f * 10f) continue;
            if (slope(x, z) < 0.45f && r.nextFloat() > 0.22f) continue;
            float s = 0.5f + r.nextFloat() * r.nextFloat() * 2.6f;
            put(rocks, nRocks++, x, h - 0.15f * s, z, s, r.nextFloat() * 6.283f);
            addCircle(x, z, 0.85f * s);
        }

        att = 0;
        while (nGrass < tg && att < tg * 8) {
            att++;
            float x = (r.nextFloat() * 2f - 1f) * (HALF - 20f);
            float z = (r.nextFloat() * 2f - 1f) * (HALF - 20f);
            float h = height(x, z);
            if (h < 1.0f || h > 32f) continue;
            if (slope(x, z) > 0.6f) continue;
            float dens = fbm(x * 0.03f + 40f, z * 0.03f - 9f, 2);
            if (dens < 0.38f) continue;
            put(grass, nGrass++, x, h - 0.02f, z, 0.7f + r.nextFloat() * 0.8f, r.nextFloat() * 6.283f);
        }

        placeCrystals(r);
    }

    private static void put(float[] a, int i, float x, float y, float z, float s, float rot) {
        int o = i * 5;
        a[o] = x;
        a[o + 1] = y;
        a[o + 2] = z;
        a[o + 3] = s;
        a[o + 4] = rot;
    }

    private void addCircle(float x, float z, float rad) {
        int gx = (int) ((x + HALF) / CELL), gz = (int) ((z + HALF) / CELL);
        if (gx < 0 || gz < 0 || gx >= GRID || gz >= GRID) return;
        int k = gz * GRID + gx;
        if (cells[k] == null) cells[k] = new ArrayList<>();
        cells[k].add(new float[]{x, z, rad});
    }

    private void placeCrystals(Random r) {
        int found = 0, tries = 0;
        while (found < NC && tries < 6000) {
            tries++;
            float ang = r.nextFloat() * 6.2832f;
            float rad = 45f + r.nextFloat() * 215f;
            float x = (float) Math.cos(ang) * rad, z = (float) Math.sin(ang) * rad;
            float h = height(x, z);
            if (h < 2.5f || h > 55f || slope(x, z) > 0.6f) continue;
            boolean ok = true;
            for (int k = 0; k < found; k++) {
                float dx = crystals[k * 3] - x, dz = crystals[k * 3 + 2] - z;
                if (dx * dx + dz * dz < 55f * 55f) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            crystals[found * 3] = x;
            crystals[found * 3 + 1] = h + 1.1f;
            crystals[found * 3 + 2] = z;
            found++;
        }
        while (found < NC) {
            float ang = found * 0.9f;
            float rad = 30f + found * 12f;
            float x = (float) Math.cos(ang) * rad, z = (float) Math.sin(ang) * rad;
            crystals[found * 3] = x;
            crystals[found * 3 + 1] = Math.max(height(x, z), 0.5f) + 1.1f;
            crystals[found * 3 + 2] = z;
            found++;
        }
    }

    /** Pushes (xz[0], xz[1]) out of nearby trees and rocks. */
    void resolve(float[] xz, float pr) {
        int cx = (int) ((xz[0] + HALF) / CELL), cz = (int) ((xz[1] + HALF) / CELL);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int gx = cx + dx, gz = cz + dz;
                if (gx < 0 || gz < 0 || gx >= GRID || gz >= GRID) continue;
                ArrayList<float[]> l = cells[gz * GRID + gx];
                if (l == null) continue;
                for (int i = 0; i < l.size(); i++) {
                    float[] c = l.get(i);
                    float ddx = xz[0] - c[0], ddz = xz[1] - c[1];
                    float rr = c[2] + pr;
                    float d2 = ddx * ddx + ddz * ddz;
                    if (d2 < rr * rr && d2 > 1e-6f) {
                        float d = (float) Math.sqrt(d2);
                        float k = (rr - d) / d;
                        xz[0] += ddx * k;
                        xz[1] += ddz * k;
                    }
                }
            }
        }
    }
}
