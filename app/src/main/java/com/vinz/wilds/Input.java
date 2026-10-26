package com.vinz.wilds;

import android.view.MotionEvent;

/**
 * Touch input. All coordinates are in "HUD units": screen height = 10 units,
 * width = 10 * aspect. Left side = floating joystick, right side = look drag,
 * plus FIRE / JUMP / RELOAD buttons.
 */
final class Input {
    static final float H = 10f;
    static final float JOY_R = 1.7f;
    static final float JOY_DEF_X = 2.8f, JOY_DEF_Y = 7.4f;

    float vw = 1f, vh = 1f;
    volatile float W = 20f;
    final float[] fire = new float[3], jump = new float[3], reload = new float[3];

    volatile float moveX, moveY;
    volatile boolean firing, joyActive;
    volatile float joyCX = JOY_DEF_X, joyCY = JOY_DEF_Y, joyKX = JOY_DEF_X, joyKY = JOY_DEF_Y;

    private boolean jumpQ, reloadQ, tapQ;
    private float lookDX, lookDY;
    private int joyId = -1, lookId = -1, fireId = -1;
    private float lastX, lastY;

    // polled values (read by the game thread)
    float oLookX, oLookY;
    boolean oJump, oReload, oTap;

    Input() {
        layout(20f);
    }

    synchronized void setViewSize(float w, float h) {
        if (w < 1f || h < 1f) return;
        vw = w;
        vh = h;
        layout(H * w / h);
    }

    private void layout(float w) {
        W = w;
        set(fire, w - 2.2f, H - 2.4f, 1.25f);
        set(jump, w - 4.9f, H - 1.5f, 0.95f);
        set(reload, w - 5.0f, H - 3.7f, 0.8f);
    }

    private static void set(float[] a, float x, float y, float r) {
        a[0] = x;
        a[1] = y;
        a[2] = r;
    }

    private static boolean hit(float[] b, float x, float y) {
        float dx = x - b[0], dy = y - b[1];
        return dx * dx + dy * dy <= b[2] * b[2] * 1.35f;
    }

    synchronized void onTouch(MotionEvent e) {
        int act = e.getActionMasked();
        int idx = e.getActionIndex();
        float k = H / vh;
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                tapQ = true;
                down(e.getPointerId(idx), e.getX(idx) * k, e.getY(idx) * k);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    move(e.getPointerId(i), e.getX(i) * k, e.getY(i) * k);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                up(e.getPointerId(idx));
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                up(joyId);
                up(lookId);
                up(fireId);
                break;
            }
            default:
                break;
        }
    }

    private void down(int id, float x, float y) {
        if (hit(fire, x, y)) {
            fireId = id;
            firing = true;
            return;
        }
        if (hit(jump, x, y)) {
            jumpQ = true;
            return;
        }
        if (hit(reload, x, y)) {
            reloadQ = true;
            return;
        }
        if (x < W * 0.42f && joyId == -1) {
            joyId = id;
            joyActive = true;
            joyCX = x;
            joyCY = y;
            joyKX = x;
            joyKY = y;
            moveX = 0f;
            moveY = 0f;
            return;
        }
        if (lookId == -1) {
            lookId = id;
            lastX = x;
            lastY = y;
        }
    }

    private void move(int id, float x, float y) {
        if (id == joyId) {
            float dx = x - joyCX, dy = y - joyCY;
            float l = (float) Math.sqrt(dx * dx + dy * dy);
            if (l > JOY_R) {
                dx *= JOY_R / l;
                dy *= JOY_R / l;
            }
            joyKX = joyCX + dx;
            joyKY = joyCY + dy;
            moveX = dx / JOY_R;
            moveY = -dy / JOY_R;
        } else if (id == lookId) {
            lookDX += x - lastX;
            lookDY += y - lastY;
            lastX = x;
            lastY = y;
        }
    }

    private void up(int id) {
        if (id < 0) return;
        if (id == joyId) {
            joyId = -1;
            joyActive = false;
            moveX = 0f;
            moveY = 0f;
            joyCX = JOY_DEF_X;
            joyCY = JOY_DEF_Y;
            joyKX = JOY_DEF_X;
            joyKY = JOY_DEF_Y;
        }
        if (id == lookId) lookId = -1;
        if (id == fireId) {
            fireId = -1;
            firing = false;
        }
    }

    synchronized void poll() {
        oLookX = lookDX;
        oLookY = lookDY;
        lookDX = 0f;
        lookDY = 0f;
        oJump = jumpQ;
        jumpQ = false;
        oReload = reloadQ;
        reloadQ = false;
        oTap = tapQ;
        tapQ = false;
    }
}
