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
 * sees the key, so the key cannot be filtered. Instead the first step of a press sets a target,
 * one step from where the volume was, and for {@link #WINDOW_MS} afterwards the volume is held at
 * that target whatever else arrives. Holding the key still changes the volume, about four steps
 * a second.
 *
 * Runs inside {@link Guard}, which is the part of the app that is always running.
 */
final class VolumeStep extends BroadcastReceiver {
    private static final String CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String PREVIOUS = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";
    /** Longer than the gap between the doubled steps (80 to 115 ms), shorter than two presses. */
    private static final long WINDOW_MS = 220;

    private final AudioManager audio;
    /** The press being handled: when its first step landed, which way, and where it should end. */
    private long pressAt;
    private int pressWay;
    private int target = -1;

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
        long at = SystemClock.uptimeMillis();
        boolean inPress = target >= 0 && at - pressAt < WINDOW_MS;
        // already where it should be, including the change this class made itself
        if (inPress && now == target) return;
        int way = now > before ? 1 : -1;
        if (inPress && way == pressWay) {
            hold(target);
            return;
        }
        // a new press: its first step stands
        pressAt = at;
        pressWay = way;
        target = now;
    }

    private void hold(int value) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, AudioManager.FLAG_SHOW_UI);
        } catch (SecurityException e) {
            Log.w("dumbtv", "volume: " + e);
        }
    }
}
