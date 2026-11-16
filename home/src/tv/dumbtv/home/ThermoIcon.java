package tv.dumbtv.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A small thermometer in front of the temperature on the lock screen. */
final class ThermoIcon extends Drawable {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF stem = new RectF();
    private final int size;

    ThermoIcon(int size, int color) {
        this.size = size;
        line.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, size * 0.08f));
        fill.setColor(color);
        setBounds(0, 0, size, size);
    }

    @Override
    public void draw(Canvas c) {
        float w = getBounds().width(), h = getBounds().height();
        float cx = getBounds().left + w / 2, top = getBounds().top;
        float half = w * 0.13f, bulb = w * 0.22f, by = top + h * 0.76f;
        stem.set(cx - half, top + h * 0.06f, cx + half, by);
        c.drawRoundRect(stem, half, half, line);
        c.drawCircle(cx, by, bulb, fill);
        // the column, half full
        c.drawRect(cx - half * 0.4f, top + h * 0.38f, cx + half * 0.4f, by, fill);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
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
