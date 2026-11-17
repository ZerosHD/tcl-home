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
    private static final java.util.concurrent.ExecutorService IO =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Counts picture loads; a load that finishes after a newer one started is dropped. */
    private int loads;
    private final ImageView wall;
    private final TextView clock, date, net;
    private final Card games, prayer, weather;
    private final GameRow[] gameRows = new GameRow[6];
    private final TextView[] prayerName = new TextView[Feed.PRAYER_NAMES.length];
    private final TextView[] prayerTime = new TextView[Feed.PRAYER_NAMES.length];
    /** The weather card's two pages: today, and the coming six days. */
    private final LinearLayout today, week;
    private final TextView degrees, skyText, range, rain;
    private final WeatherIcon sky = new WeatherIcon();
    private final TextView[] dayName = new TextView[6], dayHigh = new TextView[6], dayLow = new TextView[6];
    private final WeatherIcon[] daySky = new WeatherIcon[6];
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
        LayoutParams sl = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 380), Gravity.BOTTOM);
        addView(shade, sl);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(c, 88, Ui.TEXT, Ui.LIGHT);
        clock.setFontFeatureSettings("tnum");
        android.graphics.Typeface regular = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL);
        date = Ui.text(c, 20, 0xE6FFFFFF, regular);
        date.setPadding(Ui.dp(c, 5), Ui.dp(c, 4), 0, 0);
        net = Ui.text(c, 14, 0xA6FFFFFF, regular);
        net.setPadding(Ui.dp(c, 6), Ui.dp(c, 10), 0, 0);
        net.setGravity(Gravity.CENTER_VERTICAL);
        icon = new WifiIcon(Ui.dp(c, 15), 0xA6FFFFFF);
        net.setCompoundDrawables(icon, null, null, null);
        net.setCompoundDrawablePadding(Ui.dp(c, 8));
        box.addView(clock);
        box.addView(date);
        box.addView(net);
        LayoutParams bl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        bl.leftMargin = Ui.dp(c, EDGE);
        bl.bottomMargin = Ui.dp(c, BOTTOM);
        addView(box, bl);

        // Bottom right: three glass cards on one grid. Football spans the full width on top;
        // below it the prayer times and the weather share the width and the height, row for row.
        LinearLayout side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);

        games = new Card(c);
        for (int i = 0; i < gameRows.length; i++) {
            gameRows[i] = new GameRow(c);
            games.body.addView(gameRows[i].row, rowParams(c, i));
        }

        prayer = new Card(c);
        prayer.title.setText("GEBETSZEITEN");
        for (int i = 0; i < prayerName.length; i++) {
            LinearLayout line = new LinearLayout(c);
            line.setGravity(Gravity.CENTER_VERTICAL);
            prayerName[i] = body(c, QUIET);
            prayerName[i].setText(Feed.PRAYER_NAMES[i]);
            prayerTime[i] = body(c, QUIET);
            prayerTime[i].setGravity(Gravity.END);
            line.addView(prayerName[i], new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            line.addView(prayerTime[i]);
            prayer.body.addView(line, rowParams(c, i));
        }

        weather = new Card(c);
        // today: a large reading
        today = new LinearLayout(c);
        today.setOrientation(LinearLayout.VERTICAL);
        LinearLayout now = new LinearLayout(c);
        now.setGravity(Gravity.CENTER_VERTICAL);
        ImageView big = new ImageView(c);
        big.setImageDrawable(sky);
        now.addView(big, new LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34)));
        degrees = Ui.text(c, 34, Ui.TEXT, Ui.LIGHT);
        degrees.setFontFeatureSettings("tnum");
        degrees.setPadding(Ui.dp(c, 10), 0, 0, 0);
        now.addView(degrees);
        today.addView(now);
        skyText = body(c, Ui.TEXT);
        skyText.setPadding(0, Ui.dp(c, 9), 0, 0);
        today.addView(skyText);
        range = body(c, SOFT);
        range.setPadding(0, Ui.dp(c, 5), 0, 0);
        today.addView(range);
        rain = body(c, SOFT);
        rain.setPadding(0, Ui.dp(c, 5), 0, 0);
        today.addView(rain);
        weather.body.addView(today);
        // the week: one row per day, like the prayer times beside it
        week = new LinearLayout(c);
        week.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < dayName.length; i++) {
            LinearLayout line = new LinearLayout(c);
            line.setGravity(Gravity.CENTER_VERTICAL);
            dayName[i] = body(c, QUIET);
            line.addView(dayName[i], new LinearLayout.LayoutParams(Ui.dp(c, 26), LayoutParams.WRAP_CONTENT));
            daySky[i] = new WeatherIcon();
            ImageView small = new ImageView(c);
            small.setImageDrawable(daySky[i]);
            line.addView(small, new LinearLayout.LayoutParams(Ui.dp(c, 15), Ui.dp(c, 15)));
            dayHigh[i] = body(c, Ui.TEXT);
            dayHigh[i].setGravity(Gravity.END);
            line.addView(dayHigh[i], new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            dayLow[i] = body(c, SOFT);
            dayLow[i].setGravity(Gravity.END);
            line.addView(dayLow[i], new LinearLayout.LayoutParams(Ui.dp(c, 30), LayoutParams.WRAP_CONTENT));
            week.addView(line, rowParams(c, i));
        }
        weather.body.addView(week);

        side.addView(games.card, new LinearLayout.LayoutParams(Ui.dp(c, PANEL), LayoutParams.WRAP_CONTENT));
        LinearLayout pair = new LinearLayout(c);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        pair.addView(prayer.card, pl);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        wl.leftMargin = Ui.dp(c, GAP);
        pair.addView(weather.card, wl);
        LinearLayout.LayoutParams pairParams = new LinearLayout.LayoutParams(Ui.dp(c, PANEL), LayoutParams.WRAP_CONTENT);
        pairParams.topMargin = Ui.dp(c, GAP);
        side.addView(pair, pairParams);
        // with only one of the two, it keeps half the width instead of stretching
        pair.setWeightSum(2f);
        pair.setGravity(Gravity.END);

        LayoutParams sr = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        sr.rightMargin = Ui.dp(c, EDGE);
        sr.bottomMargin = Ui.dp(c, BOTTOM);
        addView(side, sr);
    }

    /** Width of the right-hand panel, the gap between its cards, and the screen margins, in dp. */
    private static final int PANEL = 372, GAP = 10, EDGE = 64, BOTTOM = 48;
    /** Text that is not highlighted, and secondary text. */
    private static final int QUIET = 0xD9FFFFFF, SOFT = 0x99FFFFFF;

    /** A glass card: a small heading with an optional note on the right, and a body. */
    private static final class Card {
        final LinearLayout card, body;
        final TextView title, note;

        Card(Context c) {
            card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable glass = Ui.round(0x59000000, Ui.dp(c, 16));
            glass.setStroke(Math.max(1, Ui.dp(c, 0.5f)), 0x1FFFFFFF);
            card.setBackground(glass);
            card.setPadding(Ui.dp(c, 16), Ui.dp(c, 13), Ui.dp(c, 16), Ui.dp(c, 14));
            LinearLayout head = new LinearLayout(c);
            title = heading(c);
            note = heading(c);
            note.setGravity(Gravity.END);
            head.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            head.addView(note);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            hl.bottomMargin = Ui.dp(c, 9);
            card.addView(head, hl);
            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            card.addView(body, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            card.setVisibility(GONE);
        }

        private static TextView heading(Context c) {
            TextView t = Ui.text(c, 10, 0x8CFFFFFF, Ui.MEDIUM);
            t.setLetterSpacing(0.14f);
            t.setSingleLine(true);
            return t;
        }
    }

    /**
     * One match on a fixed grid: time or state, home club right-aligned, score or dash centred,
     * away club left-aligned. Long names are cut with an ellipsis, so the card keeps its width.
     */
    private static final class GameRow {
        final LinearLayout row;
        final TextView when, home, mid, away;

        GameRow(Context c) {
            row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 4), Ui.dp(c, 1), Ui.dp(c, 4), Ui.dp(c, 1));
            when = body(c, SOFT);
            home = body(c, QUIET);
            home.setGravity(Gravity.END);
            home.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid = body(c, Ui.TEXT);
            mid.setGravity(Gravity.CENTER);
            away = body(c, QUIET);
            away.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(when, new LinearLayout.LayoutParams(Ui.dp(c, 60), LayoutParams.WRAP_CONTENT));
            row.addView(home, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            row.addView(mid, new LinearLayout.LayoutParams(Ui.dp(c, 42), LayoutParams.WRAP_CONTENT));
            row.addView(away, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            row.setVisibility(GONE);
        }
    }

    /** Body text: regular weight, one line, figures of equal width so columns line up. */
    private static TextView body(Context c, int color) {
        TextView t = Ui.text(c, 13, color, android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        t.setSingleLine(true);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    private static LinearLayout.LayoutParams rowParams(Context c, int i) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        if (i > 0) lp.topMargin = Ui.dp(c, 5);
        return lp;
    }

    /** Fills the cards from what {@link Feed} and {@link Soccer} have, and lets them fetch what is due. */
    private void side(Date now) {
        Context c = getContext();
        Config cfg = Config.get(c);
        Feed feed = Feed.get(c);
        feed.refresh(cfg);
        Gallery.get(c).refresh(cfg);
        int accent = cfg.accent();

        Feed.Weather w = feed.weather(cfg);
        if (w == null) {
            weather.card.setVisibility(GONE);
        } else {
            weather.title.setText(w.place.substring(0, w.place.indexOf('|')).toUpperCase(Locale.GERMAN));
            // today and the week take turns, ten seconds each
            boolean showWeek = w.dates.length > 0 && now.getTime() / 10_000 % 2 == 1;
            weather.note.setText(showWeek ? "WOCHE" : "HEUTE");
            today.setVisibility(showWeek ? GONE : VISIBLE);
            week.setVisibility(showWeek ? VISIBLE : GONE);
            sky.set(w.code, w.day);
            degrees.setText(w.temperature + "°");
            skyText.setText(w.sky());
            range.setText("↑ " + w.high + "°    ↓ " + w.low + "°");
            rain.setText(w.rain >= 0 ? "Regen " + w.rain + " %" : "");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat dayShort = new SimpleDateFormat("EE", Locale.GERMAN);
            for (int i = 0; i < dayName.length; i++) {
                android.view.View line = (android.view.View) dayName[i].getParent();
                if (i >= w.dates.length) {
                    line.setVisibility(INVISIBLE);
                    continue;
                }
                line.setVisibility(VISIBLE);
                String name;
                try {
                    name = dayShort.format(iso.parse(w.dates[i])).replace(".", "");
                } catch (java.text.ParseException e) {
                    name = "";
                }
                dayName[i].setText(name);
                daySky[i].set(w.codes[i], true);
                dayHigh[i].setText(w.highs[i] + "°");
                dayLow[i].setText(w.lows[i] + "°");
            }
            weather.card.setVisibility(VISIBLE);
        }

        String[] times = feed.prayers(cfg);
        if (times == null) {
            prayer.card.setVisibility(GONE);
        } else {
            // The next prayer stands out; Shuruq (sunrise) is listed but is not one.
            String clock24 = new SimpleDateFormat("HH:mm", Locale.US).format(now);
            int next = 0;
            for (int i = times.length - 1; i >= 0; i--) {
                if (i != 1 && times[i].compareTo(clock24) > 0) next = i;
            }
            for (int i = 0; i < times.length; i++) {
                int color = i == next ? accent : QUIET;
                prayerName[i].setTextColor(color);
                prayerTime[i].setTextColor(color);
                prayerTime[i].setText(times[i]);
            }
            prayer.card.setVisibility(VISIBLE);
        }

        Soccer soccer = Soccer.get(c);
        soccer.refresh(cfg);
        List<Soccer.Page> pages = soccer.pages(cfg);
        if (pages.isEmpty()) {
            games.card.setVisibility(GONE);
            return;
        }
        // One page at a time, the next one every ten seconds: my clubs, then league by league.
        int at = (int) (now.getTime() / 10_000 % pages.size());
        Soccer.Page page = pages.get(at);
        games.title.setText(page.title.toUpperCase(Locale.GERMAN));
        games.note.setText(pages.size() > 1 ? (at + 1) + " / " + pages.size() : "");
        List<String> clubs = Soccer.clubs(cfg);
        for (int i = 0; i < gameRows.length; i++) {
            GameRow r = gameRows[i];
            if (i >= page.matches.size()) {
                r.row.setVisibility(GONE);
                continue;
            }
            fill(r, page.matches.get(i), clubs, accent, now, page.withDay);
            r.row.setVisibility(VISIBLE);
        }
        games.card.setVisibility(VISIBLE);
    }

    /**
     * One match into its row. Favourite clubs are written in the accent color; a running match
     * shows its minute in red; a derby gets a faint amber band. {@code withDay} puts the weekday
     * before a later kick-off.
     */
    private void fill(GameRow r, Soccer.Match m, List<String> clubs, int accent, Date now, boolean withDay) {
        Date kick = new Date(m.kickoff);
        if (m.live()) {
            r.when.setText("● " + m.clock);
            r.when.setTextColor(0xFFFF5147);
        } else if (m.over()) {
            r.when.setText("Ende");
            r.when.setTextColor(SOFT);
        } else {
            SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd", Locale.US);
            String time = new SimpleDateFormat("HH:mm", Locale.GERMAN).format(kick);
            boolean today = stamp.format(kick).equals(stamp.format(now));
            r.when.setText(withDay && !today
                    ? new SimpleDateFormat("EE", Locale.GERMAN).format(kick).replace(".", "") + " " + time
                    : time);
            r.when.setTextColor(SOFT);
        }
        r.home.setText(m.homeName);
        r.home.setTextColor(clubs.contains(String.valueOf(m.home)) ? accent : QUIET);
        r.away.setText(m.awayName);
        r.away.setTextColor(clubs.contains(String.valueOf(m.away)) ? accent : QUIET);
        r.mid.setText(m.live() || m.over() ? m.goalsHome + ":" + m.goalsAway : "–");
        r.mid.setTextColor(m.live() || m.over() ? Ui.TEXT : SOFT);
        r.row.setBackground(m.derby ? Ui.round(0x26FFB340, Ui.dp(getContext(), 6)) : null);
    }

    void start() {
        nextWallpaper();
        ui.post(tick);
        ui.postDelayed(next, STEP_MS);
    }

    void stop() {
        loads++;
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

    /**
     * Picks the next picture and decodes it on a worker thread: a large photo takes a few hundred
     * milliseconds, and the main thread is the one the remote's keys wait on (TCL Home Tasten).
     */
    private void nextWallpaper() {
        Context c = getContext();
        List<File> pool = Config.get(c).wallpapers();
        final File file;
        if (!pool.isEmpty()) {
            index = (index + 1) % pool.size();
            file = pool.get(index);
        } else {
            file = Config.get(c).wallpaper();
        }
        final int ticket = ++loads;
        IO.execute(() -> {
            final Bitmap b = file == null ? null : decode(file);
            ui.post(() -> {
                if (ticket != loads) {
                    if (b != null) b.recycle();
                    return;
                }
                show(b);
            });
        });
    }

    private void show(Bitmap b) {
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
        return Ui.decodeFit(f, getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
    }
}
