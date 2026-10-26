package com.vinz.wilds;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

public class MainActivity extends Activity {
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double gb = mi.totalMem / 1073741824.0;
        final int rec = gb < 3.6 ? 0 : gb < 5.5 ? 1 : 2;
        final SharedPreferences sp = getSharedPreferences("vinz", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0B1026, 0xFF2B1B4D, 0xFF9A3F3A});
        sv.setBackground(bg);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(16), dp(24), dp(16));
        sv.addView(root, new ScrollView.LayoutParams(-1, -1));

        // left column: logo + title
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        left.addView(logo, new LinearLayout.LayoutParams(dp(110), dp(110)));
        TextView title = new TextView(this);
        title.setText("VINZ WILDS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30f);
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
        TextView info = new TextView(this);
        info.setText(String.format("RAM %.1f GB  |  Rekomendasi: %s", gb, rec == 0 ? "LOW" : rec == 1 ? "MEDIUM" : "EXTREME"));
        info.setTextColor(0xFFB8C4E8);
        info.setTextSize(12f);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(10), 0, 0);
        left.addView(info);
        root.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        // right column: quality buttons + help
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER);
        String[] names = {"LOW", "MEDIUM", "EXTREME"};
        String[] descs = {
                "Cepat & ringan - tanpa shadow, resolusi 60%",
                "Seimbang - shadow 1K, MSAA 4x, rumput, awan",
                "Maksimal - shadow 2K PCF, bloom, god rays, FXAA"
        };
        int[] colors = {0xFF2E7D4F, 0xFF2F6DB5, 0xFFB5342F};
        int saved = sp.getInt("q", rec);
        for (int i = 0; i < 3; i++) {
            final int q = i;
            Button btn = new Button(this);
            String t = names[i] + (i == rec ? "  (REKOMENDASI)" : "") + (i == saved && i != rec ? "  (TERAKHIR)" : "") + "\n" + descs[i];
            btn.setText(t);
            btn.setAllCaps(false);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(13f);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(colors[i]);
            gd.setCornerRadius(dp(22));
            gd.setStroke(dp(2), 0x66FFFFFF);
            btn.setBackground(gd);
            btn.setPadding(dp(16), dp(10), dp(16), dp(10));
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sp.edit().putInt("q", q).apply();
                    Intent it = new Intent(MainActivity.this, GameActivity.class);
                    it.putExtra("q", q);
                    startActivity(it);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(6), 0, dp(6));
            right.addView(btn, lp);
        }
        TextView help = new TextView(this);
        help.setText("Kiri: gerak (dorong sampai ujung = lari)  |  Kanan: arahkan kamera\n"
                + "FIRE tembak, JUMP lompat, R reload\n"
                + "Kumpulkan 12 Aether Crystal. Wraith berburu saat malam, terbakar saat siang.");
        help.setTextColor(0xFFD7DDF2);
        help.setTextSize(11.5f);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, dp(10), 0, 0);
        right.addView(help);
        root.addView(right, new LinearLayout.LayoutParams(0, -2, 1.4f));

        setContentView(sv);
    }
}
