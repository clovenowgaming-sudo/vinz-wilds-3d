package com.vinz.wilds;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private TextView info;

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button bigButton(String text, String sub, int color) {
        Button btn = new Button(this);
        btn.setAllCaps(false);
        btn.setText(text + (sub != null ? "\n" + sub : ""));
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(16f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(24));
        gd.setStroke(dp(2), 0x66FFFFFF);
        btn.setBackground(gd);
        btn.setPadding(dp(16), dp(10), dp(16), dp(10));
        return btn;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0B1026, 0xFF2B1B4D, 0xFF9A3F3A}));
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(16), dp(24), dp(16));
        sv.addView(root, new ScrollView.LayoutParams(-1, -1));

        // ---- left: logo + title
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        left.addView(logo, new LinearLayout.LayoutParams(dp(120), dp(120)));
        TextView title = new TextView(this);
        title.setText("VINZ WILDS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, 0);
        left.addView(title);
        TextView sub = new TextView(this);
        sub.setText("AETHER FRONTIER");
        sub.setTextColor(0xFFFFC27A);
        sub.setTextSize(14f);
        sub.setLetterSpacing(0.25f);
        sub.setGravity(Gravity.CENTER);
        left.addView(sub);
        TextView world = new TextView(this);
        world.setText("Open world 64.000 x 64.000 m");
        world.setTextColor(0xFFB8C4E8);
        world.setTextSize(12f);
        world.setGravity(Gravity.CENTER);
        world.setPadding(0, dp(10), 0, 0);
        left.addView(world);
        root.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        // ---- right: 3 buttons only
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER);

        Button single = bigButton("SINGLE PLAYER", "Jelajah dan bertahan hidup sendirian", 0xFF2E7D4F);
        single.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(MainActivity.this, GameActivity.class);
                it.putExtra("mp", false);
                startActivity(it);
            }
        });
        Button multi = bigButton("MULTIPLAYER", "Main bareng di server publik", 0xFF2F6DB5);
        multi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMultiplayer();
            }
        });
        Button settings = bigButton("SETTINGS", "Grafis, kontrol, suara, server", 0xFF6A4C93);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(7), 0, dp(7));
        right.addView(single, lp);
        right.addView(multi, lp);
        right.addView(settings, lp);

        info = new TextView(this);
        info.setTextColor(0xFFB8C4E8);
        info.setTextSize(11.5f);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(8), 0, 0);
        right.addView(info);

        root.addView(right, new LinearLayout.LayoutParams(0, -2, 1.2f));
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Config c = Config.load(this, false);
        String[] qn = {"LOW", "MEDIUM", "EXTREME"};
        info.setText("Grafis: " + qn[c.q] + "  |  Nama: " + c.name);
    }

    // ------------------------------------------------------------------ multiplayer dialog
    private void showMultiplayer() {
        final Config c = Config.load(this, true);
        final TextView tv = new TextView(this);
        tv.setTextSize(14f);
        tv.setTextColor(Color.WHITE);
        tv.setPadding(dp(20), dp(16), dp(20), dp(8));
        tv.setText("Server: " + c.url + "\nNama: " + c.name + "\n\nMengecek server...");

        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("MULTIPLAYER")
                .setView(tv)
                .setPositiveButton("JOIN", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Intent it = new Intent(MainActivity.this, GameActivity.class);
                        it.putExtra("mp", true);
                        startActivity(it);
                    }
                })
                .setNeutralButton("SETTINGS", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                })
                .setNegativeButton("BATAL", null)
                .create();
        dlg.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    long t0 = System.currentTimeMillis();
                    HttpURLConnection h = (HttpURLConnection) new URL(Config.statusUrl(c.url)).openConnection();
                    h.setConnectTimeout(6000);
                    h.setReadTimeout(6000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(h.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    long ms = System.currentTimeMillis() - t0;
                    JSONObject o = new JSONObject(sb.toString());
                    result = "ONLINE  (" + ms + " ms)\nPemain: " + o.optInt("online") + "/" + o.optInt("max")
                            + "\nHari ke-" + o.optInt("day") + "   PvP: " + (o.optBoolean("pvp") ? "ON" : "OFF");
                } catch (Throwable e) {
                    result = "OFFLINE / tidak terjangkau\n(" + e.getClass().getSimpleName() + ")\nPastikan server di HP-mu jalan\ndan route Cloudflare sudah dibuat.";
                }
                final String res = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tv.setText("Server: " + c.url + "\nNama: " + c.name + "\n\n" + res);
                    }
                });
            }
        }).start();
    }
}
