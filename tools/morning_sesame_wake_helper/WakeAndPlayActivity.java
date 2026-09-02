package dev.yossi.morningsesame;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;

public final class WakeAndPlayActivity extends Activity {
    private static final String TAG = "MorningSesame";
    private static final String DEFAULT_TARGET = "io.gh.yossim.tizentube.cobalt";
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "MorningSesame:Wake");
            wakeLock.acquire(30_000L);
        }

        final Uri url = getIntent().getData();
        final String suppliedTarget = getIntent().getStringExtra("target_package");
        final String target = suppliedTarget == null ? DEFAULT_TARGET : suppliedTarget;

        if (url == null) {
            Log.e(TAG, "No URL supplied");
            releaseWakeLock();
            finish();
            return;
        }

        Log.i(TAG, "Woke display; handing URL to " + target + ": " + url);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent play = new Intent(Intent.ACTION_VIEW, url);
                play.setPackage(target);
                play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(play);
                Log.i(TAG, "TizenSub+ launch requested");
            } catch (Exception e) {
                Log.e(TAG, "TizenSub+ launch failed", e);
            } finally {
                releaseWakeLock();
                finish();
            }
        }, 1500L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    protected void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }
}
