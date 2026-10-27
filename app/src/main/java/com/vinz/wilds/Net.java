package com.vinz.wilds;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Multiplayer client (WebSocket, JSON). All state is exposed through thread-safe fields. */
final class Net {
    static final class Remote {
        int id;
        volatile int seen;
        volatile float tx, ty, tz, tyaw, tpitch, hp = 100f;
        float x, y, z, yaw, pitch;
        boolean init;
    }

    static final class REnemy {
        int id, type;
        volatile int seen;
        volatile float tx, tz, tyaw, hpf = 1f;
        float x, z, yaw;
        boolean init;
    }

    private final String url, name;
    private final OkHttpClient client;
    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
    private WebSocket ws;
    private volatile boolean closed = false;
    private int snapCounter = 0;

    volatile boolean connected = false, welcomed = false, died = false, spawnReady = false;
    volatile int myId = -1, online = 1, ping = -1, dayNum = 1;
    volatile float spawnX, spawnZ, dayT = 0.06f, hp = 100f, respX, respZ;
    volatile long dayStampNs = 0L;
    volatile String status = "CONNECTING...";

    final ConcurrentHashMap<Integer, Remote> players = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, REnemy> enemies = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, String> names = new ConcurrentHashMap<>();
    final ConcurrentLinkedQueue<float[]> shots = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();
    final Set<Long> collected = ConcurrentHashMap.newKeySet();

    Net(String url, String name) {
        this.url = url;
        this.name = name.replaceAll("[^A-Za-z0-9_ -]", "");
        client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        ses.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (connected && ws != null) {
                    ws.send("{\"t\":\"p\",\"c\":" + System.nanoTime() + "}");
                }
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    static long parseKey(String k) {
        try {
            int c = k.indexOf(',');
            return World.key(Integer.parseInt(k.substring(0, c)), Integer.parseInt(k.substring(c + 1)));
        } catch (Exception e) {
            return 0L;
        }
    }

    synchronized void connect() {
        if (ws != null) {
            try {
                ws.cancel();
            } catch (Throwable ignored) {
            }
        }
        closed = false;
        connected = false;
        status = "CONNECTING...";
        Request req;
        try {
            req = new Request.Builder().url(url).build();
        } catch (Exception e) {
            status = "BAD URL";
            return;
        }
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket w, Response r) {
                connected = true;
                status = "JOINING...";
                w.send("{\"t\":\"join\",\"name\":\"" + name + "\",\"v\":2}");
            }

            @Override
            public void onMessage(WebSocket w, String text) {
                try {
                    handle(text);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onClosed(WebSocket w, int code, String reason) {
                lost("DISCONNECTED");
            }

            @Override
            public void onFailure(WebSocket w, Throwable t, Response r) {
                lost("OFFLINE - RETRYING");
            }
        });
    }

    private void lost(String s) {
        connected = false;
        status = s;
        players.clear();
        enemies.clear();
        if (!closed) {
            ses.schedule(new Runnable() {
                @Override
                public void run() {
                    if (!closed) connect();
                }
            }, 3, TimeUnit.SECONDS);
        }
    }

    void pause() {
        closed = true;
        connected = false;
        try {
            if (ws != null) ws.close(1000, "pause");
        } catch (Throwable ignored) {
        }
    }

    void close() {
        closed = true;
        connected = false;
        try {
            if (ws != null) ws.close(1000, "bye");
        } catch (Throwable ignored) {
        }
        ses.shutdownNow();
        client.dispatcher().executorService().shutdown();
    }

