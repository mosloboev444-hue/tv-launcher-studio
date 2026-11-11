package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The weather pill's little line glyph, drawn to match the launcher's thin white interface: a sun, a moon-lit
 * cloud, rain, snow or a bolt, all strokes in the pill's text colour. One drawable per kind, cheap to draw and
 * scaled by its bounds, so the same class covers the 24 dp header glyph on any density.
 */
final class WeatherGlyphDrawable extends Drawable {

    private final Weather.Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    WeatherGlyphDrawable(Weather.Kind kind) {
        this.kind = kind;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0xFFFFFFFF);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        if (size <= 0f) return;
        float left = b.left + (b.width() - size) / 2f;
        float top = b.top + (b.height() - size) / 2f;
        paint.setStrokeWidth(Math.max(1.4f, size * 0.075f));
        canvas.save();
        canvas.translate(left, top);
        switch (kind) {
            case CLEAR:
                sun(canvas, size * 0.5f, size * 0.5f, size);
                break;
            case PARTLY:
                sun(canvas, size * 0.36f, size * 0.36f, size * 0.72f);
                cloud(canvas, size, size * 0.14f, size * 0.28f, false);
                break;
            case CLOUDY:
                cloud(canvas, size, 0f, size * 0.12f, false);
                break;
            case FOG:
                fog(canvas, size);
                break;
            case RAIN:
                cloud(canvas, size, 0f, size * 0.02f, false);
                rain(canvas, size);
                break;
            case SNOW:
                cloud(canvas, size, 0f, size * 0.02f, false);
                snow(canvas, size);
                break;
            case THUNDER:
                cloud(canvas, size, 0f, size * 0.02f, false);
                bolt(canvas, size);
                break;
            default:
                cloud(canvas, size, 0f, size * 0.12f, false);
                break;
        }
        canvas.restore();
    }

    private void sun(Canvas canvas, float cx, float cy, float size) {
        float radius = size * 0.19f;
        canvas.drawCircle(cx, cy, radius, paint);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2 * i / 8;
            float inner = radius + size * 0.09f;
            float outer = radius + size * 0.21f;
            canvas.drawLine(cx + (float) Math.cos(angle) * inner, cy + (float) Math.sin(angle) * inner,
                    cx + (float) Math.cos(angle) * outer, cy + (float) Math.sin(angle) * outer, paint);
        }
    }

    /** A rounded cloud outline; {@code dy} shifts the whole shape down. */
    private void cloud(Canvas canvas, float size, float dy, float y, boolean filled) {
        float baseY = y + size * 0.5f;
        path.reset();
        float startX = size * 0.20f;
        float endX = size * 0.80f;
        path.moveTo(startX, baseY);
        // Left bump.
        path.cubicTo(size * 0.16f, baseY - size * 0.02f, size * 0.16f, baseY - size * 0.22f,
                size * 0.32f, baseY - size * 0.22f);
        // The crown.
        path.cubicTo(size * 0.36f, baseY - size * 0.42f, size * 0.62f, baseY - size * 0.44f,
                size * 0.68f, baseY - size * 0.24f);
        // Right bump and back along the base.
        path.cubicTo(size * 0.82f, baseY - size * 0.26f, size * 0.86f, baseY - size * 0.04f,
                endX, baseY);
        path.lineTo(startX, baseY);
        canvas.save();
        canvas.translate(0f, dy);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private void rain(Canvas canvas, float size) {
        float y = size * 0.86f;
        float stroke = Math.max(1.2f, size * 0.06f);
        for (int i = 0; i < 3; i++) {
            float x = size * (0.32f + i * 0.16f);
            canvas.drawLine(x, y - size * 0.16f, x - size * 0.05f, y - size * 0.02f, paint);
        }
    }

    private void snow(Canvas canvas, float size) {
        float y = size * 0.80f;
        float arm = size * 0.05f;
        for (int i = 0; i < 3; i++) {
            float x = size * (0.32f + i * 0.16f);
            canvas.drawLine(x - arm, y, x + arm, y, paint);
            canvas.drawLine(x, y - arm, x, y + arm, paint);
        }
    }

    private void bolt(Canvas canvas, float size) {
        path.reset();
        path.moveTo(size * 0.54f, size * 0.64f);
        path.lineTo(size * 0.44f, size * 0.84f);
        path.lineTo(size * 0.56f, size * 0.82f);
        path.lineTo(size * 0.48f, size * 0.98f);
        canvas.drawPath(path, paint);
    }

    private void fog(Canvas canvas, float size) {
        float stroke = Math.max(1.2f, size * 0.06f);
        for (int i = 0; i < 3; i++) {
            float y = size * (0.34f + i * 0.18f);
            float inset = size * (i == 1 ? 0.26f : 0.18f);
            canvas.drawLine(inset, y, size - inset, y, paint);
        }
        paint.setStrokeWidth(paint.getStrokeWidth());
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
