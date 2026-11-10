package dev.yossi.morningsesame;

import android.app.ActivityManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;

/** Optional diagnostics. Callers enqueue bounded work; this class never controls playback. */
final class RunDiagnostics {
    static final int VERSION = 10;
    private static final String FILE_NAME = "morning-diagnostics.bin";
    private static final int MAX_PENDING = 64, MAX_DETAIL_BYTES = 32 * 1024;
    private static final Object QUEUE_LOCK = new Object(), TELEMETRY_LOCK = new Object();
    private static final ArrayDeque<Change> pending = new ArrayDeque<>();
    private static boolean writerRunning;
    private static final Object STATUS_LOCK = new Object();
    private static final Handler STATUS_MAIN = new Handler(Looper.getMainLooper());
    private static boolean statusWorkerRunning;
    private static DiagnosticArchive working;
    private static volatile DiagnosticArchive published;
    private static volatile String storageState = "not_loaded";
    private static volatile long queueDropped, coalescedWrites, writeErrors;
    private static volatile int lastPersistedBytes;
    private static boolean telemetryRunning;
    private static TelemetryRequest telemetryPending;
    private static volatile long telemetryStartedElapsed;
    private static volatile Snapshot cachedSnapshot;
    private static String telemetryRunId;
    private static int telemetryRequests;

    private RunDiagnostics() {}
    private interface Mutation { void apply(DiagnosticArchive archive); }
    private static final class Change {
        final boolean critical, beginsRun;
        final Mutation mutation;
        Change(boolean critical, boolean beginsRun, Mutation mutation) {
            this.critical = critical; this.beginsRun = beginsRun; this.mutation = mutation;
        }
    }

    static void begin(Context context, String id, long startedWall, String trigger) {
        optional(() -> {
            enqueue(context, new Change(true, true, a -> a.start(id, startedWall, VERSION, trigger)));
            requestSnapshot(context, id);
        });
    }
    static void phase(Context context, String id, long wall, long elapsed, String phase, String video, int attempt) {
        optional(() -> {
            OperationalRecord record = new OperationalRecord(context);
            enqueue(context, new Change(false, false, a -> {
                DiagnosticArchive.Trace current = a.current();
                if (current == null || !id.equals(current.runId)) record.apply(a);
                else a.phase(id, wall, elapsed, phase, video, attempt);
            }));
            if ("warming_player".equals(phase) || "confirming_playback".equals(phase)) requestSnapshot(context, id);
        });
    }
    static void event(Context context, String id, String phase, String result, Map<String, Long> values) {
        optional(() -> {
            long wall = System.currentTimeMillis(), elapsed = runElapsed(context, id);
            Map<String, Long> copy = boundedCopy(values);
            enqueue(context, new Change(false, false, a -> a.event(id, wall, elapsed, phase, result, copy)));
        });
    }
    static void afterSuccess(Context context, String id, String result, Map<String, Long> values) {
        optional(() -> {
            long wall = System.currentTimeMillis(), elapsed = runElapsed(context, id);
            Map<String, Long> copy = boundedCopy(values);
            enqueue(context, new Change(false, false,
                    a -> a.afterSuccess(id, wall, elapsed, result, copy)));
        });
    }
    static void finish(Context context, String id, long startedWall, long wall, long elapsed,
            String phase, String video, int attempt, String outcome, String reason) {
        optional(() -> {
            Map<String, Long> snapshot = cachedSnapshotValues(id);
            enqueue(context, new Change(true, false, a -> {
                DiagnosticArchive.Trace trace = a.current();
                if (trace == null || !id.equals(trace.runId)) {
                    a.start(id, startedWall, VERSION, "recovered");
                    a.phase(id, wall, elapsed, phase, video, attempt);
                }
                a.event(id, wall, elapsed, "runtime_snapshot",
                        snapshot.get("snapshot_ok") == 1L ? "available" : "snapshot_unavailable", snapshot);
                a.finish(id, wall, elapsed, outcome, reason);
            }));
        });
    }
    static void system(Context context, String type) {
        optional(() -> {
            SharedPreferences p = RunCoordinator.prefs(context);
            String previous = p.getString("run_generation", "");
            String outcome = p.getString("diagnostic_outcome", "none");
            long at = System.currentTimeMillis(), uptime = SystemClock.elapsedRealtime();
            OperationalRecord record = new OperationalRecord(context);
            enqueue(context, new Change(true, false, a -> {
                DiagnosticArchive.Trace current = a.current();
                if (current == null || !record.id.equals(current.runId)) record.apply(a);
                a.system(type, at, uptime, previous, outcome);
            }));
        });
    }

