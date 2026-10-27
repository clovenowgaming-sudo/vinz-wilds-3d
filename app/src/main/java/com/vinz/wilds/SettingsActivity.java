package com.vinz.wilds;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Settings screen: graphics, controls, audio and multiplayer. Everything saves instantly. */
public class SettingsActivity extends Activity {
    private SharedPreferences sp;
    private LinearLayout col;
    private TextView qInfo;
    private final int[] segIds = new int[0];

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable box(int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1.5f), strokeColor);
        return g;
    }

    private TextView label(String t, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(0x55101830, 14, 0x33FFFFFF));
        c.setPadding(dp(14), dp(10), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        col.addView(c, lp);
        return c;
    }

    private void section(String title) {
        TextView t = label(title, 13f, 0xFFFFC27A, true);
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(4), dp(14), 0, dp(2));
        col.addView(t);
    }

    /** Segmented single-choice row. */
    private void choice(LinearLayout parent, final String[] opts, int selected, final OnPick cb) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final Button[] bs = new Button[opts.length];
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(opts[i]);
            b.setTextSize(12f);
            b.setTextColor(Color.WHITE);
            b.setPadding(dp(6), dp(4), dp(6), dp(4));
            b.setMinHeight(0);
            b.setMinimumHeight(dp(38));
            bs[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(3), dp(6), dp(3), 0);
            row.addView(b, lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    paintSeg(bs, idx);
                    cb.pick(idx);
                }
            });
        }
        paintSeg(bs, selected);
        parent.addView(row);
    }

    private void paintSeg(Button[] bs, int sel) {
        for (int i = 0; i < bs.length; i++) {
            bs[i].setBackground(box(i == sel ? 0xFF3B7DD8 : 0x33FFFFFF, 10, i == sel ? 0xFF8DBBFF : 0));
        }
    }

    interface OnPick {
        void pick(int idx);
    }

    private void toggle(String title, String sub, final String key, boolean def) {
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(label(title, 15f, Color.WHITE, true));
        if (sub != null) txt.addView(label(sub, 11f, 0xFFB8C4E8, false));
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(sp.getBoolean(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                sp.edit().putBoolean(key, on).apply();
            }
        });
        row.addView(sw);
        c.addView(row);
    }

    private void slider(String title, final String key, final float min, final float max, final float step,
                        float def, final String unit) {
        LinearLayout c = card();
        final TextView head = label("", 15f, Color.WHITE, true);
        c.addView(head);
        final int steps = Math.round((max - min) / step);
        float cur = sp.getFloat(key, def);
        final String t0 = title;
        head.setText(t0 + ":  " + fmt(cur) + unit);
        SeekBar sb = new SeekBar(this);
        sb.setMax(steps);
        sb.setProgress(Math.round((cur - min) / step));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                float v = min + p * step;
                head.setText(t0 + ":  " + fmt(v) + unit);
                if (user) sp.edit().putFloat(key, v).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        c.addView(sb);
    }

    private static String fmt(float v) {
        return String.format("%.1f", v);
    }

    private void textField(String title, String sub, final String key, String def, final boolean url) {
        LinearLayout c = card();
        c.addView(label(title, 15f, Color.WHITE, true));
        if (sub != null) c.addView(label(sub, 11f, 0xFFB8C4E8, false));
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(0x88FFFFFF);
        et.setTextSize(14f);
        et.setInputType(url ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI : InputType.TYPE_CLASS_TEXT);
        et.setText(sp.getString(key, def));
        et.setBackground(box(0x33FFFFFF, 10, 0));
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c2) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c2) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                sp.edit().putString(key, e.toString().trim()).apply();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, 0);
        c.addView(et, lp);
        if (url) {
            Button reset = new Button(this);
            reset.setAllCaps(false);
            reset.setText("Reset ke server default");
            reset.setTextSize(12f);
            reset.setTextColor(Color.WHITE);
            reset.setBackground(box(0x33FFFFFF, 10, 0));
            reset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    et.setText(Config.DEFAULT_URL);
                }
            });
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, dp(38));
            lp2.setMargins(0, dp(6), 0, 0);
            c.addView(reset, lp2);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sp = Config.sp(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0B1026, 0xFF1B1840, 0xFF3A2147}));
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(80), dp(14), dp(80), dp(30));
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("< BACK");
        back.setAllCaps(false);
        back.setTextColor(Color.WHITE);
        back.setBackground(box(0x44FFFFFF, 12, 0));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back, new LinearLayout.LayoutParams(dp(100), dp(40)));
        TextView title = label("SETTINGS", 24f, Color.WHITE, true);
        title.setPadding(dp(16), 0, 0, 0);
        head.addView(title);
        col.addView(head);

        // ---------------- graphics
        section("GRAPHICS");
        int rec = Config.recommended(this);
        String[] qn = {"LOW", "MEDIUM", "EXTREME"};
        LinearLayout qc = card();
        qc.addView(label("Preset kualitas", 15f, Color.WHITE, true));
        qInfo = label("", 11f, 0xFFB8C4E8, false);
        final String[] qDesc = {
                "Ringan: tanpa shadow, jarak pandang dekat, resolusi rendah. Untuk HP lemah (Helio P35 / RAM 3 GB).",
                "Seimbang: shadow 1K, MSAA 4x, rumput, awan, jarak pandang sedang.",
                "Maksimal: shadow 2K halus, bloom, god rays, FXAA, jarak pandang jauh. Butuh GPU kuat."};
        int cq = sp.getInt("q", rec);
        qInfo.setText(qDesc[cq] + "\nRekomendasi untuk HP ini: " + qn[rec]);
        qc.addView(qInfo);
        choice(qc, qn, cq, new OnPick() {
            @Override
            public void pick(int idx) {
                sp.edit().putInt("q", idx).apply();
                qInfo.setText(qDesc[idx] + "\nRekomendasi untuk HP ini: " + qn[Config.recommended(SettingsActivity.this)]);
            }
        });

        LinearLayout rc = card();
        rc.addView(label("Resolusi render", 15f, Color.WHITE, true));
        rc.addView(label("Turunkan kalau FPS rendah. AUTO mengikuti preset.", 11f, 0xFFB8C4E8, false));
        final float[] rv = {-1f, 0.5f, 0.6f, 0.7f, 0.85f, 1.0f};
        String[] rn = {"AUTO", "50%", "60%", "70%", "85%", "100%"};
        float cr = sp.getFloat("res", -1f);
        int ri = 0;
        for (int i = 0; i < rv.length; i++) if (Math.abs(rv[i] - cr) < 0.01f) ri = i;
        choice(rc, rn, ri, new OnPick() {
            @Override
            public void pick(int idx) {
                if (rv[idx] < 0f) sp.edit().remove("res").apply();
                else sp.edit().putFloat("res", rv[idx]).apply();
            }
        });

        LinearLayout vc = card();
        vc.addView(label("Jarak pandang", 15f, Color.WHITE, true));
        vc.addView(label("Makin jauh makin berat. Dunia di-stream per chunk.", 11f, 0xFFB8C4E8, false));
        final float[] vv = {0.6f, 0.8f, 1.0f, 1.3f};
        String[] vn = {"PENDEK", "SEDANG", "NORMAL", "JAUH"};
        float cvw = sp.getFloat("view", 1f);
        int vi = 2;
        for (int i = 0; i < vv.length; i++) if (Math.abs(vv[i] - cvw) < 0.01f) vi = i;
        choice(vc, vn, vi, new OnPick() {
            @Override
            public void pick(int idx) {
                sp.edit().putFloat("view", vv[idx]).apply();
            }
        });

        toggle("Shadow", "Hanya berlaku di MEDIUM / EXTREME", "shadows", true);
        toggle("Rumput", "Matikan untuk menambah FPS (MEDIUM / EXTREME)", "grass", true);
        toggle("Tampilkan FPS", null, "fps", true);

        // ---------------- controls
        section("CONTROLS");
        slider("Sensitivitas kamera", "sens", 0.5f, 2.5f, 0.1f, 1f, "x");
        toggle("Invert Y", "Geser ke atas = lihat ke bawah", "invy", false);

        // ---------------- audio
        section("AUDIO");
        toggle("Efek suara", null, "sound", true);

        // ---------------- multiplayer
        section("MULTIPLAYER");
        textField("Nama pemain", "Tampil di atas karaktermu", "name", "", false);
        textField("Alamat server", "Server Node.js di HP + Cloudflare Tunnel", "url", Config.DEFAULT_URL, true);

        // ---------------- reset
        Button reset = new Button(this);
        reset.setAllCaps(false);
        reset.setText("Reset semua ke default");
        reset.setTextColor(Color.WHITE);
        reset.setBackground(box(0xFF8E2B2B, 12, 0));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().clear().apply();
                recreate();
            }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, dp(44));
        rl.setMargins(0, dp(20), 0, 0);
        col.addView(reset, rl);

        setContentView(sv);
    }
}
