package com.vinz.wilds;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Batched 2D HUD renderer (units: screen height = 10). */
final class Hud {
    private static final int CW = 32, CH = 40, COLS = 16, ROWS = 6;
    private static final int AW = COLS * CW, AH = ROWS * CH;
    private static final int MAXV = 6 * 4000;
    private static final String[] QNAME = {"LOW", "MEDIUM", "EXTREME"};

    private Prog prog;
    private int atlas, mapTex, vao, vbo;
    private final float[] buf = new float[MAXV * 8];
    private final FloatBuffer fb = ByteBuffer.allocateDirect(MAXV * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private int nv = 0;
    private float su, sv;
    private final float[] tin = new float[4], tout = new float[4];

    void init() {
        prog = new Prog(Shaders.VH + Shaders.HUD_VS, Shaders.FH + Shaders.HUD_FS);

        Bitmap bmp = Bitmap.createBitmap(AW, AH, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextSize(30f);
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < COLS * ROWS - 1; i++) {
            int col = i % COLS, row = i / COLS;
            cv.drawText(String.valueOf((char) (32 + i)), col * CW + CW / 2f, row * CH + 31f, p);
        }
        int last = COLS * ROWS - 1;
        int lc = last % COLS, lr = last / COLS;
        cv.drawRect(lc * CW + 4, lr * CH + 4, lc * CW + CW - 4, lr * CH + CH - 4, p);
        su = (lc * CW + CW / 2f) / AW;
        sv = (lr * CH + CH / 2f) / AH;
        atlas = makeTex(bmp);
        Bitmap blank = Bitmap.createBitmap(MiniMap.N, MiniMap.N, Bitmap.Config.ARGB_8888);
        blank.eraseColor(0xFF0A1020);
        mapTex = makeTex(blank);

        int[] t = new int[1];
        GLES30.glGenVertexArrays(1, t, 0);
        vao = t[0];
        GLES30.glBindVertexArray(vao);
        GLES30.glGenBuffers(1, t, 0);
        vbo = t[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MAXV * 8 * 4, null, GLES30.GL_DYNAMIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 32, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 32, 8);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, 32, 16);
        GLES30.glBindVertexArray(0);
    }

