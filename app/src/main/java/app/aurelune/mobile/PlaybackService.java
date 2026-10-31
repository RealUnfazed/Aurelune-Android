package app.aurelune.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Runs from the first song until the app is closed. The page plays the audio; this service makes Android treat Aurelune
 * as a music player:
 *  - while playing it is a foreground service (the process is not killed, the CPU stays awake with the screen off);
 *  - it owns a MediaSession, so the notification, the lock screen and headset / Bluetooth buttons show and control the
 *    current song (title, artist, cover, previous / play-pause / next, seek bar);
 *  - while paused the notification stays, so you can resume from it, and it can be swiped away.
 * What is shown comes from MediaState; button presses go back to the page through MediaState.dispatch().
 */
public class PlaybackService extends Service {

    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final long WAKE_LOCK_MAX_MS = 6L * 60 * 60 * 1000; // safety net: never hold the CPU awake for more than 6 hours

    private static PlaybackService instance;

    static boolean isRunning() {
        return instance != null;
    }

    /** Redraws the notification and lock screen from MediaState. */
    static void refresh() {
        if (instance != null) instance.render(false);
    }

    private NotificationManager notifications;
    private MediaSession session;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        notifications = getSystemService(NotificationManager.class);
        createChannel();

        session = new MediaSession(this, "Aurelune");
        // Android 7.x only: without these flags headset / Bluetooth buttons don't reach the session (ignored on newer versions).
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                MediaState.dispatch("play", 0);
            }

            @Override
            public void onPause() {
                MediaState.dispatch("pause", 0);
            }

            @Override
            public void onSkipToNext() {
                MediaState.dispatch("next", 0);
            }

            @Override
            public void onSkipToPrevious() {
                MediaState.dispatch("prev", 0);
            }

            @Override
            public void onSeekTo(long positionMs) {
                MediaState.dispatch("seek", positionMs / 1000.0);
            }

            @Override
            public void onStop() {
                MediaState.dispatch("stop", 0);
            }

            @Override
            public void onCustomAction(String action, Bundle extras) {
                if ("shuffle".equals(action) || "repeat".equals(action)) MediaState.dispatch(action, 0);
            }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        render(true); // after startForegroundService() Android requires startForeground() within seconds, whatever the state
        if (!MediaState.playing) render(false);
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf(); // swiped away from recent apps
    }

    @Override
    public void onDestroy() {
        instance = null;
        setWakeLock(false);
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        notifications.cancel(NOTIFICATION_ID); // a paused (detached) notification would otherwise outlive the service
        super.onDestroy();
    }

    /* ------------------------------------------------------------------ drawing */

    private void render(boolean forceForeground) {
        if (session == null) return;
        boolean playing = MediaState.playing;
        updateSession(playing);
        Notification notification = buildNotification(playing);

        if (playing || forceForeground) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } catch (RuntimeException e) {
                // Android 12+ may refuse to promote a service to foreground while the app is in the background: just show it.
                notifications.notify(NOTIFICATION_ID, notification);
            }
        } else {
            stopForeground(STOP_FOREGROUND_DETACH); // stays on screen, but can now be swiped away
            notifications.notify(NOTIFICATION_ID, notification);
        }
        setWakeLock(playing);
    }

    private void updateSession(boolean playing) {
        if (!MediaState.hasMedia()) return;

        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, MediaState.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, MediaState.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, MediaState.album);
        if (MediaState.durationMs > 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, MediaState.durationMs);
        if (MediaState.artwork != null) {
            meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaState.artwork);
            meta.putBitmap(MediaMetadata.METADATA_KEY_ART, MediaState.artwork);
        }
        session.setMetadata(meta.build());

        long now = SystemClock.elapsedRealtime();
        // The clock only runs while sound is actually coming out: not while paused, and not while the song is still
        // downloading / waiting for data.
        boolean moving = playing && !MediaState.buffering;
        long position = moving ? MediaState.positionMs + (now - MediaState.positionAt) : MediaState.positionMs;
        if (MediaState.durationMs > 0) position = Math.min(position, MediaState.durationMs);
        int state = !playing ? PlaybackState.STATE_PAUSED
                : MediaState.buffering ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PLAYING;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, Math.max(0, position), moving ? 1f : 0f, now)
                // Android 13+ draws the media controls from the session: shuffle and repeat go in as custom actions.
                .addCustomAction(new PlaybackState.CustomAction.Builder("shuffle", getString(shuffleTitle()), shuffleIcon()).build())
                .addCustomAction(new PlaybackState.CustomAction.Builder("repeat", getString(repeatTitle()), repeatIcon()).build())
                .build());
    }

    private int shuffleIcon() {
        return MediaState.shuffle ? R.drawable.ic_shuffle_on : R.drawable.ic_shuffle;
    }

    private int shuffleTitle() {
        return MediaState.shuffle ? R.string.action_shuffle_on : R.string.action_shuffle_off;
    }

    private int repeatIcon() {
        return MediaState.repeat == 1 ? R.drawable.ic_repeat_all : MediaState.repeat == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat;
    }

    private int repeatTitle() {
        return MediaState.repeat == 1 ? R.string.action_repeat_all : MediaState.repeat == 2 ? R.string.action_repeat_one : R.string.action_repeat_off;
    }

    private Notification buildNotification(boolean playing) {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setDeleteIntent(actionIntent("stop", 4)) // swiped away: stop the music and the service
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);

        if (MediaState.hasMedia()) {
            b.setContentTitle(MediaState.title).setContentText(MediaState.artist);
            if (!MediaState.album.isEmpty()) b.setSubText(MediaState.album);
            if (MediaState.artwork != null) b.setLargeIcon(MediaState.artwork);
            // shuffle, previous, play/pause, next, repeat (the small collapsed notification shows the middle three)
            b.addAction(action(shuffleIcon(), shuffleTitle(), "shuffle", 5));
            b.addAction(action(R.drawable.ic_prev, R.string.action_previous, "prev", 1));
            b.addAction(playing
                    ? action(R.drawable.ic_pause, R.string.action_pause, "pause", 2)
                    : action(R.drawable.ic_play, R.string.action_play, "play", 2));
            b.addAction(action(R.drawable.ic_next, R.string.action_next, "next", 3));
            b.addAction(action(repeatIcon(), repeatTitle(), "repeat", 6));
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(1, 2, 3));
        } else {
            // The page's player could not be reached: a plain "playing" notification, no buttons.
            b.setContentTitle(getString(R.string.playing_title)).setContentText(getString(R.string.playing_text));
        }
        return b.build();
    }

    private Notification.Action action(int icon, int title, String cmd, int requestCode) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), getString(title), actionIntent(cmd, requestCode)).build();
    }

    private PendingIntent actionIntent(String cmd, int requestCode) {
        Intent intent = new Intent(this, MediaActionReceiver.class)
                .setAction("app.aurelune.mobile.MEDIA_" + cmd)
                .putExtra(MediaActionReceiver.EXTRA_CMD, cmd);
        return PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notifications.createNotificationChannel(channel);
    }

    private void setWakeLock(boolean on) {
        if (on) {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aurelune:playback");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(WAKE_LOCK_MAX_MS);
        } else if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
