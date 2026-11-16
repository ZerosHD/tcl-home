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
    private final LinearLayout gamesCard, prayerCard, weatherCard;
    /** Text on the cards that is not highlighted. */
    private static final int QUIET = 0xCCFFFFFF;
    private final TextView games, pageTitle, pageLines, degrees, skyText, range;
    private final TextView[] prayerName = new TextView[Feed.PRAYER_NAMES.length];
    private final TextView[] prayerTime = new TextView[Feed.PRAYER_NAMES.length];
    private final WeatherIcon sky;
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

        // Bottom right: the football card on top, below it the prayer times and, in the corner,
        // the weather. Each card only shows when there is something to put in it.
        LinearLayout side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.END);

        gamesCard = card(c, "Fußball");
        games = Ui.text(c, 12, QUIET, Ui.MEDIUM);
        games.setLineSpacing(Ui.dp(c, 4), 1f);
        gamesCard.addView(games);
        pageTitle = Ui.text(c, 9, 0x80FFFFFF, Ui.MEDIUM);
        pageTitle.setLetterSpacing(0.12f);
        gamesCard.addView(pageTitle);
        pageLines = Ui.text(c, 12, QUIET, Ui.MEDIUM);
        pageLines.setLineSpacing(Ui.dp(c, 4), 1f);
        pageLines.setPadding(0, Ui.dp(c, 5), 0, 0);
        gamesCard.addView(pageLines);

        // One row per prayer: the name on the left, the time on the right. Each is its own
        // single-line view with room for five digits, so a time can never wrap or lose a digit.
        prayerCard = card(c, "Gebetszeiten");
        for (int i = 0; i < prayerName.length; i++) {
            LinearLayout line = new LinearLayout(c);
            prayerName[i] = Ui.text(c, 12, QUIET, Ui.MEDIUM);
            prayerName[i].setSingleLine(true);
            prayerName[i].setText(Feed.PRAYER_NAMES[i]);
            prayerTime[i] = Ui.text(c, 12, QUIET, Ui.MEDIUM);
            prayerTime[i].setSingleLine(true);
            prayerTime[i].setGravity(Gravity.END);
            prayerTime[i].setMinWidth((int) Math.ceil(prayerTime[i].getPaint().measureText("00:00")) + Ui.dp(c, 3));
            line.addView(prayerName[i], new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            tl.leftMargin = Ui.dp(c, 16);
            line.addView(prayerTime[i], tl);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) ll.topMargin = Ui.dp(c, 3);
            prayerCard.addView(line, ll);
        }

        weatherCard = card(c, "Wetter");
        weatherCard.setMinimumWidth(Ui.dp(c, 124));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        sky = new WeatherIcon();
        ImageView picture = new ImageView(c);
        picture.setImageDrawable(sky);
        top.addView(picture, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
        degrees = Ui.text(c, 28, 0xEEFFFFFF, Ui.LIGHT);
        degrees.setPadding(Ui.dp(c, 9), 0, 0, 0);
        top.addView(degrees);
        weatherCard.addView(top);
        skyText = Ui.text(c, 12, 0xDDFFFFFF, Ui.MEDIUM);
        skyText.setPadding(0, Ui.dp(c, 5), 0, 0);
        weatherCard.addView(skyText);
        range = Ui.text(c, 10, 0x99FFFFFF, Ui.MEDIUM);
        range.setLineSpacing(Ui.dp(c, 2), 1f);
        range.setPadding(0, Ui.dp(c, 3), 0, 0);
        weatherCard.addView(range);

        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        gl.gravity = Gravity.END;
        gl.bottomMargin = Ui.dp(c, 8);
        side.addView(gamesCard, gl);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.BOTTOM);
        row.addView(prayerCard);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        wl.leftMargin = Ui.dp(c, 8);
        row.addView(weatherCard, wl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rl.gravity = Gravity.END;
        side.addView(row, rl);

        LayoutParams sr = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        sr.rightMargin = Ui.dp(c, 32);
        sr.bottomMargin = Ui.dp(c, 28);
        addView(side, sr);
    }

    /** A translucent dark box with a small heading; hidden until it has content. */
    private static LinearLayout card(Context c, String title) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(Ui.round(0x4D000000, Ui.dp(c, 11)));
        v.setPadding(Ui.dp(c, 13), Ui.dp(c, 9), Ui.dp(c, 13), Ui.dp(c, 10));
        TextView t = Ui.text(c, 9, 0x80FFFFFF, Ui.MEDIUM);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, 0, 0, Ui.dp(c, 5));
        v.addView(t);
        heading(v, title);
        v.setVisibility(GONE);
        return v;
    }

    private static void heading(LinearLayout card, String title) {
        ((TextView) card.getChildAt(0)).setText(title.toUpperCase(Locale.GERMAN));
    }

    /** Fills the cards from what {@link Feed} has, and lets it fetch what is due. */
    private void side(Date now) {
        Context c = getContext();
        Config cfg = Config.get(c);
        Feed feed = Feed.get(c);
        feed.refresh(cfg);

        Feed.Weather w = feed.weather(cfg);
        if (w == null) {
            weatherCard.setVisibility(GONE);
        } else {
            heading(weatherCard, w.place.substring(0, w.place.indexOf('|')));
            sky.set(w.code, w.day);
            degrees.setText(w.temperature + "°");
            skyText.setText(w.sky());
            String more = "Max " + w.high + "°   Min " + w.low + "°";
            if (w.rain >= 0) more += "\nRegenrisiko " + w.rain + " %";
            range.setText(more);
            weatherCard.setVisibility(VISIBLE);
        }

        String[] times = feed.prayers(cfg);
        if (times == null) {
            prayerCard.setVisibility(GONE);
        } else {
            // The next prayer stands out; Shuruq (sunrise) is listed but is not one.
            String clock24 = new SimpleDateFormat("HH:mm", Locale.US).format(now);
            int next = 0;
            for (int i = times.length - 1; i >= 0; i--) {
                if (i != 1 && times[i].compareTo(clock24) > 0) next = i;
            }
            for (int i = 0; i < times.length; i++) {
                int color = i == next ? cfg.accent() : QUIET;
                prayerName[i].setTextColor(color);
                prayerTime[i].setTextColor(color);
                prayerTime[i].setText(times[i]);
            }
            prayerCard.setVisibility(VISIBLE);
        }

        Soccer soccer = Soccer.get(c);
        soccer.refresh(cfg);
        List<Soccer.Match> top = soccer.top(cfg);
        List<Soccer.Page> pages = soccer.pages(cfg);
        if (top.isEmpty() && pages.isEmpty()) {
            gamesCard.setVisibility(GONE);
            return;
        }
        List<String> clubs = Soccer.clubs(cfg);
        int accent = cfg.accent();

        SpannableStringBuilder s = new SpannableStringBuilder();
        for (Soccer.Match m : top) line(s, m, clubs, accent, now, true);
        games.setText(s);
        games.setVisibility(top.isEmpty() ? GONE : VISIBLE);

        if (pages.isEmpty()) {
            pageTitle.setVisibility(GONE);
            pageLines.setVisibility(GONE);
        } else {
            // One league at a time, the next one every ten seconds.
            Soccer.Page page = pages.get((int) (now.getTime() / 10_000 % pages.size()));
            pageTitle.setText(("Heute · " + page.title).toUpperCase(Locale.GERMAN));
            pageTitle.setPadding(0, top.isEmpty() ? 0 : Ui.dp(c, 9), 0, 0);
            SpannableStringBuilder p = new SpannableStringBuilder();
            for (Soccer.Match m : page.matches) line(p, m, clubs, accent, now, false);
            pageLines.setText(p);
            pageTitle.setVisibility(VISIBLE);
            pageLines.setVisibility(VISIBLE);
        }
        gamesCard.setVisibility(VISIBLE);
    }

    /**
     * One match as a line: when or how it stands, then the two clubs. Favourite clubs are written
     * in the accent color, a derby gets a mark. {@code withDay} names the day of a later match.
     */
    private static void line(SpannableStringBuilder s, Soccer.Match m, List<String> clubs, int accent,
            Date now, boolean withDay) {
        if (s.length() > 0) s.append("\n");
        int from = s.length();
        Date kick = new Date(m.kickoff);
        if (m.live()) {
            s.append("● ").append(m.clock);
            color(s, from, 0xFFFF5147);
        } else if (m.over()) {
            s.append("Ende");
            color(s, from, 0x80FFFFFF);
        } else {
            SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd", Locale.US);
            if (withDay) {
                s.append(stamp.format(kick).equals(stamp.format(now)) ? "Heute"
                        : new SimpleDateFormat("EE dd.MM.", Locale.GERMAN).format(kick)).append(" ");
            }
            s.append(new SimpleDateFormat("HH:mm", Locale.GERMAN).format(kick));
        }
        s.append("   ");
        from = s.length();
        s.append(m.homeName);
        if (clubs.contains(String.valueOf(m.home))) color(s, from, accent);
        if (m.live() || m.over()) {
            s.append("  ").append(String.valueOf(m.goalsHome)).append(":")
                    .append(String.valueOf(m.goalsAway)).append("  ");
        } else {
            s.append(" – ");
        }
        from = s.length();
        s.append(m.awayName);
        if (clubs.contains(String.valueOf(m.away))) color(s, from, accent);
        if (m.derby) {
            from = s.length();
            s.append("   DERBY");
            color(s, from, 0xFFFFB340);
        }
    }

    private static void color(SpannableStringBuilder s, int from, int color) {
        s.setSpan(new ForegroundColorSpan(color), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
