package com.vinz.wilds;

import android.os.SystemClock;

import java.util.HashSet;
import java.util.Random;

/** Game state + simulation (single player and multiplayer client). */
final class Game {
    static final float DAY_LEN = 300f;
    static final int MAXE = 24, MAXB = 64, MAXP = 24;
    static final float EYE = 1.7f;
    static final float SENS = 0.157f; // radians per HUD unit of drag (~9 degrees)

    final World world;
    final int Q;
    final Config cfg;
    final Net net;
    ChunkManager cm;
    private final Random rnd = new Random();

    // player
    float px, py, pz, vy, yaw = 0.6f, pitch = -0.05f;
    boolean onGround = true, swimming = false;
    float health = 100f, stamina = 100f, ammo = 30f;
    float reloadT = 0f, fireCd = 0f, recoil = 0f, muzzle = 0f, hurt = 0f, hitMark = 0f, sinceHurt = 99f;
    float walkPhase = 0f, bobY = 0f;
    boolean dead = false;
    float deadT = 0f;
    private boolean respawnSent = false;
    boolean micToggleReq = false, chatReq = false;

    // world state
    float time = 0f, dayT = 0.06f;
    int day = 1, crystalsGot = 0, kills = 0, score = 0;
    final HashSet<Long> collected = new HashSet<>();
    String msg = "";
    float msgT = 0f;
    float spawnT = 3f;

    // enemies (local in single player, mirrored from the server in multiplayer)
    final float[] ex = new float[MAXE], ey = new float[MAXE], ez = new float[MAXE];
    final float[] ehp = new float[MAXE], emax = new float[MAXE], eyaw = new float[MAXE];
    final float[] ecd = new float[MAXE], espd = new float[MAXE], escale = new float[MAXE];
    final int[] etype = new int[MAXE], eid = new int[MAXE];
    final boolean[] ealive = new boolean[MAXE];

    // bolts
    final float[] bx = new float[MAXB], by = new float[MAXB], bz = new float[MAXB];
    final float[] bvx = new float[MAXB], bvy = new float[MAXB], bvz = new float[MAXB], blife = new float[MAXB];
    final boolean[] balive = new boolean[MAXB], bmine = new boolean[MAXB];

    // render data
    final float[] wraithInst = new float[MAXE * 5], bruteInst = new float[MAXE * 5];
    final float[] boltInst = new float[MAXB * 5], crystalInst = new float[16 * 5];
    final float[] avatarInst = new float[MAXP * 5];
    int nWraith, nBrute, nBolt, nCrystal, nAvatar;

    // remote players (HUD tags / minimap)
    final String[] rName = new String[MAXP];
    final float[] rX = new float[MAXP], rY = new float[MAXP], rZ = new float[MAXP];
    int nRemote = 0;

    // compass / minimap helpers
    float compassRel = 0f, compassDist = 0f;
    boolean hasTarget = false;
    final float[] mapCrys = new float[64 * 2];
    int nMapCrys = 0;
    private float compassT = 0f;
    private float netT = 0f;
    private final float[] sd = new float[3];
    private final float[] xz = new float[2];

    Game(World w, Config c, Net n) {
        world = w;
        cfg = c;
        Q = c.q;
        net = n;
        setSpawn(w.spawnX, w.spawnZ);
        say(n != null ? "WELCOME TO VINZ WILDS ONLINE" : "EXPLORE THE WORLD - FIND AETHER CRYSTALS", 6f);
    }

