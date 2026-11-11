package dev.yossi.onnkeyguard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Main-looper policy mutations, bounded loopback IO, and private same-boot session persistence. */
public final class PolicyRuntime {
    public enum Rollout { PREPARED, ENABLED }
    public enum Authentication { REQUIRED, CLEAR, UNKNOWN }
    private enum Lifecycle { ATTACHED, DETACHED }
    private enum ServerState { STOPPED, STARTING, LISTENING, UNAVAILABLE }

    public static final int PORT = 8768;
    static final String TARGET_PACKAGE = "io.gh.yossim.tizentube.cobalt";
    private static final String TARGET_ACTIVITY = "dev.cobalt.app.MainActivity";
    private static final String LAUNCHER_PACKAGE = "com.google.android.apps.tv.launcherx";
    private static final long EVENT_MAX_AGE_MS = 3000L;
    private static final long RECOVERY_COOLDOWN_MS = 15000L;
    private static PolicyRuntime instance;

    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object serverLock = new Object();
    private final String controllerToken;
    private final int bootCount;
    private final ArrayDeque<String> recentPhoneEvents = new ArrayDeque<String>();
    private final InputObserverPolicy inputObserver = new InputObserverPolicy();
    private SesameSessionPolicy session = new SesameSessionPolicy();
    private volatile Rollout rollout = Rollout.PREPARED;
    private volatile SessionMode sessionMode = SessionMode.SCHEDULED;
    private volatile Authentication authentication = Authentication.UNKNOWN;
    private volatile Lifecycle lifecycle = Lifecycle.DETACHED;
    private volatile ServerState serverState = ServerState.STOPPED;
    private volatile String foregroundPackage = "";
    private String lastWindowClass = "";
    private String lastWindowPackage = "";
    private String nativeLeaseStatus = "stopped";
    private String lastRecoveryResult = "none";
    private String lastRecoveryReason = "";
    private String lastStartResult = "none";
    private String lastPersistedTransition = "";
    private long lastRecoveryAttemptAtMs = -1;
    private long serverGeneration;
    private KeyGuardService attachedService;
    private ServerSocket ownedServer;
    private Socket ownedClient;
    private Thread serverThread;

    private final Runnable expiryTick = new Runnable() {
        @Override public void run() {
            if (lifecycle != Lifecycle.ATTACHED) return;
            refreshObserverSafety(System.currentTimeMillis());
            persistSessionTransition(System.currentTimeMillis());
            main.postDelayed(this, 1000L);
        }
    };

    public static synchronized PolicyRuntime get(Context context) {
        if (instance == null) instance = new PolicyRuntime(context.getApplicationContext());
        return instance;
    }

    private PolicyRuntime(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("session_policy_v1", Context.MODE_PRIVATE);
        bootCount = readBootCount(context);
        rollout = "enabled".equals(preferences.getString("rollout", "prepared"))
                ? Rollout.ENABLED : Rollout.PREPARED;
        String savedToken = preferences.getString("controller_token", "");
        if (savedToken != null && savedToken.matches("[0-9a-f]{64}")) {
            controllerToken = savedToken;
        } else {
            byte[] entropy = new byte[32];
            new SecureRandom().nextBytes(entropy);
            StringBuilder token = new StringBuilder(64);
            final char[] hex = "0123456789abcdef".toCharArray();
            for (byte value : entropy) {
                token.append(hex[(value >>> 4) & 15]).append(hex[value & 15]);
            }
            controllerToken = token.toString();
            preferences.edit().putString("controller_token", controllerToken).apply();
        }
        restoreSameBootSession();
    }

    void attach(KeyGuardService service) {
        attachedService = service;
        lifecycle = Lifecycle.ATTACHED;
        authentication = Authentication.UNKNOWN;
        scanAuthentication();
        startServer();
        main.removeCallbacks(expiryTick);
        main.post(expiryTick);
    }