    // ------------------------------------------------------------------ incoming
    private void handle(String text) throws Exception {
        JSONObject o = new JSONObject(text);
        String t = o.optString("t");
        switch (t) {
            case "welcome": {
                myId = o.getInt("id");
                spawnX = (float) o.getDouble("sx");
                spawnZ = (float) o.getDouble("sz");
                dayT = (float) o.getDouble("d");
                dayNum = o.optInt("dn", 1);
                dayStampNs = SystemClock.elapsedRealtimeNanos();
                hp = 100f;
                died = false;
                JSONArray pl = o.optJSONArray("pl");
                if (pl != null) {
                    for (int i = 0; i < pl.length(); i++) {
                        JSONArray a = pl.getJSONArray(i);
                        names.put(a.getInt(0), a.getString(1));
                    }
                }
                JSONArray col = o.optJSONArray("col");
                if (col != null) {
                    for (int i = 0; i < col.length(); i++) collected.add(parseKey(col.getString(i)));
                }
                welcomed = true;
                status = "ONLINE";
                break;
            }
            case "snap": {
                int sc = ++snapCounter;
                dayT = (float) o.getDouble("d");
                dayNum = o.optInt("dn", dayNum);
                dayStampNs = SystemClock.elapsedRealtimeNanos();
                online = o.optInt("on", online);
                JSONArray p = o.optJSONArray("p");
                if (p != null) {
                    for (int i = 0; i < p.length(); i++) {
                        JSONArray a = p.getJSONArray(i);
                        int id = a.getInt(0);
                        if (id == myId) continue;
                        Remote r = players.get(id);
                        if (r == null) {
                            r = new Remote();
                            r.id = id;
                            players.put(id, r);
                        }
                        r.tx = (float) a.getDouble(1);
                        r.ty = (float) a.getDouble(2);
                        r.tz = (float) a.getDouble(3);
                        r.tyaw = (float) a.getDouble(4);
                        r.tpitch = (float) a.getDouble(5);
                        r.hp = (float) a.getDouble(6);
                        r.seen = sc;
                    }
                }
                JSONArray e = o.optJSONArray("e");
                if (e != null) {
                    for (int i = 0; i < e.length(); i++) {
                        JSONArray a = e.getJSONArray(i);
                        int id = a.getInt(0);
                        REnemy en = enemies.get(id);
                        if (en == null) {
                            en = new REnemy();
                            en.id = id;
                            en.type = a.getInt(1);
                            enemies.put(id, en);
                        }
                        en.tx = (float) a.getDouble(2);
                        en.tz = (float) a.getDouble(3);
                        en.tyaw = (float) a.getDouble(4);
                        en.hpf = (float) a.getDouble(5);
                        en.seen = sc;
                    }
                }
                for (Remote r : players.values()) if (r.seen != sc) players.remove(r.id);
                for (REnemy en : enemies.values()) if (en.seen != sc) enemies.remove(en.id);
                break;
            }
            case "pj":
                names.put(o.getInt("id"), o.getString("n"));
                messages.add(o.getString("n") + " joined");
                break;
            case "pl": {
                int id = o.getInt("id");
                String n = names.remove(id);
                players.remove(id);
                if (n != null) messages.add(n + " left");
                break;
            }
            case "shot":
                shots.add(new float[]{(float) o.getDouble("ox"), (float) o.getDouble("oy"), (float) o.getDouble("oz"),
                        (float) o.getDouble("dx"), (float) o.getDouble("dy"), (float) o.getDouble("dz")});
                break;
            case "hp":
                hp = (float) o.getDouble("v");
                break;
            case "died":
                died = true;
                hp = 0f;
                break;
            case "spawn":
                respX = (float) o.getDouble("x");
                respZ = (float) o.getDouble("z");
                hp = 100f;
                died = false;
                spawnReady = true;
                break;
            case "crys":
                collected.add(parseKey(o.getString("k")));
                break;
            case "msg":
                messages.add(o.getString("m"));
                break;
            case "po":
                ping = (int) ((System.nanoTime() - o.getLong("c")) / 1000000L);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ outgoing
    private void send(String s) {
        WebSocket w = ws;
        if (connected && w != null) w.send(s);
    }

    void sendState(float x, float y, float z, float yaw, float pitch) {
        send(String.format(Locale.US, "{\"t\":\"st\",\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"yw\":%.3f,\"pt\":%.3f}",
                x, y, z, yaw, pitch));
    }

    void sendShot(float ox, float oy, float oz, float dx, float dy, float dz) {
        send(String.format(Locale.US, "{\"t\":\"shot\",\"ox\":%.2f,\"oy\":%.2f,\"oz\":%.2f,\"dx\":%.3f,\"dy\":%.3f,\"dz\":%.3f}",
                ox, oy, oz, dx, dy, dz));
    }

    void sendHitEnemy(int id, float dmg) {
        send("{\"t\":\"hitE\",\"id\":" + id + ",\"d\":" + (int) dmg + "}");
    }

    void sendPick(int ix, int iz) {
        send("{\"t\":\"pick\",\"k\":\"" + ix + "," + iz + "\"}");
    }

    void sendRespawn() {
        send("{\"t\":\"resp\"}");
    }
}
