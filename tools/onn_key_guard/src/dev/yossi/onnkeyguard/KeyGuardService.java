package dev.yossi.onnkeyguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public final class KeyGuardService extends AccessibilityService {
    private static final String TAG = "OnnKeyGuard";
    private static final String LAUNCHER_PKG = "com.google.android.apps.tv.launcherx";
    private static final String CAST_WEB_PKG = "com.google.android.apps.mediashell";
    private static final String CAST_WEB_ACTIVITY = "CastWebContentsActivity";
    private static final String YOUTUBE_WEBVIEW_TITLE = "YouTube on TV";
    private static final String TARGET_PACKAGE = "io.gh.yossim.tizentube.cobalt";
    private static final String TARGET_ACTIVITY = "dev.cobalt.app.MainActivity";
    private static final int CAST_ROOT_RETRIES = 20;
    private static final long CAST_POLL_MS = 75L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean lockScreenVisible;
    private boolean castHandoffInFlight;
    private int castGeneration;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.notificationTimeout = 0;
            setServiceInfo(info);
        }
        lockScreenVisible = false;
        castHandoffInFlight = false;
        Log.i(TAG, "connected; event-driven filter-key-events and YouTube Cast guard active");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence packageName = event.getPackageName();
        CharSequence className = event.getClassName();
        String pkg = packageName == null ? "" : packageName.toString();
        String cls = className == null ? "" : className.toString();
        boolean locked = LAUNCHER_PKG.equals(pkg)
                && (cls.contains("ReauthActivity")
                    || cls.contains("ProfileLockWrapperActivity")
                    || cls.contains(".profile.lock."));
        if (locked != lockScreenVisible) {
            lockScreenVisible = locked;
            Log.i(TAG, "lockState changed to " + locked + " class=" + cls);
        }

        if (CAST_WEB_PKG.equals(pkg) && cls.contains(CAST_WEB_ACTIVITY)) {
            if (!castHandoffInFlight) {
                castHandoffInFlight = true;
                int generation = ++castGeneration;
                handler.post(() -> detectYoutubeCast(generation, 0));
            }
        } else if (castHandoffInFlight) {
            castHandoffInFlight = false;
            ++castGeneration;
        }
    }

    private void detectYoutubeCast(int generation, int attempt) {
        if (!isCurrentCastGeneration(generation)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean youtube = containsYoutubeCastWebView(root);
        if (root != null) root.recycle();
        if (!youtube) {
            if (attempt < CAST_ROOT_RETRIES) {
                handler.postDelayed(() -> detectYoutubeCast(generation, attempt + 1), CAST_POLL_MS);
            } else {
                Log.i(TAG, "Cast web receiver was not YouTube; leaving it untouched");
                finishCastAttempt(generation);
            }
            return;
        }

        Log.i(TAG, "YouTube Cast web receiver detected; stopping unrestricted receiver");
        handoffYoutubeCast(generation);
    }

    private boolean containsYoutubeCastWebView(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence cls = node.getClassName();
        CharSequence text = node.getText();
        if ("android.webkit.WebView".contentEquals(cls == null ? "" : cls)
                && YOUTUBE_WEBVIEW_TITLE.contentEquals(text == null ? "" : text)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try {
                if (containsYoutubeCastWebView(child)) return true;
            } finally {
                if (child != null) child.recycle();
            }
        }
        return false;
    }

    private void handoffYoutubeCast(int generation) {
        if (!isCurrentCastGeneration(generation)) return;
        stopCurrentMediaSession();
        performGlobalAction(GLOBAL_ACTION_BACK);

        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.setClassName(TARGET_PACKAGE, TARGET_ACTIVITY);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(launch);
            Log.i(TAG, "YouTube Cast handed to TizenSub+ fail-closed home");
        } catch (Exception e) {
            Log.e(TAG, "YouTube Cast handoff to TizenSub+ failed", e);
        } finally {
            finishCastAttempt(generation);
        }
    }

    private void stopCurrentMediaSession() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager == null) return;
            long now = SystemClock.uptimeMillis();
            audioManager.dispatchMediaKeyEvent(
                    new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP, 0));
            audioManager.dispatchMediaKeyEvent(
                    new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP, 0));
            Log.i(TAG, "Dispatched media stop before YouTube Cast handoff");
        } catch (Exception e) {
            Log.w(TAG, "Unable to dispatch media stop before YouTube Cast handoff", e);
        }
    }

    private boolean isCurrentCastGeneration(int generation) {
        return castHandoffInFlight && castGeneration == generation;
    }

    private void finishCastAttempt(int generation) {
        if (castGeneration == generation) {
            castHandoffInFlight = false;
            ++castGeneration;
        }
    }

    @Override public boolean onKeyEvent(KeyEvent event) {
        boolean interactive = isInteractive();
        int keyCode = event.getKeyCode();
        boolean block = KeyPolicy.shouldBlock(interactive, lockScreenVisible, keyCode);
        if (block) {
            Log.i(TAG, "block keyCode=" + keyCode + " action=" + event.getAction()
                    + " interactive=" + interactive + " lockScreen=" + lockScreenVisible);
            return true;
        }
        return false;
    }

    private boolean isInteractive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    @Override public void onInterrupt() {}
}
