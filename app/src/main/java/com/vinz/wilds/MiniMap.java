package com.vinz.wilds;

import android.graphics.Bitmap;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Local top-down map (768 m window around the player) generated off-thread. */
final class MiniMap {
    static final int N = 96;
    static final float SPAN = 768f;

    float cx = 1e9f, cz = 1e9f; // centre of the texture currently on the GPU

    private final World world;
    private final Hud hud;
    private final Bitmap bmp = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
    private final ExecutorService ex = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "minimap");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    });
    private volatile int[] readyPx;
    private volatile float readyCx, readyCz;
    private volatile boolean busy = false;
    private float lastReqX = 1e9f, lastReqZ = 1e9f, age = 0f;

    MiniMap(World w, Hud h) {
        world = w;
        hud = h;
    }

    void tick(float px, float pz, float dt) {
        int[] px2 = readyPx;
        if (px2 != null) {
            readyPx = null;
            bmp.setPixels(px2, 0, N, 0, 0, N, N);
            hud.uploadMap(bmp);
            cx = readyCx;
            cz = readyCz;
        }
        age += dt;
        float dx = px - lastReqX, dz = pz - lastReqZ;
        if (!busy && (age > 6f || dx * dx + dz * dz > 100f * 100f)) {
            busy = true;
            age = 0f;
            lastReqX = px;
            lastReqZ = pz;
            final float rx = (float) Math.floor(px / 32f) * 32f;
            final float rz = (float) Math.floor(pz / 32f) * 32f;
            ex.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        build(rx, rz);
                    } catch (Throwable ignored) {
                    }
                    busy = false;
                }
            });
        }
    }

    private static int rgb(float r, float g, float b) {
        int ri = (int) (Math.max(0f, Math.min(1f, r)) * 255f);
        int gi = (int) (Math.max(0f, Math.min(1f, g)) * 255f);
        int bi = (int) (Math.max(0f, Math.min(1f, b)) * 255f);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private void build(float ccx, float ccz) {
        int[] out = new int[N * N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                float x = ccx - SPAN * 0.5f + (i + 0.5f) / N * SPAN;
                float z = ccz + SPAN * 0.5f - (j + 0.5f) / N * SPAN;
                float h = world.height(x, z);
                float hx = world.height(x + 12f, z);
                float shade = Math.max(0.6f, Math.min(1.4f, 1f + (hx - h) * 0.05f));
                float r, g, b;
                if (h < -8f) { r = 0.04f; g = 0.12f; b = 0.30f; }
                else if (h < 0f) { r = 0.10f; g = 0.36f; b = 0.52f; }
                else if (h < 2f) { r = 0.76f; g = 0.70f; b = 0.50f; }
                else if (h < 90f) { r = 0.16f + h * 0.002f; g = 0.38f - h * 0.002f; b = 0.12f; }
                else if (h < 170f) { r = 0.45f; g = 0.43f; b = 0.40f; }
                else { r = 0.92f; g = 0.94f; b = 0.97f; }
                if (h >= 0f) {
                    r *= shade;
                    g *= shade;
                    b *= shade;
                }
                out[j * N + i] = rgb(r, g, b);
            }
        }
        readyCx = ccx;
        readyCz = ccz;
        readyPx = out;
    }
}
