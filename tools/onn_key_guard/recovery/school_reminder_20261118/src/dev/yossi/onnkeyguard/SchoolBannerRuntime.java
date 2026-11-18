package dev.yossi.onnkeyguard;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Small hook target added alongside the installed guard; no existing policy is recompiled. */
public final class SchoolBannerRuntime {
    private static final String TAG = "SchoolBanner";
    private static final long MAX_SYNC_AGE_MS = 12000L;
    private static volatile SchoolBannerRuntime instance;
    private final SchoolBannerPolicy policy = new SchoolBannerPolicy();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private AccessibilityService service;
    private WindowManager windowManager;
    private View overlay;
    private SchoolReminderMedia reminderMedia;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocus;
    private String audioFocusStatus = "released";
    private final SchoolBannerPolicy.AudioRecovery audioRecovery = new SchoolBannerPolicy.AudioRecovery();
    private int audioFocusGeneration;
    private String lastSaved;
    private boolean persistenceReady = true;
    private boolean tickQueued;
    private long lastLiveSyncElapsedMs = -1L;
    private long testDeadlineMs = -1L;
    private long testDeadlineElapsedMs = -1L;
    private String lastTestResult = "none";
    private boolean testObserverLive;

    private SchoolBannerRuntime(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("school_banner_v1", 0);
        lastSaved = preferences.getString("state", "");
        if (!policy.restore(lastSaved, System.currentTimeMillis())) {
            Log.w(TAG, "Rejected invalid persisted school banner state");
            lastSaved = null;
        }
    }

