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
    private static final long STARTUP_WAKE_MS = 105_000L;
    private static final long VERIFY_HANDOFF_MS = 65_000L;
    private static final long PLAY_HANDOFF_MS = 75_000L;
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
            wakeLock.acquire(STARTUP_WAKE_MS);
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

        Log.i(TAG, "Woke display; warming TizenSub+ before video handoff: " + url);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent warm = getPackageManager().getLaunchIntentForPackage(target);
                if (warm == null) {
                    throw new IllegalStateException("No launch intent for " + target);
                }
                warm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(warm);
                Log.i(TAG, "TizenSub+ warm-up requested");
            } catch (Exception e) {
                Log.e(TAG, "TizenSub+ warm-up failed", e);
                releaseWakeLock();
                finish();
                return;
            }

            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(() -> handoffVideo(target, url, "verification"), VERIFY_HANDOFF_MS);
            handler.postDelayed(() -> handoffVideo(target, url, "playback"), PLAY_HANDOFF_MS);
            handler.postDelayed(() -> {
                releaseWakeLock();
                finish();
            }, STARTUP_WAKE_MS - 2_000L);
        }, 1500L);
    }

    private void handoffVideo(String target, Uri url, String phase) {
        try {
            Intent play = new Intent(Intent.ACTION_VIEW, url);
            play.setPackage(target);
            play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(play);
            Log.i(TAG, "TizenSub+ " + phase + " handoff requested");
        } catch (Exception e) {
            Log.e(TAG, "TizenSub+ " + phase + " handoff failed", e);
        }
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