    void detach(KeyGuardService service) {
        if (attachedService != service) return;
        inputObserver.invalidate(System.currentTimeMillis());
        refreshObserverSafety(System.currentTimeMillis());
        persistSessionTransition(System.currentTimeMillis());
        attachedService = null;
        lifecycle = Lifecycle.DETACHED;
        authentication = Authentication.UNKNOWN;
        foregroundPackage = "";
        main.removeCallbacks(expiryTick);
        stopServer();
    }

    /** Called only by the signature-protected receiver after the helper confirms scheduled playback. */
    void startSession(String runId, String videoId, int durationSeconds, String trigger) {
        if (!"scheduled_confirmed".equals(trigger)) {
            lastStartResult = "unverified_trigger";
            return;
        }
        if (rollout != Rollout.ENABLED) {
            lastStartResult = "prepared";
            return;
        }
        long now = System.currentTimeMillis();
        refreshObserverSafety(now);
        InputObserverPolicy.Activation admission = inputObserver.activation(SystemClock.elapsedRealtime());
        SesameSessionPolicy.BeginResult result = session.begin(runId, videoId, durationSeconds, now);
        lastStartResult = lower(result);
        if (result == SesameSessionPolicy.BeginResult.STARTED) {
            sessionMode = SessionMode.SCHEDULED;
            recentPhoneEvents.clear();
            if (admission == InputObserverPolicy.Activation.RECENT_PHONE) {
                session.recentPhoneBeforeStart(now);
                lastStartResult = "recent_phone_intent";
            } else if (admission != InputObserverPolicy.Activation.ALLOWED) {
                // Retain this declined run ID; later monitor health must not silently rearm it.
                session.inputObserverLost(now);
                lastStartResult = "input_observer_" + lower(admission);
            }
        }
        persistSessionTransition(now);
    }

    /** No shell, tree traversal, socket work, logging, or preferences access on the key callback. */
    boolean physicalProtectionActive() {
        long now = System.currentTimeMillis();
        refreshObserverSafety(now);
        if (sessionMode.isBoundedProbe()) return sessionMode.authorizesPreparedGuard(session, now);
        return rollout == Rollout.ENABLED && session.shouldGrab(now);
    }

    boolean authenticationRequired() {
        return authentication == Authentication.REQUIRED;
    }

    void onWindow(String packageName, String className, boolean profileLockVisible) {
        String pkg = packageName == null ? "" : packageName;
        String cls = className == null ? "" : className;
        if (pkg.length() == 0 || transientPackage(pkg)) return;
        foregroundPackage = pkg;
        lastWindowPackage = pkg;
        lastWindowClass = cls;
        if (profileLockVisible || lockClass(pkg, cls)) {
            authentication = Authentication.REQUIRED;
        } else if (!LAUNCHER_PACKAGE.equals(pkg) || concreteActivity(cls)) {
            authentication = Authentication.CLEAR;
        } else {
            authentication = Authentication.UNKNOWN;
        }
        long now = System.currentTimeMillis();
        refreshObserverSafety(now);
        session.observeForeground(pkg, TARGET_PACKAGE, now);
        persistSessionTransition(now);
    }

    /** Manifest DUMP permission is the sole exported entry point for these administrative operations. */
    JSONObject admin(Intent intent) {
        String command = intent.getStringExtra("command");
        if (command == null) command = intent.getStringExtra("cmd");
        if (command == null) command = "status";
        long now = System.currentTimeMillis();
        if ("controller_token".equals(command)) {
            return object("schema", 1, "token", controllerToken, "port", PORT);
        }
        if ("status".equals(command)) return statusJson();
        if ("override".equals(command)) {
            session.override(now);
            persistSessionTransition(now);
            return statusJson();
        }
        if ("enable_session".equals(command)) {
            rollout = Rollout.ENABLED;
            preferences.edit().putString("rollout", "enabled").apply();
            return statusJson();
        }
        if ("disable_session".equals(command)) {
            rollout = Rollout.PREPARED;
            preferences.edit().putString("rollout", "prepared").apply();
            session.override(now);
            persistSessionTransition(now);
            return statusJson();
        }
        if ("recover".equals(command)) return recover();
        if ("test_session".equals(command)) return boundedSession(intent, now, SessionMode.TEST);
        if ("power_probe".equals(command)) return boundedSession(intent, now, SessionMode.POWER_PROBE);
        if ("audio_tree".equals(command)) return inspectTree();
        if ("probe".equals(command)) {
            scanAuthentication();
            JSONObject result = statusJson();
            put(result, "input_devices", inputDevices());
            return result;
        }
        return error("unknown_command");
    }

