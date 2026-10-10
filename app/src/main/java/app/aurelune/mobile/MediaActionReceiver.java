package app.aurelune.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives the taps on the notification buttons (previous / play-pause / next / dismiss) and hands them to the page. */
public class MediaActionReceiver extends BroadcastReceiver {
    static final String EXTRA_CMD = "cmd";

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra(EXTRA_CMD);
        if (cmd != null) MediaState.dispatch(cmd, 0);
    }
}
