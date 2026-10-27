'use strict';
/**
 * Vinz Wilds multiplayer server (Node.js, runs fine in Termux).
 *
 *   npm install ws
 *   node server.js
 *
 * Listens on 127.0.0.1:PORT. Expose it with a Cloudflare Tunnel public hostname:
 *   https://api2.vinzgd.space/wilds  ->  http://localhost:8787
 *
 * Endpoints (BASE_PATH defaults to /wilds):
 *   GET  /wilds/status   -> JSON server info
 *   WS   /wilds/ws       -> game socket (JSON messages)
 */
const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = parseInt(process.env.PORT || '8787', 10);
const BASE = (process.env.BASE_PATH || '/wilds').replace(/\/+$/, '');
const MAX_PLAYERS = parseInt(process.env.MAX_PLAYERS || '16', 10);
const DAY_LEN = 300;
const HALF = 32000;
const STARTED = Date.now();

// ------------------------------------------------------------------ world (port of World.java)
function hash(x, y) {
  let h = (Math.imul(x, 374761393) + Math.imul(y, 668265263) + 1442695041) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= (h >>> 16);
  return (h & 0xFFFFFF) / 16777215;
}
function vnoise(x, y) {
  const xi = Math.floor(x), yi = Math.floor(y);
  const xf = x - xi, yf = y - yi;
  const u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
  const a = hash(xi, yi), b = hash(xi + 1, yi), c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
  const ab = a + (b - a) * u, cd = c + (d - c) * u;
  return ab + (cd - ab) * v;
}
function fbm(x, y, oct) {
  let s = 0, a = 0.5, n = 0;
  for (let i = 0; i < oct; i++) {
    s += a * vnoise(x, y);
    n += a;
    x = x * 2.03 + 17.3;
    y = y * 2.03 + 9.1;
    a *= 0.5;
  }
  return s / n;
}
function smooth(a, b, x) {
  let t = (x - a) / (b - a);
  t = t < 0 ? 0 : t > 1 ? 1 : t;
  return t * t * (3 - 2 * t);
}
function height(x, z) {
  const cont = fbm(x * 0.00006 + 3.1, z * 0.00006 - 5.7, 3);
  const land = smooth(0.40, 0.52, cont);
  const hills = (fbm(x * 0.0011 + 10, z * 0.0011 + 10, 5) - 0.45) * 130;
  const det = (fbm(x * 0.012 + 40, z * 0.012 - 9, 3) - 0.5) * 10;
  const mm = smooth(0.52, 0.66, fbm(x * 0.00025 - 30, z * 0.00025 + 50, 3));
  let h;
  if (mm > 0) {
    const rg = 1 - Math.abs(2 * fbm(x * 0.0022 + 5, z * 0.0022 - 7, 4) - 1);
    h = hills + det + mm * rg * rg * 260 + 4;
  } else {
    h = hills + det + 4;
  }
  h = h * land + (land - 1) * 45;
  const e = smooth(0.86, 1.0, Math.max(Math.abs(x), Math.abs(z)) / HALF);
  return h - e * 90;
}
function slope(x, z) {
  const e = 1.5;
  const dx = height(x + e, z) - height(x - e, z);
  const dz = height(x, z + e) - height(x, z - e);
  return Math.sqrt(dx * dx + dz * dz) / (2 * e);
}
function goodSpawn(x, z) {
  const h = height(x, z);
  if (h < 3 || h > 30) return false;
  if (slope(x, z) > 0.25) return false;
  for (let k = 0; k < 8; k++) {
    const a = k * 0.785398;
    if (height(x + Math.cos(a) * 100, z + Math.sin(a) * 100) < 0.8) return false;
  }
  return true;
}
function findSpawn() {
  for (let ring = 0; ring < 80; ring++) {
    const rad = ring * 60;
    const n = ring === 0 ? 1 : 8 + ring * 4;
    for (let k = 0; k < n; k++) {
      const a = (k * 2 * Math.PI) / n;
      const x = Math.cos(a) * rad, z = Math.sin(a) * rad;
      if (goodSpawn(x, z)) return { x, z };
    }
  }
  return { x: 0, z: 0 };
}
const SPAWN = findSpawn();

// ------------------------------------------------------------------ state
const players = new Map();
const enemies = new Map();
const collected = new Set();
let nextPid = 1, nextEid = 1;
let dayT = 0.06, dayNum = 1;
let spawnTimer = 3;

const r2 = (v) => Math.round(v * 100) / 100;
const num = (v, lo, hi) => (typeof v === 'number' && isFinite(v) ? Math.min(hi, Math.max(lo, v)) : null);