    private static int makeTex(Bitmap bmp) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
        return t[0];
    }

    /** Replace the minimap texture contents (GL thread). */
    void uploadMap(Bitmap bmp) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mapTex);
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bmp);
    }

    private void bindTex(int t) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t);
    }

    void begin(float aspect) {
        prog.use();
        prog.f1("uAspect", aspect);
        prog.i1("uTex", 0);
        bindTex(atlas);
        GLES30.glBindVertexArray(vao);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        nv = 0;
    }

    void end() {
        flush();
        GLES30.glBindVertexArray(0);
    }

    private void flush() {
        if (nv == 0) return;
        fb.clear();
        fb.put(buf, 0, nv * 8);
        fb.position(0);
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, nv * 32, fb);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, nv);
        nv = 0;
    }

    private void vtx(float x, float y, float u, float v, float r, float g, float b, float a) {
        int o = nv * 8;
        buf[o] = x; buf[o + 1] = y; buf[o + 2] = u; buf[o + 3] = v;
        buf[o + 4] = r; buf[o + 5] = g; buf[o + 6] = b; buf[o + 7] = a;
        nv++;
    }

    private void room(int n) {
        if (nv + n > MAXV) flush();
    }

    void tri(float x0, float y0, float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        room(3);
        vtx(x0, y0, su, sv, r, g, b, a);
        vtx(x1, y1, su, sv, r, g, b, a);
        vtx(x2, y2, su, sv, r, g, b, a);
    }

    void quad(float x, float y, float w, float h, float r, float g, float b, float a) {
        quadUV(x, y, w, h, su, sv, su, sv, r, g, b, a);
    }

    void quadUV(float x, float y, float w, float h, float u0, float v0, float u1, float v1,
                float r, float g, float b, float a) {
        room(6);
        vtx(x, y, u0, v0, r, g, b, a);
        vtx(x + w, y, u1, v0, r, g, b, a);
        vtx(x + w, y + h, u1, v1, r, g, b, a);
        vtx(x, y, u0, v0, r, g, b, a);
        vtx(x + w, y + h, u1, v1, r, g, b, a);
        vtx(x, y + h, u0, v1, r, g, b, a);
    }

    void line(float x0, float y0, float x1, float y1, float th, float r, float g, float b, float a) {
        float dx = x1 - x0, dy = y1 - y0;
        float l = (float) Math.sqrt(dx * dx + dy * dy);
        if (l < 1e-5f) return;
        float nx = -dy / l * th * 0.5f, ny = dx / l * th * 0.5f;
        room(6);
        vtx(x0 + nx, y0 + ny, su, sv, r, g, b, a);
        vtx(x1 + nx, y1 + ny, su, sv, r, g, b, a);
        vtx(x1 - nx, y1 - ny, su, sv, r, g, b, a);
        vtx(x0 + nx, y0 + ny, su, sv, r, g, b, a);
        vtx(x1 - nx, y1 - ny, su, sv, r, g, b, a);
        vtx(x0 - nx, y0 - ny, su, sv, r, g, b, a);
    }

    void circle(float cx, float cy, float rad, int seg, float r, float g, float b, float a) {
        for (int i = 0; i < seg; i++) {
            float a0 = (float) (i * 2.0 * Math.PI / seg), a1 = (float) ((i + 1) * 2.0 * Math.PI / seg);
            tri(cx, cy, cx + (float) Math.cos(a0) * rad, cy + (float) Math.sin(a0) * rad,
                    cx + (float) Math.cos(a1) * rad, cy + (float) Math.sin(a1) * rad, r, g, b, a);
        }
    }

    void ring(float cx, float cy, float rad, float th, int seg, float r, float g, float b, float a) {
        for (int i = 0; i < seg; i++) {
            float a0 = (float) (i * 2.0 * Math.PI / seg), a1 = (float) ((i + 1) * 2.0 * Math.PI / seg);
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float ro = rad, ri = rad - th;
            tri(cx + c0 * ro, cy + s0 * ro, cx + c1 * ro, cy + s1 * ro, cx + c1 * ri, cy + s1 * ri, r, g, b, a);
            tri(cx + c0 * ro, cy + s0 * ro, cx + c1 * ri, cy + s1 * ri, cx + c0 * ri, cy + s0 * ri, r, g, b, a);
        }
    }

    /** align: 0 left, 1 center, 2 right. y = top of text. */
    void text(String s, float x, float y, float h, float r, float g, float b, float a, int align) {
        float adv = h * 0.58f;
        float total = adv * s.length();
        float x0 = align == 0 ? x : align == 1 ? x - total * 0.5f : x - total;
        for (int pass = 0; pass < 2; pass++) {
            float off = pass == 0 ? h * 0.06f : 0f;
            float cr = pass == 0 ? 0f : r, cg = pass == 0 ? 0f : g, cb = pass == 0 ? 0f : b;
            float ca = pass == 0 ? a * 0.7f : a;
            for (int i = 0; i < s.length(); i++) {
                int ci = s.charAt(i) - 32;
                if (ci < 0 || ci > 94) ci = '?' - 32;
                int col = ci % COLS, row = ci / COLS;
                float u0 = (float) (col * CW) / AW, v0 = (float) (row * CH) / AH;
                float u1 = (float) (col * CW + CW) / AW, v1 = (float) (row * CH + CH) / AH;
                float cx = x0 + adv * (i + 0.5f) + off;
                quadUV(cx - h * 0.4f, y + off, h * 0.8f, h, u0, v0, u1, v1, cr, cg, cb, ca);
            }
        }
    }

    private void bar(float x, float y, float w, float h, float frac, float r, float g, float b) {
        quad(x - 0.04f, y - 0.04f, w + 0.08f, h + 0.08f, 0f, 0f, 0f, 0.55f);
        quad(x, y, w, h, 0.12f, 0.12f, 0.14f, 0.8f);
        if (frac > 0f) quad(x, y, w * Math.min(1f, frac), h, r, g, b, 0.95f);
    }

    /** Projects a world point to HUD units. Returns false if off screen / behind camera. */
    private boolean project(float[] vp, float x, float y, float z, float W, float[] out) {
        tin[0] = x;
        tin[1] = y;
        tin[2] = z;
        tin[3] = 1f;
        Matrix.multiplyMV(tout, 0, vp, 0, tin, 0);
        if (tout[3] <= 0.1f) return false;
        float nx = tout[0] / tout[3], ny = tout[1] / tout[3];
        if (nx < -1f || nx > 1f || ny < -1f || ny > 1f) return false;
        out[0] = (nx * 0.5f + 0.5f) * W;
        out[1] = (1f - (ny * 0.5f + 0.5f)) * Input.H;
        return true;
    }

    private final float[] pt = new float[2];

    void draw(Game g, Input in, float[] vp, float fps, Config cfg, float time, MiniMap mm) {
        float W = in.W, H = Input.H;
        Net net = g.net;

        // ---- screen effects
        if (g.hurt > 0f) quad(0, 0, W, H, 0.9f, 0.05f, 0.05f, g.hurt * 0.35f);
        if (g.health < 30f && !g.dead) quad(0, 0, W, H, 0.8f, 0f, 0f, 0.10f + 0.08f * (float) Math.sin(time * 7f));
        if (g.muzzle > 0f) quad(W * 0.5f, H * 0.5f, W * 0.5f, H * 0.5f, 1f, 0.75f, 0.35f, g.muzzle * 0.10f);

        // ---- enemy health bars
        for (int i = 0; i < Game.MAXE; i++) {
            if (!g.ealive[i]) continue;
            float dx = g.ex[i] - g.px, dz = g.ez[i] - g.pz;
            if (dx * dx + dz * dz > 40f * 40f) continue;
            if (!project(vp, g.ex[i], g.ey[i] + 2.9f * g.escale[i], g.ez[i], W, pt)) continue;
            float w = 0.9f * g.escale[i];
            bar(pt[0] - w * 0.5f, pt[1], w, 0.09f, g.ehp[i] / g.emax[i], 0.9f, 0.15f, 0.15f);
        }

        // ---- name tags of other players
        for (int i = 0; i < g.nRemote; i++) {
            float dx = g.rX[i] - g.px, dz = g.rZ[i] - g.pz;
            if (dx * dx + dz * dz > 160f * 160f) continue;
            if (!project(vp, g.rX[i], g.rY[i] + 2.35f, g.rZ[i], W, pt)) continue;
            text(g.rName[i], pt[0], pt[1] - 0.3f, 0.3f, 0.5f, 1f, 0.9f, 1f, 1);
        }

        // ---- crosshair
        float cx = W * 0.5f, cy = H * 0.5f;
        float gap = 0.12f + (in.firing ? 0.10f : 0f) + g.recoil * 0.12f;
        float len = 0.22f, th = 0.035f;
        quad(cx - gap - len, cy - th * 0.5f, len, th, 1f, 1f, 1f, 0.9f);
        quad(cx + gap, cy - th * 0.5f, len, th, 1f, 1f, 1f, 0.9f);
        quad(cx - th * 0.5f, cy - gap - len, th, len, 1f, 1f, 1f, 0.9f);
        quad(cx - th * 0.5f, cy + gap, th, len, 1f, 1f, 1f, 0.9f);
        quad(cx - 0.025f, cy - 0.025f, 0.05f, 0.05f, 1f, 0.3f, 0.2f, 1f);
        if (g.hitMark > 0f) {
            float a = Math.min(1f, g.hitMark * 6f);
            line(cx - 0.30f, cy - 0.30f, cx - 0.14f, cy - 0.14f, 0.05f, 1f, 0.2f, 0.2f, a);
            line(cx + 0.30f, cy - 0.30f, cx + 0.14f, cy - 0.14f, 0.05f, 1f, 0.2f, 0.2f, a);
            line(cx - 0.30f, cy + 0.30f, cx - 0.14f, cy + 0.14f, 0.05f, 1f, 0.2f, 0.2f, a);
            line(cx + 0.30f, cy + 0.30f, cx + 0.14f, cy + 0.14f, 0.05f, 1f, 0.2f, 0.2f, a);
        }

        // ---- minimap (local window around the player)
        float ms = 2.6f, mx = 0.35f, my = 0.35f;
        quad(mx - 0.07f, my - 0.07f, ms + 0.14f, ms + 0.14f, 0.02f, 0.03f, 0.05f, 0.8f);
        flush();
        bindTex(mapTex);
        quadUV(mx, my, ms, ms, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f);
        flush();
        bindTex(atlas);
        for (int i = 0; i < g.nMapCrys; i++) {
            float u = (g.mapCrys[i * 2] - mm.cx) / MiniMap.SPAN + 0.5f;
            float v = 0.5f - (g.mapCrys[i * 2 + 1] - mm.cz) / MiniMap.SPAN;
            if (u < 0f || u > 1f || v < 0f || v > 1f) continue;
            circle(mx + u * ms, my + v * ms, 0.07f, 8, 1f, 0.85f, 0.2f, 1f);
        }
        for (int i = 0; i < Game.MAXE; i++) {
            if (!g.ealive[i]) continue;
            float u = (g.ex[i] - mm.cx) / MiniMap.SPAN + 0.5f;
            float v = 0.5f - (g.ez[i] - mm.cz) / MiniMap.SPAN;
            if (u < 0f || u > 1f || v < 0f || v > 1f) continue;
            circle(mx + u * ms, my + v * ms, 0.06f, 6, 1f, 0.15f, 0.15f, 1f);
        }
        for (int i = 0; i < g.nRemote; i++) {
            float u = (g.rX[i] - mm.cx) / MiniMap.SPAN + 0.5f;
            float v = 0.5f - (g.rZ[i] - mm.cz) / MiniMap.SPAN;
            if (u < 0f || u > 1f || v < 0f || v > 1f) continue;
            circle(mx + u * ms, my + v * ms, 0.07f, 8, 0.3f, 0.6f, 1f, 1f);
        }
        {
            float u = clamp01((g.px - mm.cx) / MiniMap.SPAN + 0.5f);
            float v = clamp01(0.5f - (g.pz - mm.cz) / MiniMap.SPAN);
            float sx = mx + u * ms, sy = my + v * ms;
            float s0 = (float) Math.sin(g.yaw), c0 = (float) Math.cos(g.yaw);
            float s1 = (float) Math.sin(g.yaw + 2.5f), c1 = (float) Math.cos(g.yaw + 2.5f);
            float s2 = (float) Math.sin(g.yaw - 2.5f), c2 = (float) Math.cos(g.yaw - 2.5f);
            tri(sx + s0 * 0.22f, sy - c0 * 0.22f, sx + s1 * 0.15f, sy - c1 * 0.15f, sx + s2 * 0.15f, sy - c2 * 0.15f,
                    0.3f, 1f, 1f, 1f);
        }
        text("X " + (int) g.px + "  Z " + (int) g.pz, mx, my + ms + 0.12f, 0.24f, 1f, 1f, 1f, 0.85f, 0);

        // ---- top center info
        text("CRYSTALS " + g.crystalsGot, W * 0.5f, 0.2f, 0.46f, 0.5f, 0.95f, 1f, 1f, 1);
        int mins = (int) (((g.dayT * 24f + 6f) % 24f) * 60f);
        String clock = String.format("%02d:%02d", (mins / 60) % 24, mins % 60);
        text("DAY " + g.day + "  " + clock + "  KILLS " + g.kills, W * 0.5f, 0.72f, 0.30f, 1f, 1f, 1f, 0.9f, 1);
        if (g.hasTarget) {
            float ax = W * 0.5f, ay = 1.55f, s = 0.34f;
            float a = -g.compassRel;
            float t0 = (float) Math.sin(a), t1 = (float) -Math.cos(a);
            float l0 = (float) Math.sin(a + 2.5f), l1 = (float) -Math.cos(a + 2.5f);
            float r0 = (float) Math.sin(a - 2.5f), r1 = (float) -Math.cos(a - 2.5f);
            tri(ax + t0 * s, ay + t1 * s, ax + l0 * s * 0.7f, ay + l1 * s * 0.7f, ax + r0 * s * 0.7f, ay + r1 * s * 0.7f,
                    1f, 0.85f, 0.2f, 0.95f);
            String dist = g.compassDist >= 1000f ? String.format("%.1f KM", g.compassDist / 1000f) : (int) g.compassDist + " M";
            text(dist, ax, ay + 0.42f, 0.26f, 1f, 0.9f, 0.4f, 0.95f, 1);
        }

        // ---- top right
        float ty = 0.25f;
        if (cfg.showFps) {
            text("FPS " + (int) fps, W - 0.3f, ty, 0.30f, 1f, 1f, 1f, 0.85f, 2);
            ty += 0.40f;
        }
        text(QNAME[cfg.q], W - 0.3f, ty, 0.26f, 0.7f, 0.9f, 1f, 0.8f, 2);
        ty += 0.36f;
        if (net != null) {
            if (net.connected && net.welcomed) {
                text("ONLINE " + net.online + (net.ping >= 0 ? "  " + net.ping + " MS" : ""), W - 0.3f, ty, 0.26f, 0.5f, 1f, 0.6f, 0.9f, 2);
            } else {
                text(net.status, W - 0.3f, ty, 0.26f, 1f, 0.6f, 0.3f, 0.95f, 2);
            }
        }

        // ---- bottom bars
        float bw = 6.4f, bx = W * 0.5f - bw * 0.5f;
        float hf = g.health / 100f;
        bar(bx, 9.1f, bw, 0.34f, hf, 1f - hf * 0.7f, 0.25f + hf * 0.65f, 0.2f);
        text("HP " + (int) g.health, bx + 0.15f, 9.1f, 0.30f, 1f, 1f, 1f, 1f, 0);
        bar(bx, 9.56f, bw, 0.14f, g.stamina / 100f, 0.95f, 0.8f, 0.2f);
        String ammoStr = g.reloadT > 0f ? "RELOADING" : "AMMO " + (int) g.ammo + "/30";
        text(ammoStr, bx + bw + 0.3f, 9.08f, 0.38f, 1f, 0.95f, 0.7f, 1f, 0);

        // ---- joystick
        float jx = in.joyActive ? in.joyCX : Input.JOY_DEF_X;
        float jy = in.joyActive ? in.joyCY : Input.JOY_DEF_Y;
        float ja = in.joyActive ? 1f : 0.55f;
        circle(jx, jy, Input.JOY_R, 28, 1f, 1f, 1f, 0.10f * ja);
        ring(jx, jy, Input.JOY_R, 0.06f, 28, 1f, 1f, 1f, 0.35f * ja);
        float kx = in.joyActive ? in.joyKX : jx, ky = in.joyActive ? in.joyKY : jy;
        circle(kx, ky, 0.7f, 20, 1f, 1f, 1f, 0.28f * ja);

        // ---- buttons
        button(in.fire, "FIRE", in.firing);
        button(in.jump, "JUMP", false);
        button(in.reload, "R", g.reloadT > 0f);

        // ---- multiplayer: mic / chat buttons, chat feed, who is talking
        if (net != null) {
            micButton(in.mic, net.micOn, net.micDenied);
            button(in.chat, "CHAT", false);
            int cn = net.chatSnapshot(chatLines, chatAges);
            float cyl = 0.35f;
            int maxc = Math.max(12, (int) ((W * 0.5f - 3.45f - 0.7f) / (0.27f * 0.58f)));
            for (int i = 0; i < cn; i++) {
                float age = chatAges[i];
                if (age > 14f) continue;
                float ca = age > 10f ? (14f - age) / 4f : 1f;
                String cs = chatLines[i];
                if (cs.length() > maxc) cs = cs.substring(0, maxc);
                text(cs, 3.45f, cyl, 0.27f, 1f, 1f, 1f, ca, 0);
                cyl += 0.34f;
            }
            long nowMs = SystemClock.elapsedRealtime();
            StringBuilder who = new StringBuilder();
            for (java.util.Map.Entry<Integer, Long> en : net.speaking.entrySet()) {
                if (nowMs - en.getValue() > 500L) continue;
                String nm = net.names.get(en.getKey());
                if (who.length() > 0) who.append(", ");
                who.append(nm != null ? nm : "P" + en.getKey());
            }
            if (who.length() > 0) {
                String ws = "VOICE: " + who;
                if (ws.length() > maxc) ws = ws.substring(0, maxc);
                text(ws, 3.45f, cyl + 0.05f, 0.25f, 0.5f, 1f, 0.7f, 1f, 0);
            }
        }

        // ---- message / overlays
        if (g.msgT > 0f && !g.dead) {
            float a = Math.min(1f, g.msgT);
            text(g.msg, W * 0.5f, 2.55f, 0.5f, 1f, 1f, 1f, a, 1);
        }
        if (g.dead) {
            quad(0, 0, W, H, 0.3f, 0f, 0f, 0.55f);
            text("YOU DIED", W * 0.5f, 3.6f, 1.3f, 1f, 0.25f, 0.2f, 1f, 1);
            if (g.deadT > 1.2f) text("TAP TO RESPAWN", W * 0.5f, 5.4f, 0.55f, 1f, 1f, 1f, 0.9f, 1);
        }
        flush();
    }

    private final String[] chatLines = new String[8];
    private final float[] chatAges = new float[8];

    private void micButton(float[] b, boolean on, boolean denied) {
        float r = on ? 0.2f : 0.9f, g = on ? 0.85f : 0.25f, bl = on ? 0.3f : 0.25f;
        circle(b[0], b[1], b[2], 24, r, g, bl, on ? 0.55f : 0.35f);
        ring(b[0], b[1], b[2], 0.06f, 24, 1f, 1f, 1f, 0.5f);
        float th = b[2] * 0.55f;
        text("MIC", b[0], b[1] - th * 0.8f, th, 1f, 1f, 1f, 0.95f, 1);
        text(denied ? "DENIED" : (on ? "ON" : "OFF"), b[0], b[1] + th * 0.05f, th * 0.7f, 1f, 1f, 1f, 0.9f, 1);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private void button(float[] b, String label, boolean pressed) {
        circle(b[0], b[1], b[2], 28, 1f, 1f, 1f, pressed ? 0.40f : 0.16f);
        ring(b[0], b[1], b[2], 0.06f, 28, 1f, 1f, 1f, 0.45f);
        float th = b[2] * 0.42f;
        text(label, b[0], b[1] - th * 0.5f, th, 1f, 1f, 1f, 0.95f, 1);
    }
}
