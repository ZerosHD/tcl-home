package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Every setting is a row: left/right or OK cycles it, and the change is saved at once. */
public final class Options extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** Width of the centered column of settings, in dp. */
    private static final int WIDTH = 640;

    private final List<Runnable> refreshers = new ArrayList<>();
    private Config cfg;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF171A20, 0xFF08090B}));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setGravity(Gravity.CENTER_HORIZONTAL);
        list.setPadding(dp(56), dp(36), dp(56), dp(48));
        scroll.addView(list);

        TextView title = Ui.text(this, 34, Ui.TEXT, Ui.LIGHT);
        title.setText("Optionen");
        title.setPadding(dp(6), 0, 0, 0);
        list.addView(title, new LinearLayout.LayoutParams(dp(WIDTH), WRAP));

        LinearLayout look = section(list, "Aussehen");
        for (Config.Choice c : new Config.Choice[] {Config.ACCENT, Config.FOCUS, Config.DOCK,
                Config.TILES, Config.LAYOUT, Config.LABELS, Config.TILE_STYLE}) {
            look.addView(choice(c));
        }

        LinearLayout wall = section(list, "Hintergrund");
        for (Config.Choice c : new Config.Choice[] {Config.BACKGROUND, Config.DIM, Config.ROTATE,
                Config.GALLERY}) {
            wall.addView(choice(c));
        }
        wall.addView(action("Nächstes Hintergrundbild", () -> {
            cfg.nextWallpaper();
            refresh();
        }));

        LinearLayout home = section(list, "Home-Screen");
        for (Config.Choice c : new Config.Choice[] {Config.ORDER, Config.CLOCK, Config.MEMORY,
                Config.INPUTS, Config.SWITCH, Config.NAV_SOUND}) {
            home.addView(choice(c));
        }
        home.addView(action("Apps auf dem Home-Screen", () -> pick(Catalog.APPS)));
        home.addView(action("Eingänge auf dem Home-Screen", () -> pick(Catalog.INPUTS)));
        home.addView(action("Reihenfolge, Namen und Ausgeblendetes zurücksetzen", this::reset));

        LinearLayout lock = section(list, "Sperrbildschirm");
        lock.addView(choice(Config.BACK));
        lock.addView(choice(Config.SAVER));
        lock.addView(action("Sperrbildschirm jetzt zeigen", () -> startActivity(new Intent(this, Saver.class))));
        lock.addView(choice(Config.WEATHER));
        lock.addView(choice(Config.PRAYER));
        lock.addView(action("Ort für Wetter und Gebetszeiten", this::pickPlace));
        lock.addView(choice(Config.FOOTBALL));
        lock.addView(action("Fußball-Ligen", this::pickLeagues));
        lock.addView(action("Fußball-Vereine", this::pickClubs));

        LinearLayout system = section(list, "System");
        system.addView(choice(Config.LOCK));
        system.addView(action("Apps aktualisieren (Play Store)", this::updateApps));

        TextView about = Ui.text(this, 12, Ui.FAINT, Ui.MEDIUM);
        about.setLineSpacing(0, 1.45f);
        about.setPadding(dp(6), dp(26), dp(6), 0);
        about.setText("Einstellungsdatei: " + cfg.dir + "/" + Config.FILE
                + "\nEin Hintergrundbild: als wallpaper.jpg (oder .png) in diesen Ordner legen"
                + "\nMehrere Bilder: in den Ordner " + Config.WALLPAPERS + "/ legen und einen Bildwechsel wählen"
                + "\nDanach Hintergrund \u203A Bild oder Bild mit App-Farbton wählen"
                + "\nInternet nur für Wetter (open-meteo.com), Gebetszeiten (aladhan.com), Fußball"
                + " (espn.com) und neue Hintergrundbilder (Google Earth View, Wikimedia Commons, Met Museum)"
                + "\nKein Tracking, keine Werbung, kein Konto.");
        list.addView(about, new LinearLayout.LayoutParams(dp(WIDTH), WRAP));

        setContentView(scroll);
        Sounds.attach(getWindow().getDecorView());
        refresh();
    }


    /** OK plays the chosen sound; the TV's own click is switched off on these views (Sounds). */
    private void okSound(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return;
        int code = e.getKeyCode();
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) && getCurrentFocus() != null) {
            Sounds.get(this).select();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        okSound(e);
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg.refresh();
        refresh();
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    private View choice(Config.Choice c) {
        LinearLayout row = row();
        TextView name = label(c.title);
        TextView value = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(value, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.setOnClickListener(v -> step(c, 1));
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return step(c, -1);
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return step(c, 1);
            return false;
        });
        Runnable show = () -> {
            boolean focused = row.isFocused();
            String text = c.name(cfg.get(c));
            if (c == Config.ACCENT) {
                SpannableString s = new SpannableString("●  " + text);
                s.setSpan(new ForegroundColorSpan(cfg.accent()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                value.setText(focused ? "‹  " : "");
                value.append(s);
                if (focused) value.append("  ›");
            } else {
                value.setText(focused ? "‹  " + text + "  ›" : text);
            }
            value.setTextColor(focused ? Ui.TEXT : Ui.MUTED);
            boolean off = (c == Config.DIM || c == Config.ROTATE)
                    && !"image".equals(cfg.get(Config.BACKGROUND))
                    && !"adaptive-tint".equals(cfg.get(Config.BACKGROUND))
                    || c == Config.SWITCH && "off".equals(cfg.get(Config.INPUTS));
            row.setAlpha(off ? 0.4f : 1f);
        };
        row.setOnFocusChangeListener((v, has) -> show.run());
        refreshers.add(show);
        refreshers.add(() -> style(row));
        return row;
    }

    private boolean step(Config.Choice c, int by) {
        int i = Arrays.asList(c.values).indexOf(cfg.get(c));
        int n = c.values.length;
        cfg.set(c.key, c.values[((i + by) % n + n) % n]);
        refresh();
        if (c == Config.SAVER) applySaver(cfg.get(c));
        if (c == Config.LOCK && "on".equals(cfg.get(c))) {
            Toast.makeText(this, "Optionen und Einstellungen sind jetzt auf dem Home-Screen ausgeblendet. "
                    + "\u2630 Menü " + Home.UNLOCK_MS / 1000 + " Sekunden halten, um hierher zurückzukommen.",
                    Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private View action(String text, Runnable run) {
        LinearLayout row = row();
        row.addView(label(text), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView chevron = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
        chevron.setText("›");
        row.addView(chevron);
        row.setOnClickListener(v -> run.run());
        refreshers.add(() -> style(row));
        return row;
    }

    /** A heading and, under it, a rounded panel that the rows of one topic go into. */
    private LinearLayout section(LinearLayout list, String name) {
        TextView heading = Ui.text(this, 11, Ui.FAINT, Ui.MEDIUM);
        heading.setText(name.toUpperCase(java.util.Locale.GERMAN));
        heading.setLetterSpacing(0.14f);
        heading.setPadding(dp(6), dp(26), 0, dp(9));
        list.addView(heading, new LinearLayout.LayoutParams(dp(WIDTH), WRAP));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panel = Ui.round(0x12FFFFFF, dp(18));
        panel.setStroke(Math.max(1, dp(0.75f)), 0x1FFFFFFF);
        card.setBackground(panel);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        list.addView(card, new LinearLayout.LayoutParams(dp(WIDTH), WRAP));
        return card;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP));
        return row;
    }

    private void style(View row) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused},
                Ui.round((cfg.accent() & 0x00FFFFFF) | 0x40000000, dp(13)));
        bg.addState(new int[0], Ui.round(0x00000000, dp(13)));
        row.setBackground(bg);
    }

    private TextView label(String text) {
        TextView v = Ui.text(this, 15, Ui.TEXT, Ui.MEDIUM);
        v.setText(text);
        return v;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(18)));
        return v;
    }

    private void pick(String kind) {
        List<Item> items = Catalog.APPS.equals(kind)
                ? Catalog.apps(this)
                : Catalog.inputs(this, (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE));
        CharSequence[] names = new CharSequence[items.size()];
        boolean[] on = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            names[i] = Catalog.label(cfg, items.get(i));
            on[i] = Catalog.shown(cfg, kind, items.get(i));
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.APPS.equals(kind) ? "Apps auf dem Home-Screen" : "Eingänge auf dem Home-Screen")
                .setMultiChoiceItems(names, on, (d, which, checked) ->
                        Catalog.setShown(cfg, kind, items.get(which), checked))
                .setPositiveButton("Fertig", null)
                .show();
    }

    /** The town whose coordinates the weather and the prayer times are asked for. */
    private void pickPlace() {
        String[] names = new String[Feed.PLACES.length];
        int current = -1;
        String stored = cfg.get(Feed.KEY_PLACE, "");
        for (int i = 0; i < names.length; i++) {
            names[i] = Feed.PLACES[i].substring(0, Feed.PLACES[i].indexOf('|'));
            if (Feed.PLACES[i].equals(stored)) current = i;
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Ort für Wetter und Gebetszeiten")
                .setSingleChoiceItems(names, current, (d, which) -> {
                    cfg.set(Feed.KEY_PLACE, Feed.PLACES[which]);
                    d.dismiss();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void pickLeagues() {
        List<String> chosen = Soccer.leagues(cfg);
        CharSequence[] names = new CharSequence[Soccer.LEAGUES.length];
        boolean[] on = new boolean[Soccer.LEAGUES.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = Soccer.LEAGUES[i][1];
            on[i] = chosen.contains(Soccer.LEAGUES[i][0]);
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Fußball-Ligen")
                .setMultiChoiceItems(names, on, (d, which, checked) -> {
                    String id = Soccer.LEAGUES[which][0];
                    chosen.remove(id);
                    if (checked) chosen.add(id);
                    cfg.set(Soccer.KEY_LEAGUES, chosen.isEmpty() ? Soccer.NONE : TextUtils.join(",", chosen));
                })
                .setPositiveButton("Fertig", null)
                .show();
    }

    /** The clubs of the chosen leagues come from the internet, so the list takes a moment. */
    private void pickClubs() {
        List<String> leagues = Soccer.leagues(cfg);
        if (leagues.isEmpty()) {
            Toast.makeText(this, "Zuerst unter Fußball-Ligen eine Liga wählen.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Lade Vereine …", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<String[]> teams = Soccer.teams(leagues);
            runOnUiThread(() -> showClubs(teams));
        }).start();
    }

    private void showClubs(List<String[]> teams) {
        if (isFinishing() || isDestroyed()) return;
        if (teams.isEmpty()) {
            Toast.makeText(this, "Vereinsliste nicht geladen. Internet prüfen.", Toast.LENGTH_LONG).show();
            return;
        }
        List<String> chosen = Soccer.clubs(cfg);
        CharSequence[] names = new CharSequence[teams.size()];
        boolean[] on = new boolean[teams.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = teams.get(i)[1];
            on[i] = chosen.contains(teams.get(i)[0]);
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Fußball-Vereine")
                .setMultiChoiceItems(names, on, (d, which, checked) -> {
                    String id = teams.get(which)[0];
                    chosen.remove(id);
                    if (checked) chosen.add(id);
                    cfg.set(Soccer.KEY_CLUBS, chosen.isEmpty() ? Soccer.NONE : TextUtils.join(",", chosen));
                })
                .setPositiveButton("Fertig", null)
                .show();
    }

    private void reset() {
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Home-Screen zurücksetzen?")
                .setMessage("Reihenfolge, Namen und ausgeblendete Kacheln werden zurückgesetzt. Farben und Größen bleiben.")
                .setPositiveButton("Zurücksetzen", (d, w) -> cfg.removePrefix("name.", "apps.", "inputs."))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    /**
     * Makes TCL Home Android's screensaver and sets its delay. Needs WRITE_SETTINGS and
     * WRITE_SECURE_SETTINGS, which tcl.sh grants over adb; a plain install cannot.
     */
    private void applySaver(String value) {
        if ("keep".equals(value)) return;
        try {
            ContentResolver r = getContentResolver();
            boolean on = !"off".equals(value);
            Settings.Secure.putInt(r, "screensaver_enabled", on ? 1 : 0);
            if (!on) return;
            Settings.Secure.putString(r, "screensaver_components", getPackageName() + "/.Dream");
            Settings.System.putInt(r, Settings.System.SCREEN_OFF_TIMEOUT, Integer.parseInt(value) * 60_000);
        } catch (SecurityException e) {
            Toast.makeText(this, "Darf die TV-Einstellung nicht ändern. Einmal tcl home ausführen.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** The Play Store's own list of app updates, or the Play Store itself on builds without it. */
    private void updateApps() {
        Intent updates = new Intent("com.google.android.finsky.VIEW_MY_DOWNLOADS")
                .setPackage("com.android.vending");
        Intent store = getPackageManager().getLeanbackLaunchIntentForPackage("com.android.vending");
        if (store == null) store = getPackageManager().getLaunchIntentForPackage("com.android.vending");
        Intent go = updates.resolveActivity(getPackageManager()) != null ? updates : store;
        if (go == null) {
            Toast.makeText(this, "Play Store nicht gefunden", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            if (store != null) startActivity(store.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
