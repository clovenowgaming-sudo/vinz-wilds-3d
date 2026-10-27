const WebSocket = require('ws');
const url = process.argv[2] || 'ws://127.0.0.1:8787/wilds/ws';
const ws = new WebSocket(url);
const got = {};
const timer = setTimeout(() => { console.log('TIMEOUT, got:', Object.keys(got)); process.exit(1); }, 8000);
ws.on('open', () => {
  ws.send(JSON.stringify({ t: 'join', name: 'TestBot', v: 2 }));
  setTimeout(() => ws.send(JSON.stringify({ t: 'st', x: 10.5, y: 4, z: 20, yw: 1.2, pt: 0 })), 300);
  setTimeout(() => ws.send(JSON.stringify({ t: 'p', c: Date.now() })), 400);
  setTimeout(() => ws.send(JSON.stringify({ t: 'pick', k: '3,4' })), 500);
});
ws.on('message', (d) => {
  const m = JSON.parse(d.toString());
  got[m.t] = (got[m.t] || 0) + 1;
  if (m.t === 'welcome') console.log('welcome ok: id=%d spawn=(%d,%d) dayT=%s', m.id, m.sx, m.sz, m.d);
  if (m.t === 'snap' && got.snap === 2) console.log('snap ok: online=%d players=%d enemies=%d', m.on, m.p.length, m.e.length);
  if (m.t === 'po') console.log('pong ok');
  if (m.t === 'crys') console.log('crystal broadcast ok:', m.k);
  if (got.welcome && got.snap >= 3 && got.po && got.crys) {
    clearTimeout(timer);
    console.log('ALL OK', JSON.stringify(got));
    ws.close();
    setTimeout(() => process.exit(0), 200);
  }
});
ws.on('error', (e) => { console.log('ERR', e.message); process.exit(1); });
