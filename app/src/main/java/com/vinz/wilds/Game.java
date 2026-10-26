package com.vinz.wilds;

import java.util.Random;

/** Game state + simulation: player, weapon, wraiths, crystals, day/night. */
final class Game {
    static final float DAY_LEN = 300f;
    static final int MAXE = 24, MAXB = 48, NC = World.NC;
    static final float EYE = 1.7f;
    static final float SENS = 0.157f; // radians per HUD unit of drag (~9 degrees)

    final World world;
    final int Q;
    private final Random rnd = new Random();

    // player
    float px, py, pz, vy, yaw = 0.6f, pitch = -0.05f;
    boolean onGround = true, swimming = false;
    float health = 100f, stamina = 100f, ammo = 30f;
    float reloadT = 0f, fireCd = 0f, recoil = 0f, muzzle = 0f, hurt = 0f, hitMark = 0f, sinceHurt = 99f;
    float walkPhase = 0f, bobY = 0f;
    boolean dead = false, won = false;
    float deadT = 0f;

    // world state
    float time = 0f, dayT = 0.06f;
    int day = 1, crystalsGot = 0, kills = 0, score = 0;
    final boolean[] got = new boolean[NC];
    String msg = "";
    float msgT = 0f;
    float spawnT = 3f;

    // enemies
    final float[] ex = new float[MAXE], ey = new float[MAXE], ez = new float[MAXE];
    final float[] ehp = new float[MAXE], emax = new float[MAXE], eyaw = new float[MAXE];
    final float[] ecd = new float[MAXE], espd = new float[MAXE], escale = new float[MAXE];
    final int[] etype = new int[MAXE];
    final boolean[] ealive = new boolean[MAXE];

    // bolts
    final float[] bx = new float[MAXB], by = new float[MAXB], bz = new float[MAXB];
    final float[] bvx = new float[MAXB], bvy = new float[MAXB], bvz = new float[MAXB], blife = new float[MAXB];
    final boolean[] balive = new boolean[MAXB];

    // render instance data
    final float[] wraithInst = new float[MAXE * 5], bruteInst = new float[MAXE * 5];
    final float[] boltInst = new float[MAXB * 5], crystalInst = new float[NC * 5];
    int nWraith, nBrute, nBolt, nCrystal;

    // compass
    float compassRel = 0f, compassDist = 0f;
    boolean hasTarget = false;

    private final float[] sd = new float[3];
    private final float[] xz = new float[2];

    Game(World w, int q) {
        world = w;
        Q = q;
        px = w.spawnX;
        pz = w.spawnZ;
        py = Math.max(w.height(px, pz), 0f);
        say("FIND THE 12 AETHER CRYSTALS", 6f);
    }

    static void sunDir(float dayT, float[] o) {
        float a = dayT * 6.2831853f;
        float x = (float) Math.cos(a) * 0.85f, y = (float) Math.sin(a), z = 0.5f;
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        o[0] = x / l;
        o[1] = y / l;
        o[2] = z / l;
    }

    float camY() {
        return py + EYE + bobY;
    }

    void say(String s, float t) {
        msg = s;
        msgT = t;
    }

    int aliveCount() {
        int n = 0;
        for (int i = 0; i < MAXE; i++) if (ealive[i]) n++;
        return n;
    }

