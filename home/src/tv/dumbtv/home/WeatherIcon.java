package tv.dumbtv.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The picture on the weather card: sun, moon, cloud, rain, snow, thunder or fog, drawn from
 * the WMO weather code open-meteo reports. No image files, so it stays sharp at any size.
 */
final class WeatherIcon extends Drawable {
    private static final int SUN = 0xFFFFC83D, MOON = 0xFFEDE7C4, CLOUD = 0xFFE6EAF0,
            DARK_CLOUD = 0xFFC3CBD6, RAIN = 0xFF5AB0FF, SNOW = 0xFFFFFFFF, FOG = 0xFFD5DAE1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path bite = new Path();
    private final RectF box = new RectF();
    private int code = -1;
    private boolean day = true;

    void set(int code, boolean day) {
        if (this.code == code && this.day == day) return;
        this.code = code;
        this.day = day;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        if (code < 0) return;
        float w = Math.min(getBounds().width(), getBounds().height());
        c.save();
        c.translate(getBounds().left, getBounds().top);
        paint.setStyle(Paint.Style.FILL);
        if (code <= 1) {
            light(c, w * 0.5f, w * 0.5f, w * 0.22f, w);
        } else if (code == 2) {
            light(c, w * 0.36f, w * 0.36f, w * 0.17f, w);
            cloud(c, w * 0.56f, w * 0.58f, w * 0.82f, CLOUD);
        } else if (code == 3) {
            cloud(c, w * 0.5f, w * 0.5f, w, CLOUD);
        } else if (code == 45 || code == 48) {
            fog(c, w);
        } else {
            cloud(c, w * 0.5f, w * 0.38f, w * 0.92f, DARK_CLOUD);
            if (code >= 95) bolt(c, w);
            else if (code >= 71 && code <= 77 || code == 85 || code == 86) flakes(c, w);
            else drops(c, w);
        }
        c.restore();
    }

    /** The sun by day, a crescent moon by night. */
    private void light(Canvas c, float cx, float cy, float r, float w) {
        if (!day) {
            path.reset();
            path.addCircle(cx, cy, r * 1.25f, Path.Direction.CW);
            bite.reset();
            bite.addCircle(cx + r * 0.7f, cy - r * 0.45f, r * 1.1f, Path.Direction.CW);
            path.op(bite, Path.Op.DIFFERENCE);
            paint.setColor(MOON);
            c.drawPath(path, paint);
            return;
        }
        paint.setColor(SUN);
        c.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(w * 0.05f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            c.drawLine(cx + dx * r * 1.4f, cy + dy * r * 1.4f, cx + dx * r * 1.8f, cy + dy * r * 1.8f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** A cloud about {@code s} wide around (cx, cy). */
    private void cloud(Canvas c, float cx, float cy, float s, int color) {
        paint.setColor(color);
        box.set(cx - s * 0.36f, cy, cx + s * 0.36f, cy + s * 0.24f);
        c.drawRoundRect(box, s * 0.12f, s * 0.12f, paint);
        c.drawCircle(cx - s * 0.15f, cy + s * 0.02f, s * 0.15f, paint);
        c.drawCircle(cx + s * 0.08f, cy - s * 0.04f, s * 0.2f, paint);
    }

    private void drops(Canvas c, float w) {
        paint.setColor(RAIN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(w * 0.06f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 3; i++) {
            float x = w * (0.34f + i * 0.17f);
            c.drawLine(x, w * 0.72f, x - w * 0.06f, w * 0.9f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void flakes(Canvas c, float w) {
        paint.setColor(SNOW);
        for (int i = 0; i < 3; i++) {
            c.drawCircle(w * (0.32f + i * 0.18f), w * (i == 1 ? 0.88f : 0.78f), w * 0.045f, paint);
        }
    }

    private void bolt(Canvas c, float w) {
        path.reset();
        path.moveTo(w * 0.54f, w * 0.62f);
        path.lineTo(w * 0.4f, w * 0.8f);
        path.lineTo(w * 0.5f, w * 0.8f);
        path.lineTo(w * 0.44f, w * 0.97f);
        path.lineTo(w * 0.62f, w * 0.74f);
        path.lineTo(w * 0.52f, w * 0.74f);
        path.close();
        paint.setColor(SUN);
        c.drawPath(path, paint);
    }

    private void fog(Canvas c, float w) {
        paint.setColor(FOG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(w * 0.07f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 4; i++) {
            float y = w * (0.26f + i * 0.16f), inset = w * (i % 2 == 0 ? 0.16f : 0.26f);
            c.drawLine(inset, y, w - inset, y, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(ColorFilter cf) {}

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