    private JSONObject boundedSession(Intent intent, long now, SessionMode mode) {
        refreshObserverSafety(now);
        if (!inputObserver.isLive(SystemClock.elapsedRealtime())) return error("input_observer_unavailable");
        int seconds = intent.getIntExtra("duration_seconds", 30);
        String videoId = intent.getStringExtra("video_id");
        String runId = intent.getStringExtra("run_generation");
        if (runId == null) runId = lower(mode) + "-" + now;
        if (seconds < 1 || seconds > 60) return error(mode == SessionMode.TEST
                ? "test_duration_must_be_1_to_60_seconds" : "probe_duration_must_be_1_to_60_seconds");
        SesameSessionPolicy.Snapshot current = session.status(now);
        if (current.phase == SesameSessionPolicy.Phase.ACTIVE
                || current.phase == SesameSessionPolicy.Phase.PHONE_PAUSED) return error("existing_live_session");
        SesameSessionPolicy test = new SesameSessionPolicy();
        SesameSessionPolicy.RestoreResult restored = test.restore(runId, videoId,
                SesameSessionPolicy.Phase.ACTIVE, now + seconds * 1000L, seconds,
                null, null, SesameSessionPolicy.Reason.DAILY_START, now);
        if (restored != SesameSessionPolicy.RestoreResult.RESTORED) return error("invalid_test_identity");
        session = test;
        sessionMode = mode;
        recentPhoneEvents.clear();
        lastStartResult = lower(mode);
        persistSessionTransition(now);
        return statusJson();
    }

    private JSONObject recover() {
        long now = System.currentTimeMillis();
        refreshObserverSafety(now);
        if (!sessionMode.permitsMediaRecovery()) return error(lower(sessionMode) + "_has_no_playback_launch");
        if (rollout != Rollout.ENABLED) return error("prepared");
        if (!session.needsRecovery(now)) return error("session_not_active");
        scanAuthentication();
        if (authentication != Authentication.CLEAR) return error("authentication_" + lower(authentication));
        if (attachedService == null) return error("accessibility_service_detached");
        if (lastRecoveryAttemptAtMs >= 0 && now >= lastRecoveryAttemptAtMs
                && now - lastRecoveryAttemptAtMs < RECOVERY_COOLDOWN_MS) return error("recovery_cooldown");
        SesameSessionPolicy.Snapshot state = session.status(now);
        Uri.Builder url = Uri.parse("https://www.youtube.com/watch").buildUpon()
                .appendQueryParameter("v", state.videoId);
        if (state.positionSeconds != null && state.positionSeconds.doubleValue() >= 1) {
            url.appendQueryParameter("t", Long.toString((long) state.positionSeconds.doubleValue()));
        }
        Intent play = new Intent(Intent.ACTION_VIEW, url.build());
        play.setClassName(TARGET_PACKAGE, TARGET_ACTIVITY);
        play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        lastRecoveryAttemptAtMs = now;
        try {
            attachedService.startActivity(play);
            lastRecoveryResult = "requested";
            lastRecoveryReason = "normal_same_video_view";
            return object("schema", 1, "ok", true, "result", lastRecoveryResult, "reason", lastRecoveryReason);
        } catch (RuntimeException failure) {
            lastRecoveryResult = "failed";
            lastRecoveryReason = "activity_launch_failed";
            return error(lastRecoveryReason);
        }
    }

