package com.vinz.wilds;

import java.util.HashMap;

/**
 * Procedural 64,000 x 64,000 m world. Everything is a pure function of (x, z) so the
 * client and the multiplayer server (server.js) always agree.
 */
final class World {
    static final float SIZE = 64000f;
    static final float HALF = 32000f;
    static final int CHUNK = 128;
    static final int CCELL = 1024; // crystal cell size (m)

    final int quality;
    float spawnX = 0f, spawnZ = 0f;

    private final HashMap<Long, float[]> cc = new HashMap<>();
    private static final float[] NONE = new float[0];

    static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

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

    /** Terrain height in metres. Sea level = 0. Keep in sync with server.js. */
    float height(float x, float z) {
        float cont = fbm(x * 0.00006f + 3.1f, z * 0.00006f - 5.7f, 3);
        float land = smooth(0.40f, 0.52f, cont);
        float hills = (fbm(x * 0.0011f + 10f, z * 0.0011f + 10f, 5) - 0.45f) * 130f;
        float det = (fbm(x * 0.012f + 40f, z * 0.012f - 9f, 3) - 0.5f) * 10f;
        float mm = smooth(0.52f, 0.66f, fbm(x * 0.00025f - 30f, z * 0.00025f + 50f, 3));
        float h;
        if (mm > 0f) {
            float rg = 1f - Math.abs(2f * fbm(x * 0.0022f + 5f, z * 0.0022f - 7f, 4) - 1f);
            h = hills + det + mm * rg * rg * 260f + 4f;
        } else {
            h = hills + det + 4f;
        }
        h = h * land + (land - 1f) * 45f;
        float e = smooth(0.86f, 1.0f, Math.max(Math.abs(x), Math.abs(z)) / HALF);
        return h - e * 90f;
    }

    float slope(float x, float z) {
        float e = 1.5f;
        float dx = height(x + e, z) - height(x - e, z);
        float dz = height(x, z + e) - height(x, z - e);
        return (float) Math.sqrt(dx * dx + dz * dz) / (2f * e);
    }

    // ---------------------------------------------------------------- setup
    World(int q) {
        quality = q;
        findSpawn();
    }

    private boolean goodSpawn(float x, float z) {
        float h = height(x, z);
        if (h < 3f || h > 30f) return false;
        if (slope(x, z) > 0.25f) return false;
        for (int k = 0; k < 8; k++) {
            float a = k * 0.785398f;
            if (height(x + (float) Math.cos(a) * 100f, z + (float) Math.sin(a) * 100f) < 0.8f) return false;
        }
        return true;
    }

    private void findSpawn() {
        for (int ring = 0; ring < 80; ring++) {
            float rad = ring * 60f;
            int n = ring == 0 ? 1 : 8 + ring * 4;
            for (int k = 0; k < n; k++) {
                float a = k * 6.2831853f / n;
                float x = (float) Math.cos(a) * rad, z = (float) Math.sin(a) * rad;
                if (goodSpawn(x, z)) {
                    spawnX = x;
                    spawnZ = z;
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- crystals
    private static int ih(int a, int b, int c) {
        int h = a * 374761393 + b * 668265263 + c * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    /** Returns {x, y, z} of the crystal in a 1024 m cell, or null if the cell has none. */
    synchronized float[] crystalCell(int ix, int iz) {
        long k = key(ix, iz);
        float[] r = cc.get(k);
        if (r == null) {
            r = NONE;
            float rv = (ih(ix, iz, 11) & 0xFFFF) / 65535f;
            if (rv < 0.5f) {
                float fx = (ih(ix, iz, 12) & 0xFFFF) / 65535f;
                float fz = (ih(ix, iz, 13) & 0xFFFF) / 65535f;
                float x = (ix + 0.08f + 0.84f * fx) * CCELL;
                float z = (iz + 0.08f + 0.84f * fz) * CCELL;
                if (Math.abs(x) < HALF - 300f && Math.abs(z) < HALF - 300f) {
                    float h = height(x, z);
                    if (h > 2.5f && h < 180f && slope(x, z) < 0.7f) r = new float[]{x, h + 1.1f, z};
                }
            }
            cc.put(k, r);
        }
        return r == NONE ? null : r;
    }

    static int cellOf(float v) {
        return Math.floorDiv((int) Math.floor(v), CCELL);
    }
}