function send(ws, obj) {
  if (ws.readyState === 1) ws.send(JSON.stringify(obj));
}
function broadcast(obj, except) {
  const s = JSON.stringify(obj);
  for (const p of players.values()) {
    if (p !== except && p.ws.readyState === 1) p.ws.send(s);
  }
}
function sunY() {
  const a = dayT * 2 * Math.PI;
  const x = Math.cos(a) * 0.85, y = Math.sin(a), z = 0.5;
  return y / Math.sqrt(x * x + y * y + z * z);
}
function cleanName(s) {
  s = String(s || '').replace(/[^A-Za-z0-9_ -]/g, '').trim().slice(0, 16);
  return s || 'Player';
}
const alivePlayers = () => [...players.values()].filter((p) => !p.dead);

// ------------------------------------------------------------------ message handling
function handle(p, m) {
  p.lastMsg = Date.now();
  switch (m.t) {
    case 'join': {
      if (p.joined) return;
      p.name = cleanName(m.name);
      p.joined = true;
      players.set(p.id, p);
      send(p.ws, {
        t: 'welcome', id: p.id, sx: SPAWN.x, sz: SPAWN.z, d: dayT, dn: dayNum,
        pl: [...players.values()].filter((o) => o !== p).map((o) => [o.id, o.name]),
        col: [...collected],
      });
      broadcast({ t: 'pj', id: p.id, n: p.name }, p);
      console.log(`[+] ${p.name} (#${p.id}) joined, online=${players.size}`);
      break;
    }
    case 'st': {
      if (!p.joined || p.dead) return;
      const x = num(m.x, -HALF, HALF), y = num(m.y, -200, 600), z = num(m.z, -HALF, HALF);
      if (x === null || y === null || z === null) return;
      p.x = x; p.y = y; p.z = z;
      p.yaw = num(m.yw, -100, 100) || 0;
      p.pitch = num(m.pt, -3, 3) || 0;
      break;
    }
    case 'shot': {
      if (!p.joined || p.dead) return;
      const o = ['ox', 'oy', 'oz', 'dx', 'dy', 'dz'].map((k) => num(m[k], -100000, 100000));
      if (o.some((v) => v === null)) return;
      const msg = { t: 'shot', ox: o[0], oy: o[1], oz: o[2], dx: o[3], dy: o[4], dz: o[5] };
      const s = JSON.stringify(msg);
      for (const q of players.values()) {
        if (q === p || q.ws.readyState !== 1) continue;
        const dx = q.x - p.x, dz = q.z - p.z;
        if (dx * dx + dz * dz < 800 * 800) q.ws.send(s);
      }
      break;
    }
    case 'hitE': {
      if (!p.joined || p.dead) return;
      const now = Date.now();
      p.tokens = Math.min(20, p.tokens + ((now - p.tokT) / 1000) * 20);
      p.tokT = now;
      if (p.tokens < 1) return;
      p.tokens -= 1;
      const e = enemies.get(m.id | 0);
      if (!e) return;
      const dx = e.x - p.x, dz = e.z - p.z;
      if (dx * dx + dz * dz > 300 * 300) return;
      e.hp -= Math.min(25, Math.max(1, m.d | 0));
      if (e.hp <= 0) {
        enemies.delete(e.id);
        p.kills++;
        p.hp = Math.min(100, p.hp + 6);
        send(p.ws, { t: 'hp', v: p.hp });
      }
      break;
    }
    case 'pick': {
      if (!p.joined || p.dead) return;
      const k = String(m.k || '');
      if (!/^-?\d+,-?\d+$/.test(k) || collected.has(k)) return;
      collected.add(k);
      broadcast({ t: 'crys', k });
      p.hp = Math.min(100, p.hp + 15);
      send(p.ws, { t: 'hp', v: p.hp });
      break;
    }
    case 'resp': {
      if (!p.joined || !p.dead) return;
      p.dead = false;
      p.hp = 100;
      p.x = SPAWN.x; p.z = SPAWN.z; p.y = Math.max(0, height(SPAWN.x, SPAWN.z));
      send(p.ws, { t: 'spawn', x: SPAWN.x, z: SPAWN.z });
      break;
    }
    case 'chat': {
      if (!p.joined) return;
      const nowC = Date.now();
      if (nowC - (p.lastChat || 0) < 600) return;
      p.lastChat = nowC;
      const text = String(m.m || '').replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, 120);
      if (!text) return;
      broadcast({ t: 'chat', id: p.id, n: p.name, m: text });
      console.log(`[chat] ${p.name}: ${text}`);
      break;
    }
    case 'p': {
      send(p.ws, { t: 'po', c: m.c });
      break;
    }
    default:
      break;
  }
}

