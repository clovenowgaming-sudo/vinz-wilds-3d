package com.vinz.wilds;

/** Procedural model definitions (trees, rocks, grass, crystals, enemies, weapon). */
final class Meshes {
    private Meshes() {}

    static MeshBuilder pine(int q) {
        MeshBuilder b = new MeshBuilder(11);
        int seg = q == 0 ? 6 : q == 1 ? 8 : 10;
        b.cyl(0f, 0f, 0f, 0.30f, 0.14f, 2.6f, seg, 0.22f, 0.13f, 0.07f, 0.13f, 0.08f, 0.045f);
        b.cone(0f, 1.4f, 0f, 2.0f, 2.8f, seg, 0.03f, 0.13f, 0.05f, 0.35f);
        b.cone(0f, 2.8f, 0f, 1.6f, 2.5f, seg, 0.04f, 0.16f, 0.06f, 0.35f);
        b.cone(0f, 4.1f, 0f, 1.2f, 2.2f, seg, 0.05f, 0.19f, 0.07f, 0.35f);
        b.cone(0f, 5.2f, 0f, 0.75f, 2.0f, seg, 0.07f, 0.23f, 0.09f, 0.35f);
        return b;
    }

    static MeshBuilder oak(int q) {
        MeshBuilder b = new MeshBuilder(23);
        int seg = q == 0 ? 6 : 8;
        int la = q == 0 ? 4 : q == 1 ? 5 : 7;
        int lo = q == 0 ? 6 : q == 1 ? 8 : 10;
        b.cyl(0f, 0f, 0f, 0.34f, 0.20f, 3.0f, seg, 0.24f, 0.16f, 0.09f, 0.15f, 0.10f, 0.06f);
        b.blob(0f, 3.9f, 0f, 2.2f, 1.8f, 2.2f, la, lo, 0.07f, 0.22f, 0.04f, 0.30f, 0f);
        b.blob(1.2f, 3.3f, 0.5f, 1.6f, 1.4f, 1.6f, la, lo, 0.10f, 0.28f, 0.05f, 0.30f, 0f);
        b.blob(-1.1f, 3.4f, -0.6f, 1.5f, 1.3f, 1.5f, la, lo, 0.06f, 0.19f, 0.04f, 0.30f, 0f);
        return b;
    }

    static MeshBuilder rock(int q) {
        MeshBuilder b = new MeshBuilder(37);
        b.rock(1.2f, 0.9f, 1.1f, 4 + q, 6 + 2 * q, 0.30f, 0.29f, 0.28f);
        return b;
    }

    static MeshBuilder grass() {
        MeshBuilder b = new MeshBuilder(5);
        float[] ang = {0f, 1.1f, 2.2f, 3.4f};
        float[] hh = {0.8f, 1.0f, 0.65f, 0.9f};
        float[] ox = {0f, 0.12f, -0.10f, 0.05f};
        float[] oz = {0f, -0.08f, 0.10f, 0.12f};
        for (int i = 0; i < 4; i++) b.blade(ox[i], oz[i], ang[i], hh[i], 0.07f);
        return b;
    }

    static MeshBuilder crystal() {
        MeshBuilder b = new MeshBuilder(3);
        b.crystalMesh(0.65f, 0.28f, 6, 0.30f, 0.90f, 1.0f);
        return b;
    }

    static MeshBuilder bolt() {
        MeshBuilder b = new MeshBuilder(8);
        b.blob(0f, 0f, 0f, 0.13f, 0.13f, 0.13f, 4, 6, 1.0f, 0.75f, 0.2f, 0f, 1f);
        return b;
    }