    private JSONObject statusJson() {
        long now = System.currentTimeMillis();
        refreshObserverSafety(now);
        persistSessionTransition(now);
        InputObserverPolicy.Snapshot observer = inputObserver.status(SystemClock.elapsedRealtime());
        return object("schema", 1, "session", sessionJson(session.status(now)),
                "foreground_package", foregroundPackage, "authentication", lower(authentication),
                "audio_adapter", "unverified", "native_lease_status", nativeLeaseStatus,
                "rollout", lower(rollout), "session_mode", lower(sessionMode),
                "readiness", sessionMode.isBoundedProbe() ? lower(sessionMode) : lower(rollout),
                "server_status", lower(serverState), "last_start_result", lastStartResult,
                "input_observer_status", lower(observer.state), "input_observer_age_ms", observer.ageMs,
                "input_observer_max_age_ms", InputObserverPolicy.MAX_PROOF_AGE_MS,
                "input_activation_status", lower(observer.activation),
                "last_recovery", object("result", lastRecoveryResult, "reason", lastRecoveryReason));
    }

    private JSONObject handleEvent(JSONObject event) {
        String type = event.optString("type", "");
        long now = System.currentTimeMillis();
        refreshObserverSafety(now);
        if ("phone_key".equals(type)) {
            long sample = integer(event, "wall_ms", -1);
            long code = integer(event, "linux_code", -1);
            long value = integer(event, "value", -1);
            if (!fresh(sample, now) || code <= 0 || code > 767 || value < 0 || value > 2) {
                return error("invalid_or_stale_phone_event");
            }
            String identity = sample + ":" + code + ":" + value;
            if (recentPhoneEvents.contains(identity)) return object("schema", 1, "ok", true, "result", "duplicate");
            recentPhoneEvents.addLast(identity);
            while (recentPhoneEvents.size() > 64) recentPhoneEvents.removeFirst();
            inputObserver.observePhoneKey((int) code, (int) value, SystemClock.elapsedRealtime());
            session.phoneKey((int) code, (int) value, sample);
        } else if ("playback".equals(type)) {
            long sample = integer(event, "sample_wall_ms", -1);
            if (!fresh(sample, now)) return error("invalid_or_stale_playback_event");
            SesameSessionPolicy.PlaybackPhase playing;
            String phase = event.optString("playing", "unknown");
            if ("playing".equals(phase)) playing = SesameSessionPolicy.PlaybackPhase.PLAYING;
            else if ("paused".equals(phase)) playing = SesameSessionPolicy.PlaybackPhase.PAUSED;
            else if ("ended".equals(phase)) playing = SesameSessionPolicy.PlaybackPhase.ENDED;
            else if ("unknown".equals(phase)) playing = SesameSessionPolicy.PlaybackPhase.UNKNOWN;
            else return error("invalid_playback_phase");
            if (session.hasPendingGenericIntent(now)) {
                String currentPackage = currentRootPackage();
                if (currentPackage.length() != 0 && !transientPackage(currentPackage)) {
                    foregroundPackage = currentPackage;
                    session.observeForeground(currentPackage, TARGET_PACKAGE, now);
                }
            }
            session.observePlayback(event.optString("video_id", ""), number(event, "position_seconds"),
                    number(event, "duration_seconds"), playing, sample);
        } else if ("recovery".equals(type)) {
            lastRecoveryResult = bounded(event.optString("result", "unknown"), 80);
            lastRecoveryReason = bounded(event.optString("reason", ""), 160);
        } else if ("native_health".equals(type)) {
            String status = event.optString("status", "");
            InputObserverPolicy.Health health;
            try { health = InputObserverPolicy.Health.valueOf(status.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { return error("invalid_native_health"); }
            InputObserverPolicy.Proof proof = InputObserverPolicy.Proof.NONE;
            if ("native_status".equals(event.optString("proof", ""))) {
                if (!fresh(integer(event, "sample_wall_ms", -1), now)
                        || !(health == InputObserverPolicy.Health.MONITORING
                            || health == InputObserverPolicy.Health.PHYSICAL_GRABBED)) {
                    return error("invalid_or_stale_native_proof");
                }
                proof = InputObserverPolicy.Proof.NATIVE_STATUS;
            }
            if (!inputObserver.observeHealth(health, proof, SystemClock.elapsedRealtime(),
                    integer(event, "sample_wall_ms", -1), now)) return error("native_proof_before_loss_cutoff");
            nativeLeaseStatus = status;
            refreshObserverSafety(now);
        } else {
            return error("unknown_event_type");
        }
        persistSessionTransition(now);
        return object("schema", 1, "ok", true);
    }

    private void restoreSameBootSession() {
        if (bootCount < 0 || preferences.getInt("session_boot_count", -2) != bootCount) return;
        String encoded = preferences.getString("session_json", "");
        if (encoded == null || encoded.length() == 0 || encoded.length() > 4096) return;
        try {
            JSONObject saved = new JSONObject(encoded);
            SesameSessionPolicy.Phase phase = SesameSessionPolicy.Phase.valueOf(
                    saved.getString("phase").toUpperCase(Locale.ROOT));
            SesameSessionPolicy.RestoreResult result = session.restore(saved.getString("id"),
                    saved.getString("video_id"), phase, saved.getLong("expires_at_ms"),
                    saved.getInt("duration_seconds"), optionalNumber(saved, "position_seconds"),
                    optionalNumber(saved, "observed_duration_seconds"),
                    SesameSessionPolicy.Reason.fromValue(saved.getString("reason")), System.currentTimeMillis());
            if (result != SesameSessionPolicy.RestoreResult.REJECTED) {
                sessionMode = SessionMode.valueOf(saved.optString("session_mode", "scheduled").toUpperCase(Locale.ROOT));
                // A process gap has no retained proof or complete phone-event history.
                // Keep the original run ID terminal instead of reviving an unobserved session.
                session.inputObserverLost(System.currentTimeMillis());
            }
        } catch (JSONException invalid) {
            // Invalid private state cannot arm protection or recreate phone intent.
        } catch (IllegalArgumentException invalid) {
            // Unknown enum values are incompatible state, not authorization to enforce.
        }
    }

    private void persistSessionTransition(long now) {
        SesameSessionPolicy.Snapshot snapshot = session.status(now);
        String key = snapshot.id + "|" + snapshot.phase.name() + "|" + snapshot.expiresAtMs;
        if (key.equals(lastPersistedTransition)) return;
        lastPersistedTransition = key;
        JSONObject saved = sessionJson(snapshot);
        put(saved, "session_mode", lower(sessionMode));
        preferences.edit().putString("session_json", saved.toString())
                .putInt("session_boot_count", bootCount).apply();
    }

    private void refreshObserverSafety(long nowWallMs) {
        if (!inputObserver.isLive(SystemClock.elapsedRealtime())) session.inputObserverLost(nowWallMs);
    }

    /** One root-package read only while resolving phone intent; no tree traversal or UI actions. */
    private String currentRootPackage() {
        if (attachedService == null) return "";
        AccessibilityNodeInfo root = null;
        try {
            root = attachedService.getRootInActiveWindow();
            return root == null ? "" : text(root.getPackageName());
        } catch (RuntimeException unavailable) {
            return "";
        } finally {
            if (root != null) root.recycle();
        }
    }

    private static JSONObject sessionJson(SesameSessionPolicy.Snapshot state) {
        return object("id", state.id, "video_id", state.videoId, "phase", lower(state.phase),
                "expires_at_ms", state.expiresAtMs, "duration_seconds", state.durationSeconds,
                "position_seconds", state.positionSeconds, "observed_duration_seconds", state.observedDurationSeconds,
                "reason", state.reason);
    }

    private void scanAuthentication() {
        KeyGuardService service = attachedService;
        if (service == null) {
            authentication = Authentication.UNKNOWN;
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            authentication = Authentication.UNKNOWN;
            return;
        }
        String rootPackage = text(root.getPackageName());
        Authentication found = Authentication.UNKNOWN;
        if (rootPackage.equals(lastWindowPackage) && lockClass(rootPackage, lastWindowClass)) {
            found = Authentication.REQUIRED;
        }
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<AccessibilityNodeInfo>();
        nodes.add(root);
        int visited = 0;
        long deadline = SystemClock.uptimeMillis() + 150L;
        while (!nodes.isEmpty() && visited < 96 && SystemClock.uptimeMillis() <= deadline) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            try {
                visited++;
                if (lockClass(text(node.getPackageName()), text(node.getClassName()))
                        || (!node.isPassword() && pinPrompt(text(node.getText())))) {
                    found = Authentication.REQUIRED;
                    break;
                }
                int children = Math.min(node.getChildCount(), 96 - visited - nodes.size());
                for (int i = 0; i < children; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) nodes.addLast(child);
                }
            } finally {
                node.recycle();
            }
        }
        while (!nodes.isEmpty()) nodes.removeFirst().recycle();
        if (found != Authentication.REQUIRED && rootPackage.length() != 0 && !transientPackage(rootPackage)) {
            if (!LAUNCHER_PACKAGE.equals(rootPackage)
                    || (rootPackage.equals(lastWindowPackage) && concreteActivity(lastWindowClass))) {
                found = Authentication.CLEAR;
            }
        }
        authentication = found;
        if (rootPackage.length() != 0 && !transientPackage(rootPackage)) foregroundPackage = rootPackage;
    }

