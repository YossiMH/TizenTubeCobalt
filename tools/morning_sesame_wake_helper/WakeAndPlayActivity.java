package dev.yossi.morningsesame;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.icu.util.HebrewCalendar;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WakeAndPlayActivity extends Activity {
    private static final String TAG = "MorningSesame";
    private static final String TARGET_PACKAGE = "io.gh.yossim.tizentube.cobalt";
    private static final String TARGET_ACTIVITY = "dev.cobalt.app.MainActivity";
    private static final String CHANNEL_URL = "https://www.youtube.com/@SesameStreetClassics/videos";
    private static final String PREFS = "morning_sesame";
    private static final String KEY_PENDING = "pending_video";
    private static final String KEY_CANDIDATES = "candidate_list";
    private static final int MIN_SECONDS = 30 * 60;

    public static final String ACTION_SETUP = "dev.yossi.morningsesame.SETUP";
    public static final String ACTION_RUN = "dev.yossi.morningsesame.RUN";
    public static final String ACTION_VERIFY = "dev.yossi.morningsesame.VERIFY";
    public static final String ACTION_PLAY = "dev.yossi.morningsesame.PLAY";
    static final String EXTRA_VIDEO = "video_id";
    private static final String EXTRA_TEST_DELAY = "test_delay_ms";

    private static final int REQ_RUN = 700;
    private static final int REQ_VERIFY = 701;
    private static final int REQ_PLAY = 702;
    private static final long VERIFY_DELAY_MS = 65_000L;
    private static final long PLAY_DELAY_MS = 75_000L;

    private static final Pattern VIDEO_ENTRY = Pattern.compile(
            "\"videoWithContextRenderer\".*?\"lengthText\".*?"
                    + "\"text\":\"([0-9:]+)\".*?"
                    + "\"videoId\":\"([A-Za-z0-9_-]{11})\"",
            Pattern.DOTALL);

    private static final Map<String, WeakReference<WakeAndPlayActivity>> activeActivities = new HashMap<>();
    private String runId;
    private ExecutorService worker;
    private Future<?> work;
    private boolean handoffComplete;
    private TextView startupText;
    private int lastCandidateHttpStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String action = getIntent().getAction();
        if (ACTION_SETUP.equals(action)) {
            long testDelay = getIntent().getLongExtra(EXTRA_TEST_DELAY, 0L);
            if (testDelay > 0L) {
                scheduleRunAt(this, System.currentTimeMillis() + testDelay);
                Log.i(TAG, "Scheduled one-time acceptance run in " + testDelay + " ms");
            } else {
                scheduleNext(this);
            }
            finish();
            return;
        }

        if (ACTION_VERIFY.equals(action) || ACTION_PLAY.equals(action)) {
            String videoId = getIntent().getStringExtra(EXTRA_VIDEO);
            if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
                videoId = prefs().getString(KEY_PENDING, null);
            }
            if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
                finish();
                return;
            }
            runId = RunCoordinator.begin(this, "manual_play");
            registerRun();
            if (wakeScreen() && RunCoordinator.storePending(this, runId, videoId)) {
                RunCoordinator.note(this, runId, "play_requested", videoId, 0);
                AlarmReceiver.routePlayback(this, runId, videoId);
                AlarmReceiver.schedulePlay(this, runId, videoId);
                handoffComplete = true;
            }
            finish();
            return;
        }

        if (ACTION_RUN.equals(action)) {
            scheduleNext(this);
            beginScheduledRun();
            return;
        }

        Uri direct = getIntent().getData();
        if (direct != null) {
            String videoId = direct.getQueryParameter("v");
            if (videoId != null && videoId.matches("[A-Za-z0-9_-]{11}")) {
                scheduleNext(this);
                beginWithVideo(videoId);
                return;
            }
        }

        scheduleNext(this);
        finish();
    }

    private void beginScheduledRun() {
        runId = RunCoordinator.adoptScheduledStartup(this);
        registerRun();
        if (runId == null || !wakeScreen()) {
            finish();
            return;
        }
        showStartupMessage("Starting Sesame Street…");
        final String ownedRun = runId;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        worker = executor;
        work = executor.submit(() -> {
            try {
                RunCoordinator.note(this, ownedRun, "waiting_for_network", null, 0);
                waitForValidatedNetwork();
                ensureCurrent();
                RunCoordinator.note(this, ownedRun, "fetching_candidates", null, 0);
                postStartupMessage("Starting Sesame Street…");
                Map<String, Integer> candidates = fetchCandidatesWithRetry();
                if (!RunCoordinator.storeCandidates(this, ownedRun, serializeCandidates(candidates))) return;
                Log.i(TAG, "Prepared " + candidates.size() + " qualifying videos for account-progress selection");
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && RunCoordinator.checkpoint(this, ownedRun)) beginDeferredSelection();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                RunCoordinator.finish(this, ownedRun, "cancelled", "startup_interrupted");
            } catch (Exception e) {
                Log.e(TAG, "Video selection failed", e);
                RunCoordinator.finish(this, ownedRun, "failed", "network_or_candidate_selection_failed");
            } finally {
                executor.shutdown();
            }
        });
    }

    private void beginDeferredSelection() {
        if (!wakeScreen()) return;
        if (!RunCoordinator.note(this, runId, "warming_player", null, 0)) return;
        try {
            Intent warm = new Intent(Intent.ACTION_MAIN);
            warm.setClassName(TARGET_PACKAGE, TARGET_ACTIVITY);
            warm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(warm);
            Log.i(TAG, "TizenSub+ warm-up requested before account-progress selection");
        } catch (Exception e) {
            Log.e(TAG, "TizenSub+ warm-up failed", e);
            RunCoordinator.finish(this, runId, "failed", "player_warmup_failed");
            finish();
            return;
        }
        if (!schedulePhase(ACTION_VERIFY, REQ_VERIFY, VERIFY_DELAY_MS, null)) return;
        RunCoordinator.note(this, runId, "waiting_for_account_progress", null, 0);
        handoffComplete = true;
        // Alarms and RunCoordinator own the wake lease now; no empty window remains behind.
        finish();
    }

    private void beginWithVideo(String videoId) {
        runId = RunCoordinator.begin(this, "manual_video");
        registerRun();
        showStartupMessage("Starting Sesame Street…");
        if (!wakeScreen() || !RunCoordinator.storePending(this, runId, videoId)) return;
        RunCoordinator.note(this, runId, "warming_player", videoId, 0);
        try {
            Intent warm = new Intent(Intent.ACTION_MAIN);
            warm.setClassName(TARGET_PACKAGE, TARGET_ACTIVITY);
            warm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(warm);
            Log.i(TAG, "TizenSub+ warm-up requested for " + videoId);
        } catch (Exception e) {
            Log.e(TAG, "TizenSub+ warm-up failed", e);
            RunCoordinator.finish(this, runId, "failed", "player_warmup_failed");
            finish();
            return;
        }
        if (!schedulePhase(ACTION_VERIFY, REQ_VERIFY, VERIFY_DELAY_MS, videoId)
                || !schedulePhase(ACTION_PLAY, REQ_PLAY, PLAY_DELAY_MS, videoId)) return;
        RunCoordinator.note(this, runId, "waiting_for_playback", videoId, 0);
        handoffComplete = true;
        finish();
    }

    private boolean schedulePhase(String action, int requestCode, long delayMs, String videoId) {
        synchronized (RunCoordinator.class) {
            if (!RunCoordinator.checkpoint(this, runId)) return false;
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            Intent intent = new Intent(this, AlarmReceiver.class).setAction(action)
                    .putExtra(RunCoordinator.EXTRA_RUN, runId);
            if (videoId != null && videoId.matches("[A-Za-z0-9_-]{11}")) {
                intent.putExtra(EXTRA_VIDEO, videoId);
        }
        PendingIntent pi = PendingIntent.getBroadcast(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pi);
        return true;
        }
    }


    private void ensureCurrent() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()
                || !RunCoordinator.checkpoint(this, runId)) {
            throw new InterruptedException("Morning run no longer active");
        }
        if (!RunCoordinator.keepAwake(this, runId)) {
            throw new InterruptedException("Morning wake lease ended");
        }
    }

    private void waitForValidatedNetwork() throws Exception {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) throw new IllegalStateException("Connectivity service unavailable");
        long started = SystemClock.elapsedRealtime(), deadline = started + 60_000L;
        NetworkCapabilities observed = null;
        String result = "unavailable";
        try {
            do {
                ensureCurrent();
                Network network = cm.getActiveNetwork();
                observed = network == null ? null : cm.getNetworkCapabilities(network);
                if (observed != null
                        && observed.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && observed.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    result = "ok";
                    Log.i(TAG, "Validated internet is ready for TizenSub+ warm-up");
                    return;
                }
                postStartupMessage("Waiting for the internet…");
                Thread.sleep(1_000L);
            } while (SystemClock.elapsedRealtime() < deadline);
            result = "timeout";
            throw new IllegalStateException("Validated internet did not return after TV wake");
        } finally {
            RunDiagnostics.event(this, runId, "network_ready", result, RunDiagnostics.values(
                    "network_wait_ms", SystemClock.elapsedRealtime() - started,
                    "network_internet", observed != null && observed.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ? 1 : 0,
                    "network_validated", observed != null && observed.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 1 : 0,
                    "network_wifi", observed != null && observed.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 1 : 0,
                    "network_ethernet", observed != null && observed.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? 1 : 0));
        }
    }

    private Map<String, Integer> fetchCandidatesWithRetry() throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            ensureCurrent();
            long started = SystemClock.elapsedRealtime();
            int count = 0;
            String result = "unknown_error";
            lastCandidateHttpStatus = 0;
            try {
                Map<String, Integer> candidates = fetchCandidates();
                count = candidates.size();
                if (!candidates.isEmpty()) { result = "ok"; return candidates; }
                result = "no_candidates";
                last = new IllegalStateException("No qualifying >30 minute Sesame Street Classics video found");
            } catch (Exception e) {
                result = RunDiagnostics.classify(e);
                last = e;
            } finally {
                RunDiagnostics.event(this, runId, "candidate_fetch", result, RunDiagnostics.values(
                        "candidate_attempt", attempt, "candidate_count", count,
                        "candidate_fetch_ms", SystemClock.elapsedRealtime() - started,
                        "candidate_http_status", lastCandidateHttpStatus));
            }
            if (attempt < 4) {
                Log.w(TAG, "Morning source unavailable; retrying after network wake (attempt " + attempt + ")");
                try { Thread.sleep(2_000L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw last == null ? new IllegalStateException("Morning source unavailable") : last;
    }

    private Map<String, Integer> fetchCandidates() throws Exception {
        String html = decodeHexEscapes(fetch(CHANNEL_URL));
        Matcher matcher = VIDEO_ENTRY.matcher(html);
        Map<String, Integer> ordered = new LinkedHashMap<>();
        while (matcher.find() && ordered.size() < 60) {
            int duration = parseDuration(matcher.group(1));
            String videoId = matcher.group(2);
            if (duration > MIN_SECONDS && !ordered.containsKey(videoId)) {
                ordered.put(videoId, duration);
            }
        }
        return ordered;
    }

    private static String serializeCandidates(Map<String, Integer> candidates) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : candidates.entrySet()) {
            if (out.length() > 0) out.append(';');
            out.append(entry.getKey()).append(',').append(entry.getValue());
        }
        return out.toString();
    }

    private static String decodeHexEscapes(String input) {
        StringBuilder output = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == '\\' && i + 3 < input.length()
                    && input.charAt(i + 1) == 'x') {
                int high = Character.digit(input.charAt(i + 2), 16);
                int low = Character.digit(input.charAt(i + 3), 16);
                if (high >= 0 && low >= 0) {
                    output.append((char) ((high << 4) | low));
                    i += 3;
                    continue;
                }
            }
            output.append(current);
        }
        return output.toString();
    }

    private static int parseDuration(String text) {
        String[] parts = text.split(":");
        try {
            if (parts.length == 2) return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private String fetch(String urlText) throws Exception {
        ensureCurrent();
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        int remaining = (int) Math.max(1L, RunCoordinator.remaining(this, runId));
        connection.setConnectTimeout(Math.min(15_000, remaining));
        connection.setReadTimeout(Math.min(20_000, remaining));
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; Morning Sesame)");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        try {
            int status = connection.getResponseCode();
            lastCandidateHttpStatus = status;
            if (status < 200 || status >= 300) throw new RunDiagnostics.HttpStatusException(status);
            InputStream input = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(1_200_000);
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                ensureCurrent();
                out.append(buffer, 0, read);
                if (out.length() > 3_000_000) throw new IllegalStateException("Channel page unexpectedly large");
            }
            return out.toString();
        } finally {
            connection.disconnect();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean wakeScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true);
            setShowWhenLocked(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        return RunCoordinator.renewWake(this, runId);
    }

    private void showStartupMessage(String message) {
        if (startupText == null) {
            startupText = new TextView(this);
            startupText.setGravity(Gravity.CENTER);
            startupText.setTextSize(24f);
            startupText.setTextColor(Color.WHITE);
            startupText.setBackgroundColor(Color.rgb(18, 18, 18));
            setContentView(startupText);
        }
        if (!message.contentEquals(startupText.getText())) startupText.setText(message);
    }

    private void postStartupMessage(String message) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed() && RunCoordinator.isActive(this, runId)) {
                showStartupMessage(message);
            }
        });
    }

    private void registerRun() {
        if (runId != null) activeActivities.put(runId, new WeakReference<>(this));
    }

    static void finishRunIfOwned(String id) {
        WeakReference<WakeAndPlayActivity> ref = activeActivities.get(id);
        WakeAndPlayActivity activity = ref == null ? null : ref.get();
        if (activity != null && id != null && id.equals(activity.runId)) {
            if (activity.work != null) activity.work.cancel(true);
            if (activity.worker != null) activity.worker.shutdownNow();
            activity.finish();
        }
    }

    @Override protected void onDestroy() {
        if (work != null && !work.isDone()) work.cancel(true);
        if (worker != null) worker.shutdownNow();
        if (!handoffComplete && runId != null && RunCoordinator.isActive(this, runId)) {
            RunCoordinator.finish(this, runId, "cancelled", "startup_activity_destroyed");
        }
        WeakReference<WakeAndPlayActivity> ref = activeActivities.get(runId);
        if (ref != null && ref.get() == this) activeActivities.remove(runId);
        super.onDestroy();
    }

    public static void scheduleNext(Context context) {
        Calendar now = Calendar.getInstance();
        for (int offset = 0; offset < 16; offset++) {
            Calendar candidate = (Calendar) now.clone();
            candidate.add(Calendar.DAY_OF_MONTH, offset);
            candidate.set(Calendar.HOUR_OF_DAY, 7);
            candidate.set(Calendar.MINUTE, 0);
            candidate.set(Calendar.SECOND, 0);
            candidate.set(Calendar.MILLISECOND, 0);
            if (candidate.getTimeInMillis() <= now.getTimeInMillis() + 1_000L) continue;
            if (candidate.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) continue;
            if (isYomTov(candidate)) continue;
            scheduleRunAt(context, candidate.getTimeInMillis());
            Log.i(TAG, "Next morning run: " + candidate.getTime());
            return;
        }
        Log.e(TAG, "Could not find next allowed morning within 16 days");
    }

    private static void scheduleRunAt(Context context, long whenMillis) {
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class).setAction(ACTION_RUN);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, REQ_RUN, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        setExact(am, whenMillis, pi);
    }

    private static void setExact(AlarmManager am, long whenMillis, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, whenMillis, pi);
        }
    }

    private static boolean isYomTov(Calendar day) {
        HebrewCalendar hebrew = new HebrewCalendar();
        hebrew.setTimeInMillis(day.getTimeInMillis());
        int month = hebrew.get(HebrewCalendar.MONTH);
        int date = hebrew.get(HebrewCalendar.DATE);
        return (month == HebrewCalendar.TISHRI
                    && (date == 1 || date == 2 || date == 10
                        || date == 15 || date == 16 || date == 22 || date == 23))
                || (month == HebrewCalendar.NISAN
                    && (date == 15 || date == 16 || date == 21 || date == 22))
                || (month == HebrewCalendar.SIVAN && (date == 6 || date == 7));
    }

    private static final class Candidate {
        final String videoId;
        final int durationSeconds;
        Candidate(String videoId, int durationSeconds) {
            this.videoId = videoId;
            this.durationSeconds = durationSeconds;
        }
    }
}