    static MeshBuilder enemy(boolean brute) {
        MeshBuilder b = new MeshBuilder(brute ? 77 : 55);
        float cr = brute ? 0.16f : 0.06f, cg = brute ? 0.04f : 0.05f, cb = brute ? 0.04f : 0.09f;
        float bodyR = brute ? 0.85f : 0.60f, bodyH = brute ? 2.2f : 1.9f;
        b.cone(0f, 0.15f, 0f, bodyR, bodyH, 8, cr, cg, cb, 0.2f);
        float sy = brute ? 2.1f : 1.85f;
        float sx = brute ? 0.70f : 0.45f, sh = brute ? 0.50f : 0.35f;
        b.blob(0f, sy, 0f, sx, sh, sh, 5, 8, cr * 1.4f, cg * 1.4f, cb * 1.4f, 0.2f, 0f);
        float hy = brute ? 2.65f : 2.35f;
        float hr = brute ? 0.36f : 0.30f;
        b.blob(0f, hy, 0.05f, hr, hr, hr, 5, 8, 0.45f, 0.45f, 0.50f, 0.1f, 0f);
        float er = 1f, eg = brute ? 0.45f : 0.12f, eb = 0.05f;
        b.box(-0.12f, hy + 0.03f, 0.33f, 0.06f, 0.035f, 0.03f, er, eg, eb, 1f);
        b.box(0.12f, hy + 0.03f, 0.33f, 0.06f, 0.035f, 0.03f, er, eg, eb, 1f);
        float ax = brute ? 0.85f : 0.55f;
        b.box(-ax, 1.5f, 0.25f, 0.08f, 0.6f, 0.08f, cr * 1.2f, cg * 1.2f, cb * 1.2f, 0f);
        b.box(ax, 1.5f, 0.25f, 0.08f, 0.6f, 0.08f, cr * 1.2f, cg * 1.2f, cb * 1.2f, 0f);
        if (brute) {
            b.cone(-0.22f, hy + 0.25f, 0f, 0.08f, 0.40f, 5, 0.80f, 0.75f, 0.65f, 0.1f);
            b.cone(0.22f, hy + 0.25f, 0f, 0.08f, 0.40f, 5, 0.80f, 0.75f, 0.65f, 0.1f);
            b.box(0f, 1.8f, 0.55f, 0.35f, 0.25f, 0.06f, 0.30f, 0.06f, 0.05f, 0.4f);
        }
        return b;
    }

    /** Weapon in view space, barrel pointing toward -Z. */
    static MeshBuilder gun() {
        MeshBuilder b = new MeshBuilder(1);
        b.box(0f, 0f, 0f, 0.035f, 0.05f, 0.22f, 0.10f, 0.11f, 0.13f, 0f);
        b.box(0f, 0.01f, -0.36f, 0.018f, 0.018f, 0.20f, 0.20f, 0.21f, 0.24f, 0f);
        b.box(0f, -0.09f, 0.10f, 0.025f, 0.07f, 0.035f, 0.06f, 0.06f, 0.07f, 0f);
        b.box(0f, 0.058f, -0.02f, 0.012f, 0.008f, 0.15f, 0.2f, 0.9f, 1.0f, 1f);
        b.box(0f, 0.075f, 0.08f, 0.02f, 0.02f, 0.04f, 0.15f, 0.16f, 0.18f, 0f);
        b.box(0.037f, 0.0f, 0.0f, 0.004f, 0.02f, 0.12f, 0.2f, 0.9f, 1.0f, 1f);
        b.box(-0.037f, 0.0f, 0.0f, 0.004f, 0.02f, 0.12f, 0.2f, 0.9f, 1.0f, 1f);
        return b;
    }

    /** Other players in multiplayer. Faces +Z. */
    static MeshBuilder avatar() {
        MeshBuilder b = new MeshBuilder(91);
        b.box(0f, 0.45f, 0f, 0.20f, 0.45f, 0.14f, 0.10f, 0.16f, 0.22f, 0f);      // legs
        b.box(0f, 1.20f, 0f, 0.27f, 0.38f, 0.17f, 0.12f, 0.30f, 0.50f, 0f);      // torso
        b.box(0f, 1.35f, -0.20f, 0.18f, 0.28f, 0.07f, 0.08f, 0.10f, 0.14f, 0f);  // backpack
        b.box(0f, 1.38f, -0.275f, 0.05f, 0.18f, 0.01f, 0.2f, 0.9f, 1.0f, 1f);    // pack glow
        b.box(-0.36f, 1.15f, 0.05f, 0.08f, 0.34f, 0.09f, 0.10f, 0.22f, 0.36f, 0f);
        b.box(0.36f, 1.15f, 0.05f, 0.08f, 0.34f, 0.09f, 0.10f, 0.22f, 0.36f, 0f);
        b.blob(0f, 1.86f, 0f, 0.20f, 0.22f, 0.20f, 5, 8, 0.75f, 0.62f, 0.52f, 0.05f, 0f);  // head
        b.box(0f, 1.90f, 0.17f, 0.16f, 0.06f, 0.05f, 0.2f, 0.9f, 1.0f, 1f);      // visor
        return b;
    }
}
