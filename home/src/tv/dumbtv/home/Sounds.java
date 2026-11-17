package tv.dumbtv.home;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

/**
 * The sounds of moving and choosing in TCL Home, in place of the TV's own clicks.
 *
 * Android plays a navigation click itself whenever focus moves between views that allow sound
 * effects. Here those effects are switched off on every view of the screen and the sound picked
 * under Optionen › Navigationston is played instead: Edel (a muted, tactile tick and, for OK, a calm low tock, both with
 * a little room), Glas or Holz (short samples made for this,
 * in res/raw), the TV's own click, or nothing. Other apps keep the TV's sounds.
 *
 * Like the TV's clicks, nothing plays while sound effects are off in the TV's settings.
 */
final class Sounds {
    private static Sounds instance;

    private final Context app;
    private final AudioManager audio;
    private final SoundPool pool;
    private final int edelMove, edelSelect, glassMove, glassSelect, woodMove, woodSelect;

    static synchronized Sounds get(Context c) {
        if (instance == null) instance = new Sounds(c.getApplicationContext());
        return instance;
    }

    private Sounds(Context c) {
        app = c;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        pool = new SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        edelMove = pool.load(c, R.raw.edel_move, 1);
        edelSelect = pool.load(c, R.raw.edel_select, 1);
        glassMove = pool.load(c, R.raw.glass_move, 1);
        glassSelect = pool.load(c, R.raw.glass_select, 1);
        woodMove = pool.load(c, R.raw.wood_move, 1);
        woodSelect = pool.load(c, R.raw.wood_select, 1);
    }

    /** Focus moved to another tile, button or row. */
    void move() {
        play(false);
    }

    /** OK on a tile, button or row. */
    void select() {
        play(true);
    }

    private void play(boolean select) {
        String mode = Config.get(app).get(Config.NAV_SOUND);
        if ("off".equals(mode) || !effectsOn()) return;
        if ("tv".equals(mode)) {
            if (audio != null) {
                audio.playSoundEffect(select ? AudioManager.FX_KEY_CLICK : AudioManager.FX_FOCUS_NAVIGATION_RIGHT);
            }
            return;
        }
        int id;
        if ("wood".equals(mode)) id = select ? woodSelect : woodMove;
        else if ("glass".equals(mode)) id = select ? glassSelect : glassMove;
        else id = select ? edelSelect : edelMove;
        pool.play(id, 1f, 1f, 1, 0, 1f);
    }

    private boolean effectsOn() {
        return Settings.System.getInt(app.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0;
    }

    /**
     * Silences the TV's own clicks on every view under {@code root}, now and whenever views are
     * added, and plays ours when focus moves inside it.
     */
    static void attach(final View root) {
        final Sounds sounds = get(root.getContext());
        mute(root);
        ViewTreeObserver tree = root.getViewTreeObserver();
        tree.addOnGlobalLayoutListener(() -> mute(root));
        tree.addOnGlobalFocusChangeListener((from, to) -> {
            if (from != null && to != null && from != to) sounds.move();
        });
    }

    /**
     * Moves focus for an arrow key the screen did not use, the way Android would, but without
     * its click: Android plays that click itself after a successful focus move, whatever the
     * views' sound settings say. Call it with the result of the normal key dispatch; true when
     * the key moved focus here.
     */
    static boolean navigate(android.app.Activity a, android.view.KeyEvent e, boolean handled) {
        if (handled || e.getAction() != android.view.KeyEvent.ACTION_DOWN) return handled;
        int direction;
        switch (e.getKeyCode()) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP: direction = View.FOCUS_UP; break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: direction = View.FOCUS_DOWN; break;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: direction = View.FOCUS_LEFT; break;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: direction = View.FOCUS_RIGHT; break;
            default: return false;
        }
        View from = a.getCurrentFocus();
        if (from == null) return false;
        View to = from.focusSearch(direction);
        return to != null && to != from && to.requestFocus(direction);
    }

    private static void mute(View v) {
        v.setSoundEffectsEnabled(false);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) mute(g.getChildAt(i));
        }
    }
}
