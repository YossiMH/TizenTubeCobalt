package dev.yossi.morningsesame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Bounded worker-side read of Guard's authenticated, admitted-run account-history cache. */
final class MorningHistoryReader {
    private static final long WORK_MS = 6500L;
    private static final long REPLY_MS = 800L;
    private static final int MAX_ATTEMPTS = 6;

    private MorningHistoryReader() {}

    static JSONObject read(Context context, String runId) {
        long begin = SystemClock.elapsedRealtime();
        long end = begin + Math.min(WORK_MS, RunCoordinator.remaining(context, runId));
        SharedPreferences prefs = context.getSharedPreferences("morning_sesame", Context.MODE_PRIVATE);
        long startedWall = prefs.getLong("run_started_at", -1L);
        long deadlineWall = RunCoordinator.startupDeadlineWall(context, runId);
        int attempts = 0, count = 0;
        String result = "history_unavailable";
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                result = "history_wrong_thread";
                return null;
            }
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                if (Thread.currentThread().isInterrupted() || !RunCoordinator.checkpoint(context, runId)) {
                    result = "history_cancelled";
                    return null;
                }
                long remaining = end - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                attempts++;
                final CountDownLatch done = new CountDownLatch(1);
                final AtomicReference<String> response = new AtomicReference<>();
                Intent request = new Intent("dev.yossi.onnkeyguard.MORNING_STARTUP_AUTH")
                        .setClassName("dev.yossi.onnkeyguard", "dev.yossi.onnkeyguard.MorningPlaybackReceiver")
                        .putExtra("command", "history")
                        .putExtra("run_generation", runId)
                        .putExtra("run_started_wall_ms", startedWall)
                        .putExtra("run_deadline_wall_ms", deadlineWall);
                try {
                    MorningOrderedBroadcast.send(context, request, null, new BroadcastReceiver() {
                        @Override public void onReceive(Context ignored, Intent intent) {
                            try { response.set(getResultData()); }
                            finally { done.countDown(); }
                        }
                    }, new Handler(Looper.getMainLooper()), 0, null, null);
                    boolean replied = done.await(Math.min(REPLY_MS, remaining), TimeUnit.MILLISECONDS);
                    if (!RunCoordinator.checkpoint(context, runId) || Thread.currentThread().isInterrupted()) {
                        result = "history_cancelled";
                        return null;
                    }
                    if (!replied) {
                        result = "history_reply_timeout";
                    } else {
                        JSONObject progress = MorningHistoryReply.parse(response.get(), runId, startedWall,
                                deadlineWall, System.currentTimeMillis());
                        count = progress.getJSONObject("__youtube_history").getJSONArray("covered_ids").length();
                        result = "history_ready";
                        return progress;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result = "history_cancelled";
                    return null;
                } catch (Exception unavailable) {
                    result = MorningHistoryReply.reason(unavailable);
                    if ("history_cancelled".equals(result)) return null;
                }
                remaining = end - SystemClock.elapsedRealtime();
                if (i + 1 < MAX_ATTEMPTS && remaining > 0)
                    Thread.sleep(Math.min(1000L, remaining));
            }
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = "history_cancelled";
            return null;
        } finally {
            RunDiagnostics.event(context, runId, "account_progress", result, RunDiagnostics.values(
                    "progress_attempts", attempts, "progress_count", count,
                    "progress_fetch_ms", SystemClock.elapsedRealtime() - begin));
        }
    }
}
