package dev.yossi.morningsesame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Manifest permission restricts this read-only endpoint to Android DUMP callers. */
public final class DiagnosticsReceiver extends BroadcastReceiver {
    public static final String ACTION_STATUS = "dev.yossi.morningsesame.STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_STATUS.equals(intent.getAction())) return;
        PendingResult pending = goAsync();
        RunDiagnostics.respondStatus(context, intent.getStringExtra("detail"),
                Math.min(64, intent.getIntExtra("offset", -1)), pending);
    }
}
