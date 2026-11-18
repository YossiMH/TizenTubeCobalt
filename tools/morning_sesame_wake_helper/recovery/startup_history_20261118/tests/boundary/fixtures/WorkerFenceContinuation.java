package dev.yossi.morningsesame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/* JADX INFO: loaded from: classes.dex */
final class WorkerFenceContinuation implements Runnable {
    private static final int CONFIRM = 1;
    private static final int PLAYBACK = 0;
    private final Context context;
    private long deadlineElapsed;
    private boolean finished;
    private long minimumSampleWall;
    private final int operation;
    private long releasedElapsed;
    private final String runId;
    private final String videoId;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadLocal<WorkerFenceContinuation> DEFERRED = new ThreadLocal<>();

    private WorkerFenceContinuation(Context context, String str, String str2, int i) {
        this.context = context.getApplicationContext();
        this.runId = str;
        this.videoId = str2;
        this.operation = i;
    }

    static void deferPlayback(Context context, String str, String str2) {
        defer(new WorkerFenceContinuation(context, str, str2, 0));
    }

    static void deferConfirmation(Context context, String str, String str2) {
        defer(new WorkerFenceContinuation(context, str, str2, CONFIRM));
    }

    private static void defer(WorkerFenceContinuation workerFenceContinuation) {
        if (DEFERRED.get() != null) {
            throw new IllegalStateException("duplicate_worker_result");
        }
        DEFERRED.set(workerFenceContinuation);
    }

    static void finishReceiver(BroadcastReceiver.PendingResult pendingResult) {
        WorkerFenceContinuation workerFenceContinuation = DEFERRED.get();
        DEFERRED.remove();
        try {
            pendingResult.finish();
            if (workerFenceContinuation != null) {
                workerFenceContinuation.afterRelease();
            }
        } catch (RuntimeException e) {
            if (workerFenceContinuation != null) {
                workerFenceContinuation.cancel("startup_worker_release_failed");
            }
            throw e;
        }
    }

    private void afterRelease() {
        try {
            synchronized (RunCoordinator.class) {
                if (!RunCoordinator.checkpoint(this.context, this.runId)) {
                    this.finished = true;
                    return;
                }
                this.releasedElapsed = SystemClock.elapsedRealtime();
                this.minimumSampleWall = System.currentTimeMillis();
                this.deadlineElapsed = this.releasedElapsed + Math.min(3000L, RunCoordinator.remaining(this.context, this.runId));
                if (!MAIN.post(this)) {
                    cancel("startup_worker_fence_unavailable");
                }
            }
        } catch (RuntimeException e) {
            cancel("startup_worker_fence_unavailable");
        }
    }

    @Override // java.lang.Runnable
    public void run() {
        if (this.finished) {
            return;
        }
        try {
            synchronized (RunCoordinator.class) {
                if (!RunCoordinator.checkpoint(this.context, this.runId)) {
                    this.finished = true;
                    return;
                }
                long jElapsedRealtime = SystemClock.elapsedRealtime();
                if (jElapsedRealtime >= this.deadlineElapsed) {
                    cancel("startup_worker_fence_deadline");
                    return;
                }
                boolean zIsStartupBound = RunCoordinator.isStartupBound(this.context, this.runId) ^ CONFIRM;
                if (!zIsStartupBound) {
                    SharedPreferences sharedPreferencesPrefs = RunCoordinator.prefs(this.context);
                    long j = sharedPreferencesPrefs.getLong("startup_fence_received_elapsed", -1L);
                    zIsStartupBound = (sharedPreferencesPrefs.getLong("startup_fence_sample_wall", -1L) < this.minimumSampleWall || j < this.releasedElapsed || !StartupFencePolicy.permits(StartupFencePolicy.parse(sharedPreferencesPrefs.getString("startup_fence_state", "unknown")), j, jElapsedRealtime, sharedPreferencesPrefs.getLong("startup_fence_deadline_wall", 0L), System.currentTimeMillis())) ? false : CONFIRM;
                }
                if (zIsStartupBound) {
                    if (this.operation == 0) {
                        AlarmReceiver.routePlayback(this.context, this.runId, this.videoId);
                    } else {
                        RunCoordinator.confirmPlayed(this.context, this.runId, this.videoId);
                    }
                    this.finished = true;
                    return;
                }
                if (!MAIN.postDelayed(this, Math.min(50L, this.deadlineElapsed - jElapsedRealtime))) {
                    cancel("startup_worker_fence_unavailable");
                }
            }
        } catch (RuntimeException e) {
            cancel("startup_worker_fence_unavailable");
        }
    }

    private void cancel(String str) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        try {
            this.context.sendBroadcast(new Intent("dev.yossi.onnkeyguard.MORNING_STARTUP_AUTH").setClassName("dev.yossi.onnkeyguard", "dev.yossi.onnkeyguard.MorningPlaybackReceiver").putExtra("command", "cancel").putExtra("run_generation", this.runId));
        } catch (RuntimeException e) {
        } finally {
            if (RunCoordinator.isActive(this.context, this.runId)) {
                RunCoordinator.finish(this.context, this.runId, "cancelled", str);
            }
        }
    }
}
