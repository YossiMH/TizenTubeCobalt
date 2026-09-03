package dev.yossi.onnkeyguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public final class KeyGuardService extends AccessibilityService {
    private static final String TAG = "OnnKeyGuard";
    private static final String LAUNCHER_PKG = "com.google.android.apps.tv.launcherx";
    private volatile boolean lockScreenVisible;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.notificationTimeout = 0;
            setServiceInfo(info);
        }
        lockScreenVisible = false;
        Log.i(TAG, "connected; event-driven filter-key-events active");
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
