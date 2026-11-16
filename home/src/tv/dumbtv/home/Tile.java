package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** A card drawn in one pass: banner, or icon plus name, or an input name; focus ring on top. */
final class Tile extends View {
    private static final float FOCUS_SCALE = 1.08f;
    /** The "lift" focus style raises the tile further, since it has no colored ring to mark it. */
    private static final float LIFT_SCALE = 1.12f;

    final Item item;
    final String kind;
    private final CharSequence title;
    private final int cardW, cardH, labelH;
    private final boolean labelAlways, labelOnFocus;
    private final float radius, stroke;
    private final RectF card = new RectF();
    private final Path clip = new Path();
    private final Path arrow = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint inner = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint under = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean moving;
    private final AfterglowTile afterglow;
    private static final int HALO_STEPS = 6;
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean glow;
    private final float spread;
    private final boolean lift;
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float drop;

    Tile(Context c, Item item, String kind, CharSequence title, int cardW, int cardH,
            String labels, int accent) {
        super(c);
        this.item = item;
        this.kind = kind;
        this.title = title;
        this.cardW = cardW;
        this.cardH = cardH;
        boolean app = Catalog.APPS.equals(kind);
        afterglow = app && "afterglow".equals(Config.get(c).get(Config.TILE_STYLE))
                ? new AfterglowTile(c, item.id, title, accent) : null;
        labelH = app && afterglow == null ? Ui.dp(c, 26) : 0;
        labelAlways = "always".equals(labels);
        labelOnFocus = "focus".equals(labels);
        radius = Ui.dp(c, 14);
        stroke = Ui.dp(c, afterglow == null ? 2.5f : 1f);

        fill.setColor(app ? Ui.SURFACE : 0xFF17181B);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(stroke);
        ring.setColor(accent);
        glow = "glow".equals(Config.get(c).get(Config.FOCUS));
        lift = "lift".equals(Config.get(c).get(Config.FOCUS));
        drop = Ui.dp(c, 2);
        // Drawn under the card, so only the blurred shadow around it shows.
        shadow.setColor(0xFF0B0C0E);
        shadow.setShadowLayer(Ui.dp(c, 18), 0, Ui.dp(c, 10), 0xB8000000);
        rest.setColor(0x22000000);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Ui.dp(c, 1.5f));
        rim.setColor(0xE6FFFFFF);
        spread = Ui.dp(c, 10);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(spread / HALO_STEPS + 1f);
        halo.setColor(accent);
        dot.setColor(0xFF34C759);
        inner.setColor(Ui.TEXT);
        inner.setTypeface(Ui.MEDIUM);
        inner.setTextAlign(Paint.Align.CENTER);
        inner.setTextSize(Ui.dp(c, app ? 14 : 17));
        under.setColor(Ui.TEXT);
        under.setTypeface(Ui.MEDIUM);
        under.setTextAlign(Paint.Align.CENTER);
        under.setTextSize(Ui.dp(c, 13));

        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setContentDescription(title);
    }

    void setMoving(boolean on) {
        moving = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(cardW, cardH + labelH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        card.set(0, 0, cardW, cardH);
        clip.reset();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        if (afterglow != null) afterglow.resize(cardW, cardH);
        sheen.setShader(new LinearGradient(0, 0, 0, cardH * 0.62f, 0x30FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        setPivotX(cardW / 2f);
        setPivotY(cardH / 2f);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        animate().cancel();
        if (focused) {
            float to = lift ? LIFT_SCALE : FOCUS_SCALE;
            animate().scaleX(to).scaleY(to).setDuration(lift ? 150 : 110).start();
        } else {
            animate().scaleX(1f).scaleY(1f).setDuration(90).start();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable banner = item.banner, icon = item.icon;
        if (glow && isFocused() && !moving) drawHalo(canvas);
        if (lift && !moving) {
            if (isFocused()) {
                canvas.drawRoundRect(card, radius, radius, shadow);
            } else {
                // A faint contact shadow, two soft steps, so idle tiles do not look pasted on.
                canvas.drawRoundRect(drop, drop * 2, cardW - drop, cardH + drop * 1.5f, radius, radius, rest);
                canvas.drawRoundRect(drop * 2, drop * 3, cardW - drop * 2, cardH + drop * 3, radius, radius, rest);
            }
        }
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(card, fill);
        if (afterglow != null) {
            afterglow.draw(canvas, icon, cardW, cardH);
        } else if (banner != null) {
            banner.setBounds(0, 0, cardW, cardH);
            banner.draw(canvas);
        } else if (icon != null) {
            int size = cardH * 46 / 100;
            int left = (cardW - size) / 2, top = cardH * 12 / 100;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
            drawLine(canvas, inner, cardW / 2f, cardH - cardH * 13 / 100f);
        } else if (!Catalog.APPS.equals(kind)) {
            drawLine(canvas, inner, cardW / 2f, cardH / 2f - (inner.ascent() + inner.descent()) / 2);
        }
        if (moving) canvas.drawColor(0x99000000);
        canvas.restore();

        if (afterglow != null) afterglow.edge(canvas, cardW, cardH, radius);

        if (item.state == TvInputManager.INPUT_STATE_CONNECTED) {
            float r = Ui.dp(getContext(), 3.5f);
            canvas.drawCircle(cardW - r * 3, r * 3, r, dot);
        }
        if (lift && isFocused() && !moving) {
            // Light from above and a thin bright edge instead of the colored ring.
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawRect(0, 0, cardW, cardH * 0.62f, sheen);
            canvas.restore();
            float h = rim.getStrokeWidth() / 2;
            canvas.drawRoundRect(h, h, cardW - h, cardH - h, radius - h, radius - h, rim);
        } else if (isFocused() || moving) {
            float half = stroke / 2;
            ring.setPathEffect(moving ? new DashPathEffect(new float[] {stroke * 3, stroke * 2}, 0) : null);
            canvas.drawRoundRect(half, half, cardW - half, cardH - half, radius - half, radius - half, ring);
        }
        if (moving) drawArrows(canvas);
        boolean label = banner != null && (labelAlways || labelOnFocus && isFocused());
        if (labelH > 0 && label) {
            drawLine(canvas, under, cardW / 2f, cardH + labelH - Ui.dp(getContext(), 6));
        }
    }

    /**
     * A soft halo in the accent color just outside the card, drawn before the card so only the
     * part beyond its edge shows. Plain stroked outlines that fade outwards: no shadow layer and
     * no blur, so it costs the same on every TV. Relies on the rows not clipping their children.
     */
    private void drawHalo(Canvas canvas) {
        for (int i = 1; i <= HALO_STEPS; i++) {
            float out = spread * i / HALO_STEPS;
            float fade = 1f - (i - 1) / (float) HALO_STEPS;
            halo.setAlpha(Math.round(110 * fade * fade));
            canvas.drawRoundRect(-out, -out, cardW + out, cardH + out, radius + out, radius + out, halo);
        }
    }

    private void drawLine(Canvas canvas, TextPaint paint, float x, float baseline) {
        float room = cardW - Ui.dp(getContext(), 16);
        CharSequence s = TextUtils.ellipsize(title, paint, room, TextUtils.TruncateAt.END);
        canvas.drawText(s, 0, s.length(), x, baseline, paint);
    }

    private void drawArrows(Canvas canvas) {
        float h = cardH / 6f, w = h * 0.7f, mid = cardH / 2f, pad = h * 0.8f;
        inner.setStyle(Paint.Style.FILL);
        arrow.reset();
        arrow.moveTo(pad, mid);
        arrow.lineTo(pad + w, mid - h / 2);
        arrow.lineTo(pad + w, mid + h / 2);
        arrow.close();
        arrow.moveTo(cardW - pad, mid);
        arrow.lineTo(cardW - pad - w, mid - h / 2);
        arrow.lineTo(cardW - pad - w, mid + h / 2);
        arrow.close();
        canvas.drawPath(arrow, inner);
    }
}
