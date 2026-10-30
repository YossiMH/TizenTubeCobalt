package dev.yossi.morningsesame;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Owns only this helper's run, alarms, and bounded wake lease. */
final class RunCoordinator {
    static final String PREFS = "morning_sesame";
    static final String EXTRA_RUN = "run_generation";
    static final String ACTION_TIMEOUT = "dev.yossi.morningsesame.TIMEOUT";
    private static final String TAG = "MorningSesame";
    private static final String GENERATION = "run_generation";
    private static final String START_ELAPSED = "run_started_elapsed";
    private static final String START_WALL = "diagnostic_started_at";
    private static final String PHASE = "diagnostic_phase";
    private static final String VIDEO = "diagnostic_video";
    private static final String ATTEMPT = "diagnostic_attempt";
    private static final String OUTCOME = "diagnostic_outcome";
    private static final String REASON = "diagnostic_reason";
    private static final String UPDATED = "diagnostic_updated_at";
    private static final String HISTORY = "diagnostic_history";
    private static final int MAX_HISTORY = 32;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static PowerManager.WakeLock wakeLock;
    private static String wakeOwner;
    private static long wakeLeaseExpiresElapsed;
    private static Runnable deadlineCallback;

    private RunCoordinator() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized String begin(Context context, String trigger) {
        SharedPreferences p = prefs(context);
        RunDiagnostics.reconcile(context);
        String previous = p.getString(GENERATION, null);
        if ("running".equals(p.getString(OUTCOME, ""))) {
            finish(context, previous, "interrupted", "superseded");
        }
        AlarmReceiver.cancelPhases(context);
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        p.edit().putString(GENERATION, id)
                .putLong(START_ELAPSED, SystemClock.elapsedRealtime())
                .putLong(START_WALL, now).putLong(AlarmReceiver.KEY_RUN_STARTED, now)
                .putString(OUTCOME, "running").putString(REASON, "")
                .putInt("diagnostic_helper_version", RunDiagnostics.VERSION)
                .putString("diagnostic_trigger", trigger).putLong("diagnostic_elapsed_ms", 0L)
                .putString(VIDEO, "").putInt(ATTEMPT, 0)
                .remove("pending_video").remove("candidate_list").commit();
        RunDiagnostics.begin(context, id, now, trigger);
        note(context, id, trigger, null, 0);
        armDeadline(context, id);
        return id;
    }

    static synchronized String adoptScheduledStartup(Context context) {
        SharedPreferences p = prefs(context);
        String id = p.getString(GENERATION, null);
        if (isActive(context, id)) {
            // The Key Guard forwards the fixed RUN action, so adopt its pending run once.
            if (!"wake_requested".equals(p.getString(PHASE, ""))) return null;
            note(context, id, "starting", null, 0);
            return id;
        }
        checkpoint(context, id);
        return null;
    }

    static synchronized boolean isActive(Context context, String id) {
        SharedPreferences p = prefs(context);
        return RunPolicy.mayContinue(id, p.getString(GENERATION, null),
                p.getString(OUTCOME, ""), p.getLong(START_ELAPSED, -1L),
                SystemClock.elapsedRealtime());
    }

    static synchronized boolean checkpoint(Context context, String id) {
        if (isActive(context, id)) return true;
        SharedPreferences p = prefs(context);
        if (id != null && id.equals(p.getString(GENERATION, null))
                && "running".equals(p.getString(OUTCOME, ""))) {
            finish(context, id, "failed", "deadline_exceeded");
        }
        return false;
    }

    static synchronized long remaining(Context context, String id) {
        return isActive(context, id)
                ? RunPolicy.remaining(prefs(context).getLong(START_ELAPSED, -1L),
                        SystemClock.elapsedRealtime()) : 0L;
    }

