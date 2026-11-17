package tv.dumbtv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

/**
 * One press of Volume up or down is one step.
 *
 * The TCL remote sends a single key press (seen with getevent), but the TV's own key handling
 * turns it into two volume steps, the second 80 to 115 ms after the first. That happens before
 * any app sees the key, so the key cannot be filtered. Instead every step that starts a press is
 * kept and exactly one twin after it, in the same direction and within {@link #TWIN_MS}, is taken
 * back. A further step is a new press, so quick presses all count.
 *
 * The notices are handled on a thread of their own: on the main thread they waited whenever the
 * home screen was busy, arrived bunched up, and their timing said nothing.
 *
 * Runs inside {@link Guard}, which is the part of the app that is always running.
 */
final class VolumeStep extends BroadcastReceiver {
    private static final String CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String PREVIOUS = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";
    /** Longer than the gap to the doubled step, shorter than a quick second press. */
    private static final long TWIN_MS = 160;

    private final AudioManager audio;
    private final HandlerThread thread;
    /** The press waiting for its twin: when it landed and which way (+1 or -1); 0 when none. */
    private long pressAt;
    private int pressWay;
    /** A value this class set itself; its own notice is not a step of the user's. */
    private int ours = -1;

    private VolumeStep(Context c, HandlerThread thread) {
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        this.thread = thread;
    }

    static VolumeStep register(Context c) {
        HandlerThread t = new HandlerThread("volume-step");
        t.start();
        VolumeStep v = new VolumeStep(c, t);
        c.registerReceiver(v, new IntentFilter(CHANGED), null, new Handler(t.getLooper()));
        return v;
    }

    void stop() {
        thread.quitSafely();
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
        long at = SystemClock.uptimeMillis();
        int way = now > before ? 1 : -1;
        if (pressWay == way && at - pressAt < TWIN_MS) {
            // the twin of the press just kept: back to where that press left it
            pressWay = 0;
            ours = before;
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, AudioManager.FLAG_SHOW_UI);
            } catch (SecurityException e) {
                Log.w("dumbtv", "volume: " + e);
            }
            return;
        }
        // a new press: it stands, and its twin is expected next
        pressAt = at;
        pressWay = way;
    }
}