// ------------------------------------------------------------------ proximity voice (binary frames)
// client -> server: [1][160 x u-law]   server -> client: [1][id hi][id lo][160 x u-law]
const VOICE_RANGE = 160;
function handleVoice(p, data) {
  if (!p.joined || !Buffer.isBuffer(data) || data.length !== 161 || data[0] !== 1) return;
  const now = Date.now();
  const tok = p.vTok === undefined ? 80 : p.vTok;
  p.vTok = Math.min(80, tok + ((now - (p.vT || now)) / 1000) * 60);
  p.vT = now;
  if (p.vTok < 1) return;
  p.vTok -= 1;
  const out = Buffer.allocUnsafe(163);
  out[0] = 1;
  out[1] = (p.id >> 8) & 255;
  out[2] = p.id & 255;
  data.copy(out, 3, 1);
  for (const q of players.values()) {
    if (q === p || q.ws.readyState !== 1) continue;
    const dx = q.x - p.x, dz = q.z - p.z;
    if (dx * dx + dz * dz < VOICE_RANGE * VOICE_RANGE) q.ws.send(out, { binary: true });
  }
}

// ------------------------------------------------------------------ enemy simulation
function spawnEnemy() {
  const alive = alivePlayers();
  if (!alive.length) return;
  const night = sunY() < 0.02;
  const cap = night ? Math.min(24, 3 + alive.length * 3 + dayNum * 2) : Math.min(2 * alive.length, dayNum - 1);
  if (enemies.size >= cap) return;
  const base = alive[Math.floor(Math.random() * alive.length)];
  for (let t = 0; t < 8; t++) {
    const ang = Math.random() * Math.PI * 2;
    const d = 40 + Math.random() * 30;
    const x = base.x + Math.cos(ang) * d, z = base.z + Math.sin(ang) * d;
    const h = height(x, z);
    if (h < 0.8 || h > 250) continue;
    if (Math.abs(x) > HALF - 200 || Math.abs(z) > HALF - 200) continue;
    const brute = dayNum >= 2 && Math.random() < 0.25;
    const max = (brute ? 220 : 60) + 12 * (dayNum - 1);
    const e = {
      id: nextEid++, type: brute ? 1 : 0, x, z, y: h, yaw: 0, hp: max, max,
      spd: brute ? 3.2 : 4.6 + Math.random() * 0.8, cd: 0.4,
    };
    enemies.set(e.id, e);
    return;
  }
}

function updateEnemies(dt) {
  const burn = sunY() > 0.2;
  const alive = alivePlayers();
  for (const e of enemies.values()) {
    let best = null, bd = 1e12;
    for (const p of alive) {
      const dx = p.x - e.x, dz = p.z - e.z, d2 = dx * dx + dz * dz;
      if (d2 < bd) { bd = d2; best = p; }
    }
    if (!best || bd > 200 * 200) { enemies.delete(e.id); continue; }
    if (burn) {
      e.hp -= (e.type === 0 ? 10 : 4) * dt;
      if (e.hp <= 0) { enemies.delete(e.id); continue; }
    }
    const dx = best.x - e.x, dz = best.z - e.z;
    const d = Math.sqrt(bd);
    e.yaw = Math.atan2(dx, dz);
    const reach = e.type === 1 ? 2.2 : 1.7;
    if (d > reach && d < 130) {
      const nx = e.x + (dx / d) * e.spd * dt, nz = e.z + (dz / d) * e.spd * dt;
      const nh = height(nx, nz);
      if (nh > -0.4 && nh - e.y < 0.9 * e.spd * dt + 0.3) {
        e.x = nx; e.z = nz; e.y = nh;
      } else {
        const sx = e.x - (dz / d) * e.spd * dt, sz = e.z + (dx / d) * e.spd * dt;
        const sh = height(sx, sz);
        if (sh > -0.4) { e.x = sx; e.z = sz; e.y = sh; }
      }
      e.cd = Math.min(e.cd, 0.4);
    } else if (d <= reach) {
      e.cd -= dt;
      if (e.cd <= 0) {
        e.cd = 0.9;
        best.hp -= e.type === 1 ? 22 : 12;
        best.lastHurt = Date.now();
        if (best.hp <= 0) {
          best.hp = 0;
          best.dead = true;
          send(best.ws, { t: 'hp', v: 0 });
          send(best.ws, { t: 'died' });
          broadcast({ t: 'msg', m: `${best.name} was slain` });
        } else {
          send(best.ws, { t: 'hp', v: best.hp });
        }
      }
    }
  }
}

