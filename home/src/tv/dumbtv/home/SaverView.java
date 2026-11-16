package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The lock screen: the wallpapers, crossfading every minute, with a large clock, the date and
 * the network line in the corner. Shown by {@link Saver} (Back on the home screen) and by
 * {@link Dream} (Android's screensaver after the idle time).
 */
final class SaverView extends FrameLayout {
    private static final long STEP_MS = 60_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ImageView wall;
    private final TextView clock, date, net;
    private final TextView games, prayer, weather;
    private boolean sideShown;
    private final WifiIcon icon;
    private Drawable shown;
    private int index = -1;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            ui.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };
    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            nextWallpaper();
            ui.postDelayed(this, STEP_MS);
        }
    };

    SaverView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        wall = new ImageView(c);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(wall, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // A soft dark fade at the bottom keeps the white text readable on bright photos.
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xAA000000, 0x00000000});
        android.view.View shade = new android.view.View(c);
        shade.setBackground(fade);
        LayoutParams sl = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 320), Gravity.BOTTOM);
        addView(shade, sl);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(c, 96, Ui.TEXT, Ui.LIGHT);
        date = Ui.text(c, 22, 0xDDFFFFFF, Ui.MEDIUM);
        date.setPadding(Ui.dp(c, 4), Ui.dp(c, 6), 0, 0);
        net = Ui.text(c, 16, 0xBBFFFFFF, Ui.MEDIUM);
        net.setPadding(Ui.dp(c, 4), Ui.dp(c, 10), 0, 0);
        net.setGravity(Gravity.CENTER_VERTICAL);
        icon = new WifiIcon(Ui.dp(c, 18), 0xDDFFFFFF);
        net.setCompoundDrawables(icon, null, null, null);
        net.setCompoundDrawablePadding(Ui.dp(c, 8));
        box.addView(clock);
        box.addView(date);
        box.addView(net);
        LayoutParams bl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        bl.leftMargin = Ui.dp(c, 64);
        bl.bottomMargin = Ui.dp(c, 56);
        addView(box, bl);

        // Bottom right: football, prayer times and the weather, each only when there is something.
        LinearLayout side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.END);
        games = Ui.text(c, 16, 0xDDFFFFFF, Ui.MEDIUM);
        games.setGravity(Gravity.END);
        games.setLineSpacing(Ui.dp(c, 6), 1f);
        prayer = Ui.text(c, 15, 0xBBFFFFFF, Ui.MEDIUM);
        prayer.setGravity(Gravity.END);
        prayer.setPadding(0, Ui.dp(c, 16), 0, 0);
        weather = Ui.text(c, 20, 0xDDFFFFFF, Ui.MEDIUM);
        weather.setGravity(Gravity.CENTER_VERTICAL);
        weather.setPadding(0, Ui.dp(c, 14), 0, 0);
        weather.setCompoundDrawables(new ThermoIcon(Ui.dp(c, 22), 0xDDFFFFFF), null, null, null);
        weather.setCompoundDrawablePadding(Ui.dp(c, 6));
        for (TextView v : new TextView[] {games, prayer, weather}) {
            v.setVisibility(GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.END;
            side.addView(v, lp);
        }
        LayoutParams sr = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        sr.rightMargin = Ui.dp(c, 64);
        sr.bottomMargin = Ui.dp(c, 56);
        addView(side, sr);
    }

    /** Fills the bottom right corner from what {@link Feed} has, and lets it fetch what is due. */
    private void side(Date now) {
        Context c = getContext();
        Config cfg = Config.get(c);
        Feed feed = Feed.get(c);
        feed.refresh(cfg);

        String w = feed.weather(cfg);
        weather.setText(w == null ? "" : w);
        weather.setVisibility(w == null ? GONE : VISIBLE);

        String[] times = feed.prayers(cfg);
        if (times == null) {
            prayer.setVisibility(GONE);
        } else {
            // The next prayer stands out; Shuruq (sunrise) is shown but is not one.
            String clock24 = new SimpleDateFormat("HH:mm", Locale.US).format(now);
            int next = 0;
            for (int i = times.length - 1; i >= 0; i--) {
                if (i != 1 && times[i].compareTo(clock24) > 0) next = i;
            }
            SpannableStringBuilder s = new SpannableStringBuilder();
            for (int i = 0; i < times.length; i++) {
                if (i > 0) s.append("     ");
                int from = s.length();
                s.append(Feed.PRAYER_NAMES[i]).append(" ").append(times[i]);
                if (i == next) {
                    s.setSpan(new ForegroundColorSpan(cfg.accent()), from, s.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            prayer.setText(s);
            prayer.setVisibility(VISIBLE);
        }

        List<Feed.Match> matches = feed.matches(cfg);
        if (matches.isEmpty()) {
            games.setVisibility(GONE);
            return;
        }
        long ms = now.getTime();
        SimpleDateFormat day = new SimpleDateFormat("EE", Locale.GERMAN);
        SimpleDateFormat hour = new SimpleDateFormat("HH:mm", Locale.GERMAN);
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(now);
        SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd", Locale.US);
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (Feed.Match m : matches) {
            if (s.length() > 0) s.append("\n");
            Date kick = new Date(m.kickoff);
            if (m.upcoming(ms)) {
                String when = stamp.format(kick).equals(today) ? "Heute" : day.format(kick);
                s.append(when).append(" ").append(hour.format(kick)).append("   ")
                        .append(m.team1).append(" – ").append(m.team2);
            } else {
                if (m.live(ms)) {
                    int from = s.length();
                    s.append("● LIVE");
                    s.setSpan(new ForegroundColorSpan(0xFFFF5147), from, s.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.append("   ");
                }
                s.append(m.team1).append("  ").append(String.valueOf(m.goals1)).append(":")
                        .append(String.valueOf(m.goals2)).append("  ").append(m.team2);
                if (m.finished) s.append("   Ende");
            }
        }
        games.setText(s);
        games.setVisibility(VISIBLE);
    }

    void start() {
        nextWallpaper();
        ui.post(tick);
        ui.postDelayed(next, STEP_MS);
    }

    void stop() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(next);
    }

    private void update() {
        Context c = getContext();
        Date now = new Date();
        clock.setText(DateFormat.getTimeFormat(c).format(now));
        Locale locale = Locale.getDefault();
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now));
        if (now.getTime() / 1000 % 10 == 0 || net.getText().length() == 0) {
            Net n = Net.now(c);
            net.setText(n.label);
            icon.setSignal(n.level);
        }
        if (now.getTime() / 1000 % 5 == 0 || !sideShown) {
            sideShown = true;
            side(now);
        }
    }

    private void nextWallpaper() {
        Context c = getContext();
        List<File> pool = Config.get(c).wallpapers();
        Bitmap b = null;
        if (!pool.isEmpty()) {
            index = (index + 1) % pool.size();
            b = decode(pool.get(index));
        } else {
            File one = Config.get(c).wallpaper();
            if (one != null) b = decode(one);
        }
        Drawable d = b != null ? new BitmapDrawable(getResources(), b)
                : getResources().getDrawable(R.drawable.wallpaper, null);
        if (shown == null) {
            wall.setImageDrawable(d);
        } else {
            TransitionDrawable t = new TransitionDrawable(new Drawable[] {shown, d});
            t.setCrossFadeEnabled(true);
            wall.setImageDrawable(t);
            t.startTransition(1500);
        }
        shown = d;
    }

    private Bitmap decode(File f) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
            o.inSampleSize *= 2;
        }
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }
}