    static synchronized boolean renewWake(Context context, String id) {
        if (!checkpoint(context, id)) return false;
        long lease = RunPolicy.wakeLease(prefs(context).getLong(START_ELAPSED, -1L),
                SystemClock.elapsedRealtime());
        if (lease <= 0L) return false;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null) {
            finish(context, id, "failed", "power_service_unavailable");
            return false;
        }
        if (wakeLock == null) {
            wakeLock = power.newWakeLock(PowerManager.FULL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                    "MorningSesame:Wake");
            wakeLock.setReferenceCounted(false);
        }
        wakeOwner = id;
        // Reacquiring a non-reference-counted lock refreshes its timeout, even when held.
        wakeLock.acquire(lease);
        wakeLeaseExpiresElapsed = SystemClock.elapsedRealtime() + lease;
        if (deadlineCallback == null) armDeadline(context, id);
        return true;
    }

    static synchronized boolean keepAwake(Context context, String id) {
        if (!checkpoint(context, id)) return false;
        if (id.equals(wakeOwner) && wakeLock != null && wakeLock.isHeld()
                && wakeLeaseExpiresElapsed - SystemClock.elapsedRealtime() > 30_000L) return true;
        return renewWake(context, id);
    }

    private static synchronized void armDeadline(Context context, String id) {
        long remaining = remaining(context, id);
        if (remaining <= 0L) return;
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, AlarmReceiver.class).setAction(ACTION_TIMEOUT)
                .putExtra(EXTRA_RUN, id);
        PendingIntent pi = PendingIntent.getBroadcast(app, 703, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + remaining, pi);
        if (deadlineCallback != null) MAIN.removeCallbacks(deadlineCallback);
        deadlineCallback = () -> checkpoint(app, id);
        MAIN.postDelayed(deadlineCallback, remaining + 1L);
    }

    static synchronized boolean note(Context context, String id, String phase,
            String video, int attempt) {
        if (!checkpoint(context, id)) return false;
        SharedPreferences p = prefs(context);
        if (video == null) video = p.getString(VIDEO, "");
        long now = System.currentTimeMillis();
        long elapsed = RunDiagnostics.runElapsed(context, id);
        p.edit().putString(PHASE, phase).putString(VIDEO, video)
                .putInt(ATTEMPT, attempt).putLong(UPDATED, now).putLong("diagnostic_elapsed_ms", elapsed)
                .putString(HISTORY, appendHistory(p, id, now, phase, video,
                        attempt, "running", "")).commit();
        RunDiagnostics.phase(context, id, now, elapsed, phase, video, attempt);
        Log.i(TAG, "Run " + id + " phase=" + phase + " attempt=" + attempt);
        return true;
    }

    static synchronized boolean storeCandidates(Context context, String id, String encoded) {
        if (!checkpoint(context, id)) return false;
        prefs(context).edit().putString("candidate_list", encoded).remove("pending_video").commit();
        return true;
    }

    static synchronized boolean storePending(Context context, String id, String video) {
        if (!checkpoint(context, id)) return false;
        prefs(context).edit().putString("pending_video", video).commit();
        return true;
    }

    static synchronized void confirmPlayed(Context context, String id, String video) {
        if (!checkpoint(context, id)) return;
        SharedPreferences p = prefs(context);
        Set<String> played = new HashSet<>(p.getStringSet("played_ids", new HashSet<>()));
        played.add(video);
        if (played.size() > 250) {
            played.clear();
            played.add(video);
        }
        p.edit().putStringSet("played_ids", played).commit();
        finish(context, id, "success", "playback_confirmed");
        Log.i(TAG, "Playback confirmed for " + video);
    }

    static synchronized void finish(Context context, String id, String outcome, String reason) {
        SharedPreferences p = prefs(context);
        if (id == null || !id.equals(p.getString(GENERATION, null))
                || !"running".equals(p.getString(OUTCOME, ""))) return;
        long now = System.currentTimeMillis();
        String phase = p.getString(PHASE, "");
        String video = p.getString(VIDEO, "");
        int attempt = p.getInt(ATTEMPT, 0);
        long elapsed = "device_restarted".equals(reason) ? -1L : RunDiagnostics.runElapsed(context, id);
        p.edit().putString(OUTCOME, outcome).putString(REASON, reason).putLong(UPDATED, now).putLong("diagnostic_elapsed_ms", elapsed)
                .putString(HISTORY, appendHistory(p, id, now, phase, video, attempt, outcome, reason))
                .remove("pending_video").remove("candidate_list")
                .remove(AlarmReceiver.KEY_RUN_STARTED).commit();
        RunDiagnostics.finish(context, id, p.getLong(START_WALL, now), now, elapsed, phase, video, attempt, outcome, reason);
        AlarmReceiver.cancelPhases(context);
        if (deadlineCallback != null) MAIN.removeCallbacks(deadlineCallback);
        deadlineCallback = null;
        if (id.equals(wakeOwner)) {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            wakeOwner = null;
            wakeLeaseExpiresElapsed = 0L;
        }
        MAIN.post(() -> WakeAndPlayActivity.finishRunIfOwned(id));
        Log.i(TAG, "Run " + id + " outcome=" + outcome + " reason=" + reason);
    }

    static synchronized void interruptedAfterBoot(Context context, String reason) {
        SharedPreferences p = prefs(context);
        if ("running".equals(p.getString(OUTCOME, ""))) {
            finish(context, p.getString(GENERATION, null), "interrupted", reason);
        }
    }

    /** Reads preferences only; invoking diagnostics never schedules, starts, or repairs anything. */
    static synchronized String diagnostics(Context context) {
        SharedPreferences p = prefs(context);
        JSONObject result = new JSONObject();
        try {
            result.put("schema", 7);
            result.put("run_id", p.getString(GENERATION, ""));
            result.put("started_at_ms", p.getLong(START_WALL, 0L));
            result.put("updated_at_ms", p.getLong(UPDATED, 0L));
            result.put("phase", p.getString(PHASE, "none"));
            result.put("video_id", p.getString(VIDEO, ""));
            result.put("attempt", p.getInt(ATTEMPT, 0));
            result.put("outcome", p.getString(OUTCOME, "none"));
            result.put("reason", p.getString(REASON, ""));
            result.put("history", new JSONArray(p.getString(HISTORY, "[]")));
            if (!p.contains(GENERATION)) {
                long legacyStart = p.getLong(AlarmReceiver.KEY_RUN_STARTED, 0L);
                String pending = p.getString("pending_video", "");
                String candidates = p.getString("candidate_list", "");
                if (legacyStart > 0L || !pending.isEmpty() || !candidates.isEmpty()) {
                    JSONObject legacy = new JSONObject();
                    legacy.put("started_at_ms", legacyStart);
                    legacy.put("pending_video", pending.matches("[A-Za-z0-9_-]{11}") ? pending : "");
                    legacy.put("candidate_count", candidates.isEmpty() ? 0 : candidates.split(";").length);
                    legacy.put("outcome", "not_recorded_by_v6");
                    result.put("legacy", legacy);
                }
            }
            return result.toString();
        } catch (Exception ignored) {
            return "{\"schema\":7,\"error\":\"diagnostic_decode_failed\"}";
        }
    }

    private static String appendHistory(SharedPreferences p, String id, long at, String phase,
            String video, int attempt, String outcome, String reason) {
        try {
            JSONArray old = new JSONArray(p.getString(HISTORY, "[]"));
            JSONArray next = new JSONArray();
            for (int i = Math.max(0, old.length() - MAX_HISTORY + 1); i < old.length(); i++) {
                next.put(old.get(i));
            }
            JSONObject event = new JSONObject();
            event.put("run_id", id).put("at_ms", at).put("phase", phase)
                    .put("video_id", video).put("attempt", attempt)
                    .put("outcome", outcome).put("reason", reason);
            next.put(event);
            return next.toString();
        } catch (Exception ignored) {
            return "[]";
        }
    }
}