    public static void attach(final AccessibilityService service) {
        if (service == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { attach(service); }
            });
            return;
        }
        SchoolBannerRuntime runtime = instance;
        if (runtime == null) {
            runtime = new SchoolBannerRuntime(service);
            instance = runtime;
        }
        if (runtime.service != service) runtime.removeOverlay();
        runtime.service = service;
        runtime.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        runtime.renderAndPersist();
    }

    public static void detach(AccessibilityService service) {
        SchoolBannerRuntime runtime = instance;
        if (runtime == null || runtime.service != service) return;
        runtime.policy.observeInputProof(false, System.currentTimeMillis());
        runtime.testObserverLive = false;
        runtime.finishTest("service_detached");
        runtime.removeOverlay();
        runtime.service = null;
        runtime.windowManager = null;
        runtime.main.removeCallbacks(runtime.tick);
        runtime.tickQueued = false;
        runtime.persist();
    }

    /** Hook BEFORE persistSessionTransition's unchanged-transition early return. */
    public static void sync(final SesameSessionPolicy.Snapshot session,
            final boolean scheduledEnabled, final boolean observerLive, final long now) {
        final SchoolBannerRuntime runtime = instance;
        if (runtime == null || session == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runtime.main.post(new Runnable() {
                @Override public void run() { sync(session, scheduledEnabled, observerLive, now); }
            });
            return;
        }
        runtime.policy.observeSession(session.id, session.videoId,
                session.phase.name().toLowerCase(Locale.ROOT), session.reason,
                scheduledEnabled, observerLive, now);
        runtime.updateProof(observerLive);
        runtime.renderAndPersist();
    }

    /** Hook only on handleEvent's validated successful path, before session persistence/resync. */
    public static void onAcceptedEvent(JSONObject event) {
        SchoolBannerRuntime runtime = instance;
        if (runtime == null || event == null) return;
        long now = System.currentTimeMillis();
        String type = event.optString("type", "");
        if ("phone_key".equals(type)) {
            int code = event.optInt("linux_code", -1);
            int value = event.optInt("value", -1);
            long sampledAt = event.optLong("wall_ms", -1L);
            runtime.policy.phoneKey(code, value, sampledAt, now);
            if (SchoolBannerPolicy.fresh(sampledAt, now) && (value == 1 || value == 2)
                    && code > 0 && code <= 767 && code != 113 && code != 114 && code != 115) {
                runtime.finishTest("phone_dismissed");
            }
        } else if ("playback".equals(type) && "ended".equals(event.optString("playing", ""))) {
            SchoolBannerPolicy.Snapshot before = runtime.policy.snapshot(now);
            String eventRun = event.optString("run_id", before.runId);
            runtime.policy.ended(eventRun, event.optString("video_id", ""),
                    event.optLong("sample_wall_ms", -1L), now);
        }
        runtime.renderAndPersist();
    }

    /** Hook the existing parent/admin override and disable commands before their session update. */
    public static void onAdminCommand(String command) {
        SchoolBannerRuntime runtime = instance;
        if (runtime == null) return;
        if ("override".equals(command) || "disable_session".equals(command)) {
            runtime.policy.dismiss("parent_override", System.currentTimeMillis());
            runtime.finishTest("parent_override");
            runtime.renderAndPersist();
        }
    }

    /** Explicit DUMP-guarded diagnostic; neither enrolls a morning nor persists diagnostic state. */
    public static JSONObject testBanner(int seconds, boolean observerLive) {
        SchoolBannerRuntime runtime = instance;
        if (runtime == null || runtime.service == null) return error("accessibility_service_detached");
        if (seconds < 1 || seconds > 60) return error("test_duration_must_be_1_to_60_seconds");
        if (!observerLive) return error("input_observer_unavailable");
        SchoolBannerPolicy.Snapshot saved = runtime.policy.snapshot(System.currentTimeMillis());
        if (saved.phase == SchoolBannerPolicy.Phase.ACTIVE || saved.phase == SchoolBannerPolicy.Phase.WAITING)
            return error("scheduled_session_present");
        runtime.testDeadlineMs = System.currentTimeMillis() + seconds * 1000L;
        runtime.testDeadlineElapsedMs = SystemClock.elapsedRealtime() + seconds * 1000L;
        runtime.lastTestResult = "active";
        runtime.updateProof(true);
        runtime.renderAndPersist();
        JSONObject result = new JSONObject();
        put(result, "schema", 1);
        put(result, "ok", runtime.overlay != null);
        put(result, "school_banner", runtime.status());
        return result;
    }

    /** Merge one new canonical field; leave every existing session field untouched. */
    public static JSONObject decorateStatus(JSONObject original) {
        if (original == null) return null;
        SchoolBannerRuntime runtime = instance;
        if (runtime == null) {
            JSONObject empty = new JSONObject();
            put(empty, "phase", "inactive");
            put(empty, "protection_ready", false);
            put(empty, "overlay_visible", false);
            put(empty, "reason", "accessibility_service_detached");
            put(original, "school_banner", empty);
        } else {
            runtime.renderAndPersist();
            put(original, "school_banner", runtime.status());
        }
        return original;
    }

    /** OR this into the existing physicalProtectionActive result; existing volume policy is reused. */
    public static boolean physicalProtectionActive() {
        SchoolBannerRuntime runtime = instance;
        if (runtime == null || runtime.service == null || runtime.overlay == null || !runtime.persistenceReady)
            return false;
        long elapsed = SystemClock.elapsedRealtime();
        if (runtime.lastLiveSyncElapsedMs < 0 || elapsed < runtime.lastLiveSyncElapsedMs
                || elapsed - runtime.lastLiveSyncElapsedMs >= MAX_SYNC_AGE_MS) return false;
        return runtime.policy.snapshot(System.currentTimeMillis()).protectionReady || runtime.testActive();
    }

    private void updateProof(boolean live) {
        testObserverLive = live;
        if (live) lastLiveSyncElapsedMs = SystemClock.elapsedRealtime();
        else finishTest("input_observer_lost");
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            tickQueued = false;
            long elapsed = SystemClock.elapsedRealtime();
            if (lastLiveSyncElapsedMs >= 0
                    && (elapsed < lastLiveSyncElapsedMs || elapsed - lastLiveSyncElapsedMs >= MAX_SYNC_AGE_MS)) {
                policy.observeInputProof(false, System.currentTimeMillis());
                testObserverLive = false;
                finishTest("input_observer_lost");
            }
            if (testDeadlineElapsedMs >= 0 && elapsed >= testDeadlineElapsedMs) finishTest("expired");
            renderAndPersist();
        }
    };

    private void renderAndPersist() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(new Runnable() {
                @Override public void run() { renderAndPersist(); }
            });
            return;
        }
        long now = System.currentTimeMillis();
        SchoolBannerPolicy.Snapshot state = policy.snapshot(now);
        persist();
        boolean shouldShow = persistenceReady && service != null && windowManager != null
                && (state.protectionReady || testActive());
        if (shouldShow && overlay == null) addOverlay();
        else if (!shouldShow) removeOverlay();
        boolean hasPendingWork = state.phase == SchoolBannerPolicy.Phase.ACTIVE
                || state.phase == SchoolBannerPolicy.Phase.WAITING || testActive();
        if (service != null && hasPendingWork && !tickQueued) {
            tickQueued = true;
            long delay = 1000L;
            if (state.phase == SchoolBannerPolicy.Phase.ACTIVE || state.phase == SchoolBannerPolicy.Phase.WAITING)
                delay = Math.min(delay, Math.max(1L, state.expiresAtMs - now));
            if (testDeadlineElapsedMs >= 0)
                delay = Math.min(delay, Math.max(1L, testDeadlineElapsedMs - SystemClock.elapsedRealtime()));
            main.postDelayed(tick, delay);
        } else if (!hasPendingWork && tickQueued) {
            main.removeCallbacks(tick);
            tickQueued = false;
        }
    }

    private void persist() {
        String saved = policy.save();
        if (saved.equals(lastSaved)) return;
        if (preferences.edit().putString("state", saved).commit()) {
            lastSaved = saved;
        } else {
            persistenceReady = false;
            policy.dismiss("persistence_unavailable", System.currentTimeMillis());
            finishTest("persistence_unavailable");
            Log.e(TAG, "School banner released because state could not be persisted");
        }
    }

    private void addOverlay() {
        final SchoolReminderMedia media = new SchoolReminderMedia(service, new SchoolReminderMedia.Guard() {
            @Override public boolean authorized() { return physicalProtectionActive(); }
        }, testActive());
        View view = media.view();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.FILL;
        params.setTitle("Morning school reminder");
        try {
            windowManager.addView(view, params);
            overlay = view;
            reminderMedia = media;
            stopMediaAndHoldAudio();
            media.start();
            Log.i(TAG, "School banner visible");
        } catch (RuntimeException failed) {
            media.close();
            if (overlay == view) removeOverlay();
            policy.dismiss("overlay_unavailable", System.currentTimeMillis());
            finishTest("overlay_unavailable");
            persist();
            Log.e(TAG, "School banner released because overlay could not be attached", failed);
        }
    }

    private void removeOverlay() {
        if (reminderMedia != null) { reminderMedia.close(); reminderMedia = null; }
        releaseAudio();
        if (overlay == null) return;
        View old = overlay;
        overlay = null;
        try {
            if (windowManager != null) windowManager.removeViewImmediate(old);
        } catch (RuntimeException alreadyDetached) {
            Log.w(TAG, "School banner view was already detached");
        }
    }

    private void stopMediaAndHoldAudio() {
        try {
            audioManager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                audioFocusStatus = "unavailable";
                return;
            }
            final int generation = ++audioFocusGeneration;
            audioRecovery.cancel();
            audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override public void onAudioFocusChange(int change) {
                            if (generation != audioFocusGeneration || audioFocus == null || overlay == null) return;
                            if (change > 0) {
                                audioFocusStatus = "held";
                                if (reminderMedia != null) reminderMedia.setAudioFocusHeld(true);
                                audioRecovery.gained(SystemClock.elapsedRealtime());
                                main.removeCallbacks(recoverAudio);
                            } else {
                                audioFocusStatus = "lost";
                                if (reminderMedia != null) reminderMedia.setAudioFocusHeld(false);
                                audioRecovery.lost(SystemClock.elapsedRealtime());
                                scheduleAudioRecovery();
                            }
                        }
                    }, main).build();
            requestAudioAndStop();
        } catch (RuntimeException unavailable) {
            audioFocusStatus = "unavailable";
            Log.w(TAG, "Audio focus or media stop unavailable", unavailable);
        }
    }

    private void releaseAudio() {
        if (reminderMedia != null) reminderMedia.setAudioFocusHeld(false);
        audioFocusGeneration++;
        main.removeCallbacks(recoverAudio);
        audioRecovery.cancel();
        if (audioManager != null && audioFocus != null) {
            try { audioManager.abandonAudioFocusRequest(audioFocus); }
            catch (RuntimeException alreadyReleased) { Log.w(TAG, "Audio focus already released"); }
        }
        audioFocus = null;
        audioManager = null;
        audioFocusStatus = "released";
    }

    private void requestAudioAndStop() {
        boolean granted = audioManager.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        audioFocusStatus = granted ? "held" : "not_granted";
        if (granted) {
            audioRecovery.gained(SystemClock.elapsedRealtime());
            main.removeCallbacks(recoverAudio);
        } else {
            audioRecovery.lost(SystemClock.elapsedRealtime());
            scheduleAudioRecovery();
        }
        long eventTime = SystemClock.uptimeMillis();
        try {
            audioManager.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime,
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP, 0));
        } finally {
            audioManager.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime,
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP, 0));
        }
        if (reminderMedia != null) reminderMedia.setAudioFocusHeld(granted);
    }

    private void scheduleAudioRecovery() {
        main.removeCallbacks(recoverAudio);
        long due = audioRecovery.dueAtMs();
        if (due >= 0 && overlay != null)
            main.postDelayed(recoverAudio, Math.max(1L, due - SystemClock.elapsedRealtime()));
    }

    private final Runnable recoverAudio = new Runnable() {
        @Override public void run() {
            // The delay is longer than the accepted phone event's freshness
            // window. A parent's valid phone dismissal wins before this work.
            boolean active = audioManager != null && audioFocus != null && physicalProtectionActive();
            if (!audioRecovery.claim(SystemClock.elapsedRealtime(), active)) return;
            try {
                requestAudioAndStop();
            } catch (RuntimeException unavailable) {
                audioFocusStatus = "unavailable";
                audioRecovery.lost(SystemClock.elapsedRealtime());
                scheduleAudioRecovery();
                Log.w(TAG, "Bounded audio recovery unavailable", unavailable);
            }
        }
    };

    private boolean testActive() {
        return testObserverLive && testDeadlineElapsedMs >= 0
                && SystemClock.elapsedRealtime() < testDeadlineElapsedMs
                && System.currentTimeMillis() < testDeadlineMs;
    }

    private void finishTest(String result) {
        if (testDeadlineElapsedMs >= 0) {
            testDeadlineElapsedMs = -1L;
            testDeadlineMs = -1L;
            lastTestResult = result;
        }
    }

    private JSONObject status() {
        long now = System.currentTimeMillis();
        SchoolBannerPolicy.Snapshot state = policy.snapshot(now);
        boolean diagnostic = testActive();
        boolean ready = physicalProtectionActive();
        String phase = diagnostic ? "active" : state.phase.name().toLowerCase(Locale.ROOT);
        if (!diagnostic && state.phase == SchoolBannerPolicy.Phase.ACTIVE && !ready)
            phase = "awaiting_observer";
        JSONObject out = new JSONObject();
        put(out, "schema", 1);
        put(out, "phase", phase);
        put(out, "protection_ready", ready);
        put(out, "overlay_visible", overlay != null);
        put(out, "audio_focus", audioFocusStatus);
        if (reminderMedia != null) put(out, "reminder_media", reminderMedia.status());
        put(out, "audio_recovery_attempts", audioRecovery.attempts());
        put(out, "audio_recovery_pending", audioRecovery.dueAtMs() >= 0);
        put(out, "audio_recovery_exhausted", audioRecovery.exhausted());
        put(out, "observer_ready", diagnostic ? testObserverLive : state.observerReady);
        put(out, "run_id", diagnostic ? "diagnostic" : state.runId);
        put(out, "video_id", state.videoId);
        put(out, "local_date", state.localDate);
        put(out, "expires_at_ms", diagnostic ? testDeadlineMs : state.expiresAtMs);
        put(out, "completed_at_ms", state.completedAtMs);
        put(out, "reason", diagnostic ? "bounded_diagnostic" : state.reason);
        put(out, "diagnostic", diagnostic);
        put(out, "last_diagnostic_result", lastTestResult);
        put(out, "message", SchoolBannerPolicy.MESSAGE);
        return out;
    }

    private static JSONObject error(String message) {
        JSONObject result = new JSONObject();
        put(result, "schema", 1);
        put(result, "ok", false);
        put(result, "error", message);
        return result;
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (JSONException impossibleForConstantKeys) { throw new IllegalStateException(impossibleForConstantKeys); }
    }
}
