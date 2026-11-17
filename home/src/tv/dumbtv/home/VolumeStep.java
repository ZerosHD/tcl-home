package tv.dumbtv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * One press of Volume up or down is one step.
 *
 * The TCL remote sends a single key press (seen with getevent), but the TV's own key handling
 * turns it into two volume steps about a tenth of a second apart. That happens before any app
 * sees the key, so the key cannot be filtered; instead the second step is taken back right
 * after it lands. Two steps in the same direction sooner than {@link #ECHO_MS} apart cannot come
 * from two presses of a hand. Holding the key still changes the volume, at half the TV's speed.
 *
 * Runs inside {@link Guard}, which is the part of the app that is always running.
 */
final class VolumeStep extends BroadcastReceiver {
    private static final String CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String PREVIOUS = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";
    private static final long ECHO_MS = 160;

    private final AudioManager audio;
    /** When the last step that was kept landed, and which way it went (+1 or -1). */
    private long keptAt;
    private int keptWay;
    /** A value this class set itself; its own broadcast is not a step of the user's. */
    private int ours = -1;

    private VolumeStep(Context c) {
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    static VolumeStep register(Context c) {
        VolumeStep v = new VolumeStep(c);
        c.registerReceiver(v, new IntentFilter(CHANGED));
        return v;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (audio == null || i.getIntExtra(STREAM, -1) != AudioManager.STREAM_MUSIC) return;
        int now = i.getIntExtra(VALUE, -1), before = i.getIntExtra(PREVIOUS, -1);
        if (now < 0 || before < 0 || now == before) return;
        if (now == ours) {
            ours = -1;
            return;
        }
        int way = now > before ? 1 : -1;
        long at = SystemClock.uptimeMillis();
        if (way == keptWay && at - keptAt < ECHO_MS && Math.abs(now - before) == 1) {
            // the doubled step: back to where the kept one left it
            ours = before;
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, AudioManager.FLAG_SHOW_UI);
            } catch (SecurityException e) {
                Log.w("dumbtv", "volume: " + e);
            }
            // the next step counts from here, so holding the key keeps every other step
            keptAt = 0;
            return;
        }
        keptAt = at;
        keptWay = way;
    }
}