    void update(float dt, Input in) {
        if (dt > 0.05f) dt = 0.05f;
        if (dt <= 0f) return;
        in.poll();
        time += dt;
        dayT += dt / DAY_LEN;
        if (dayT >= 1f) {
            dayT -= 1f;
            day++;
            say("DAY " + day + " BEGINS", 4f);
        }
        sunDir(dayT, sd);
        boolean night = sd[1] < 0.02f;

        if (msgT > 0f) msgT -= dt;
        muzzle = Math.max(0f, muzzle - dt * 9f);
        recoil = Math.max(0f, recoil - dt * 7f);
        hurt = Math.max(0f, hurt - dt * 1.6f);
        hitMark = Math.max(0f, hitMark - dt);

        if (dead) {
            deadT += dt;
            if (in.oTap && deadT > 1.2f) respawn();
            prepareRender();
            return;
        }

        // ---- look
        yaw -= in.oLookX * SENS;
        pitch -= in.oLookY * SENS;
        if (pitch > 1.45f) pitch = 1.45f;
        if (pitch < -1.45f) pitch = -1.45f;

        // ---- movement
        float mx = in.moveX, my = in.moveY;
        float mag = (float) Math.sqrt(mx * mx + my * my);
        float amt = 0f;
        if (mag > 0.1f) {
            amt = Math.min(1f, (mag - 0.1f) / 0.8f);
            mx /= mag;
            my /= mag;
        } else {
            mx = 0f;
            my = 0f;
        }
        float gh = world.height(px, pz);
        swimming = gh < -0.5f;
        boolean sprint = mag > 0.93f && stamina > 4f && !swimming;
        float spd = swimming ? 3.0f : (sprint ? 8.6f : 5.2f);
        spd *= amt;
        float fx = (float) Math.sin(yaw), fz = (float) Math.cos(yaw);
        float rx = -fz, rz = fx;
        float vx = (fx * my + rx * mx) * spd;
        float vz = (fz * my + rz * mx) * spd;
        if (spd > 0.01f) {
            float nx = px + vx * dt, nz = pz + vz * dt;
            float reach = py + 0.75f;
            if (world.height(nx, nz) < reach) {
                px = nx;
                pz = nz;
            } else if (world.height(nx, pz) < reach) {
                px = nx;
            } else if (world.height(px, nz) < reach) {
                pz = nz;
            }
            xz[0] = px;
            xz[1] = pz;
            world.resolve(xz, 0.4f);
            px = xz[0];
            pz = xz[1];
            float rr = (float) Math.sqrt(px * px + pz * pz);
            float lim = World.HALF - 25f;
            if (rr > lim) {
                px *= lim / rr;
                pz *= lim / rr;
            }
        }
        if (sprint && spd > 0.5f) stamina = Math.max(0f, stamina - 22f * dt);
        else stamina = Math.min(100f, stamina + 16f * dt);

        // ---- vertical
        float floor = Math.max(world.height(px, pz), -1.0f);
        if (in.oJump && onGround) {
            vy = 6.2f;
            onGround = false;
        }
        vy -= 18f * dt;
        py += vy * dt;
        if (py <= floor) {
            py = floor;
            vy = 0f;
            onGround = true;
        } else if (py - floor > 0.08f) {
            onGround = false;
        }
        if (onGround && spd > 0.5f) {
            walkPhase += dt * spd * 1.5f;
            bobY = (float) Math.sin(walkPhase) * 0.04f * Math.min(1f, spd / 6f);
        } else {
            bobY *= 0.9f;
        }

        // ---- weapon
        if (reloadT > 0f) {
            reloadT -= dt;
            if (reloadT <= 0f) ammo = 30f;
        }
        fireCd -= dt;
        if (in.oReload && reloadT <= 0f && ammo < 30f) startReload();
        if (in.firing && reloadT <= 0f && fireCd <= 0f) {
            if (ammo >= 1f) shoot();
            else startReload();
        }

        updateBolts(dt);

        // ---- crystals
        for (int i = 0; i < NC; i++) {
            if (got[i]) continue;
            float cx = world.crystals[i * 3], cy = world.crystals[i * 3 + 1], cz = world.crystals[i * 3 + 2];
            float dx = cx - px, dz = cz - pz;
            if (dx * dx + dz * dz < 2.6f * 2.6f && Math.abs(cy - py - 1f) < 4f) {
                got[i] = true;
                crystalsGot++;
                score += 100;
                health = Math.min(100f, health + 15f);
                Sfx.play(Sfx.PICK);
                if (crystalsGot >= NC && !won) {
                    won = true;
                    say("ISLAND RESTORED - KEEP SURVIVING!", 8f);
                } else {
                    say("AETHER CRYSTAL " + crystalsGot + "/" + NC, 3f);
                }
            }
        }

        // ---- enemies
        spawnT -= dt;
        if (spawnT <= 0f) {
            spawnT = night ? 2.2f : 7f;
            int lim = Q == 0 ? 10 : Q == 1 ? 16 : MAXE;
            int cap = night ? Math.min(lim, 4 + day * 2) : Math.min(2, day - 1);
            if (aliveCount() < cap) spawnEnemy();
        }
        updateEnemies(dt, night);

        // ---- regen / death
        sinceHurt += dt;
        if (sinceHurt > 6f && health < 100f) health = Math.min(100f, health + 2.5f * dt);
        if (health <= 0f) {
            health = 0f;
            dead = true;
            deadT = 0f;
            Sfx.play(Sfx.DIE);
            say("YOU DIED", 5f);
        }

        prepareRender();
    }

