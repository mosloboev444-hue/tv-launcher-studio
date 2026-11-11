package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * The karaoke tile's banner: animated fire with «КАРАОКЕ!!!» burning on top of it.
 *
 * <p>Tiles normally show a static banner. This one is a {@link Drawable} that animates itself while it is
 * visible (config.xml fire_app), so the fire burns only while Home is on screen: {@link #setVisible} from
 * the ImageView starts and stops it, and nothing runs in the background.
 */
public final class FireBannerDrawable extends Drawable implements Animatable {

    private static final int EMBERS = 22;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] emberX = new float[EMBERS];
    private final float[] emberPhase = new float[EMBERS];
    private final float[] emberSpeed = new float[EMBERS];
    private final float[] emberSize = new float[EMBERS];
    private final float[][] tongueX = new float[3][];
    private final float[][] tongueW = new float[3][];
    private final float[][] tongueH = new float[3][];
    private final float[][] tongueP = new float[3][];

    private boolean running;
    private long start;
    private long lastFrame;
    private ValueAnimator animator;

    private final ValueAnimator.AnimatorUpdateListener update = animation -> {
        long now = SystemClock.uptimeMillis();
        if (now - lastFrame < 33) return;
        lastFrame = now;
        invalidateSelf();
    };

    public FireBannerDrawable() {
        textPaint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setShadowLayer(10f, 0f, 0f, 0xFFFF5A00);

        Random random = new Random(20261104L);
        for (int i = 0; i < EMBERS; i++) {
            emberX[i] = random.nextFloat();
            emberPhase[i] = random.nextFloat();
            emberSpeed[i] = 0.5f + random.nextFloat();
            emberSize[i] = 1.2f + random.nextFloat() * 2.2f;
        }
        int[] counts = {16, 22, 28};
        for (int layer = 0; layer < 3; layer++) {
            int n = counts[layer];
            tongueX[layer] = new float[n];
            tongueW[layer] = new float[n];
            tongueH[layer] = new float[n];
            tongueP[layer] = new float[n];
            for (int i = 0; i < n; i++) {
                tongueX[layer][i] = (i + 0.2f + random.nextFloat() * 0.6f) / n;
                tongueW[layer][i] = 0.7f + random.nextFloat() * 0.8f;
                tongueH[layer][i] = 0.5f + random.nextFloat() * 0.8f;
                tongueP[layer][i] = random.nextFloat() * 6.283f;
            }
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        int w = b.width();
        int h = b.height();
        float t = running ? (SystemClock.uptimeMillis() - start) / 1000f : 0.3f;

        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF1B0600, 0xFF070100, 0xFF000000}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(b, paint);

        paint.setShader(new RadialGradient(w * 0.5f, h * 1.05f, Math.max(w * 0.55f, h * 0.9f),
                0x88FF3D00, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(b, paint);
        paint.setShader(null);

        sheet(canvas, w, h, t, 0, 0.96f, 0.58f, 0xD0, 0xFF9E1B00);
        sheet(canvas, w, h, t, 1, 0.99f, 0.44f, 0xF0, 0xFFFF4D00);
        sheet(canvas, w, h, t, 2, 1.02f, 0.30f, 0xFF, 0xFFFFB300);

        drawEmbers(canvas, w, h, t);

        int size = (int) (h * 0.34f);
        textPaint.setTextSize(size);
        float max = w * 0.86f;
        while (textPaint.measureText("КАРАОКЕ!!!") > max && size > 8) {
            size -= 2;
            textPaint.setTextSize(size);
        }
        canvas.drawText("КАРАОКЕ!!!", w / 2f, h * 0.46f, textPaint);
    }

    private void sheet(Canvas c, int w, int h, float t, int layer, float base, float height,
                       int alpha, int colour) {
        float baseY = h * base;
        float maxH = h * height;
        float halfBase = (layer == 0 ? 0.070f : layer == 1 ? 0.048f : 0.032f) * w;
        float step = Math.max(2f, w / 120f);

        paint.setAlpha(255);
        path.reset();
        path.moveTo(0, h);
        for (float x = 0; x <= w; x += step) {
            float y = baseY;
            for (int i = 0; i < tongueX[layer].length; i++) {
                float centre = (tongueX[layer][i] + (float) Math.sin(t * 0.12f + tongueP[layer][i]) * 0.03f) * w;
                float half = halfBase * tongueW[layer][i];
                float d = Math.abs(x - centre);
                if (d >= half) continue;
                float tip = (float) Math.pow(1f - d / half, 0.55f);
                float breath = 0.55f + 0.45f * (float) Math.sin(t * (0.9f + tongueP[layer][i] * 0.4f) + tongueP[layer][i] * 5f);
                float tongue = maxH * tongueH[layer][i] * breath * tip;
                if (baseY - tongue < y) y = baseY - tongue;
            }
            path.lineTo(x, y);
        }
        path.lineTo(w, h);
        path.close();

        paint.setShader(new LinearGradient(0, baseY - maxH, 0, h,
                0x00FFFFFF & colour, (alpha << 24) | (colour & 0x00FFFFFF), Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);
    }

    private void drawEmbers(Canvas c, int w, int h, float t) {
        for (int i = 0; i < EMBERS; i++) {
            float cycle = (t * emberSpeed[i] + emberPhase[i]) % 1f;
            float y = h * (0.96f - 0.75f * cycle);
            float x = w * emberX[i] + (float) Math.sin(t * 1.3f + i) * w * 0.02f;
            int alpha = (int) (210 * (1f - cycle) * Math.min(1f, cycle * 5f));
            paint.setColor(Color.argb(Math.max(0, alpha), 255, 190, 90));
            c.drawCircle(x, y, emberSize[i] * (1f - 0.35f * cycle), paint);
        }
    }

    // ---- Animation -----------------------------------------------------------------------------

    @Override
    public void start() {
        if (!running) {
            running = true;
            start = SystemClock.uptimeMillis();
        }
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1000);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(update);
        }
        if (!animator.isStarted()) {
            lastFrame = 0;
            animator.start();
        }
        invalidateSelf();
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        if (animator != null) animator.cancel();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible) start();
        else stop();
        return changed;
    }

    // ---- Drawable plumbing ---------------------------------------------------------------------

    @Override
    public void setAlpha(int alpha) {
        // The banner is artwork; the tile's own alpha animations handle it.
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // Not used.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
