package dev.yossi.morningsesame;

import android.app.AlarmManager;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "MorningSesame";
    private static final String PREFS = "morning_sesame";
    private static final String KEY_PLAYED = "played_ids";
    private static final String KEY_PENDING = "pending_video";
    private static final String KEY_CANDIDATES = "candidate_list";
    private static final String DIAL_URL = "http://127.0.0.1:8012/apps/YouTube";
    private static final long PLAY_AFTER_VERIFY_MS = 10_000L;
    private static final long PLAY_RETRY_MS = 15_000L;
    private static final int MAX_PLAY_RETRIES = 3;
    private static final long RECEIVER_WORK_MS = 6_500L;
    private static final int LOOPBACK_TIMEOUT_MS = 800;
    static final String KEY_RUN_STARTED = "run_started_at";
    private static final String EXTRA_PLAY_ATTEMPT = "play_attempt";
    private static final Pattern PROGRESS_XML =
           Pattern.compile("<ms_progress>(.*?)</ms_progress>", Pattern.DOTALL);
    private static final String ACTION_POST_SUCCESS = "dev.yossi.morningsesame.POST_SUCCESS";
    private static final String EXTRA_POST_INDEX = "post_index";
    private static final int POST_REQUEST_BASE = 704;
    // Non-waking and finite: before and after the likely end of a resumed long episode.
    private static final int[] POST_MINUTES = {10, 35, 55, 70};
    private static final Pattern POST_PLAYING_AT =
            Pattern.compile("<ms_playing_at>([0-9]+)</ms_playing_at>");
    private static final Pattern POST_POSITION =
            Pattern.compile("<ms_position>([0-9]+(?:\\.[0-9]+)?)</ms_position>");
    private static final Pattern POST_VIDEO =
            Pattern.compile("<ms_playing>([A-Za-z0-9_-]{11})</ms_playing>");

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (WakeAndPlayActivity.ACTION_RUN.equals(action)) {
            WakeAndPlayActivity.scheduleNext(context);
            String id = RunCoordinator.begin(context, "wake_requested");
            routeScheduledRun(context, id);
            return;
        }
        String runId = intent.getStringExtra(RunCoordinator.EXTRA_RUN);
        if (RunCoordinator.ACTION_TIMEOUT.equals(action)) {
            RunCoordinator.checkpoint(context, runId);
            return;
        }
        if (ACTION_POST_SUCCESS.equals(action)) {
            int index = intent.getIntExtra(EXTRA_POST_INDEX, -1);
            if (index < 0 || index >= POST_MINUTES.length
                    || !RunCoordinator.canObservePostSuccess(context, runId)) return;
            PendingResult pending = goAsync();
            new Thread(() -> {
                try { observeAfterSuccess(context, runId, index); }
                catch (Exception e) {
                    Log.w(TAG, "Passive post-success observation unavailable", e);
                } finally { pending.finish(); }
            }, "MorningSesamePostCheck").start();
            return;
        }
        if (!WakeAndPlayActivity.ACTION_VERIFY.equals(action)
                && !WakeAndPlayActivity.ACTION_PLAY.equals(action)) return;
        if (!RunCoordinator.checkpoint(context, runId)
                || !RunCoordinator.renewWake(context, runId)) return;
        String videoId = intent.getStringExtra(WakeAndPlayActivity.EXTRA_VIDEO);
        if (WakeAndPlayActivity.ACTION_VERIFY.equals(action)) {
            if (isVideoId(videoId)) {
                RunCoordinator.note(context, runId, "play_requested", videoId, 0);
                routePlayback(context, runId, videoId);
            } else {
                PendingResult pending = goAsync();
                new Thread(() -> {
                    try {
                        selectAndVerify(context, runId);
                    } catch (Exception e) {
                        Log.e(TAG, "Account-progress selection failed", e);
                        RunCoordinator.finish(context, runId, "failed", "account_selection_failed");
                    } finally {
                        pending.finish();
                    }
                }, "MorningSesameSelect").start();
            }
            return;
        }
        int attempt = Math.max(0, intent.getIntExtra(EXTRA_PLAY_ATTEMPT, 0));
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                handlePlaybackCheck(context, runId, videoId, attempt);
            } catch (Exception e) {
                Log.e(TAG, "Playback confirmation check failed", e);
                RunCoordinator.finish(context, runId, "failed", "playback_check_failed");
            } finally {
                pending.finish();
            }
        }, "MorningSesamePlayCheck").start();
    }

    private static void routeScheduledRun(Context context, String runId) {
        synchronized (RunCoordinator.class) {
            if (!RunCoordinator.checkpoint(context, runId)) return;
            Intent handoff = new Intent("dev.yossi.onnkeyguard.MORNING_RUN")
                    .setClassName("dev.yossi.onnkeyguard",
                            "dev.yossi.onnkeyguard.MorningPlaybackReceiver")
                    .putExtra(RunCoordinator.EXTRA_RUN, runId);
            context.sendBroadcast(handoff);
            Log.i(TAG, "Alarm receiver routed scheduled run through Onn Key Guard");
        }
    }

    private static void selectAndVerify(Context context, String runId) throws Exception {
        if (!RunCoordinator.note(context, runId, "selecting_account_progress", null, 0)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> candidates = parseCandidates(prefs.getString(KEY_CANDIDATES, ""));
        if (candidates.isEmpty()) {
            Log.e(TAG, "No stored qualifying candidates for verification");
            RunCoordinator.finish(context, runId, "failed", "no_stored_candidates");
            return;
        }

        Set<String> played = new HashSet<>(
                prefs.getStringSet(KEY_PLAYED, new HashSet<>()));
        JSONObject progress = readProgressWithRetry(context, runId);

        String chosen = null;
        String selection = "local_history_fallback";
        long selectedProgress = -1L;
        if (progress != null && progress.length() > 0) {
            for (String candidate : candidates) {
                if (played.contains(candidate) || !progress.has(candidate)) continue;
                double pct = progress.optDouble(candidate, 100.0);
                if (pct < 50.0) {
                    chosen = candidate;
                    selection = "account_progress_selected";
                    selectedProgress = Math.round(pct);
                    Log.i(TAG, "Selected " + candidate
                            + " from account progress (" + pct + "% watched)");
                    break;
                }
            }
        }

        if (chosen == null) {
            for (String candidate : candidates) {
                if (!played.contains(candidate)) {
                    chosen = candidate;
                    Log.i(TAG, "Selected " + candidate + " using local play-history fallback");
                    break;
                }
            }
        }
        if (chosen == null) {
            Map<String, Integer> durations = parseDurations(prefs.getString(KEY_CANDIDATES, ""));
            Map<String, Double> pct = new LinkedHashMap<>();
            if (progress != null) for (String candidate : candidates) {
                if (progress.has(candidate)) pct.put(candidate, progress.optDouble(candidate, 0.0));
            }
            chosen = MorningCandidatePolicy.longestRemaining(candidates, durations, pct);
            if (chosen == null) chosen = candidates.get(0);
            selection = "cycling_history";
            Log.i(TAG, "All recent candidates previously played; selecting longest remaining " + chosen);
        }

        RunDiagnostics.event(context, runId, "account_progress", selection, RunDiagnostics.values(
                "candidate_count", candidates.size(), "progress_count", progress == null ? 0 : progress.length(),
                "account_progress_percent", selectedProgress));
        if (!RunCoordinator.storePending(context, runId, chosen)) return;
        RunCoordinator.note(context, runId, "play_requested", chosen, 0);
        routePlayback(context, runId, chosen);
        schedulePlay(context, runId, chosen);
    }

    private static JSONObject readProgressWithRetry(Context context, String runId) {
        long started = SystemClock.elapsedRealtime(), deadline = started + RECEIVER_WORK_MS;
        HttpObservation http = new HttpObservation();
        int attempts = 0, count = 0;
        String observation = "unavailable";
        try {
            for (int attempt = 0; attempt < 6; attempt++) {
                if (!RunCoordinator.checkpoint(context, runId)
                        || SystemClock.elapsedRealtime() >= deadline) break;
                attempts++;
                try {
                    String xml = fetch(context, runId, DIAL_URL, deadline, http);
                    observation = "unavailable";
                    if (xml.contains("<yumi>morning-sesame</yumi>")) {
                        Matcher matcher = PROGRESS_XML.matcher(xml);
                        if (matcher.find()) {
                            JSONObject result = new JSONObject(unescapeXml(matcher.group(1)));
                            count = result.length();
                            if (count > 0) { observation = "available"; return result; }
                        }
                    }
                } catch (Exception e) {
                    observation = RunDiagnostics.classify(e);
                    if (attempt == 5) Log.w(TAG, "Signed-in progress feed unavailable: " + e);
                }
                try {
                    long left = deadline - SystemClock.elapsedRealtime();
                    if (left <= 0L) break;
                    Thread.sleep(Math.min(1_000L, left));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); observation = "interrupted"; break;
                }
            }
            return null;
        } finally {
            RunDiagnostics.event(context, runId, "account_progress", observation, RunDiagnostics.values(
                    "progress_attempts", attempts, "progress_count", count,
                    "progress_fetch_ms", SystemClock.elapsedRealtime() - started,
                    "dial_http_status", http.status, "dial_fetch_ms", http.totalMs));
        }
    }

    private static List<String> parseCandidates(String encoded) {
        List<String> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return out;
        for (String row : encoded.split(";")) {
            int comma = row.indexOf(',');
            String id = comma > 0 ? row.substring(0, comma) : row;
            if (isVideoId(id) && !out.contains(id)) out.add(id);
        }
        return out;
    }

    static Map<String, Integer> parseDurations(String encoded) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (encoded == null) return result;
        for (String row : encoded.split(";")) {
            int comma = row.indexOf(',');
            if (comma < 0) continue;
            String id = row.substring(0, comma);
            if (!isVideoId(id)) continue;
            try {
                int seconds = Integer.parseInt(row.substring(comma + 1));
                if (seconds > 0 && seconds <= 24 * 3600) result.put(id, seconds);
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    static void schedulePlay(Context context, String runId, String videoId) {
        schedulePlay(context, runId, videoId, 0, PLAY_AFTER_VERIFY_MS);
    }

    private static void schedulePlay(Context context, String runId, String videoId,
            int attempt, long delayMs) {
        synchronized (RunCoordinator.class) {
            if (!RunCoordinator.checkpoint(context, runId)) return;
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, AlarmReceiver.class)
                    .setAction(WakeAndPlayActivity.ACTION_PLAY)
                    .putExtra(WakeAndPlayActivity.EXTRA_VIDEO, videoId)
                    .putExtra(RunCoordinator.EXTRA_RUN, runId)
                    .putExtra(EXTRA_PLAY_ATTEMPT, attempt);
            PendingIntent pi = PendingIntent.getBroadcast(context, 702, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, pi);
            Log.i(TAG, "Scheduled playback confirmation check for " + videoId
                    + " attempt " + attempt);
        }
    }

    static void cancelPhases(Context context) {
        String[] actions = {WakeAndPlayActivity.ACTION_VERIFY, WakeAndPlayActivity.ACTION_PLAY,
                RunCoordinator.ACTION_TIMEOUT};
        int[] requests = {701, 702, 703};
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (int i = 0; i < actions.length; i++) {
            PendingIntent pi = PendingIntent.getBroadcast(context, requests[i],
                    new Intent(context, AlarmReceiver.class).setAction(actions[i]),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) am.cancel(pi);
        }
    }

    static void cancelPostSuccess(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int index = 0; index < POST_MINUTES.length; index++) {
            PendingIntent old = PendingIntent.getBroadcast(context, POST_REQUEST_BASE + index,
                    new Intent(context, AlarmReceiver.class).setAction(ACTION_POST_SUCCESS),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (old != null) am.cancel(old);
        }
    }

    static void schedulePostSuccess(Context context, String runId) {
        if (!RunCoordinator.canObservePostSuccess(context, runId)) return;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = SystemClock.elapsedRealtime();
        for (int index = 0; index < POST_MINUTES.length; index++) {
            Intent intent = new Intent(context, AlarmReceiver.class).setAction(ACTION_POST_SUCCESS)
                    .putExtra(RunCoordinator.EXTRA_RUN, runId).putExtra(EXTRA_POST_INDEX, index);
            PendingIntent pi = PendingIntent.getBroadcast(context, POST_REQUEST_BASE + index, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // ELAPSED_REALTIME (without WAKEUP) cannot wake an otherwise sleeping TV.
            am.setExact(AlarmManager.ELAPSED_REALTIME,
                    now + POST_MINUTES[index] * 60_000L, pi);
        }
    }

    private static void observeAfterSuccess(Context context, String runId, int index) {
        if (!RunCoordinator.canObservePostSuccess(context, runId)) return;
        long started = SystemClock.elapsedRealtime();
        long completedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("diagnostic_updated_at", 0L);
        long expectedAt = completedAt + POST_MINUTES[index] * 60_000L;
        long late = Math.max(0L, System.currentTimeMillis() - expectedAt);
        // Deferred non-wake alarms are not evidence for the original window hours later.
        if (late > 15 * 60_000L) return;
        Map<String, Long> facts = RunDiagnostics.values(
                "post_index", index, "post_lag_ms", late, "remote_input_present", remotePresent(context));
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) facts.put("interactive", power.isInteractive() ? 1L : 0L);
            ActivityManager activity = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activity != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                activity.getMemoryInfo(memory);
                facts.put("available_memory_mb", memory.availMem / 1_048_576L);
                facts.put("low_memory", memory.lowMemory ? 1L : 0L);
            }
        } catch (RuntimeException ignored) {}
        String result = "post_unavailable";
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(DIAL_URL).openConnection();
            connection.setConnectTimeout(600);
            connection.setReadTimeout(600);
            int status = connection.getResponseCode();
            facts.put("post_http_status", (long) status);
            if (status >= 200 && status < 300) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder body = new StringBuilder(2048);
                char[] buffer = new char[2048];
                int count;
                while ((count = reader.read(buffer)) >= 0 && body.length() <= 100_000) {
                    body.append(buffer, 0, count);
                }
                String xml = body.toString();
                boolean marker = xml.contains("<yumi>morning-sesame</yumi>");
                boolean playing = xml.contains("<ms_state>playing</ms_state>");
                Matcher video = POST_VIDEO.matcher(xml);
                String expected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString("diagnostic_video", "");
                boolean matches = video.find() && expected.equals(video.group(1));
                facts.put("post_marker_present", marker ? 1L : 0L);
                facts.put("post_playing", playing ? 1L : 0L);
                facts.put("post_video_matches", matches ? 1L : 0L);
                result = !marker ? "post_marker_missing" : !playing ? "post_idle" : "post_playing_stale";
                Matcher at = POST_PLAYING_AT.matcher(xml), position = POST_POSITION.matcher(xml);
                if (at.find()) {
                    long age = System.currentTimeMillis() - Long.parseLong(at.group(1));
                    facts.put("post_sample_age_ms", age);
                    if (marker && playing && matches && age >= -1_000L && age <= 5_000L) {
                        result = "post_playing_fresh";
                    }
                }
                if (position.find()) {
                    double seconds = Double.parseDouble(position.group(1));
                    if (Double.isFinite(seconds) && seconds >= 0
                            && seconds < Long.MAX_VALUE / 1000.0) {
                        facts.put("post_position_ms", Math.round(seconds * 1000));
                    }
                }
            }
        } catch (Exception ignored) {
            result = "post_unavailable";
        } finally {
            if (connection != null) connection.disconnect();
            facts.put("post_fetch_ms", SystemClock.elapsedRealtime() - started);
            RunDiagnostics.afterSuccess(context, runId, result, facts);
        }
    }

    private static long remotePresent(Context context) {
        try {
            InputManager input = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (input == null) return -1L;
            for (int id : input.getInputDeviceIds()) {
                InputDevice device = input.getInputDevice(id);
                if (device != null && device.getName() != null
                        && device.getName().startsWith("Onn-Remote")) return 1L;
            }
            return 0L;
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private static void handlePlaybackCheck(Context context, String runId, String videoId, int attempt) {
        if (!RunCoordinator.note(context, runId, "confirming_playback", videoId, attempt)) return;
        if (!isVideoId(videoId)) {
            Log.w(TAG, "Playback check ignored invalid video id");
            RunCoordinator.finish(context, runId, "failed", "invalid_video_id");
            return;
        }
        if (isConfirmedPlaying(context, runId, videoId, attempt)) {
            RunCoordinator.confirmPlayed(context, runId, videoId);
            return;
        }
        if (attempt >= MAX_PLAY_RETRIES) {
            Log.e(TAG, "Playback not confirmed after retries for " + videoId);
            RunCoordinator.finish(context, runId, "failed", "playback_not_confirmed");
            return;
        }
        routePlayback(context, runId, videoId);
        schedulePlay(context, runId, videoId, attempt + 1, PLAY_RETRY_MS);
        Log.w(TAG, "Playback not confirmed; retrying " + videoId
                + " attempt " + (attempt + 1));
    }

    private static boolean isConfirmedPlaying(Context context, String runId, String videoId, int attempt) {
        long started = SystemClock.elapsedRealtime(), deadline = started + RECEIVER_WORK_MS;
        HttpObservation http = new HttpObservation();
        Map<String, Long> facts = new LinkedHashMap<>();
        String result = "unavailable";
        try {
            if (!RunCoordinator.checkpoint(context, runId)) return false;
            long runStarted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_RUN_STARTED, 0L);
            String xml = fetch(context, runId, DIAL_URL, deadline, http);
            long observedAt = System.currentTimeMillis();
            PlaybackEvidence first = PlaybackEvidence.read(xml, videoId, runStarted, observedAt);
            PlaybackDiagnostics.Observation firstObservation = PlaybackDiagnostics.inspect(xml, videoId, runStarted, observedAt);
            facts.putAll(firstObservation.values); result = firstObservation.reason;
            if (first == null) return false;
            // Two independently refreshed samples still decide success; diagnostic parsing is descriptive only.
            if (deadline - SystemClock.elapsedRealtime() < 2300L) { result = "timeout"; return false; }
            Thread.sleep(2300L);
            if (!RunCoordinator.checkpoint(context, runId)) { result = "interrupted"; return false; }
            xml = fetch(context, runId, DIAL_URL, deadline, http);
            observedAt = System.currentTimeMillis();
            PlaybackEvidence second = PlaybackEvidence.read(xml, videoId, runStarted, observedAt);
            PlaybackDiagnostics.Observation secondObservation = PlaybackDiagnostics.inspect(xml, videoId, runStarted, observedAt);
            Long firstPosition = facts.get("first_position_ms");
            facts.putAll(secondObservation.values);
            if (firstPosition != null) facts.put("first_position_ms", firstPosition);
            Long secondPosition = secondObservation.values.get("first_position_ms");
            if (secondPosition != null) facts.put("second_position_ms", secondPosition);
            if (second != null) {
                facts.put("sample_delta_ms", second.at - first.at);
                facts.put("position_delta_ms", Math.round((second.position - first.position) * 1000.0));
            }
            boolean advances = PlaybackEvidence.advances(first, second);
            result = advances ? "confirmed" : second == null ? secondObservation.reason : "not_advancing";
            return advances;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); result = "interrupted"; return false;
        } catch (Exception e) {
            result = RunDiagnostics.classify(e);
            Log.w(TAG, "Playback confirmation unavailable: " + e);
            return false;
        } finally {
            facts.put("playback_checks", (long) attempt + 1L);
            facts.put("playback_check_ms", SystemClock.elapsedRealtime() - started);
            facts.put("dial_http_status", (long) http.status); facts.put("dial_fetch_ms", http.totalMs);
            RunDiagnostics.event(context, runId, "playback_check", result, facts);
        }
    }

    static void routePlayback(Context context, String runId, String videoId) {
        synchronized (RunCoordinator.class) {
            if (!isVideoId(videoId) || !RunCoordinator.checkpoint(context, runId)) return;
            Intent handoff = new Intent("dev.yossi.onnkeyguard.MORNING_PLAY")
                    .setClassName("dev.yossi.onnkeyguard",
                            "dev.yossi.onnkeyguard.MorningPlaybackReceiver")
                    .putExtra("video_id", videoId).putExtra(RunCoordinator.EXTRA_RUN, runId);
            context.sendBroadcast(handoff);
            Log.i(TAG, "Alarm receiver routed playback for " + videoId);
        }
    }

    private static boolean isVideoId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{11}");
    }

    private static final class HttpObservation { int status; long totalMs; }

    private static String fetch(Context context, String runId, String urlText, long deadline, HttpObservation observation)
            throws Exception {
        long started = SystemClock.elapsedRealtime();
        observation.status = 0;
        if (!RunCoordinator.checkpoint(context, runId)) throw new InterruptedException("Run ended");
        long left = deadline - SystemClock.elapsedRealtime();
        if (left <= 0L) throw new java.net.SocketTimeoutException("Receiver work deadline");
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        int timeout = (int) Math.max(1L, Math.min(LOOPBACK_TIMEOUT_MS, left));
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        try {
            int status = connection.getResponseCode();
            observation.status = status;
            if (status < 200 || status >= 300) throw new RunDiagnostics.HttpStatusException(status);
            InputStream input = connection.getInputStream();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (SystemClock.elapsedRealtime() >= deadline
                        || !RunCoordinator.checkpoint(context, runId)) {
                    throw new java.net.SocketTimeoutException("Receiver work deadline");
                }
                out.append(buffer, 0, read);
                if (out.length() > 1_000_000) throw new IllegalStateException("DIAL response too large");
            }
            return out.toString();
        } finally {
            observation.totalMs += SystemClock.elapsedRealtime() - started;
            connection.disconnect();
        }
    }

    private static String unescapeXml(String value) {
        return value.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
