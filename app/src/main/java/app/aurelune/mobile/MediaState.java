package app.aurelune.mobile;

import android.graphics.Bitmap;

/**
 * What is playing right now, as reported by the page (bridge.js). Shared by MainActivity (writes it, forwards button
 * presses to the page) and PlaybackService (draws the notification / lock screen from it).
 * Everything here is touched on the main thread only.
 */
final class MediaState {

    interface Listener {
        /** action: play, pause, next, prev, seek (arg = seconds) or stop. */
        void onMediaAction(String action, double arg);
    }

    static Listener listener;

    static String title = "";
    static String artist = "";
    static String album = "";
    static String artUrl = "";
    static Bitmap artwork;
    static long durationMs;
    static long positionMs;
    static long positionAt;   // SystemClock.elapsedRealtime() when positionMs was reported
    static boolean playing;     // the listener wants music: playing, or about to (still loading)
    static boolean buffering;   // ...but no sound yet (downloading / waiting for data): the clock must not run
    static boolean shuffle;
    static int repeat;          // 0 = off, 1 = all, 2 = one

    private MediaState() {
    }

    static boolean hasMedia() {
        return !title.isEmpty();
    }

    static void clear() {
        title = "";
        artist = "";
        album = "";
        artUrl = "";
        artwork = null;
        durationMs = 0;
        positionMs = 0;
        playing = false;
        buffering = false;
        shuffle = false;
        repeat = 0;
    }

    static void dispatch(String action, double arg) {
        if (listener != null) listener.onMediaAction(action, arg);
    }
}
