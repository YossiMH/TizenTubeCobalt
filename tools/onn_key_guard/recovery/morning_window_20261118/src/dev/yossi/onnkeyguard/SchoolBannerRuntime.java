package dev.yossi.onnkeyguard;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Build;
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
    private final SchoolBannerPolicy scheduledPolicy = new SchoolBannerPolicy();
    private SchoolBannerPolicy policy = scheduledPolicy;
    private final MorningHistoryCache history = new MorningHistoryCache();
    private final MorningWindowGrant diagnosticGrant = new MorningWindowGrant();
    private static Intent currentSenderIntent;
    private static int currentSenderUid = -1;
    private String workFenceCancelledRun = "";
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
        runtime.history.invalidate("cancelled", "input_observer_lost");
        runtime.diagnosticGrant.cancel();
        runtime.testObserverLive = false;
        runtime.finishTest("service_detached");
        runtime.removeOverlay();
        runtime.service = null;
        runtime.windowManager = null;
        runtime.main.removeCallbacks(runtime.tick);
        runtime.tickQueued = false;
        runtime.persist();
    }

    /** Receiver-only identity capture; the incoming Intent cannot supply this UID itself. */
    public static void captureSender(BroadcastReceiver receiver, Intent intent) {
        currentSenderIntent = intent;
        currentSenderUid = Build.VERSION.SDK_INT >= 34 ? receiver.getSentFromUid() : -1;
    }

    /** Exact hook after StartupAuthenticationPolicy and StartupRunPolicy both admit the run. */
    public static boolean scheduledStart(Intent intent) {
        SchoolBannerRuntime r = instance;
        if (r == null || r.service == null || intent == null
                || !"scheduled_start".equals(intent.getStringExtra("trigger"))) return false;
        long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        long workDeadline = intent.getLongExtra("run_deadline_wall_ms", -1L);
        long start = workDeadline - 300000L;
        long issued = intent.getLongExtra("issued_wall_ms", -1L);
        String run = intent.getStringExtra("run_generation");
        if (issued < start || issued > now + 1000L || issued < now - 3000L) return false;
        boolean accepted;
        if (r.diagnosticGrant.live(elapsed) && r.diagnosticGrant.consumedRun().length() == 0) {
            int sender = intent == currentSenderIntent ? currentSenderUid : -1;
            if (!r.diagnosticGrant.consume(run, start, issued, sender, now, elapsed)) return false;
            r.policy = new SchoolBannerPolicy();
            accepted = r.policy.diagnosticStart(run, start, workDeadline,
                    r.diagnosticGrant.fallbackWall(), r.diagnosticGrant.cutoffWall(), true, now);
        } else {
            r.policy = r.scheduledPolicy;
            accepted = r.policy.scheduledStart(run, start, workDeadline, true, now);
        }
        if (!accepted) return false;
        r.workFenceCancelledRun = "";
        r.history.bind(run, start, workDeadline);
        r.updateProof(true);
        r.renderAndPersist();
        return r.persistenceReady;
    }

    /** Additional finite fence; existing StartupRunPolicy remains the original authority. */
    public static boolean allowsStartupWork(String run) {
        SchoolBannerRuntime r = instance;
        if (r == null) return true;
        SchoolBannerPolicy.Snapshot s = r.policy.snapshot(System.currentTimeMillis());
        if (!s.scheduledAdmission || !s.runId.equals(run)) return true;
        return r.persistenceReady && r.policy.startupWorkAllowed(run, System.currentTimeMillis())
                && physicalProtectionActive();
    }

    /** Signature-owned launch binds the selected candidate before any Cobalt intent is sent. */
    public static boolean bindCandidate(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("startup_bound", false)) return true;
        SchoolBannerRuntime r = instance;
        if (r == null) return false;
        boolean accepted = r.policy.candidate(intent.getStringExtra("run_generation"),
                intent.getStringExtra("video_id"), System.currentTimeMillis());
        r.renderAndPersist();
        return accepted && r.persistenceReady;
    }

    public static boolean allowsEpisodeConfirmation(String run, String video) {
        SchoolBannerRuntime r = instance;
        if (r == null) return true;
        long now = System.currentTimeMillis();
        SchoolBannerPolicy.Snapshot state = r.policy.snapshot(now);
        if (!state.scheduledAdmission || !state.runId.equals(run)) return true;
        if (state.phase == SchoolBannerPolicy.Phase.WAITING && state.videoId.equals(video)) return true;
        return r.policy.startupWorkAllowed(run, now) && physicalProtectionActive()
                && (state.candidateVideoId.length() == 0 || state.candidateVideoId.equals(video));
    }

    /** Only shorten the canonical episode lease for the consumed bounded pipeline diagnostic. */
    public static long sessionDeadline(String run, long proposed) {
        SchoolBannerRuntime r = instance;
        if (r == null) return proposed;
        SchoolBannerPolicy.Snapshot state = r.policy.snapshot(System.currentTimeMillis());
        return state.diagnostic && state.runId.equals(run)
                ? Math.min(proposed, state.expiresAtMs) : proposed;
    }

    /** Existing signature receiver, read-only command; never enrolls an inactive event. */
    public static JSONObject historyForRun(Intent intent, boolean originalWorkAllowed) {
        SchoolBannerRuntime r = instance;
        if (r == null) return error("accessibility_service_detached");
        String run = intent.getStringExtra("run_generation");
        boolean allowed = originalWorkAllowed && allowsStartupWork(run);
        return r.history.read(run, intent.getLongExtra("run_started_wall_ms", -1L),
                intent.getLongExtra("run_deadline_wall_ms", -1L), allowed,
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    /** Called inside the existing authenticated /event path before its ordinary event switch. */
    public static JSONObject controllerEvent(JSONObject event) {
        if (event == null || !"morning_history".equals(event.optString("type", ""))) return null;
        SchoolBannerRuntime r = instance;
        if (r == null) return error("accessibility_service_detached");
        boolean accepted = r.history.accept(event,
                allowsStartupWork(event.optString("run_id", "")),
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
        JSONObject out = new JSONObject();
        put(out, "schema", 1); put(out, "ok", accepted);
        put(out, "morning_history", r.history.status());
        if (!accepted) put(out, "error", "morning_history_not_accepted");
        return out;
    }

    /** Only the existing DUMP-protected admin entry point calls this. */
    public static JSONObject admin(Intent intent, boolean authenticationClear, String foreground) {
        String command = intent.getStringExtra("command");
        if (command == null) command = intent.getStringExtra("cmd");
        if (!"morning_recover".equals(command) && !"test_morning_window".equals(command)) return null;
        SchoolBannerRuntime r = instance;
        if (r == null || r.service == null) return error("accessibility_service_detached");
        long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        SchoolBannerPolicy.Snapshot s = r.policy.snapshot(now);
        if ("test_morning_window".equals(command)) {
            if (s.phase == SchoolBannerPolicy.Phase.STARTING || s.phase == SchoolBannerPolicy.Phase.WAITING
                    || s.phase == SchoolBannerPolicy.Phase.ACTIVE) return error("scheduled_session_present");
            int helperUid = intent.getIntExtra("helper_uid", -1);
            try {
                PackageManager packages = r.service.getPackageManager();
                if (helperUid != packages.getApplicationInfo("dev.yossi.morningsesame", 0).uid
                        || packages.checkSignatures(r.service.getPackageName(), "dev.yossi.morningsesame")
                            != PackageManager.SIGNATURE_MATCH) return error("helper_identity_mismatch");
            } catch (PackageManager.NameNotFoundException missing) { return error("helper_unavailable"); }
            if (!r.diagnosticGrant.arm(intent.getLongExtra("scheduled_wall_ms", -1L), helperUid,
                    intent.getIntExtra("fallback_after_seconds", 90), intent.getIntExtra("duration_seconds", 180),
                    now, elapsed)) return error("invalid_morning_test_grant");
            JSONObject out = new JSONObject();
            put(out, "schema", 1); put(out, "ok", true); put(out, "state", "armed");
            put(out, "scheduled_wall_ms", r.diagnosticGrant.scheduledWall());
            put(out, "expires_elapsed_ms", r.diagnosticGrant.expiresElapsed());
            put(out, "helper_uid", helperUid);
            return out;
        }
        if (!authenticationClear || s.phase != SchoolBannerPolicy.Phase.STARTING
                || !physicalProtectionActive()) return error("morning_recovery_not_authorized");
        boolean ownsForeground = "io.gh.yossim.tizentube.cobalt".equals(foreground)
                || "dev.yossi.morningsesame".equals(foreground) || "dev.yossi.onnkeyguard".equals(foreground);
        if (!ownsForeground) {
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.setClassName("io.gh.yossim.tizentube.cobalt", "dev.cobalt.app.MainActivity");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try { r.service.startActivity(launch); }
            catch (RuntimeException failed) { return error("morning_player_launch_failed"); }
        }
        JSONObject out = new JSONObject();
        put(out, "schema", 1); put(out, "ok", true); put(out, "launched", !ownsForeground);
        return out;
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
                if (runtime.diagnosticGrant.consumedRun().length() == 0) runtime.diagnosticGrant.cancel();
                runtime.history.invalidate("cancelled", "phone_override");
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
            runtime.diagnosticGrant.cancel();
            runtime.history.invalidate("cancelled", "parent_override");
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
        if (saved.phase == SchoolBannerPolicy.Phase.STARTING || saved.phase == SchoolBannerPolicy.Phase.ACTIVE
                || saved.phase == SchoolBannerPolicy.Phase.WAITING)
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
        if (runtime == null || runtime.service == null || !runtime.persistenceReady)
            return false;
        long elapsed = SystemClock.elapsedRealtime();
        if (runtime.lastLiveSyncElapsedMs < 0 || elapsed < runtime.lastLiveSyncElapsedMs
                || elapsed - runtime.lastLiveSyncElapsedMs >= MAX_SYNC_AGE_MS) return false;
        SchoolBannerPolicy.Snapshot state = runtime.policy.snapshot(System.currentTimeMillis());
        if (state.diagnostic && !runtime.diagnosticGrant.live(elapsed)) return false;
        return state.startupProtectionReady || (runtime.overlay != null && (state.protectionReady || runtime.testActive()));
    }

    private void updateProof(boolean live) {
        testObserverLive = live;
        if (live) lastLiveSyncElapsedMs = SystemClock.elapsedRealtime();
        else {
            finishTest("input_observer_lost");
            diagnosticGrant.cancel();
            history.invalidate("cancelled", "input_observer_lost");
        }
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
        if (policy.snapshot(now).diagnostic && !diagnosticGrant.live(SystemClock.elapsedRealtime()))
            policy.expireDiagnostic();
        SchoolBannerPolicy.Snapshot state = policy.snapshot(now);
        if (!policy.startupWorkAllowed(state.runId, now)) {
            history.invalidate("cancelled", "startup_not_authorized");
            if (state.scheduledAdmission && state.phase != SchoolBannerPolicy.Phase.WAITING
                    && service != null && !state.runId.equals(workFenceCancelledRun)) {
                workFenceCancelledRun = state.runId;
                PolicyRuntime.get(service).cancelMorningWindowWork(state.runId);
            }
        }
        persist();
        boolean shouldShow = persistenceReady && service != null && windowManager != null
                && (state.protectionReady || testActive());
        if (shouldShow && overlay == null) addOverlay();
        else if (!shouldShow) removeOverlay();
        boolean hasPendingWork = state.phase == SchoolBannerPolicy.Phase.ACTIVE
                || state.phase == SchoolBannerPolicy.Phase.WAITING || state.phase == SchoolBannerPolicy.Phase.STARTING
                || testActive();
        if (service != null && hasPendingWork && !tickQueued) {
            tickQueued = true;
            long delay = 1000L;
            if (state.phase == SchoolBannerPolicy.Phase.ACTIVE || state.phase == SchoolBannerPolicy.Phase.WAITING)
                delay = Math.min(delay, Math.max(1L, state.expiresAtMs - now));
            if (state.phase == SchoolBannerPolicy.Phase.STARTING)
                delay = Math.min(delay, Math.max(1L, state.fallbackAtMs - now));
            if (state.diagnostic)
                delay = Math.min(delay, Math.max(1L, diagnosticGrant.expiresElapsed() - SystemClock.elapsedRealtime()));
            if (testDeadlineElapsedMs >= 0)
                delay = Math.min(delay, Math.max(1L, testDeadlineElapsedMs - SystemClock.elapsedRealtime()));
            main.postDelayed(tick, delay);
        } else if (!hasPendingWork && tickQueued) {
            main.removeCallbacks(tick);
            tickQueued = false;
        }
    }

    private void persist() {
        String saved = scheduledPolicy.save();
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
        put(out, "candidate_video_id", state.candidateVideoId);
        put(out, "started_at_ms", state.startedAtMs);
        put(out, "startup_deadline_ms", state.startupDeadlineMs);
        put(out, "fallback_at_ms", state.fallbackAtMs);
        put(out, "scheduled_admission", state.scheduledAdmission);
        put(out, "pipeline_diagnostic", state.diagnostic);
        put(out, "morning_history", history.status());
        put(out, "diagnostic_alarm_wall_ms", diagnosticGrant.scheduledWall());
        put(out, "diagnostic_run_id", diagnosticGrant.consumedRun());
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
