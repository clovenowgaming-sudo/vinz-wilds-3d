package com.vinz.wilds;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

/** All user-facing settings (stored in SharedPreferences "vinz"). */
final class Config {
    static final String DEFAULT_URL = "wss://api2.vinzgd.space/wilds/ws";

    int q = 1;                 // 0 low, 1 medium, 2 extreme
    float res = 0.85f;         // render resolution scale
    float view = 1f;           // view distance multiplier
    boolean shadows = true, grass = true, showFps = true, invertY = false, sound = true;
    float sens = 1f;
    String name = "Player";
    String url = DEFAULT_URL;
    boolean mp = false;

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("vinz", Context.MODE_PRIVATE);
    }

    static int recommended(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double gb = mi.totalMem / 1073741824.0;
        return gb < 3.6 ? 0 : gb < 5.5 ? 1 : 2;
    }

    static float defaultRes(int q) {
        return q == 0 ? 0.6f : q == 1 ? 0.85f : 1.0f;
    }

    static Config load(Context c, boolean mp) {
        SharedPreferences sp = sp(c);
        Config k = new Config();
        k.mp = mp;
        k.q = sp.getInt("q", recommended(c));
        if (k.q < 0) k.q = 0;
        if (k.q > 2) k.q = 2;
        float r = sp.getFloat("res", -1f);
        k.res = r > 0f ? r : defaultRes(k.q);
        k.view = sp.getFloat("view", 1f);
        k.shadows = sp.getBoolean("shadows", true);
        k.grass = sp.getBoolean("grass", true);
        k.showFps = sp.getBoolean("fps", true);
        k.invertY = sp.getBoolean("invy", false);
        k.sound = sp.getBoolean("sound", true);
        k.sens = sp.getFloat("sens", 1f);
        String n = sp.getString("name", "");
        if (n == null || n.trim().isEmpty()) {
            n = "Player" + (int) (Math.random() * 900 + 100);
            sp.edit().putString("name", n).apply();
        }
        k.name = n;
        String u = sp.getString("url", DEFAULT_URL);
        k.url = (u == null || u.trim().isEmpty()) ? DEFAULT_URL : u.trim();
        return k;
    }

    /** ws(s)://host/path/ws  ->  http(s)://host/path/status */
    static String statusUrl(String ws) {
        String s = ws.trim();
        if (s.startsWith("wss://")) s = "https://" + s.substring(6);
        else if (s.startsWith("ws://")) s = "http://" + s.substring(5);
        if (s.endsWith("/ws")) s = s.substring(0, s.length() - 3);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s + "/status";
    }
}
