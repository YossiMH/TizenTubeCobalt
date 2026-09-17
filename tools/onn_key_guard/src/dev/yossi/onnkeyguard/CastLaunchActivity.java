package dev.yossi.onnkeyguard;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;

public final class CastLaunchActivity extends Activity {
    private static final String TAG = "OnnKeyGuard";
    private static final String ACTION_CAST_LAUNCH = "com.google.android.gms.cast.tv.action.LAUNCH";
    private static final String TARGET_PACKAGE = "io.gh.yossim.tizentube.cobalt";
    private static final String TARGET_ACTIVITY = "dev.cobalt.app.MainActivity";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent incoming = getIntent();
        if (!isTrustedCastLaunch(incoming)) {
            Log.w(TAG, "Ignoring non-Cast launch routed to CastLaunchActivity");
            finish();
            return;
        }

        wakeForCast();

        Intent launch = new Intent();
        launch.setClassName(TARGET_PACKAGE, TARGET_ACTIVITY);
        Uri data = incoming.getData();
        if (data != null) {
            launch.setAction(Intent.ACTION_VIEW);
            launch.setData(data);
        } else {
            launch.setAction(Intent.ACTION_MAIN);
        }
        if (incoming.getExtras() != null) launch.putExtras(incoming.getExtras());
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        try {
            startActivity(launch);
            Log.i(TAG, "Cast launch handed to TizenSub+" + (data == null ? "" : " data=" + data));
        } catch (Exception e) {
            Log.e(TAG, "Cast handoff to TizenSub+ failed", e);
        } finally {
            finish();
        }
    }

    static boolean isTrustedCastLaunch(Intent intent) {
        if (intent == null || !ACTION_CAST_LAUNCH.equals(intent.getAction())) return false;
        Uri data = intent.getData();
        if (data == null) return true;
        return "https".equalsIgnoreCase(data.getScheme())
                && "www.youtube.com".equalsIgnoreCase(data.getHost());
    }

    @SuppressWarnings("deprecation")
    private void wakeForCast() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isInteractive()) return;
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "OnnKeyGuard:CastLaunch");
            wakeLock.acquire(10_000L);
            Log.i(TAG, "Cast launch requested display wake");
        } catch (Exception e) {
            Log.w(TAG, "Unable to wake display for Cast launch", e);
        }
    }
}