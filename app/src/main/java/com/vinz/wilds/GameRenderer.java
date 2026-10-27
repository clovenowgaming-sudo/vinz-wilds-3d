package com.vinz.wilds;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Renders the streamed world with OpenGL ES 3.0. Q: 0 low, 1 medium, 2 extreme. */
public final class GameRenderer implements GLSurfaceView.Renderer {
    interface Callback {
        void ready();

        void error(String s);

        void progress(String s);

        void chat();

        void toggleMic();
    }

    private final Config cfg;
    private final int Q;
    private final Input in;
    private final Net net;
    private final Callback cb;

    private World world;
    private Game game;
    private ChunkManager cm;
    private Hud hud;
    private MiniMap miniMap;
    private boolean failed = false, readyFired = false, started = false, spawned = false;
    private int lastPct = -1;
    private float loadTime = 0f;

    private int W = 1, H = 1;
    private float aspect = 2f;

    private Prog pTerr, pObj, pWater, pSky, pGun, pShTerr, pShObj, pBright, pBlur, pComp;
    private Geo gPine, gOak, gRock, gGrass, gCrystal, gWraith, gBrute, gBolt, gGun, gAvatar;
    private int emptyVao;
    private int texShadow, fboShadow;
    private int fboScene, texScene, rboScene, fboB1, texB1, fboB2, texB2, bw, bh;
    private boolean shadowOn;
    private int shadowSize;
    private float shadowR, viewDist, treeCull, grassCull;

    private final float[] proj = new float[16], view = new float[16], vp = new float[16], invVp = new float[16];
    private final float[] lightVP = new float[16], lv = new float[16], lp = new float[16];
    private final float[] gunProj = new float[16], gunM = new float[16];
    private final float[] tmp4 = new float[4], tmpO = new float[4], tmpL = new float[4];
    private final float[] planes = new float[24];

    // lighting state
    private final float[] sunD = new float[3], lightD = new float[3], lightC = new float[3];
    private final float[] ambS = new float[3], ambG = new float[3], skyZ = new float[3], skyH = new float[3];
    private final float[] sunDisc = new float[3], sunTint = new float[3];
    private float day, night, sunVis, fogD;

    private long last = 0;
    private float fpsAcc = 0f, fps = 0f;
    private int fpsFrames = 0;

    GameRenderer(Config config, Input input, Net network, Callback callback) {
        cfg = config;
        Q = config.q;
        in = input;
        in.mp = config.mp;
        net = network;
        cb = callback;
    }

