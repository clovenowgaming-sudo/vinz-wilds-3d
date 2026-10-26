package com.vinz.wilds;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;

/** Tiny procedural sound effects (no asset files needed). */
final class Sfx {
    static final int SHOT = 0, HIT = 1, PICK = 2, HURT = 3, DIE = 4, RELOAD = 5;
    private static final int RATE = 22050;
    private static final AudioTrack[] tracks = new AudioTrack[6];
    private static boolean ready = false;

    private Sfx() {}

    static synchronized void init() {
        if (ready) return;
        try {
            Random r = new Random(7);
            tracks[SHOT] = make(shot(r));
            tracks[HIT] = make(hit(r));
            tracks[PICK] = make(pick());
            tracks[HURT] = make(hurt(r));
            tracks[DIE] = make(die(r));
            tracks[RELOAD] = make(reload(r));
            ready = true;
        } catch (Throwable t) {
            ready = false;
        }
    }

    static void play(int id) {
        if (!ready) return;
        try {
            AudioTrack t = tracks[id];
            if (t == null) return;
            t.stop();
            t.reloadStaticData();
            t.play();
        } catch (Throwable ignored) {
        }
    }

    static synchronized void release() {
        for (int i = 0; i < tracks.length; i++) {
            try {
                if (tracks[i] != null) tracks[i].release();
            } catch (Throwable ignored) {
            }
            tracks[i] = null;
        }
        ready = false;
    }

    @SuppressWarnings("deprecation")
    private static AudioTrack make(short[] pcm) {
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.length * 2, AudioTrack.MODE_STATIC);
        t.write(pcm, 0, pcm.length);
        return t;
    }

    private static short s(double v) {
        if (v > 1.0) v = 1.0;
        if (v < -1.0) v = -1.0;
        return (short) (v * 30000);
    }

    private static short[] shot(Random r) {
        int n = (int) (RATE * 0.20);
        short[] d = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double env = Math.pow(1.0 - t, 2.2);
            double f = 520 - 440 * t;
            ph += 2 * Math.PI * f / RATE;
            double v = (r.nextDouble() * 2 - 1) * 0.55 + Math.sin(ph) * 0.6;
            d[i] = s(v * env * 0.8);
        }
        return d;
    }

    private static short[] hit(Random r) {
        int n = (int) (RATE * 0.09);
        short[] d = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double env = Math.pow(1.0 - t, 3.0);
            d[i] = s((r.nextDouble() * 2 - 1) * env * 0.7);
        }
        return d;
    }

    private static short[] pick() {
        int n = (int) (RATE * 0.32);
        short[] d = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double f = t < 0.5 ? 660 : 990;
            ph += 2 * Math.PI * f / RATE;
            double env = Math.pow(1.0 - t, 1.5);
            d[i] = s(Math.sin(ph) * env * 0.6);
        }
        return d;
    }

    private static short[] hurt(Random r) {
        int n = (int) (RATE * 0.24);
        short[] d = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            ph += 2 * Math.PI * (140 - 70 * t) / RATE;
            double saw = ((ph / (2 * Math.PI)) % 1.0) * 2 - 1;
            double env = Math.pow(1.0 - t, 1.4);
            d[i] = s((saw * 0.6 + (r.nextDouble() * 2 - 1) * 0.25) * env);
        }
        return d;
    }

    private static short[] die(Random r) {
        int n = (int) (RATE * 0.45);
        short[] d = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            ph += 2 * Math.PI * (300 - 260 * t) / RATE;
            double env = Math.pow(1.0 - t, 1.2);
            d[i] = s((Math.sin(ph) * 0.5 + (r.nextDouble() * 2 - 1) * 0.35) * env);
        }
        return d;
    }

    private static short[] reload(Random r) {
        int n = (int) (RATE * 0.32);
        short[] d = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double local = (t < 0.5) ? t * 2 : (t - 0.5) * 2;
            double env = Math.pow(1.0 - local, 6.0);
            d[i] = s((r.nextDouble() * 2 - 1) * env * 0.5);
        }
        return d;
    }
}