    void setSpawn(float x, float z) {
        px = x;
        pz = z;
        py = Math.max(world.height(px, pz), 0f);
        vy = 0f;
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

    private boolean isCollected(long k) {
        return collected.contains(k) || (net != null && net.collected.contains(k));
    }

    int aliveCount() {
        int n = 0;
        for (int i = 0; i < MAXE; i++) if (ealive[i]) n++;
        return n;
    }

    // ------------------------------------------------------------------------------ update
    void update(float dt, Input in) {
        if (dt > 0.05f) dt = 0.05f;
        if (dt <= 0f) return;
        in.poll();
        time += dt;
        boolean mp = net != null;
        if (mp) {
            if (in.oMic) micToggleReq = true;
            if (in.oChat) chatReq = true;
            net.myX = px;
            net.myZ = pz;
        }

        if (mp) {
            long el = SystemClock.elapsedRealtimeNanos() - net.dayStampNs;
            float t = net.dayT + (float) (el / 1e9) / DAY_LEN;
            dayT = t - (float) Math.floor(t);
            if (net.dayNum != day) {
                day = net.dayNum;
                say("DAY " + day, 3f);
            }
            String m;
            while ((m = net.messages.poll()) != null) say(m, 3f);
        } else {
            dayT += dt / DAY_LEN;
            if (dayT >= 1f) {
                dayT -= 1f;
                day++;
                say("DAY " + day + " BEGINS", 4f);
            }
        }
        sunDir(dayT, sd);
        boolean night = sd[1] < 0.02f;

        if (msgT > 0f) msgT -= dt;
        muzzle = Math.max(0f, muzzle - dt * 9f);
        recoil = Math.max(0f, recoil - dt * 7f);
        hurt = Math.max(0f, hurt - dt * 1.6f);
        hitMark = Math.max(0f, hitMark - dt);

        if (mp) syncNet(dt);

        if (dead) {
            deadT += dt;
            if (mp) {
                if (in.oTap && deadT > 1.2f && !respawnSent) {
                    net.sendRespawn();
                    respawnSent = true;
                }
                if (net.spawnReady) {
                    net.spawnReady = false;
                    respawn(net.respX, net.respZ);
                }
            } else if (in.oTap && deadT > 1.2f) {
                respawn(world.spawnX, world.spawnZ);
            }
            updateBolts(dt);
            prepareRender();
            return;
        }

        // ---- look
        yaw -= in.oLookX * SENS * cfg.sens;
        pitch -= in.oLookY * SENS * cfg.sens * (cfg.invertY ? -1f : 1f);
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
            if (cm != null) {
                xz[0] = px;
                xz[1] = pz;
                cm.resolve(xz, 0.4f);
                px = xz[0];
                pz = xz[1];
            }
            float lim = World.HALF - 100f;
            if (px > lim) px = lim;
            if (px < -lim) px = -lim;
            if (pz > lim) pz = lim;
            if (pz < -lim) pz = -lim;
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

        updateCrystals(dt);

        if (!mp) {
            spawnT -= dt;
            if (spawnT <= 0f) {
                spawnT = night ? 2.2f : 7f;
                int lim = Q == 0 ? 10 : Q == 1 ? 16 : MAXE;
                int cap = night ? Math.min(lim, 4 + day * 2) : Math.min(2, day - 1);
                if (aliveCount() < cap) spawnEnemy();
            }
            updateEnemies(dt);
            sinceHurt += dt;
            if (sinceHurt > 6f && health < 100f) health = Math.min(100f, health + 2.5f * dt);
            if (health <= 0f) die();
        } else {
            netT += dt;
            if (netT >= 0.08f) {
                netT = 0f;
                net.sendState(px, py, pz, yaw, pitch);
            }
        }
        prepareRender();
    }

    private void die() {
        health = 0f;
        dead = true;
        deadT = 0f;
        respawnSent = false;
        Sfx.play(Sfx.DIE);
        say("YOU DIED", 5f);
    }

    // ------------------------------------------------------------------------------ multiplayer sync
    private float wrapPi(float a) {
        while (a > Math.PI) a -= 6.2831853f;
        while (a < -Math.PI) a += 6.2831853f;
        return a;
    }

    private void syncNet(float dt) {
        float k = 1f - (float) Math.exp(-12f * dt);

        // health is server-authoritative
        float h = net.hp;
        if (!dead) {
            if (h < health - 0.5f) {
                hurt = 1f;
                sinceHurt = 0f;
                Sfx.play(Sfx.HURT);
            }
            health = h;
            if (net.died) die();
        }

        // remote players
        nRemote = 0;
        nAvatar = 0;
        for (Net.Remote r : net.players.values()) {
            if (!r.init) {
                r.x = r.tx;
                r.y = r.ty;
                r.z = r.tz;
                r.yaw = r.tyaw;
                r.pitch = r.tpitch;
                r.init = true;
            }
            r.x += (r.tx - r.x) * k;
            r.y += (r.ty - r.y) * k;
            r.z += (r.tz - r.z) * k;
            r.yaw += wrapPi(r.tyaw - r.yaw) * k;
            r.pitch += (r.tpitch - r.pitch) * k;
            if (nRemote >= MAXP) break;
            String n = net.names.get(r.id);
            rName[nRemote] = n != null ? n : "P" + r.id;
            rX[nRemote] = r.x;
            rY[nRemote] = r.y;
            rZ[nRemote] = r.z;
            int o = nAvatar * 5;
            avatarInst[o] = r.x;
            avatarInst[o + 1] = r.y;
            avatarInst[o + 2] = r.z;
            avatarInst[o + 3] = 1f;
            avatarInst[o + 4] = r.yaw;
            nAvatar++;
            nRemote++;
        }

        // server enemies
        for (int i = 0; i < MAXE; i++) ealive[i] = false;
        int ne = 0;
        for (Net.REnemy en : net.enemies.values()) {
            if (ne >= MAXE) break;
            if (!en.init) {
                en.x = en.tx;
                en.z = en.tz;
                en.yaw = en.tyaw;
                en.init = true;
            }
            en.x += (en.tx - en.x) * k;
            en.z += (en.tz - en.z) * k;
            en.yaw += wrapPi(en.tyaw - en.yaw) * k;
            ealive[ne] = true;
            eid[ne] = en.id;
            etype[ne] = en.type;
            ex[ne] = en.x;
            ez[ne] = en.z;
            ey[ne] = world.height(en.x, en.z);
            eyaw[ne] = en.yaw;
            escale[ne] = en.type == 1 ? 1.6f : 1f;
            emax[ne] = 1f;
            ehp[ne] = Math.max(0.02f, en.hpf);
            ne++;
        }

        // other players' shots (visual only)
        float[] s;
        while ((s = net.shots.poll()) != null) {
            int slot = freeBolt();
            if (slot < 0) break;
            bx[slot] = s[0];
            by[slot] = s[1];
            bz[slot] = s[2];
            bvx[slot] = s[3] * 115f;
            bvy[slot] = s[4] * 115f;
            bvz[slot] = s[5] * 115f;
            blife[slot] = 1.2f;
            balive[slot] = true;
            bmine[slot] = false;
        }
    }

    // ------------------------------------------------------------------------------ crystals
    private void updateCrystals(float dt) {
        int cix = World.cellOf(px), ciz = World.cellOf(pz);
        nCrystal = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int ix = cix + dx, iz = ciz + dz;
                float[] c = world.crystalCell(ix, iz);
                if (c == null) continue;
                long k = World.key(ix, iz);
                if (isCollected(k)) continue;
                float ddx = c[0] - px, ddz = c[2] - pz;
                float d2 = ddx * ddx + ddz * ddz;
                if (d2 < 2.6f * 2.6f && Math.abs(c[1] - py - 1f) < 4f) {
                    pickup(ix, iz, k);
                } else if (d2 < 320f * 320f && nCrystal < 16) {
                    int o = nCrystal++ * 5;
                    crystalInst[o] = c[0];
                    crystalInst[o + 1] = c[1];
                    crystalInst[o + 2] = c[2];
                    crystalInst[o + 3] = 1.15f;
                    crystalInst[o + 4] = time * 1.2f + ix * 7 + iz;
                }
            }
        }
        compassT -= dt;
        if (compassT <= 0f) {
            compassT = 0.5f;
            scanCompass(cix, ciz);
        }
        if (hasTarget) {
            // refresh angle every frame from the cached target
            float bearing = (float) Math.atan2(tgtX - px, tgtZ - pz);
            compassRel = wrapPi(bearing - yaw);
            float dx = tgtX - px, dz = tgtZ - pz;
            compassDist = (float) Math.sqrt(dx * dx + dz * dz);
        }
    }

    private float tgtX, tgtZ;

    private void scanCompass(int cix, int ciz) {
        float best = 1e12f;
        hasTarget = false;
        nMapCrys = 0;
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                int ix = cix + dx, iz = ciz + dz;
                float[] c = world.crystalCell(ix, iz);
                if (c == null || isCollected(World.key(ix, iz))) continue;
                float ddx = c[0] - px, ddz = c[2] - pz;
                float d2 = ddx * ddx + ddz * ddz;
                if (d2 < best) {
                    best = d2;
                    tgtX = c[0];
                    tgtZ = c[2];
                    hasTarget = true;
                }
                if (d2 < 600f * 600f && nMapCrys < 64) {
                    mapCrys[nMapCrys * 2] = c[0];
                    mapCrys[nMapCrys * 2 + 1] = c[2];
                    nMapCrys++;
                }
            }
        }
    }

    private void pickup(int ix, int iz, long k) {
        collected.add(k);
        crystalsGot++;
        score += 100;
        Sfx.play(Sfx.PICK);
        say("AETHER CRYSTAL x" + crystalsGot, 3f);
        if (net != null) net.sendPick(ix, iz);
        else health = Math.min(100f, health + 15f);
        compassT = 0f;
    }

    // ------------------------------------------------------------------------------ weapon
    private void startReload() {
        if (reloadT > 0f) return;
        reloadT = 1.6f;
        Sfx.play(Sfx.RELOAD);
    }

    private int freeBolt() {
        for (int i = 0; i < MAXB; i++) if (!balive[i]) return i;
        return -1;
    }

    private void shoot() {
        int s = freeBolt();
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
        bmine[s] = true;
        if (net != null) net.sendShot(bx[s], by[s], bz[s], dx, dy, dz);
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
                if (!bmine[i]) continue;
                for (int e = 0; e < MAXE; e++) {
                    if (!ealive[e]) continue;
                    float cy = ey[e] + 1.2f * escale[e];
                    float dx = bx[i] - ex[e], dy = by[i] - cy, dz = bz[i] - ez[e];
                    float r = 0.85f * escale[e] + 0.15f;
                    if (dx * dx + dy * dy + dz * dz < r * r) {
                        balive[i] = false;
                        hitMark = 0.2f;
                        Sfx.play(Sfx.HIT);
                        if (net != null) {
                            net.sendHitEnemy(eid[e], 25f);
                        } else {
                            ehp[e] -= 25f;
                            if (ehp[e] <= 0f) killEnemy(e, true);
                        }
                        break;
                    }
                }
            }
            if (balive[i] && by[i] < world.height(bx[i], bz[i])) balive[i] = false;
        }
    }

    // ------------------------------------------------------------------------------ single-player enemies
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
            if (h < 0.8f || h > 250f) continue;
            if (Math.abs(x) > World.HALF - 200f || Math.abs(z) > World.HALF - 200f) continue;
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

    private void updateEnemies(float dt) {
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
                    ey[i] = nh;
                } else {
                    float sx = ex[i] - dz / d * sp * dt, sz = ez[i] + dx / d * sp * dt;
                    float sh = world.height(sx, sz);
                    if (sh > -0.4f) {
                        ex[i] = sx;
                        ez[i] = sz;
                        ey[i] = sh;
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
        }
    }

    private void respawn(float x, float z) {
        setSpawn(x, z);
        health = 100f;
        stamina = 100f;
        ammo = 30f;
        reloadT = 0f;
        dead = false;
        respawnSent = false;
        sinceHurt = 99f;
        if (net == null) {
            for (int i = 0; i < MAXE; i++) ealive[i] = false;
            dayT = 0.05f;
            spawnT = 6f;
        }
        for (int i = 0; i < MAXB; i++) balive[i] = false;
        say("RESPAWNED", 3f);
    }

    // ------------------------------------------------------------------------------ render data
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
    }
}
