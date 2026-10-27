package com.vinz.wilds;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Proximity voice chat: 8 kHz mono, G.711 u-law, 20 ms frames, sent as binary WebSocket
 * messages. The server only relays frames to players within ~160 m.
 */
final class VoiceChat {
    static final int RATE = 8000;
    static final int FRAME = 160;
    private static final float RANGE = 150f;
    private static final int BIAS = 0x84, CLIP = 32635;

    private static final class Spk {
        final ConcurrentLinkedQueue<short[]> q = new ConcurrentLinkedQueue<>();
        boolean playing = false;
    }

    private final Net net;
    private volatile boolean running = true;
    private volatile boolean micOn = false;
    private Thread recThread;
    private final Thread playThread;
    private final ConcurrentHashMap<Integer, Spk> speakers = new ConcurrentHashMap<>();

    VoiceChat(Net n) {
        net = n;
        playThread = new Thread(new Runnable() {
            @Override
            public void run() {
                playLoop();
            }
        }, "voice-play");
        playThread.setDaemon(true);
        playThread.start();
    }

    // ------------------------------------------------------------------ codec
    static byte enc(short s) {
        int sign = (s >> 8) & 0x80;
        int v = s;
        if (sign != 0) v = -v;
        if (v > CLIP) v = CLIP;
        v += BIAS;
        int exp = 7;
        for (int mask = 0x4000; (v & mask) == 0 && exp > 0; exp--, mask >>= 1) {
            // find exponent
        }
        int mant = (v >> (exp + 3)) & 0x0F;
        return (byte) ~(sign | (exp << 4) | mant);
    }

    static short dec(byte b) {
        int u = ~b & 0xFF;
        int sign = u & 0x80;
        int exp = (u >> 4) & 7;
        int mant = u & 0x0F;
        int v = (((mant << 3) + BIAS) << exp) - BIAS;
        return (short) (sign != 0 ? -v : v);
    }

    // ------------------------------------------------------------------ microphone
    synchronized void setMic(boolean on) {
        if (on) {
            if (micOn) return;
            micOn = true;
            recThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    recLoop();
                }
            }, "voice-rec");
            recThread.setDaemon(true);
            recThread.start();
        } else {
            micOn = false;
        }
    }

    private void recLoop() {
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min, FRAME * 2 * 8);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                net.micOn = false;
                net.micDenied = true;
                micOn = false;
                return;
            }
            rec.startRecording();
            short[] pcm = new short[FRAME];
            byte[] pkt = new byte[1 + FRAME];
            pkt[0] = 1;
            int hang = 0;
            while (micOn && running) {
                int n = 0;
                while (n < FRAME && micOn) {
                    int r = rec.read(pcm, n, FRAME - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (n < FRAME) break;
                long sum = 0;
                for (int i = 0; i < FRAME; i++) sum += (long) pcm[i] * pcm[i];
                double rms = Math.sqrt(sum / (double) FRAME);
                if (rms > 350.0) hang = 15; // keep sending ~300 ms after speech
                if (hang > 0) {
                    hang--;
                    for (int i = 0; i < FRAME; i++) pkt[1 + i] = enc(pcm[i]);
                    net.sendVoice(pkt);
                }
            }
        } catch (Throwable ignored) {
            net.micOn = false;
            micOn = false;
        } finally {
            try {
                if (rec != null) {
                    rec.stop();
                    rec.release();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ playback
    void onFrame(int senderId, byte[] data, int off) {
        if (data.length < off + FRAME) return;
        short[] f = new short[FRAME];
        for (int i = 0; i < FRAME; i++) f[i] = dec(data[off + i]);
        Spk s = speakers.get(senderId);
        if (s == null) {
            s = new Spk();
            speakers.put(senderId, s);
        }
        s.q.add(f);
        while (s.q.size() > 12) s.q.poll();
    }

    private float gainFor(int id) {
        Net.Remote r = net.players.get(id);
        if (r == null) return 0.35f;
        float dx = r.tx - net.myX, dz = r.tz - net.myZ;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        if (d < 10f) return 1f;
        float g = 1f - (d - 10f) / (RANGE - 10f);
        return g < 0.08f ? 0.08f : g;
    }

    private void playLoop() {
        AudioTrack track;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, FRAME * 2 * 6))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (Throwable t) {
            return;
        }
        short[] mix = new short[FRAME];
        int[] acc = new int[FRAME];
        while (running) {
            boolean any = false;
            Arrays.fill(acc, 0);
            for (Map.Entry<Integer, Spk> e : speakers.entrySet()) {
                Spk s = e.getValue();
                if (!s.playing) {
                    if (s.q.size() >= 3) s.playing = true; // small jitter buffer
                    else continue;
                }
                short[] f = s.q.poll();
                if (f == null) {
                    s.playing = false;
                    continue;
                }
                float g = gainFor(e.getKey());
                for (int i = 0; i < FRAME; i++) acc[i] += (int) (f[i] * g);
                any = true;
            }
            if (!any) {
                try {
                    Thread.sleep(8);
                } catch (InterruptedException ex) {
                    break;
                }
                continue;
            }
            for (int i = 0; i < FRAME; i++) {
                int v = acc[i];
                if (v > 32767) v = 32767;
                if (v < -32768) v = -32768;
                mix[i] = (short) v;
            }
            track.write(mix, 0, FRAME);
        }
        try {
            track.stop();
            track.release();
        } catch (Throwable ignored) {
        }
    }

    void release() {
        running = false;
        micOn = false;
        playThread.interrupt();
        speakers.clear();
    }
}
