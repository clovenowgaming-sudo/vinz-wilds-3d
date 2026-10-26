package com.vinz.wilds;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
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
    private boolean sized = false;

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
        int q = getIntent().getIntExtra("q", 1);
        if (q < 0) q = 0;
        if (q > 2) q = 2;
        final float scale = q == 0 ? 0.6f : q == 1 ? 0.85f : 1.0f;
        Sfx.init();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(3);
        glView.setEGLConfigChooser(new Chooser(q == 1 ? 4 : 0));
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
                        overlay.setText("GRAPHICS ERROR\n\n" + s + "\n\nCoba kualitas yang lebih rendah.");
                    }
                });
            }
        };
        glView.setRenderer(new GameRenderer(q, input, cb));
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                input.onTouch(e);
                return true;
            }
        });
        glView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int bt, int ol, int ot, int or, int ob) {
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
        immersive();
    }

    @Override
    protected void onPause() {
        glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Sfx.release();
        super.onDestroy();
    }
}
