package tv.dumbtv.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

final class Ui {
    static final int TEXT = 0xFFF2F2F3;
    static final int MUTED = 0x99FFFFFF;
    static final int FAINT = 0x66FFFFFF;
    static final int SURFACE = 0xFF1C1D21;
    static final int DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Black on light accents, white on dark ones. */
    static int ink(int color) {
        return Color.luminance(color) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Accent fill when focused, a faint fill otherwise. */
    static void focusStyle(TextView v, int accent, int idle, float radius) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, round(accent, radius));
        // A hairline edge makes the idle pill read as glass instead of a flat patch.
        GradientDrawable resting = round(idle, radius);
        resting.setStroke(Math.max(1, dp(v.getContext(), 0.75f)), 0x33FFFFFF);
        bg.addState(new int[0], resting);
        v.setBackground(bg);
        v.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {ink(accent), 0xD9FFFFFF}));
    }

    /**
     * A wallpaper decoded just large enough to cover a w by h screen: halved while that still
     * covers it, then scaled down the rest of the way. A 2560 pixel photo for a 1920 by 1080
     * screen takes 8 MB instead of 16, in memory and again as a texture on the graphics chip.
     * Null when the file cannot be read or memory runs out.
     */
    static Bitmap decodeFit(java.io.File f, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
                o.inSampleSize *= 2;
            }
            o.inJustDecodeBounds = false;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b == null) return null;
            float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            if (scale >= 0.9f) return b;
            Bitmap fit = Bitmap.createScaledBitmap(b, Math.max(w, Math.round(b.getWidth() * scale)),
                    Math.max(h, Math.round(b.getHeight() * scale)), true);
            if (fit != b) b.recycle();
            return fit;
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }
}
