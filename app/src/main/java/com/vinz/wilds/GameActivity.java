package com.vinz.wilds;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

public class GameActivity extends Activity {
    private GLSurfaceView glView;
    private final Input input = new Input();
    private TextView overlay;
    private Net net;
    private boolean sized = false;
    private boolean paused = true;

    /** Picks an ES3 config, tries MSAA first when requested, falls back gracefully. */
    private static final class Chooser implements GLSurfaceView.EGLConfigChooser {
        private final int samples;

        Chooser(int s) {
            samples = s;
        }

        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay d) {
            int[][] tries = samples > 0
                    ? new int[][]{{24, samples}, {16, samples}, {24, 0}, {16, 0}}
                    : new int[][]{{24, 0}, {16, 0}};
            for (int[] t : tries) {
                int[] a = {
                        EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                        EGL10.EGL_ALPHA_SIZE, 0, EGL10.EGL_DEPTH_SIZE, t[0],
                        EGL10.EGL_RENDERABLE_TYPE, 0x40,
                        EGL10.EGL_SAMPLE_BUFFERS, t[1] > 0 ? 1 : 0, EGL10.EGL_SAMPLES, t[1],
                        EGL10.EGL_NONE
                };
                int[] n = new int[1];
                EGLConfig[] c = new EGLConfig[1];
                if (egl.eglChooseConfig(d, a, c, 1, n) && n[0] > 0) return c[0];
            }
            throw new IllegalArgumentException("No suitable OpenGL ES 3.0 config");
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        boolean mp = getIntent().getBooleanExtra("mp", false);
        final Config cfg = Config.load(this, mp);
        Sfx.enabled = cfg.sound;
        Sfx.init();
        if (mp) net = new Net(cfg.url, cfg.name);
        final float scale = Math.max(0.4f, Math.min(1f, cfg.res));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(3);
        glView.setEGLConfigChooser(new Chooser(cfg.q == 1 ? 4 : 0));
        GameRenderer.Callback cb = new GameRenderer.Callback() {
            @Override
            public void ready() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        overlay.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void error(final String s) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        overlay.setVisibility(View.VISIBLE);
                        overlay.setText("ERROR\n\n" + s + "\n\n(Back untuk keluar)");
                    }
                });
            }

            @Override
            public void progress(final String s) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        overlay.setText(s);
                    }
                });
            }
        };
        glView.setRenderer(new GameRenderer(cfg, input, net, cb));
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                input.onTouch(e);
                return true;
            }
        });
        glView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int bt, int ol, int ot, int orr, int ob) {
                final int w = r - l, h = bt - t;
                if (w <= 0 || h <= 0) return;
                input.setViewSize(w, h);
                if (!sized) {
                    sized = true;
                    if (scale < 0.99f) {
                        final int fw = Math.max(320, (int) (w * scale));
                        final int fh = Math.max(180, (int) (h * scale));
                        glView.post(new Runnable() {
                            @Override
                            public void run() {
                                glView.getHolder().setFixedSize(fw, fh);
                            }
                        });
                    }
                }
            }
        });
        root.addView(glView, new FrameLayout.LayoutParams(-1, -1));

        overlay = new TextView(this);
        overlay.setText("LOADING WORLD...");
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(20f);
        overlay.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(Color.BLACK);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        if (net != null && paused) net.connect();
        paused = false;
        immersive();
    }

    @Override
    protected void onPause() {
        glView.onPause();
        if (net != null) net.pause();
        paused = true;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (net != null) net.close();
        Sfx.release();
        super.onDestroy();
    }
}