    private void startReload() {
        if (reloadT > 0f) return;
        reloadT = 1.6f;
        Sfx.play(Sfx.RELOAD);
    }

    private void shoot() {
        int s = -1;
        for (int i = 0; i < MAXB; i++) {
            if (!balive[i]) {
                s = i;
                break;
            }
        }
        if (s < 0) return;
        float cp = (float) Math.cos(pitch);
        float dx = cp * (float) Math.sin(yaw), dy = (float) Math.sin(pitch), dz = cp * (float) Math.cos(yaw);
        float sp = 0.012f;
        dx += (rnd.nextFloat() - 0.5f) * sp;
        dy += (rnd.nextFloat() - 0.5f) * sp;
        dz += (rnd.nextFloat() - 0.5f) * sp;
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= l;
        dy /= l;
        dz /= l;
        float rx = -(float) Math.cos(yaw), rz = (float) Math.sin(yaw);
        bx[s] = px + dx * 0.8f + rx * 0.18f;
        by[s] = py + EYE - 0.12f + dy * 0.8f;
        bz[s] = pz + dz * 0.8f + rz * 0.18f;
        bvx[s] = dx * 115f;
        bvy[s] = dy * 115f;
        bvz[s] = dz * 115f;
        blife[s] = 1.6f;
        balive[s] = true;
        ammo -= 1f;
        fireCd = 0.11f;
        recoil = 1f;
        muzzle = 1f;
        pitch += 0.008f;
        Sfx.play(Sfx.SHOT);
    }

    private void updateBolts(float dt) {
        for (int i = 0; i < MAXB; i++) {
            if (!balive[i]) continue;
            blife[i] -= dt;
            if (blife[i] <= 0f) {
                balive[i] = false;
                continue;
            }
            for (int st = 0; st < 3 && balive[i]; st++) {
                float k = dt / 3f;
                bx[i] += bvx[i] * k;
                by[i] += bvy[i] * k;
                bz[i] += bvz[i] * k;
                if (by[i] < world.height(bx[i], bz[i])) {
                    balive[i] = false;
                    break;
                }
                for (int e = 0; e < MAXE; e++) {
                    if (!ealive[e]) continue;
                    float cy = ey[e] + 1.2f * escale[e];
                    float dx = bx[i] - ex[e], dy = by[i] - cy, dz = bz[i] - ez[e];
                    float r = 0.85f * escale[e] + 0.15f;
                    if (dx * dx + dy * dy + dz * dz < r * r) {
                        balive[i] = false;
                        ehp[e] -= 25f;
                        hitMark = 0.2f;
                        Sfx.play(Sfx.HIT);
                        if (ehp[e] <= 0f) killEnemy(e, true);
                        break;
                    }
                }
            }
        }
    }

    private void spawnEnemy() {
        int s = -1;
        for (int i = 0; i < MAXE; i++) {
            if (!ealive[i]) {
                s = i;
                break;
            }
        }
        if (s < 0) return;
        for (int t = 0; t < 8; t++) {
            float ang = rnd.nextFloat() * 6.2832f;
            float d = 38f + rnd.nextFloat() * 30f;
            float x = px + (float) Math.cos(ang) * d, z = pz + (float) Math.sin(ang) * d;
            float h = world.height(x, z);
            if (h < 0.8f || h > 60f) continue;
            if (Math.abs(x) > World.HALF - 20f || Math.abs(z) > World.HALF - 20f) continue;
            boolean brute = day >= 2 && rnd.nextFloat() < 0.25f;
            ealive[s] = true;
            etype[s] = brute ? 1 : 0;
            ex[s] = x;
            ey[s] = h;
            ez[s] = z;
            escale[s] = brute ? 1.6f : 1f;
            emax[s] = (brute ? 220f : 60f) + 12f * (day - 1);
            ehp[s] = emax[s];
            espd[s] = brute ? 3.2f : 4.6f + rnd.nextFloat() * 0.8f;
            ecd[s] = 0.4f;
            eyaw[s] = 0f;
            return;
        }
    }

    private void killEnemy(int i, boolean reward) {
        ealive[i] = false;
        if (reward) {
            kills++;
            score += etype[i] == 1 ? 50 : 10;
            health = Math.min(100f, health + 6f);
            Sfx.play(Sfx.DIE);
        }
    }

