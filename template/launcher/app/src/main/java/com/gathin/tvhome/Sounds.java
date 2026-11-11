package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * The cinema screen's soft interface sounds, generated on the TV itself (no audio assets shipped):
 * a short, quiet click when the app ring turns one place and a deeper thud when OK opens the app.
 *
 * <p>Both are tiny PCM tones synthesised once into the cache directory and played through a
 * {@link SoundPool} with four streams, so several fast key presses overlap instead of cutting each other
 * off, and nothing blocks the UI thread. The launcher keeps no other sounds.
 */
final class Sounds {

    private static final String TAG = "CinemaSounds";
    private static final int RATE = 44100;

    private final SoundPool pool;
    private final int tickId;
    private final int confirmId;
    private boolean released;

    Sounds(Context context) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build();
        tickId = load(context, "cyt_tick_v1.wav", tickTone());
        confirmId = load(context, "cyt_confirm_v1.wav", confirmTone());
        Log.i(TAG, "ready tick=" + tickId + " confirm=" + confirmId);
    }

    /** The ring turned one app: a soft wooden click, quiet enough to stay under the TV's own UI. */
    void focusTick() {
        play(tickId, 0.55f);
    }

    /** OK was pressed: a deeper, rounder thud that confirms the app is opening. */
    void confirm() {
        play(confirmId, 0.85f);
    }

    private void play(int id, float volume) {
        if (released || id == 0) return;
        try {
            pool.play(id, volume, volume, 1, 0, 1f);
        } catch (RuntimeException ignored) {
            // a released pool or a missing stream: silence is the correct failure for a UI sound
        }
    }

    void release() {
        if (released) return;
        released = true;
        try {
            pool.release();
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    private int load(Context context, String name, short[] samples) {
        String path = write(context, name, samples);
        if (path == null) return 0;
        try {
            return pool.load(path, 1);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Writes a 16-bit mono PCM WAV into the cache on first use; returns its path, or null on failure. */
    private static String write(Context context, String name, short[] samples) {
        File file = new File(context.getCacheDir(), name);
        int dataLength = samples.length * 2;
        if (file.isFile() && file.length() == 44 + dataLength) return file.getAbsolutePath();
        try (FileOutputStream out = new FileOutputStream(file)) {
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
            header.putInt(36 + dataLength);
            header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
            header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
            header.putInt(16);                       // PCM chunk size
            header.putShort((short) 1);              // PCM
            header.putShort((short) 1);              // mono
            header.putInt(RATE);
            header.putInt(RATE * 2);                 // byte rate
            header.putShort((short) 2);              // block align
            header.putShort((short) 16);             // bits per sample
            header.put("data".getBytes(StandardCharsets.US_ASCII));
            header.putInt(dataLength);
            out.write(header.array());
            byte[] data = new byte[dataLength];
            for (int i = 0; i < samples.length; i++) {
                data[i * 2] = (byte) (samples[i] & 0xFF);
                data[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
            }
            out.write(data);
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + name, e);
            return null;
        }
        return file.getAbsolutePath();
    }

    /**
     * The soft click: a short 1180 Hz ping with its second harmonic, plus a tiny low-passed noise
     * transient that gives it the "snap" of a carousel detent, 60 ms in all, quiet.
     */
    private static short[] tickTone() {
        int n = (int) (RATE * 0.06);
        short[] out = new short[n];
        Random random = new Random(7L);
        double lowpass = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double attack = Math.min(1, t / 0.0012);
            double decay = Math.exp(-t / 0.011);
            double body = 0.55 * Math.sin(2 * Math.PI * 1180 * t)
                    + 0.28 * Math.sin(2 * Math.PI * 2360 * t + 0.6)
                    + 0.18 * Math.sin(2 * Math.PI * 590 * t);
            // A 4 ms transient, low-passed so it clicks instead of hissing.
            lowpass = 0.35 * (random.nextDouble() * 2 - 1) + 0.65 * lowpass;
            double snap = lowpass * Math.exp(-t / 0.0022) * 0.55;
            double value = attack * decay * body * 0.8 + snap;
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE,
                    Math.round(value * 0.30 * 32767)));
        }
        return out;
    }

    /**
     * The confirmation: a round 210→110 Hz sweep with a sub-bass companion, 240 ms, a bit louder than the
     * click. Deeper than any of the launcher's other sounds on purpose.
     */
    private static short[] confirmTone() {
        int n = (int) (RATE * 0.24);
        short[] out = new short[n];
        Random random = new Random(11L);
        double lowpass = 0;
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double frequency = 210 * Math.exp(-t / 0.09) + 108;
            phase += 2 * Math.PI * frequency / RATE;
            double attack = Math.min(1, t / 0.004);
            double decay = Math.exp(-t / 0.065);
            double body = 0.62 * Math.sin(phase)
                    + 0.30 * Math.sin(2 * Math.PI * 96 * t + 0.5)
                    + 0.18 * Math.sin(phase * 2);
            lowpass = 0.22 * (random.nextDouble() * 2 - 1) + 0.78 * lowpass;
            double thump = lowpass * Math.exp(-t / 0.004) * 0.35;
            double value = attack * decay * body + thump;
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE,
                    Math.round(value * 0.62 * 32767)));
        }
        return out;
    }
}