    private JSONObject inspectTree() {
        if (attachedService == null) return error("accessibility_service_detached");
        AccessibilityNodeInfo root = attachedService.getRootInActiveWindow();
        if (root == null) return error("no_active_tree");
        int windowId = root.getWindowId();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        ArrayDeque<Integer> parents = new ArrayDeque<Integer>();
        queue.add(root);
        parents.add(Integer.valueOf(-1));
        JSONArray nodes = new JSONArray();
        Rect bounds = new Rect();
        int omittedChildSlots = 0;
        long deadline = SystemClock.uptimeMillis() + 350L;
        while (!queue.isEmpty() && nodes.length() < 250 && SystemClock.uptimeMillis() <= deadline) {
            AccessibilityNodeInfo node = queue.removeFirst();
            int parentIndex = parents.removeFirst().intValue();
            int index = nodes.length();
            try {
                node.getBoundsInScreen(bounds);
                nodes.put(object("index", index, "parent_index", parentIndex,
                        "window_id", node.getWindowId(),
                        "package", bounded(text(node.getPackageName()), 160),
                        "class", bounded(text(node.getClassName()), 200),
                        "resource", bounded(node.getViewIdResourceName(), 200),
                        "text", node.isPassword() ? "[redacted]" : bounded(text(node.getText()), 256),
                        "content_description", node.isPassword() ? "[redacted]"
                                : bounded(text(node.getContentDescription()), 256),
                        "selected", node.isSelected(), "checked", node.isChecked(),
                        "focused", node.isFocused(), "enabled", node.isEnabled(), "clickable", node.isClickable(),
                        "visible_to_user", node.isVisibleToUser(), "checkable", node.isCheckable(),
                        "focusable", node.isFocusable(),
                        "bounds_in_screen", object("left", bounds.left, "top", bounds.top,
                                "right", bounds.right, "bottom", bounds.bottom)));
                int childCount = node.getChildCount();
                int count = Math.min(childCount, 250 - nodes.length() - queue.size());
                omittedChildSlots += childCount - count;
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.addLast(child);
                        parents.addLast(Integer.valueOf(index));
                    }
                }
            } finally {
                node.recycle();
            }
        }
        int remaining = queue.size();
        while (!queue.isEmpty()) queue.removeFirst().recycle();
        parents.clear();
        return object("schema", 1, "audio_adapter", "unverified", "foreground_package", foregroundPackage,
                "authentication", lower(authentication), "window_id", windowId, "nodes", nodes, "bounded", true,
                "truncated", omittedChildSlots > 0 || remaining > 0 || nodes.length() >= 250);
    }

    private static JSONArray inputDevices() {
        JSONArray result = new JSONArray();
        int[] ids = InputDevice.getDeviceIds();
        for (int i = 0; i < ids.length && i < 32; i++) {
            InputDevice device = InputDevice.getDevice(ids[i]);
            if (device == null) continue;
            result.put(object("id", device.getId(), "name", bounded(device.getName(), 120),
                    "vendor", device.getVendorId(), "product", device.getProductId(),
                    "descriptor", bounded(device.getDescriptor(), 120),
                    "origin", lower(RemoteIdentity.classify(device.getId(), device.getName(),
                            device.getVendorId(), device.getProductId(), device.getDescriptor()))));
        }
        return result;
    }

    private void startServer() {
        synchronized (serverLock) {
            if (serverThread != null && serverThread.isAlive()) return;
            final long generation = ++serverGeneration;
            serverState = ServerState.STARTING;
            serverThread = new Thread(new Runnable() {
                @Override public void run() { serve(generation); }
            }, "OnnPolicyLoopback");
            serverThread.setDaemon(true);
            serverThread.start();
        }
    }

    private void stopServer() {
        synchronized (serverLock) {
            serverGeneration++;
            close(ownedClient);
            close(ownedServer);
            ownedClient = null;
            ownedServer = null;
            if (serverThread != null) serverThread.interrupt();
            serverThread = null;
            serverState = ServerState.STOPPED;
        }
    }

    private void serve(long generation) {
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            synchronized (serverLock) {
                if (generation != serverGeneration || lifecycle != Lifecycle.ATTACHED) return;
                server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4);
                server.setSoTimeout(1000);
                ownedServer = server;
                serverState = ServerState.LISTENING;
            }
            while (currentServer(generation)) {
                Socket client;
                try {
                    client = server.accept();
                } catch (SocketTimeoutException idle) {
                    continue;
                }
                synchronized (serverLock) {
                    if (generation != serverGeneration || lifecycle != Lifecycle.ATTACHED) {
                        close(client);
                        break;
                    }
                    ownedClient = client;
                }
                try {
                    client.setSoTimeout(1000);
                    handleRequest(client);
                } catch (IOException disconnected) {
                    // Broken/malformed client requests never affect input filtering or the UI thread.
                } finally {
                    close(client);
                    synchronized (serverLock) {
                        if (ownedClient == client) ownedClient = null;
                    }
                }
            }
        } catch (IOException unavailable) {
            synchronized (serverLock) {
                if (generation == serverGeneration) serverState = ServerState.UNAVAILABLE;
            }
        } finally {
            close(server);
            synchronized (serverLock) {
                if (generation == serverGeneration) {
                    ownedServer = null;
                    serverThread = null;
                    if (serverState != ServerState.UNAVAILABLE) serverState = ServerState.STOPPED;
                }
            }
        }
    }

    private boolean currentServer(long generation) {
        synchronized (serverLock) {
            return generation == serverGeneration && lifecycle == Lifecycle.ATTACHED;
        }
    }

    private void handleRequest(Socket client) throws IOException {
        try {
            ControllerProtocol.Request request = ControllerProtocol.read(client.getInputStream(),
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            if (!ControllerProtocol.authorized(request, controllerToken)) {
                respond(client, 401, error("unauthorized").toString());
                return;
            }
            if (request.method == ControllerProtocol.Method.GET && "/status".equals(request.path)) {
                String reply = onMain(new Callable<String>() {
                    @Override public String call() { return statusJson().toString(); }
                });
                respond(client, responseCode(reply), reply);
            } else if (request.method == ControllerProtocol.Method.POST && "/event".equals(request.path)) {
                final JSONObject event;
                try {
                    event = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
                } catch (JSONException invalid) {
                    respond(client, 400, error("invalid_json").toString());
                    return;
                }
                String reply = onMain(new Callable<String>() {
                    @Override public String call() { return handleEvent(event).toString(); }
                });
                respond(client, responseCode(reply), reply);
            } else {
                respond(client, 404, error("not_found").toString());
            }
        } catch (ControllerProtocol.HttpError invalid) {
            respond(client, invalid.status, error(invalid.getMessage()).toString());
        }
    }

    private String onMain(Callable<String> action) {
        FutureTask<String> task = new FutureTask<String>(action);
        main.post(task);
        try {
            return task.get(750L, TimeUnit.MILLISECONDS);
        } catch (Exception unavailable) {
            task.cancel(false);
            return error("main_thread_unavailable").toString();
        }
    }

    private static void respond(Socket client, int code, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        String label = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found"
                : code == 408 ? "Request Timeout" : code == 413 ? "Payload Too Large"
                : code == 431 ? "Request Header Fields Too Large" : code == 503 ? "Service Unavailable" : "Bad Request";
        byte[] headers = ("HTTP/1.1 " + code + " " + label + "\r\nContent-Type: application/json; charset=utf-8"
                + "\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        OutputStream output = client.getOutputStream();
        output.write(headers);
        output.write(payload);
        output.flush();
    }

    private static int readBootCount(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (RuntimeException unavailable) { return -1; }
    }

    private static int responseCode(String reply) {
        try {
            JSONObject result = new JSONObject(reply);
            if (!Boolean.FALSE.equals(result.opt("ok"))) return 200;
            return "main_thread_unavailable".equals(result.optString("reason", "")) ? 503 : 400;
        } catch (JSONException invalid) {
            return 503;
        }
    }

    private static boolean lockClass(String pkg, String cls) {
        return LAUNCHER_PACKAGE.equals(pkg) && (cls.contains("ReauthActivity")
                || cls.contains("ProfileLockWrapperActivity") || cls.contains(".profile.lock."));
    }

    private static boolean concreteActivity(String cls) {
        return cls.endsWith("Activity") && !cls.startsWith("android.");
    }

    private static boolean pinPrompt(String label) {
        String normalized = label.toLowerCase(Locale.ROOT);
        return normalized.contains("enter your pin") || normalized.contains("enter pin")
                || normalized.contains("unlock your profile") || normalized.contains("unlock profile")
                || normalized.contains("confirm your pin");
    }

    private static boolean transientPackage(String pkg) {
        return "com.android.systemui".equals(pkg) || "dev.yossi.onnkeyguard".equals(pkg)
                || pkg.startsWith("com.google.android.inputmethod") || pkg.startsWith("com.android.inputmethod");
    }

    private static boolean fresh(long sample, long now) {
        return sample >= 0 && sample <= now + 1000L && (sample > now || now - sample <= EVENT_MAX_AGE_MS);
    }

    private static long integer(JSONObject object, String key, long fallback) {
        Object value = object.opt(key);
        if (!(value instanceof Number)) return fallback;
        double number = ((Number) value).doubleValue();
        long result = ((Number) value).longValue();
        return !Double.isNaN(number) && !Double.isInfinite(number) && number == result ? result : fallback;
    }

    private static double number(JSONObject object, String key) {
        Double value = optionalNumber(object, key);
        return value == null ? Double.NaN : value.doubleValue();
    }

    private static Double optionalNumber(JSONObject object, String key) {
        Object raw = object.opt(key);
        if (!(raw instanceof Number)) return null;
        double number = ((Number) raw).doubleValue();
        return Double.isNaN(number) || Double.isInfinite(number) ? null : Double.valueOf(number);
    }

    private static JSONObject object(Object... pairs) {
        JSONObject object = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) put(object, pairs[i].toString(), pairs[i + 1]);
        return object;
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value == null ? JSONObject.NULL : value); }
        catch (JSONException invalid) { throw new IllegalArgumentException("invalid_status_value", invalid); }
    }

    private static JSONObject error(String reason) {
        return object("schema", 1, "ok", false, "reason", reason);
    }

    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }
    private static void close(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
    private static void close(ServerSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