    private void updateEnemies(float dt, boolean night) {
        boolean burn = sd[1] > 0.2f;
        for (int i = 0; i < MAXE; i++) {
            if (!ealive[i]) continue;
            float dx = px - ex[i], dz = pz - ez[i];
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d > 130f) {
                ealive[i] = false;
                continue;
            }
            if (burn) {
                ehp[i] -= (etype[i] == 0 ? 10f : 4f) * dt;
                if (ehp[i] <= 0f) {
                    killEnemy(i, false);
                    continue;
                }
            }
            eyaw[i] = (float) Math.atan2(dx, dz);
            float reach = 1.7f + 0.5f * (escale[i] - 1f);
            if (d > reach) {
                float sp = espd[i];
                float nx = ex[i] + dx / d * sp * dt, nz = ez[i] + dz / d * sp * dt;
                float nh = world.height(nx, nz);
                if (nh > -0.4f && nh - ey[i] < 0.9f * sp * dt + 0.3f) {
                    ex[i] = nx;
                    ez[i] = nz;
                } else {
                    float sx = ex[i] - dz / d * sp * dt, sz = ez[i] + dx / d * sp * dt;
                    if (world.height(sx, sz) > -0.4f) {
                        ex[i] = sx;
                        ez[i] = sz;
                    }
                }
                ecd[i] = Math.min(ecd[i], 0.4f);
            } else {
                ecd[i] -= dt;
                if (ecd[i] <= 0f) {
                    ecd[i] = 0.9f;
                    health -= etype[i] == 1 ? 22f : 12f;
                    hurt = 1f;
                    sinceHurt = 0f;
                    Sfx.play(Sfx.HURT);
                }
            }
            ey[i] = world.height(ex[i], ez[i]);
        }
    }

    private void respawn() {
        px = world.spawnX;
        pz = world.spawnZ;
        py = Math.max(world.height(px, pz), 0f);
        vy = 0f;
        health = 100f;
        stamina = 100f;
        ammo = 30f;
        reloadT = 0f;
        dead = false;
        sinceHurt = 99f;
        for (int i = 0; i < MAXE; i++) ealive[i] = false;
        for (int i = 0; i < MAXB; i++) balive[i] = false;
        dayT = 0.05f;
        spawnT = 6f;
        say("RESPAWNED", 3f);
    }

    private void prepareRender() {
        nWraith = 0;
        nBrute = 0;
        for (int i = 0; i < MAXE; i++) {
            if (!ealive[i]) continue;
            float[] a;
            int n;
            if (etype[i] == 0) {
                a = wraithInst;
                n = nWraith++;
            } else {
                a = bruteInst;
                n = nBrute++;
            }
            int o = n * 5;
            a[o] = ex[i];
            a[o + 1] = ey[i];
            a[o + 2] = ez[i];
            a[o + 3] = escale[i];
            a[o + 4] = eyaw[i];
        }
        nBolt = 0;
        for (int i = 0; i < MAXB; i++) {
            if (!balive[i]) continue;
            int o = nBolt++ * 5;
            boltInst[o] = bx[i];
            boltInst[o + 1] = by[i];
            boltInst[o + 2] = bz[i];
            boltInst[o + 3] = 1f;
            boltInst[o + 4] = 0f;
        }
        nCrystal = 0;
        float best = 1e9f;
        int bi = -1;
        for (int i = 0; i < NC; i++) {
            if (got[i]) continue;
            int o = nCrystal++ * 5;
            float cx = world.crystals[i * 3], cy = world.crystals[i * 3 + 1], cz = world.crystals[i * 3 + 2];
            crystalInst[o] = cx;
            crystalInst[o + 1] = cy;
            crystalInst[o + 2] = cz;
            crystalInst[o + 3] = 1.15f;
            crystalInst[o + 4] = time * 1.2f + i;
            float dx = cx - px, dz = cz - pz;
            float d2 = dx * dx + dz * dz;
            if (d2 < best) {
                best = d2;
                bi = i;
            }
        }
        hasTarget = bi >= 0;
        if (hasTarget) {
            float dx = world.crystals[bi * 3] - px, dz = world.crystals[bi * 3 + 2] - pz;
            float bearing = (float) Math.atan2(dx, dz);
            float rel = bearing - yaw;
            while (rel > Math.PI) rel -= 6.2831853f;
            while (rel < -Math.PI) rel += 6.2831853f;
            compassRel = rel;
            compassDist = (float) Math.sqrt(best);
        }
    }
}
