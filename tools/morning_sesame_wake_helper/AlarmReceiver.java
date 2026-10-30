package dev.yossi.morningsesame;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

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
        if (progress != null && progress.length() > 0) {
            for (String candidate : candidates) {
                if (played.contains(candidate) || !progress.has(candidate)) continue;
                double pct = progress.optDouble(candidate, 100.0);
                if (pct < 50.0) {
                    chosen = candidate;
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
            chosen = candidates.get(0);
            Log.i(TAG, "All recent candidates were previously played; cycling to " + chosen);
        }

        if (!RunCoordinator.storePending(context, runId, chosen)) return;
        RunCoordinator.note(context, runId, "play_requested", chosen, 0);
        routePlayback(context, runId, chosen);
        schedulePlay(context, runId, chosen);
    }

    private static JSONObject readProgressWithRetry(Context context, String runId) {
        long deadline = SystemClock.elapsedRealtime() + RECEIVER_WORK_MS;
        for (int attempt = 0; attempt < 6; attempt++) {
            if (!RunCoordinator.checkpoint(context, runId)
                    || SystemClock.elapsedRealtime() >= deadline) break;
            try {
                String xml = fetch(context, runId, DIAL_URL, deadline);
                if (xml.contains("<yumi>morning-sesame</yumi>")) {
                    Matcher matcher = PROGRESS_XML.matcher(xml);
                    if (matcher.find()) {
                        String json = unescapeXml(matcher.group(1));
                        JSONObject result = new JSONObject(json);
                        if (result.length() > 0) return result;
                    }
                }
            } catch (Exception e) {
                if (attempt == 5) Log.w(TAG, "Signed-in progress feed unavailable: " + e);
            }
            try {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0L) break;
                Thread.sleep(Math.min(1_000L, left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
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

    private static void handlePlaybackCheck(Context context, String runId, String videoId, int attempt) {
        if (!RunCoordinator.note(context, runId, "confirming_playback", videoId, attempt)) return;
        if (!isVideoId(videoId)) {
            Log.w(TAG, "Playback check ignored invalid video id");
            RunCoordinator.finish(context, runId, "failed", "invalid_video_id");
            return;
        }
        if (isConfirmedPlaying(context, runId, videoId)) {
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

    private static boolean isConfirmedPlaying(Context context, String runId, String videoId) {
        long deadline = SystemClock.elapsedRealtime() + RECEIVER_WORK_MS;
        try {
            if (!RunCoordinator.checkpoint(context, runId)) return false;
            long runStarted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_RUN_STARTED, 0L);
            PlaybackEvidence first = PlaybackEvidence.read(
                    fetch(context, runId, DIAL_URL, deadline), videoId, runStarted, System.currentTimeMillis());
            if (first == null) return false;
            // Two independently refreshed samples reject cached success and frozen playback.
            if (deadline - SystemClock.elapsedRealtime() < 2300L) return false;
            Thread.sleep(2300L);
            if (!RunCoordinator.checkpoint(context, runId)) return false;
            PlaybackEvidence second = PlaybackEvidence.read(
                    fetch(context, runId, DIAL_URL, deadline), videoId, runStarted, System.currentTimeMillis());
            return PlaybackEvidence.advances(first, second);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Playback confirmation unavailable: " + e);
            return false;
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

    private static String fetch(Context context, String runId, String urlText, long deadline)
            throws Exception {
        if (!RunCoordinator.checkpoint(context, runId)) throw new InterruptedException("Run ended");
        long left = deadline - SystemClock.elapsedRealtime();
        if (left <= 0L) throw new java.net.SocketTimeoutException("Receiver work deadline");
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        int timeout = (int) Math.max(1L, Math.min(LOOPBACK_TIMEOUT_MS, left));
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
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
