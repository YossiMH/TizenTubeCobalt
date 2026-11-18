package dev.yossi.morningsesame;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;

/** Lets the signature receiver verify the actual helper UID on Android 14 and newer. */
final class MorningOrderedBroadcast {
    private MorningOrderedBroadcast() {}

    static void send(Context context, Intent intent, String permission,
                     BroadcastReceiver result, Handler scheduler,
                     int initialCode, String initialData, Bundle initialExtras) {
        if (Build.VERSION.SDK_INT >= 34) {
            Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
            context.sendOrderedBroadcast(intent, permission, options, result, scheduler,
                    initialCode, initialData, initialExtras);
        } else {
            context.sendOrderedBroadcast(intent, permission, result, scheduler,
                    initialCode, initialData, initialExtras);
        }
    }
}
