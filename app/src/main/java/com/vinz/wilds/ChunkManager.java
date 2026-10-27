package com.vinz.wilds;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;

/** Streams terrain chunks around the player: generation on worker threads, GL upload on the GL thread. */
final class ChunkManager {
    static final class Chunk {
        int cx, cz, lod;
        float ox, oz;
        int vao, vbo, wVao, wVbo;
        boolean hasWater, obj, grass;
        float minH, maxH;
        float[] pines, oaks, rocks, grassI, circles;
        int nPines, nOaks, nRocks, nGrass, nCircles;
    }

    private static final class Req implements Comparable<Req> {
        final int cx, cz, lod;
        final boolean obj, grass;
        final float pri;

        Req(int cx, int cz, int lod, boolean obj, boolean grass, float pri) {
            this.cx = cx;
            this.cz = cz;
            this.lod = lod;
            this.obj = obj;
            this.grass = grass;
            this.pri = pri;
        }

        @Override
        public int compareTo(Req o) {
            return Float.compare(pri, o.pri);
        }
    }

    private static final float[] T = {250f, 500f, 1000f, 1500f};

    final World world;
    final int Q;
    final float viewDist, objDist, grassDist;
    final boolean grassOn;
    private final int lodBias;

    final HashMap<Long, Chunk> chunks = new HashMap<>();
    private final HashSet<Long> pending = new HashSet<>();
    private final PriorityBlockingQueue<Req> queue = new PriorityBlockingQueue<>();
    private final ConcurrentLinkedQueue<ChunkGen.Data> ready = new ConcurrentLinkedQueue<>();
    private final ArrayList<Thread> workers = new ArrayList<>();
    private volatile boolean running = true;

    final int[] ibo = new int[5];
    final int[] gridIdx = new int[5];
    final int[] totalIdx = new int[5];
    int version = 0;

