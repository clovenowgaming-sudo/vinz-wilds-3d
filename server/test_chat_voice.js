const WebSocket = require('ws');
const url = 'ws://127.0.0.1:8787/wilds/ws';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function mk(name) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const st = { ws, name, id: -1, chat: [], voice: [] };
    const t = setTimeout(() => reject(new Error('no welcome for ' + name)), 5000);
    ws.on('open', () => ws.send(JSON.stringify({ t: 'join', name, v: 3 })));
    ws.on('message', (d, isBin) => {
      if (isBin) { st.voice.push(Buffer.from(d)); return; }
      const m = JSON.parse(d.toString());
      if (m.t === 'welcome') { st.id = m.id; clearTimeout(t); resolve(st); }
      if (m.t === 'chat') st.chat.push(m);
    });
    ws.on('error', reject);
  });
}

(async () => {
  const a = await mk('Alice');
  const b = await mk('Bob');
  await sleep(300);
  a.ws.send(JSON.stringify({ t: 'chat', m: 'halo dunia \u0007<ok>' }));
  const pkt = Buffer.alloc(161);
  pkt[0] = 1;
  for (let i = 1; i < 161; i++) pkt[i] = 0x7f;
  a.ws.send(pkt, { binary: true });
  a.ws.send(Buffer.alloc(50), { binary: true }); // wrong size, must be ignored
  await sleep(600);
  const chatOk = b.chat.length === 1 && b.chat[0].n === 'Alice' && b.chat[0].m.startsWith('halo dunia');
  console.log('chat relayed to Bob:', chatOk, JSON.stringify(b.chat[0]));
  console.log('chat echoed to Alice:', a.chat.length === 1);
  const v = b.voice[0];
  const voiceOk = b.voice.length === 1 && v.length === 163 && v[0] === 1 && ((v[1] << 8) | v[2]) === a.id && v[3] === 0x7f;
  console.log('voice relayed to Bob:', voiceOk, 'count=' + b.voice.length, 'len=' + (v && v.length));
  console.log('voice NOT echoed to Alice:', a.voice.length === 0);
  a.ws.close(); b.ws.close();
  await sleep(200);
  process.exit(chatOk && voiceOk ? 0 : 1);
})().catch((e) => { console.log('TEST ERROR', e.message); process.exit(1); });