// ------------------------------------------------------------------ main loop
let last = Date.now(), snapAcc = 0;
setInterval(() => {
  const now = Date.now();
  const dt = Math.min(0.2, (now - last) / 1000);
  last = now;

  dayT += dt / DAY_LEN;
  if (dayT >= 1) {
    dayT -= 1;
    dayNum++;
    broadcast({ t: 'msg', m: `DAY ${dayNum} BEGINS` });
  }

  // passive regen + idle kick
  for (const p of players.values()) {
    if (!p.dead && p.hp < 100 && now - p.lastHurt > 6000) {
      p.hp = Math.min(100, p.hp + 2.5 * dt);
    }
    if (now - p.lastMsg > 60000) p.ws.terminate();
  }

  if (players.size > 0) {
    spawnTimer -= dt;
    if (spawnTimer <= 0) {
      spawnTimer = sunY() < 0.02 ? 2.2 : 7;
      spawnEnemy();
    }
    updateEnemies(dt);
  } else if (enemies.size) {
    enemies.clear();
  }

  snapAcc += dt;
  if (snapAcc >= 0.1) {
    snapAcc = 0;
    for (const p of players.values()) {
      if (p.ws.readyState !== 1) continue;
      const pl = [];
      for (const o of players.values()) {
        if (o === p || o.dead) continue;
        const dx = o.x - p.x, dz = o.z - p.z;
        if (dx * dx + dz * dz > 1000 * 1000) continue;
        pl.push([o.id, r2(o.x), r2(o.y), r2(o.z), r2(o.yaw), r2(o.pitch), Math.round(o.hp)]);
      }
      const en = [];
      for (const e of enemies.values()) {
        const dx = e.x - p.x, dz = e.z - p.z;
        if (dx * dx + dz * dz > 900 * 900) continue;
        en.push([e.id, e.type, r2(e.x), r2(e.z), r2(e.yaw), r2(Math.max(0, e.hp) / e.max)]);
      }
      p.ws.send(JSON.stringify({ t: 'snap', d: dayT, dn: dayNum, on: players.size, p: pl, e: en }));
    }
  }
}, 50);

// ------------------------------------------------------------------ http + websocket
function statusJson() {
  return {
    name: 'Vinz Wilds', version: 3, chat: true, voice: true, online: players.size, max: MAX_PLAYERS,
    day: dayNum, dayT: r2(dayT), enemies: enemies.size, pvp: false,
    uptime: Math.round((Date.now() - STARTED) / 1000), spawn: { x: r2(SPAWN.x), z: r2(SPAWN.z) },
  };
}

const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0].replace(/\/+$/, '');
  res.setHeader('Access-Control-Allow-Origin', '*');
  if (url === BASE + '/status') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(statusJson()));
  } else if (url === BASE || url === BASE + '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('Vinz Wilds server OK\n');
  } else {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('not found\n');
  }
});

const wss = new WebSocketServer({ noServer: true, maxPayload: 4096 });
server.on('upgrade', (req, socket, head) => {
  const url = req.url.split('?')[0].replace(/\/+$/, '');
  if (url === BASE + '/ws') {
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  } else {
    socket.destroy();
  }
});

wss.on('connection', (ws) => {
  if (players.size >= MAX_PLAYERS) {
    send(ws, { t: 'msg', m: 'Server penuh' });
    ws.close();
    return;
  }
  const p = {
    id: nextPid++, ws, name: 'Player', joined: false, x: SPAWN.x, y: 0, z: SPAWN.z, yaw: 0, pitch: 0,
    hp: 100, dead: false, kills: 0, tokens: 20, tokT: Date.now(), lastMsg: Date.now(), lastHurt: 0,
  };
  ws.on('message', (data, isBinary) => {
    if (isBinary) { handleVoice(p, data); return; }
    let m;
    try { m = JSON.parse(data.toString()); } catch (e) { return; }
    if (m && typeof m === 'object') handle(p, m);
  });
  ws.on('close', () => {
    if (p.joined) {
      players.delete(p.id);
      broadcast({ t: 'pl', id: p.id });
      console.log(`[-] ${p.name} (#${p.id}) left, online=${players.size}`);
    }
  });
  ws.on('error', () => {});
});

server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`Port ${PORT} sudah dipakai: server Vinz Wilds kemungkinan SUDAH JALAN di background.`);
    console.error('Restart dengan:  sh run.sh     Stop dengan:  sh stop.sh');
  } else {
    console.error('Server error:', err.message);
  }
  process.exit(1);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Vinz Wilds server listening on 127.0.0.1:${PORT}  base=${BASE}`);
  console.log(`spawn at x=${SPAWN.x.toFixed(0)} z=${SPAWN.z.toFixed(0)}`);
});