    private float scanTimer = 0f, lastScanX = 1e9f, lastScanZ = 1e9f;
    private final FloatBuffer upV = ByteBuffer.allocateDirect(27000 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final FloatBuffer upW = ByteBuffer.allocateDirect(13000 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    // gathered instances (rebuilt when chunk set / player position changes)
    final float[] gP, gO, gR, gG;
    int nP, nO, nR, nG;
    private int gatherVersion = -1;
    private float gatherX = 1e9f, gatherZ = 1e9f;

    ChunkManager(World w, int q, float viewDist, float objDist, float grassDist, boolean grassOn) {
        world = w;
        Q = q;
        this.viewDist = viewDist;
        this.objDist = objDist;
        this.grassDist = grassDist;
        this.grassOn = grassOn;
        lodBias = q == 0 ? 1 : 0;
        gP = new float[(q == 2 ? 7000 : q == 1 ? 3500 : 1500) * 5];
        gO = new float[(q == 2 ? 5000 : q == 1 ? 2500 : 1000) * 5];
        gR = new float[(q == 2 ? 1800 : q == 1 ? 900 : 400) * 5];
        gG = new float[(q == 2 ? 9000 : q == 1 ? 4000 : 1) * 5];
        buildIbos();
        int nt = q == 0 ? 1 : 2;
        for (int i = 0; i < nt; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    work();
                }
            }, "chunk-gen-" + i);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
            workers.add(t);
        }
    }

    // ------------------------------------------------------------------ shared index buffers
    private void buildIbos() {
        for (int lod = 0; lod < 5; lod++) {
            int cells = ChunkGen.CELLS[lod];
            int n = cells + 1;
            int g = cells * cells * 6;
            int total = g + 4 * cells * 6;
            short[] idx = new short[total];
            int k = 0;
            for (int j = 0; j < cells; j++) {
                for (int i = 0; i < cells; i++) {
                    int a = j * n + i, b = a + 1, c = a + n, d = c + 1;
                    idx[k++] = (short) a;
                    idx[k++] = (short) c;
                    idx[k++] = (short) b;
                    idx[k++] = (short) b;
                    idx[k++] = (short) c;
                    idx[k++] = (short) d;
                }
            }
            int base = n * n;
            for (int i = 0; i < cells; i++) {
                k = skirtQuad(idx, k, i, i + 1, base + i, base + i + 1);
                k = skirtQuad(idx, k, cells * n + i, cells * n + i + 1, base + n + i, base + n + i + 1);
                k = skirtQuad(idx, k, i * n, (i + 1) * n, base + 2 * n + i, base + 2 * n + i + 1);
                k = skirtQuad(idx, k, i * n + cells, (i + 1) * n + cells, base + 3 * n + i, base + 3 * n + i + 1);
            }
            gridIdx[lod] = g;
            totalIdx[lod] = total;
            ShortBuffer sb = ByteBuffer.allocateDirect(total * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            sb.put(idx);
            sb.position(0);
            int[] t = new int[1];
            GLES30.glGenBuffers(1, t, 0);
            ibo[lod] = t[0];
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[lod]);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, total * 2, sb, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
    }

    private static int skirtQuad(short[] idx, int k, int g0, int g1, int s0, int s1) {
        idx[k++] = (short) g0;
        idx[k++] = (short) s0;
        idx[k++] = (short) g1;
        idx[k++] = (short) g1;
        idx[k++] = (short) s0;
        idx[k++] = (short) s1;
        return k;
    }

    // ------------------------------------------------------------------ worker
    private void work() {
        while (running) {
            Req r;
            try {
                r = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            ChunkGen.Data d;
            try {
                d = ChunkGen.generate(world, r.cx, r.cz, r.lod, r.obj, r.grass, Q);
            } catch (Throwable t) {
                d = new ChunkGen.Data();
                d.cx = r.cx;
                d.cz = r.cz;
                d.key = World.key(r.cx, r.cz);
                d.failed = true;
            }
            ready.add(d);
        }
    }

    private int lodFor(float d, int cur) {
        int raw = 0;
        while (raw < 4 && d > T[raw]) raw++;
        if (cur >= 0) {
            int c = Math.max(0, cur - lodBias);
            if (raw > c && c < 4 && d < T[c] + 40f) raw = c;
            else if (raw < c && c > 0 && d > T[c - 1] - 40f) raw = c;
        }
        return Math.min(4, raw + lodBias);
    }

    private void enqueue(int cx, int cz, int lod, boolean obj, boolean grass, float dist) {
        pending.add(World.key(cx, cz));
        queue.add(new Req(cx, cz, lod, obj, grass, dist));
    }

    /** Decide which chunks should exist / change LOD. Cheap when the player has not moved. */
    void update(float px, float pz, float dt, boolean force) {
        scanTimer -= dt;
        float mdx = px - lastScanX, mdz = pz - lastScanZ;
        if (!force && scanTimer > 0f && mdx * mdx + mdz * mdz < 24f * 24f) return;
        scanTimer = 0.4f;
        lastScanX = px;
        lastScanZ = pz;
        int R = (int) Math.ceil(viewDist / World.CHUNK) + 1;
        int pcx = Math.floorDiv((int) Math.floor(px), World.CHUNK);
        int pcz = Math.floorDiv((int) Math.floor(pz), World.CHUNK);
        int lim = (int) (World.HALF / World.CHUNK);
        for (int dz = -R; dz <= R; dz++) {
            for (int dx = -R; dx <= R; dx++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (cx < -lim || cx >= lim || cz < -lim || cz >= lim) continue;
                float ccx = cx * (float) World.CHUNK + 64f, ccz = cz * (float) World.CHUNK + 64f;
                float ddx = ccx - px, ddz = ccz - pz;
                float d = (float) Math.sqrt(ddx * ddx + ddz * ddz);
                if (d > viewDist + 90f) continue;
                long k = World.key(cx, cz);
                if (pending.contains(k)) continue;
                Chunk c = chunks.get(k);
                boolean wantObj = d < objDist;
                boolean wantGrass = grassOn && d < grassDist;
                if (c == null) {
                    enqueue(cx, cz, lodFor(d, -1), wantObj, wantGrass, d);
                } else {
                    int want = lodFor(d, c.lod);
                    if (c.lod != want || (wantObj && !c.obj) || (wantGrass && !c.grass)) {
                        enqueue(cx, cz, want, wantObj || c.obj, wantGrass || c.grass, d);
                    }
                }
            }
        }
        Iterator<Chunk> it = chunks.values().iterator();
        float far = viewDist + 320f;
        while (it.hasNext()) {
            Chunk c = it.next();
            float dx = c.ox + 64f - px, dz = c.oz + 64f - pz;
            if (dx * dx + dz * dz > far * far) {
                free(c);
                it.remove();
                version++;
            }
        }
    }

    /** Creates GL buffers for finished chunks (call on the GL thread). */
    int uploadReady(int max) {
        int n = 0;
        while (n < max) {
            ChunkGen.Data d = ready.poll();
            if (d == null) break;
            pending.remove(d.key);
            if (d.failed || d.verts == null) continue;
            Chunk c = new Chunk();
            c.cx = d.cx;
            c.cz = d.cz;
            c.lod = d.lod;
            c.ox = d.cx * (float) World.CHUNK;
            c.oz = d.cz * (float) World.CHUNK;
            c.obj = d.obj;
            c.grass = d.grass;
            c.minH = d.minH;
            c.maxH = d.maxH;
            c.hasWater = d.hasWater;
            c.pines = d.pines;
            c.oaks = d.oaks;
            c.rocks = d.rocks;
            c.grassI = d.grassI;
            c.circles = d.circles;
            c.nPines = d.nPines;
            c.nOaks = d.nOaks;
            c.nRocks = d.nRocks;
            c.nGrass = d.nGrass;
            c.nCircles = d.nCircles;
            upload(d, c);
            Chunk old = chunks.put(d.key, c);
            if (old != null) free(old);
            version++;
            n++;
        }
        return n;
    }

    private void upload(ChunkGen.Data d, Chunk c) {
        int[] t = new int[1];
        GLES30.glGenVertexArrays(1, t, 0);
        c.vao = t[0];
        GLES30.glBindVertexArray(c.vao);
        GLES30.glGenBuffers(1, t, 0);
        c.vbo = t[0];
        upV.clear();
        upV.put(d.verts, 0, d.verts.length);
        upV.position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, c.vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, d.verts.length * 4, upV, GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[d.lod]);
        if (d.hasWater && d.water != null) {
            GLES30.glGenVertexArrays(1, t, 0);
            c.wVao = t[0];
            GLES30.glBindVertexArray(c.wVao);
            GLES30.glGenBuffers(1, t, 0);
            c.wVbo = t[0];
            upW.clear();
            upW.put(d.water, 0, d.water.length);
            upW.position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, c.wVbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, d.water.length * 4, upW, GLES30.GL_STATIC_DRAW);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[d.lod]);
        }
        GLES30.glBindVertexArray(0);
    }

    private void free(Chunk c) {
        if (c.vbo != 0) GLES30.glDeleteBuffers(1, new int[]{c.vbo}, 0);
        if (c.vao != 0) GLES30.glDeleteVertexArrays(1, new int[]{c.vao}, 0);
        if (c.wVbo != 0) GLES30.glDeleteBuffers(1, new int[]{c.wVbo}, 0);
        if (c.wVao != 0) GLES30.glDeleteVertexArrays(1, new int[]{c.wVao}, 0);
    }

    void dispose() {
        running = false;
        for (Thread t : workers) t.interrupt();
        queue.clear();
        for (Chunk c : chunks.values()) free(c);
        chunks.clear();
    }

    // ------------------------------------------------------------------ gameplay helpers
    /** Percent of chunks within rad metres that are already uploaded. */
    int progress(float px, float pz, float rad) {
        int R = (int) Math.ceil(rad / World.CHUNK) + 1;
        int pcx = Math.floorDiv((int) Math.floor(px), World.CHUNK);
        int pcz = Math.floorDiv((int) Math.floor(pz), World.CHUNK);
        int need = 0, have = 0;
        for (int dz = -R; dz <= R; dz++) {
            for (int dx = -R; dx <= R; dx++) {
                int cx = pcx + dx, cz = pcz + dz;
                float ddx = cx * (float) World.CHUNK + 64f - px, ddz = cz * (float) World.CHUNK + 64f - pz;
                if (ddx * ddx + ddz * ddz > rad * rad) continue;
                need++;
                if (chunks.containsKey(World.key(cx, cz))) have++;
            }
        }
        return need == 0 ? 100 : have * 100 / need;
    }

    boolean gatherDue(float px, float pz) {
        float dx = px - gatherX, dz = pz - gatherZ;
        return version != gatherVersion || dx * dx + dz * dz > 24f * 24f;
    }

    void gather(float px, float pz, float treeR, float grassR) {
        nP = 0;
        nO = 0;
        nR = 0;
        nG = 0;
        float lim = treeR + 110f, glim = grassR + 110f;
        for (Chunk c : chunks.values()) {
            if (!c.obj) continue;
            float dx = c.ox + 64f - px, dz = c.oz + 64f - pz;
            float d2 = dx * dx + dz * dz;
            if (d2 > lim * lim) continue;
            nP += copy(c.pines, c.nPines, gP, nP);
            nO += copy(c.oaks, c.nOaks, gO, nO);
            nR += copy(c.rocks, c.nRocks, gR, nR);
            if (c.grass && d2 < glim * glim) nG += copy(c.grassI, c.nGrass, gG, nG);
        }
        gatherVersion = version;
        gatherX = px;
        gatherZ = pz;
    }

    private static int copy(float[] src, int n, float[] dst, int at) {
        if (src == null) return 0;
        int cap = dst.length / 5 - at;
        if (n > cap) n = cap;
        if (n <= 0) return 0;
        System.arraycopy(src, 0, dst, at * 5, n * 5);
        return n;
    }

    /** Pushes (xz[0], xz[1]) out of trees and rocks. */
    void resolve(float[] xz, float pr) {
        int cx = Math.floorDiv((int) Math.floor(xz[0]), World.CHUNK);
        int cz = Math.floorDiv((int) Math.floor(xz[1]), World.CHUNK);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Chunk c = chunks.get(World.key(cx + dx, cz + dz));
                if (c == null || c.circles == null) continue;
                for (int i = 0; i < c.nCircles; i++) {
                    float ddx = xz[0] - c.circles[i * 3], ddz = xz[1] - c.circles[i * 3 + 1];
                    float rr = c.circles[i * 3 + 2] + pr;
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