    // ---------------------------------------------------------------------------------- lifecycle
    @Override
    public void onSurfaceCreated(GL10 gl10, EGLConfig config) {
        try {
            init();
            failed = false;
        } catch (Throwable t) {
            failed = true;
            if (cb != null) cb.error(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        started = false;
        lastPct = -1;
        last = SystemClock.elapsedRealtimeNanos();
    }

    @Override
    public void onSurfaceChanged(GL10 gl10, int w, int h) {
        W = w;
        H = h;
        aspect = (float) w / (float) h;
        GLES30.glViewport(0, 0, w, h);
        if (!failed && Q == 2) {
            try {
                buildPostTargets();
            } catch (Throwable t) {
                failed = true;
                if (cb != null) cb.error("FBO: " + t.getMessage());
            }
        }
    }

    private void init() {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        GLES30.glDisable(GLES30.GL_CULL_FACE);

        shadowOn = cfg.shadows && Q > 0;
        shadowSize = Q == 2 ? 2048 : 1024;
        shadowR = Q == 2 ? 70f : 55f;
        float vm = Math.max(0.5f, Math.min(1.5f, cfg.view));
        viewDist = (Q == 0 ? 700f : Q == 1 ? 1100f : 1800f) * vm;
        treeCull = (Q == 0 ? 220f : Q == 1 ? 320f : 450f) * Math.min(vm, 1.3f);
        grassCull = Q == 1 ? 38f : 62f;

        if (world == null) {
            world = new World(Q);
            game = new Game(world, cfg, net);
        }
        if (cm != null) cm.dispose();
        cm = new ChunkManager(world, Q, viewDist, treeCull + 130f, Q == 1 ? 150f : 220f, cfg.grass && Q >= 1);
        game.cm = cm;

        pTerr = new Prog(Shaders.VH + Shaders.TERRAIN_VS, Shaders.FH + Shaders.COMMON + Shaders.TERRAIN_FS);
        pObj = new Prog(Shaders.VH + Shaders.OBJ_VS, Shaders.FH + Shaders.COMMON + Shaders.OBJ_FS);
        pWater = new Prog(Shaders.VH + Shaders.WATER_VS, Shaders.FH + Shaders.COMMON + Shaders.WATER_FS);
        pSky = new Prog(Shaders.VH + Shaders.SKY_VS, Shaders.FH + Shaders.COMMON + Shaders.SKY_FS);
        pGun = new Prog(Shaders.VH + Shaders.GUN_VS, Shaders.FH + Shaders.GUN_FS);
        if (shadowOn) {
            pShTerr = new Prog(Shaders.VH + Shaders.SH_TERR_VS, Shaders.SH_FS);
            pShObj = new Prog(Shaders.VH + "#define SHADOW\n" + Shaders.OBJ_VS, Shaders.SH_FS);
        }
        if (Q == 2) {
            pBright = new Prog(Shaders.VH + Shaders.QUAD_VS, Shaders.FH + Shaders.BRIGHT_FS);
            pBlur = new Prog(Shaders.VH + Shaders.QUAD_VS, Shaders.FH + Shaders.BLUR_FS);
            pComp = new Prog(Shaders.VH + Shaders.QUAD_VS, Shaders.FH + Shaders.COMP_FS);
        }

        buildGeometry();
        if (shadowOn) buildShadowTarget();

        int[] t = new int[1];
        GLES30.glGenVertexArrays(1, t, 0);
        emptyVao = t[0];

        hud = new Hud();
        hud.init();
        miniMap = new MiniMap(world, hud);
    }

    // ---------------------------------------------------------------------------------- geometry
    private void buildGeometry() {
        gPine = new Geo(Meshes.pine(Q), cm.gP.length / 5);
        gOak = new Geo(Meshes.oak(Q), cm.gO.length / 5);
        gRock = new Geo(Meshes.rock(Q), cm.gR.length / 5);
        gGrass = (Q >= 1 && cfg.grass) ? new Geo(Meshes.grass(), cm.gG.length / 5) : null;
        gCrystal = new Geo(Meshes.crystal(), 16);
        gWraith = new Geo(Meshes.enemy(false), Game.MAXE);
        gBrute = new Geo(Meshes.enemy(true), Game.MAXE);
        gBolt = new Geo(Meshes.bolt(), Game.MAXB);
        gAvatar = new Geo(Meshes.avatar(), Game.MAXP);
        gGun = new Geo(Meshes.gun(), 0);
    }

    private void buildShadowTarget() {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        texShadow = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texShadow);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, shadowSize, shadowSize, 0,
                GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL);
        GLES30.glGenFramebuffers(1, t, 0);
        fboShadow = t[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboShadow);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, texShadow, 0);
        GLES30.glDrawBuffers(1, new int[]{GLES30.GL_NONE}, 0);
        GLES30.glReadBuffer(GLES30.GL_NONE);
        int st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) throw new RuntimeException("Shadow FBO incomplete: " + st);
    }

    private int makeColorTex(int w, int h) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private int makeFbo(int colorTex, int depthRbo) {
        int[] t = new int[1];
        GLES30.glGenFramebuffers(1, t, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, colorTex, 0);
        if (depthRbo != 0) {
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depthRbo);
        }
        int st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) throw new RuntimeException("FBO incomplete: " + st);
        return t[0];
    }

    private void buildPostTargets() {
        if (fboScene != 0) {
            GLES30.glDeleteFramebuffers(3, new int[]{fboScene, fboB1, fboB2}, 0);
            GLES30.glDeleteTextures(3, new int[]{texScene, texB1, texB2}, 0);
            GLES30.glDeleteRenderbuffers(1, new int[]{rboScene}, 0);
        }
        int[] t = new int[1];
        texScene = makeColorTex(W, H);
        GLES30.glGenRenderbuffers(1, t, 0);
        rboScene = t[0];
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rboScene);
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, W, H);
        fboScene = makeFbo(texScene, rboScene);
        bw = Math.max(1, W / 4);
        bh = Math.max(1, H / 4);
        texB1 = makeColorTex(bw, bh);
        fboB1 = makeFbo(texB1, 0);
        texB2 = makeColorTex(bw, bh);
        fboB2 = makeFbo(texB2, 0);
    }

    // ---------------------------------------------------------------------------------- per-frame
    private boolean loadStep(float dt) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, W, H);
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        loadTime += dt;
        if (net != null && !spawned) {
            if (!net.welcomed) {
                if (lastPct != -2) {
                    lastPct = -2;
                    cb.progress("CONNECTING TO SERVER...");
                }
                if (loadTime > 15f) {
                    failed = true;
                    cb.error("Tidak bisa konek ke server:\n" + cfg.url + "\n(" + net.status + ")\n\nPastikan server jalan dan route Cloudflare sudah benar.");
                }
                return false;
            }
            game.setSpawn(net.spawnX, net.spawnZ);
            spawned = true;
            loadTime = 0f;
        }
        cm.update(game.px, game.pz, dt, true);
        cm.uploadReady(4);
        int pct = cm.progress(game.px, game.pz, 300f);
        if (pct != lastPct) {
            lastPct = pct;
            cb.progress("LOADING WORLD " + pct + "%");
        }
        if (pct >= 100 || loadTime > 90f) {
            started = true;
            return true;
        }
        return false;
    }

    @Override
    public void onDrawFrame(GL10 gl10) {
        if (failed) {
            GLES30.glClearColor(0.15f, 0f, 0f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        long now = SystemClock.elapsedRealtimeNanos();
        float dt = (now - last) / 1e9f;
        last = now;
        if (dt > 0.1f || dt < 0f) dt = 0.016f;
        try {
            if (!started && !loadStep(dt)) return;
            fpsAcc += dt;
            fpsFrames++;
            if (fpsAcc >= 0.5f) {
                fps = fpsFrames / fpsAcc;
                fpsAcc = 0f;
                fpsFrames = 0;
            }
            cm.update(game.px, game.pz, dt, false);
            cm.uploadReady(Q == 0 ? 1 : 2);
            game.update(dt, in);
            if (game.chatReq) {
                game.chatReq = false;
                cb.chat();
            }
            if (game.micToggleReq) {
                game.micToggleReq = false;
                cb.toggleMic();
            }
            miniMap.tick(game.px, game.pz, dt);
            updateCamera();
            updateLighting();
            if (cm.gatherDue(game.px, game.pz)) {
                cm.gather(game.px, game.pz, treeCull, grassCull);
                gPine.setInstances(cm.gP, cm.nP);
                gOak.setInstances(cm.gO, cm.nO);
                gRock.setInstances(cm.gR, cm.nR);
                if (gGrass != null) gGrass.setInstances(cm.gG, cm.nG);
            }
            if (shadowOn) renderShadow();
            renderMain();
            if (Q == 2) post();
            renderHud();
        } catch (Throwable t) {
            failed = true;
            if (cb != null) cb.error("Frame: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return;
        }
        if (!readyFired) {
            readyFired = true;
            if (cb != null) cb.ready();
        }
    }

    private void updateCamera() {
        float ex = game.px, ey = game.camY(), ez = game.pz;
        float cp = (float) Math.cos(game.pitch);
        float fx = cp * (float) Math.sin(game.yaw), fy = (float) Math.sin(game.pitch), fz = cp * (float) Math.cos(game.yaw);
        Matrix.setLookAtM(view, 0, ex, ey, ez, ex + fx, ey + fy, ez + fz, 0f, 1f, 0f);
        Matrix.perspectiveM(proj, 0, 56f, aspect, 0.25f, viewDist + 500f);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        Matrix.invertM(invVp, 0, vp, 0);
        extractPlanes(vp);
    }

    private void extractPlanes(float[] m) {
        for (int i = 0; i < 3; i++) {
            for (int s = 0; s < 2; s++) {
                float sg = s == 0 ? 1f : -1f;
                int o = (i * 2 + s) * 4;
                planes[o] = m[3] + sg * m[i];
                planes[o + 1] = m[7] + sg * m[4 + i];
                planes[o + 2] = m[11] + sg * m[8 + i];
                planes[o + 3] = m[15] + sg * m[12 + i];
                float l = (float) Math.sqrt(planes[o] * planes[o] + planes[o + 1] * planes[o + 1] + planes[o + 2] * planes[o + 2]);
                if (l > 1e-6f) {
                    planes[o] /= l;
                    planes[o + 1] /= l;
                    planes[o + 2] /= l;
                    planes[o + 3] /= l;
                }
            }
        }
    }

    private boolean inFrustum(ChunkManager.Chunk c) {
        float cx = c.ox + 64f, cz = c.oz + 64f, cy = (c.minH + c.maxH) * 0.5f;
        float hh = (c.maxH - c.minH) * 0.5f;
        float r = (float) Math.sqrt(64f * 64f * 2f + hh * hh) + 2f;
        for (int i = 0; i < 6; i++) {
            int o = i * 4;
            if (planes[o] * cx + planes[o + 1] * cy + planes[o + 2] * cz + planes[o + 3] < -r) return false;
        }
        return true;
    }

    private static float clamp(float v, float a, float b) {
        return v < a ? a : Math.min(v, b);
    }

    private static void mix3(float[] o, float a0, float a1, float a2, float b0, float b1, float b2, float t) {
        o[0] = a0 + (b0 - a0) * t;
        o[1] = a1 + (b1 - a1) * t;
        o[2] = a2 + (b2 - a2) * t;
    }

    private void updateLighting() {
        Game.sunDir(game.dayT, sunD);
        float sy = sunD[1];
        day = clamp((sy + 0.12f) / 0.45f, 0f, 1f);
        night = 1f - day;
        sunVis = World.smooth(-0.04f, 0.05f, sy);
        float warm = 1f - World.smooth(0f, 0.38f, sy);
        mix3(sunTint, 1.0f, 0.97f, 0.92f, 1.0f, 0.52f, 0.22f, warm);
        sunDisc[0] = sunTint[0];
        sunDisc[1] = sunTint[1];
        sunDisc[2] = sunTint[2];
        float sunInt = World.smooth(-0.02f, 0.15f, sy);
        float moonInt = clamp((-0.02f - sy) / 0.28f, 0f, 1f);
        if (sy > -0.02f) {
            lightD[0] = sunD[0]; lightD[1] = sunD[1]; lightD[2] = sunD[2];
            lightC[0] = sunTint[0] * 5.2f * sunInt;
            lightC[1] = sunTint[1] * 5.2f * sunInt;
            lightC[2] = sunTint[2] * 5.2f * sunInt;
        } else {
            lightD[0] = -sunD[0]; lightD[1] = -sunD[1]; lightD[2] = -sunD[2];
            lightC[0] = 0.30f * moonInt;
            lightC[1] = 0.42f * moonInt;
            lightC[2] = 0.95f * moonInt;
        }
        float dusk = Math.max(0f, 1f - Math.abs(sy) / 0.30f);
        mix3(skyZ, 0.008f, 0.012f, 0.035f, 0.10f, 0.27f, 0.62f, day);
        skyZ[0] += 0.14f * dusk * 0.35f;
        skyZ[1] += 0.05f * dusk * 0.35f;
        skyZ[2] += 0.10f * dusk * 0.35f;
        mix3(skyH, 0.025f, 0.035f, 0.08f, 0.60f, 0.74f, 0.90f, day);
        skyH[0] += 1.0f * dusk * 0.55f;
        skyH[1] += 0.42f * dusk * 0.55f;
        skyH[2] += 0.18f * dusk * 0.55f;
        mix3(ambS, 0.035f, 0.050f, 0.115f, 0.40f, 0.52f, 0.78f, day);
        mix3(ambG, 0.012f, 0.016f, 0.030f, 0.16f, 0.15f, 0.12f, day);
        fogD = (3.3f / viewDist) * (1f + dusk * 0.35f);
    }

    private void common(Prog p) {
        p.f3("uCam", game.px, game.camY(), game.pz);
        p.f1("uTime", game.time);
        p.f3v("uSunDir", sunD);
        p.f3v("uLightDir", lightD);
        p.f3v("uLightCol", lightC);
        p.f3v("uAmbSky", ambS);
        p.f3v("uAmbGnd", ambG);
        p.f3v("uSkyZen", skyZ);
        p.f3v("uSkyHor", skyH);
        p.f3v("uSunDisc", sunDisc);
        p.f3v("uSunTint", sunTint);
        p.f1("uDay", day);
        p.f1("uNight", night);
        p.f1("uSunVis", sunVis);
        p.f1("uFog", fogD);
        p.i1("uQ", Q);
        p.f1("uShadowOn", shadowOn ? 1f : 0f);
        p.f1("uTexel", shadowOn ? 1f / shadowSize : 0f);
        p.mat4("uLightVP", lightVP);
        p.i1("uShadow", 1);
    }

    private void objDraw(Prog p, Geo g, int n, float cull, float sway, float bob, float fade,
                         float rough, float metal, float wrap, float emit, float two) {
        if (n <= 0 || g == null) return;
        p.f1("uCull", cull);
        p.f1("uSway", sway);
        p.f1("uBob", bob);
        p.f1("uFadeScale", fade);
        p.f1("uRough", rough);
        p.f1("uMetal", metal);
        p.f1("uWrap", wrap);
        p.f1("uEmit", emit);
        p.f1("uTwoSided", two);
        g.draw(n);
    }

    // ---------------------------------------------------------------------------------- shadows
    private void renderShadow() {
        float R = shadowR;
        float cx = game.px, cy = game.py, cz = game.pz;
        float dist = R + 140f;
        Matrix.setLookAtM(lv, 0, cx + lightD[0] * dist, cy + lightD[1] * dist, cz + lightD[2] * dist,
                cx, cy, cz, 0f, 1f, 0f);
        tmp4[0] = cx; tmp4[1] = cy; tmp4[2] = cz; tmp4[3] = 1f;
        Matrix.multiplyMV(tmpO, 0, lv, 0, tmp4, 0);
        float texel = 2f * R / shadowSize;
        float sx = (float) Math.floor(tmpO[0] / texel + 0.5f) * texel;
        float sy = (float) Math.floor(tmpO[1] / texel + 0.5f) * texel;
        Matrix.orthoM(lp, 0, sx - R, sx + R, sy - R, sy + R, 1f, dist + R + 250f);
        Matrix.multiplyMM(lightVP, 0, lp, 0, lv, 0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboShadow);
        GLES30.glViewport(0, 0, shadowSize, shadowSize);
        GLES30.glDepthMask(true);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL);
        GLES30.glPolygonOffset(2f, 4f);

        pShTerr.use();
        pShTerr.mat4("uLightVP", lightVP);
        float lim = R + 220f;
        for (ChunkManager.Chunk c : cm.chunks.values()) {
            float dx = c.ox + 64f - cx, dz = c.oz + 64f - cz;
            if (dx * dx + dz * dz > lim * lim) continue;
            pShTerr.f2("uChunk", c.ox, c.oz);
            GLES30.glBindVertexArray(c.vao);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, cm.totalIdx[c.lod], GLES30.GL_UNSIGNED_SHORT, 0);
        }

        pShObj.use();
        pShObj.mat4("uLightVP", lightVP);
        pShObj.f3("uCam", game.px, game.camY(), game.pz);
        pShObj.f1("uTime", game.time);
        float c = R + 25f;
        objDraw(pShObj, gPine, cm.nP, c, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        objDraw(pShObj, gOak, cm.nO, c, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        objDraw(pShObj, gRock, cm.nR, c, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        if (game.nWraith > 0) {
            gWraith.setInstances(game.wraithInst, game.nWraith);
            objDraw(pShObj, gWraith, game.nWraith, c, 0f, 0.08f, 0f, 0f, 0f, 0f, 0f, 0f);
        }
        if (game.nBrute > 0) {
            gBrute.setInstances(game.bruteInst, game.nBrute);
            objDraw(pShObj, gBrute, game.nBrute, c, 0f, 0.05f, 0f, 0f, 0f, 0f, 0f, 0f);
        }
        if (game.nAvatar > 0) {
            gAvatar.setInstances(game.avatarInst, game.nAvatar);
            objDraw(pShObj, gAvatar, game.nAvatar, c, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL);
        GLES30.glBindVertexArray(0);
    }

    // ---------------------------------------------------------------------------------- main pass
    private void renderMain() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, Q == 2 ? fboScene : 0);
        GLES30.glViewport(0, 0, W, H);
        GLES30.glDepthMask(true);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowOn ? texShadow : 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);

        // sky
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        pSky.use();
        common(pSky);
        pSky.mat4("uInvVP", invVp);
        GLES30.glBindVertexArray(emptyVao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);

        // terrain chunks
        pTerr.use();
        common(pTerr);
        pTerr.mat4("uVP", vp);
        for (ChunkManager.Chunk c : cm.chunks.values()) {
            if (!inFrustum(c)) continue;
            pTerr.f2("uChunk", c.ox, c.oz);
            GLES30.glBindVertexArray(c.vao);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, cm.totalIdx[c.lod], GLES30.GL_UNSIGNED_SHORT, 0);
        }

        // objects
        pObj.use();
        common(pObj);
        pObj.mat4("uVP", vp);
        objDraw(pObj, gPine, cm.nP, treeCull, 1f, 0f, 0f, 0.9f, 0f, 0.35f, 0f, 0f);
        objDraw(pObj, gOak, cm.nO, treeCull, 1f, 0f, 0f, 0.9f, 0f, 0.35f, 0f, 0f);
        objDraw(pObj, gRock, cm.nR, treeCull, 0f, 0f, 0f, 0.85f, 0f, 0f, 0f, 0f);
        if (gGrass != null) {
            objDraw(pObj, gGrass, cm.nG, grassCull, 35f, 0f, 1f, 0.8f, 0f, 0.5f, 0f, 1f);
        }
        if (game.nCrystal > 0) {
            gCrystal.setInstances(game.crystalInst, game.nCrystal);
            objDraw(pObj, gCrystal, game.nCrystal, 700f, 0f, 0.25f, 0f, 0.2f, 0.5f, 0f, 2.5f, 0f);
        }
        if (game.nWraith > 0) {
            gWraith.setInstances(game.wraithInst, game.nWraith);
            objDraw(pObj, gWraith, game.nWraith, 400f, 0f, 0.08f, 0f, 0.6f, 0f, 0.2f, 3f, 0f);
        }
        if (game.nBrute > 0) {
            gBrute.setInstances(game.bruteInst, game.nBrute);
            objDraw(pObj, gBrute, game.nBrute, 400f, 0f, 0.05f, 0f, 0.6f, 0f, 0.2f, 3f, 0f);
        }
        if (game.nAvatar > 0) {
            gAvatar.setInstances(game.avatarInst, game.nAvatar);
            objDraw(pObj, gAvatar, game.nAvatar, 600f, 0f, 0f, 0f, 0.6f, 0.1f, 0.2f, 1.5f, 0f);
        }
        if (game.nBolt > 0) {
            gBolt.setInstances(game.boltInst, game.nBolt);
            objDraw(pObj, gBolt, game.nBolt, 500f, 0f, 0f, 0f, 0.3f, 0f, 0f, 4f, 0f);
        }

        // water (per chunk, depth stored in the vertices)
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDepthMask(false);
        pWater.use();
        common(pWater);
        pWater.mat4("uVP", vp);
        for (ChunkManager.Chunk c : cm.chunks.values()) {
            if (!c.hasWater || c.wVao == 0 || !inFrustum(c)) continue;
            pWater.f2("uChunk", c.ox, c.oz);
            GLES30.glBindVertexArray(c.wVao);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, cm.gridIdx[c.lod], GLES30.GL_UNSIGNED_SHORT, 0);
        }
        GLES30.glDepthMask(true);
        GLES30.glDisable(GLES30.GL_BLEND);

        // weapon
        if (!game.dead) drawGun();
        GLES30.glBindVertexArray(0);
    }

    private void drawGun() {
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        Matrix.perspectiveM(gunProj, 0, 50f, aspect, 0.02f, 5f);
        Matrix.setIdentityM(gunM, 0);
        Matrix.translateM(gunM, 0, 0.26f, -0.24f + game.bobY * 0.8f, -0.52f + game.recoil * 0.07f);
        Matrix.rotateM(gunM, 0, game.recoil * 4f, 1f, 0f, 0f);
        Matrix.rotateM(gunM, 0, -3f, 0f, 1f, 0f);
        tmpL[0] = lightD[0]; tmpL[1] = lightD[1]; tmpL[2] = lightD[2]; tmpL[3] = 0f;
        Matrix.multiplyMV(tmpO, 0, view, 0, tmpL, 0);
        float lvl = clamp((lightC[0] + lightC[1] + lightC[2]) / 12f, 0.07f, 1f);
        pGun.use();
        pGun.mat4("uProj", gunProj);
        pGun.mat4("uModel", gunM);
        pGun.f3("uLv", tmpO[0], tmpO[1], tmpO[2]);
        pGun.f1("uLevel", lvl);
        gGun.drawOne();
    }

    // ---------------------------------------------------------------------------------- post
    private void fullscreen() {
        GLES30.glBindVertexArray(emptyVao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    private void post() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB1);
        GLES30.glViewport(0, 0, bw, bh);
        pBright.use();
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texScene);
        pBright.i1("uTex", 0);
        pBright.f2("uInv", 1.5f / W, 1.5f / H);
        fullscreen();

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB2);
        pBlur.use();
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texB1);
        pBlur.i1("uTex", 0);
        pBlur.f2("uDir", 1.5f / bw, 0f);
        fullscreen();

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texB2);
        pBlur.f2("uDir", 0f, 1.5f / bh);
        fullscreen();

        float sxu = 0.5f, syu = 0.5f, shaft = 0f;
        tmp4[0] = game.px + sunD[0] * 1000f;
        tmp4[1] = game.camY() + sunD[1] * 1000f;
        tmp4[2] = game.pz + sunD[2] * 1000f;
        tmp4[3] = 1f;
        Matrix.multiplyMV(tmpO, 0, vp, 0, tmp4, 0);
        if (tmpO[3] > 0.01f) {
            float nx = tmpO[0] / tmpO[3], ny = tmpO[1] / tmpO[3];
            sxu = nx * 0.5f + 0.5f;
            syu = ny * 0.5f + 0.5f;
            float dx = sxu - 0.5f, dy = syu - 0.5f;
            shaft = sunVis * clamp(1.3f - (float) Math.sqrt(dx * dx + dy * dy) * 1.1f, 0f, 1f) * 0.9f;
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, W, H);
        pComp.use();
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texScene);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texB1);
        pComp.i1("uScene", 0);
        pComp.i1("uBloom", 1);
        pComp.f2("uInv", 1f / W, 1f / H);
        pComp.f2("uSun", sxu, syu);
        pComp.f1("uShaft", shaft);
        pComp.f1("uBloomK", 0.65f);
        pComp.f1("uTime", game.time % 100f);
        fullscreen();
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    }

    // ---------------------------------------------------------------------------------- hud
    private void renderHud() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, W, H);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        hud.begin(aspect);
        hud.draw(game, in, vp, fps, cfg, game.time, miniMap);
        hud.end();
        GLES30.glDisable(GLES30.GL_BLEND);
    }
}