    static long runElapsed(Context context, String id) {
        SharedPreferences p = RunCoordinator.prefs(context);
        if (id == null || !id.equals(p.getString("run_generation", null))) return -1L;
        long start = p.getLong("run_started_elapsed", -1L), now = SystemClock.elapsedRealtime();
        return start >= 0L && now >= start ? now - start : -1L;
    }
    static Map<String, Long> values(Object... pairs) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length && out.size() < DiagnosticArchive.MAX_METRICS; i += 2) {
            if (pairs[i] instanceof String && pairs[i + 1] instanceof Number) {
                out.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
            }
        }
        return out;
    }
    private static Map<String, Long> boundedCopy(Map<String, Long> values) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (values != null) for (Map.Entry<String, Long> e : values.entrySet()) {
            if (out.size() >= DiagnosticArchive.MAX_METRICS) break;
            if (e.getKey() != null && e.getKey().length() <= 40 && e.getValue() != null) out.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }
    private static void optional(Runnable action) {
        try { action.run(); } catch (RuntimeException | OutOfMemoryError ignored) { writeErrors = saturatingIncrement(writeErrors); }
    }

    private static void enqueue(Context context, Change change) {
        Context app = context.getApplicationContext();
        boolean start = false;
        synchronized (QUEUE_LOCK) {
            if (pending.size() >= MAX_PENDING) {
                Change removable = null;
                for (Change old : pending) if (!old.critical) { removable = old; break; }
                if (removable != null) pending.remove(removable);
                else if (change.critical) pending.removeFirst();
                else { queueDropped = saturatingIncrement(queueDropped); return; }
                queueDropped = saturatingIncrement(queueDropped);
            }
            pending.addLast(change);
            if (!writerRunning) { writerRunning = true; start = true; }
        }
        if (start) {
            try { new Thread(() -> drain(app), "MorningDiagnosticsWriter").start(); }
            catch (RuntimeException | OutOfMemoryError failure) {
                synchronized (QUEUE_LOCK) { writerRunning = false; }
                writeErrors = saturatingIncrement(writeErrors);
            }
        }
    }

    private static void drain(Context context) {
        boolean idleExit = false;
        try {
            while (true) {
                List<Change> batch;
                synchronized (QUEUE_LOCK) {
                    if (pending.isEmpty()) { writerRunning = false; idleExit = true; return; }
                    batch = new ArrayList<>(pending); pending.clear();
                }
                if (working == null) {
                    Load loaded = readArchive(context); working = loaded.archive; storageState = loaded.state;
                    String recovery = "corrupt_rejected".equals(loaded.state) ? "archive_corrupt"
                            : "oversized_rejected".equals(loaded.state) ? "archive_oversized"
                            : "backup_recovered".equals(loaded.state) ? "archive_backup_recovered" : null;
                    if (recovery != null) working.system(recovery, System.currentTimeMillis(),
                            SystemClock.elapsedRealtime(), "", "none");
                }
                for (Change change : batch) {
                    try { change.mutation.apply(working); }
                    catch (RuntimeException malformed) { writeErrors = saturatingIncrement(writeErrors); }
                }
                published = working.copy();
                if (batch.size() > 1) coalescedWrites += batch.size() - 1L;
                try {
                    byte[] payload = working.encode();
                    writeArchive(context, payload);
                    lastPersistedBytes = payload.length; storageState = "ok";
                } catch (IOException | RuntimeException failure) {
                    writeErrors = saturatingIncrement(writeErrors); storageState = "write_failed";
                    // No retry timer: another existing stage event is the only next write opportunity.
                }
            }
        } catch (RuntimeException | OutOfMemoryError failure) {
            writeErrors = saturatingIncrement(writeErrors); storageState = "worker_failed";
        } finally {
            if (!idleExit) synchronized (QUEUE_LOCK) { writerRunning = false; }
        }
    }

    private static final class Load {
        final DiagnosticArchive archive; final String state;
        Load(DiagnosticArchive archive, String state) { this.archive = archive; this.state = state; }
    }
    private static File baseFile(Context context) { return new File(context.getFilesDir(), FILE_NAME); }
    private static Load readArchive(Context context) {
        File base = baseFile(context);
        String error = "empty";
        for (int slot = 0; slot < 2; slot++) {
            File file = slot == 0 ? base : new File(base.getPath() + ".bak");
            if (!file.exists()) continue;
            if (file.length() > DiagnosticArchive.MAX_BYTES) { error = "oversized_rejected"; continue; }
            try (FileInputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
                int n; while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() > DiagnosticArchive.MAX_BYTES - n) throw new IOException("size");
                    bytes.write(buffer, 0, n);
                }
                return new Load(DiagnosticArchive.decode(bytes.toByteArray()), slot == 0 ? "ok" : "backup_recovered");
            } catch (IOException | RuntimeException failure) { error = "corrupt_rejected"; }
        }
        return new Load(new DiagnosticArchive(), error);
    }
    private static void writeArchive(Context context, byte[] payload) throws IOException {
        if (payload.length > DiagnosticArchive.MAX_BYTES) throw new IOException("archive_size");
        File base = baseFile(context);
        for (String suffix : new String[]{"", ".new", ".bak"}) {
            File slot = new File(base.getPath() + suffix);
            if (slot.exists() && slot.length() > DiagnosticArchive.MAX_BYTES && !slot.delete()) {
                throw new IOException("oversized_slot");
            }
        }
        AtomicFile atomic = new AtomicFile(base); FileOutputStream out = null;
        try { out = atomic.startWrite(); out.write(payload); atomic.finishWrite(out); }
        catch (IOException | RuntimeException failure) { if (out != null) atomic.failWrite(out); throw failure; }
    }
    private static final class OperationalRecord {
        final String id, trigger, phase, video, outcome, reason;
        final long start, updated, elapsed;
        final int version, attempt;
        OperationalRecord(Context context) {
            SharedPreferences p = RunCoordinator.prefs(context);
            id = p.getString("run_generation", "");
            start = p.getLong("diagnostic_started_at", 0L);
            updated = p.getLong("diagnostic_updated_at", start);
            elapsed = p.getLong("diagnostic_elapsed_ms", -1L);
            version = p.getInt("diagnostic_helper_version", 7);
            trigger = p.getString("diagnostic_trigger", "recovered");
            phase = p.getString("diagnostic_phase", "unknown");
            video = p.getString("diagnostic_video", "");
            attempt = p.getInt("diagnostic_attempt", 0);
            outcome = p.getString("diagnostic_outcome", "none");
            reason = p.getString("diagnostic_reason", "unknown");
        }
        void apply(DiagnosticArchive archive) {
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) return;
            DiagnosticArchive.Trace current = archive.current();
            if (current == null || !id.equals(current.runId)) archive.start(id, start, version, trigger);
            archive.phase(id, updated, elapsed, phase, video, attempt);
            if (!"running".equals(outcome) && !"none".equals(outcome)) archive.finish(id, updated, elapsed, outcome, reason);
        }
    }
    static void reconcile(Context context) {
        // Called before control preferences are replaced, so an async terminal write can be recovered later.
        optional(() -> {
            OperationalRecord previous = new OperationalRecord(context);
            enqueue(context, new Change(true, false, previous::apply));
        });
    }
    private static final class TelemetryRequest {
        final Context context; final String runId;
        TelemetryRequest(Context context, String runId) { this.context = context; this.runId = runId; }
    }
    private static final class Snapshot {
        final String runId; final long capturedElapsed; final Map<String, Long> values;
        Snapshot(String runId, long capturedElapsed, Map<String, Long> values) {
            this.runId = runId; this.capturedElapsed = capturedElapsed; this.values = Collections.unmodifiableMap(values);
        }
    }
    private static void requestSnapshot(Context context, String id) {
        boolean start = false;
        synchronized (TELEMETRY_LOCK) {
            if (!id.equals(telemetryRunId)) { telemetryRunId = id; telemetryRequests = 0; }
            if (telemetryRequests >= 8) return;
            telemetryRequests++;
            telemetryPending = new TelemetryRequest(context.getApplicationContext(), id);
            if (!telemetryRunning) { telemetryRunning = true; start = true; }
        }
        if (start) {
            try { new Thread(RunDiagnostics::samplePending, "MorningDiagnosticsTelemetry").start(); }
            catch (RuntimeException | OutOfMemoryError failure) {
                synchronized (TELEMETRY_LOCK) { telemetryRunning = false; }
            }
        }
    }
    private static void samplePending() {
        boolean idleExit = false;
        try {
            while (true) {
                TelemetryRequest request;
                synchronized (TELEMETRY_LOCK) {
                    if (telemetryPending == null) { telemetryRunning = false; idleExit = true; return; }
                    request = telemetryPending; telemetryPending = null;
                }
                telemetryStartedElapsed = SystemClock.elapsedRealtime();
                Map<String, Long> values = new LinkedHashMap<>();
                boolean okay = true;
                try {
                    PowerManager power = (PowerManager) request.context.getSystemService(Context.POWER_SERVICE);
                    if (power != null) {
                        values.put("interactive", power.isInteractive() ? 1L : 0L);
                        values.put("device_idle", power.isDeviceIdleMode() ? 1L : 0L);
                        values.put("power_save", power.isPowerSaveMode() ? 1L : 0L);
                        if (Build.VERSION.SDK_INT >= 29) values.put("thermal_status", (long) power.getCurrentThermalStatus());
                    }
                    ActivityManager manager = (ActivityManager) request.context.getSystemService(Context.ACTIVITY_SERVICE);
                    if (manager != null) {
                        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo(); manager.getMemoryInfo(memory);
                        values.put("available_memory_mb", memory.availMem / 1048576L);
                        values.put("total_memory_mb", memory.totalMem / 1048576L);
                        values.put("memory_threshold_mb", memory.threshold / 1048576L);
                        values.put("low_memory", memory.lowMemory ? 1L : 0L);
                    }
                    Runtime runtime = Runtime.getRuntime();
                    values.put("helper_heap_used_kb", (runtime.totalMemory() - runtime.freeMemory()) / 1024L);
                    values.put("helper_heap_max_kb", runtime.maxMemory() / 1024L);
                    values.put("storage_available_mb", new StatFs(request.context.getFilesDir().getPath()).getAvailableBytes() / 1048576L);
                } catch (RuntimeException unavailable) { okay = false; }
                long finished = SystemClock.elapsedRealtime();
                values.put("snapshot_duration_ms", Math.max(0L, finished - telemetryStartedElapsed));
                values.put("snapshot_elapsed_ms", finished); values.put("snapshot_ok", okay ? 1L : 0L);
                cachedSnapshot = new Snapshot(request.runId, finished, values);
                event(request.context, request.runId, "runtime_snapshot", okay ? "available" : "snapshot_unavailable", values);
            }
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // A blocked Binder call never spawns a replacement worker. Exceptions simply end this worker.
        } finally { if (!idleExit) synchronized (TELEMETRY_LOCK) { telemetryRunning = false; } }
    }
    private static Map<String, Long> cachedSnapshotValues(String id) {
        Snapshot snapshot = cachedSnapshot;
        Map<String, Long> values = new LinkedHashMap<>();
        if (snapshot != null && id.equals(snapshot.runId)) {
            values.putAll(snapshot.values);
            values.put("snapshot_age_ms", Math.max(0L, SystemClock.elapsedRealtime() - snapshot.capturedElapsed));
        } else { values.put("snapshot_ok", 0L); values.put("snapshot_age_ms", -1L); }
        return values;
    }

    static void respondStatus(Context context, String detail, int offset,
            BroadcastReceiver.PendingResult pendingResult) {
        synchronized (STATUS_LOCK) {
            if (statusWorkerRunning) {
                pendingResult.setResultCode(1);
                pendingResult.setResultData("{\"schema\":9,\"error\":\"diagnostics_busy\"}");
                pendingResult.finish();
                return;
            }
            statusWorkerRunning = true;
        }
        AtomicBoolean answered = new AtomicBoolean();
        Runnable timeout = () -> {
            if (answered.compareAndSet(false, true)) {
                pendingResult.setResultCode(1);
                pendingResult.setResultData("{\"schema\":9,\"error\":\"diagnostics_timeout\"}");
                pendingResult.finish();
            }
        };
        STATUS_MAIN.postDelayed(timeout, 5_000L);
        try {
            Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    String result = status(app, detail, offset);
                    if (answered.compareAndSet(false, true)) {
                        pendingResult.setResultCode(0); pendingResult.setResultData(result); pendingResult.finish();
                    }
                } finally {
                    STATUS_MAIN.removeCallbacks(timeout);
                    synchronized (STATUS_LOCK) { statusWorkerRunning = false; }
                }
            }, "MorningDiagnosticsStatus").start();
        } catch (RuntimeException | OutOfMemoryError failure) {
            timeout.run(); STATUS_MAIN.removeCallbacks(timeout);
            synchronized (STATUS_LOCK) { statusWorkerRunning = false; }
        }
    }

    private static String status(Context context, String detail, int offset) {
        try {
            JSONObject root = new JSONObject(RunCoordinator.diagnostics(context));
            root.put("schema", VERSION);
            if (!"legacy".equals(detail)) root.remove("history");
            DiagnosticArchive archive = published;
            String observedStorage = storageState;
            if (archive == null) { Load loaded = readArchive(context); archive = loaded.archive; observedStorage = loaded.state; }
            JSONObject limits = new JSONObject();
            limits.put("recent_runs", DiagnosticArchive.MAX_RECENT).put("failure_traces", DiagnosticArchive.MAX_FAILURES)
                    .put("events_per_trace", DiagnosticArchive.MAX_EVENTS).put("system_events", DiagnosticArchive.MAX_SYSTEM)
                    .put("archive_bytes", DiagnosticArchive.MAX_BYTES).put("fixed_slots_bytes", 3L * DiagnosticArchive.MAX_BYTES);
            root.put("retention", limits);
            JSONObject storage = new JSONObject();
            storage.put("state", observedStorage).put("last_persisted_bytes", lastPersistedBytes)
                    .put("write_errors", writeErrors).put("queue_dropped", queueDropped).put("coalesced_writes", coalescedWrites)
                    .put("dropped_fields", archive.droppedFields()).put("dropped_events", archive.droppedEvents());
            synchronized (QUEUE_LOCK) { storage.put("pending_operations", pending.size()).put("writer_active", writerRunning); }
            synchronized (TELEMETRY_LOCK) {
                storage.put("telemetry_active", telemetryRunning).put("telemetry_pending", telemetryPending != null);
                if (telemetryRunning) storage.put("telemetry_running_ms", Math.max(0L, SystemClock.elapsedRealtime() - telemetryStartedElapsed));
            }
            root.put("logging", storage).put("recent_run_count", archive.recentCount()).put("failure_trace_count", archive.failureCount());
            DiagnosticArchive.Trace lastFailure = archive.latestFailure();
            if (lastFailure != null) root.put("latest_failure", summaryJson(lastFailure));
            List<DiagnosticArchive.SystemEvent> systems = archive.systemEvents();
            if (!systems.isEmpty()) root.put("last_system_event", systemJson(systems.get(systems.size() - 1)));
            if (detail != null && detail.length() <= 80) {
                if ("latest_failure".equals(detail)) root.put("detail", lastFailure == null ? JSONObject.NULL : traceJson(lastFailure, offset));
                else if ("current".equals(detail)) root.put("detail", archive.current() == null ? JSONObject.NULL : traceJson(archive.current(), offset));
                else if (detail.startsWith("run:")) {
                    String id = detail.substring(4); DiagnosticArchive.Trace trace = archive.findTrace(id);
                    if (trace != null) root.put("detail", traceJson(trace, offset));
                    else for (DiagnosticArchive.Summary summary : archive.recentSummaries()) if (id.equals(summary.runId)) root.put("detail", summaryJson(summary));
                } else if ("recent".equals(detail)) {
                    root.put("detail", summaryPage(archive.recentSummaries(), offset));
                } else if ("failures".equals(detail)) {
                    List<DiagnosticArchive.Summary> summaries = new ArrayList<>();
                    summaries.addAll(archive.failureTraces()); root.put("detail", summaryPage(summaries, offset));
                } else if ("system".equals(detail)) {
                    JSONArray rows = new JSONArray();
                    int start = Math.max(0, offset);
                    for (int i = start; i < Math.min(systems.size(), start + 8); i++) rows.put(systemJson(systems.get(systems.size() - 1 - i)));
                    root.put("detail", new JSONObject().put("total", systems.size()).put("offset", start).put("rows", rows));
                } else if (!"legacy".equals(detail)) root.put("detail_error", "unknown_selector");
            }
            String encoded = root.toString();
            if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DETAIL_BYTES) {
                root.remove("detail"); root.remove("history"); root.put("detail_error", "response_size_limit");
                encoded = root.toString();
            }
            return encoded;
        } catch (Exception | OutOfMemoryError ignored) {
            return "{\"schema\":9,\"error\":\"diagnostics_unavailable\"}";
        }
    }
    private static JSONObject summaryJson(DiagnosticArchive.Summary summary) throws Exception {
        JSONObject out = new JSONObject();
        out.put("run_id", summary.runId).put("helper_version", summary.version).put("trigger", summary.trigger)
                .put("started_at_ms", summary.startedAtMs).put("updated_at_ms", summary.updatedAtMs)
                .put("duration_ms", summary.elapsedMs).put("phase", summary.phase).put("video_id", summary.videoId)
                .put("attempt", summary.attempt).put("outcome", summary.outcome).put("reason", summary.reason)
                .put("stages_ms", new JSONObject(summary.stages)).put("values", new JSONObject(summary.values))
                .put("events_dropped", summary.droppedEvents);
        return out;
    }
    private static JSONObject traceJson(DiagnosticArchive.Trace trace, int offset) throws Exception {
        JSONObject out = summaryJson(trace); JSONArray events = new JSONArray();
        int start = offset < 0 ? Math.max(0, trace.events.size() - 8) : Math.min(trace.events.size(), offset);
        for (int i = start; i < Math.min(trace.events.size(), start + 8); i++) {
            DiagnosticArchive.Event event = trace.events.get(i);
            JSONObject row = new JSONObject(); row.put("at_ms", event.atMs).put("elapsed_ms", event.elapsedMs)
                    .put("phase", event.phase).put("result", event.result).put("values", new JSONObject(event.values)); events.put(row);
        }
        out.put("events", events).put("event_count", trace.events.size()).put("event_offset", start)
                .put("next_offset", start + 8 < trace.events.size() ? start + 8 : JSONObject.NULL)
                .put("previous_offset", start > 0 ? Math.max(0, start - 8) : JSONObject.NULL);
        return out;
    }
    private static JSONObject summaryPage(List<DiagnosticArchive.Summary> source, int offset) throws Exception {
        int start = Math.max(0, offset); JSONArray rows = new JSONArray();
        for (int i = start; i < Math.min(source.size(), start + 8); i++) rows.put(summaryJson(source.get(source.size() - 1 - i)));
        return new JSONObject().put("total", source.size()).put("offset", start).put("rows", rows)
                .put("next_offset", start + 8 < source.size() ? start + 8 : JSONObject.NULL);
    }
    private static JSONObject systemJson(DiagnosticArchive.SystemEvent event) throws Exception {
        return new JSONObject().put("type", event.type).put("at_ms", event.atMs).put("uptime_ms", event.uptimeMs)
                .put("previous_run_id", event.previousRunId).put("previous_outcome", event.previousOutcome);
    }
    static String classify(Exception error) {
        if (error instanceof SocketTimeoutException) return "timeout";
        if (error instanceof UnknownHostException) return "dns_error";
        if (error instanceof ConnectException) return "connect_error";
        if (error instanceof SSLException) return "tls_error";
        if (error instanceof HttpStatusException) return "http_error";
        if (error instanceof IOException) return "io_error";
        if (error instanceof InterruptedException) return "interrupted";
        return "unknown_error";
    }
    static final class HttpStatusException extends IOException {
        final int status;
        HttpStatusException(int status) { super("http_status"); this.status = status; }
    }
    private static long saturatingIncrement(long value) { return value == Long.MAX_VALUE ? value : value + 1L; }
}
