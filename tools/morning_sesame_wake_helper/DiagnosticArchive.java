package dev.yossi.morningsesame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** Dependency-free bounded diagnostics. It has no Android, I/O, scheduling, or playback powers. */
final class DiagnosticArchive {
    static final int MAX_BYTES = 128 * 1024;
    static final int MAX_RECENT = 64, MAX_FAILURES = 8, MAX_EVENTS = 32, MAX_SYSTEM = 8;
    static final int MAX_METRICS = 24;
    // FORMAT=1 persists enum/metric ordinals. Append entries only; reorder/removal requires migration and a format bump.
    private static final int MAGIC = 0x4d534438, FORMAT = 1;
    private static final String[] PHASES = {"unknown", "wake_requested", "starting", "manual_play",
            "manual_video", "waiting_for_network", "fetching_candidates", "warming_player",
            "waiting_for_account_progress", "selecting_account_progress", "play_requested",
            "confirming_playback", "waiting_for_playback", "network_ready", "candidate_fetch",
            "account_progress", "playback_check", "runtime_snapshot", "completed", "recovered"};
    private static final String[] RESULTS = {"none", "ok", "timeout", "dns_error", "connect_error",
            "tls_error", "http_error", "io_error", "unknown_error", "no_candidates", "available",
            "unavailable", "local_history_fallback", "cycling_history", "marker_missing",
            "state_idle", "video_mismatch", "sample_missing", "sample_before_run", "sample_future",
            "sample_stale", "position_invalid", "not_advancing", "confirmed", "interrupted",
            "snapshot_unavailable", "account_progress_selected"};
    private static final String[] OUTCOMES = {"none", "running", "success", "failed", "interrupted", "cancelled"};
    private static final String[] REASONS = {"", "playback_confirmed", "playback_not_confirmed",
            "deadline_exceeded", "network_or_candidate_selection_failed", "account_selection_failed",
            "playback_check_failed", "no_stored_candidates", "invalid_video_id", "player_warmup_failed",
            "power_service_unavailable", "startup_interrupted", "startup_activity_destroyed",
            "device_restarted", "package_updated", "superseded", "process_interrupted", "unknown"};
    private static final String[] TRIGGERS = {"unknown", "wake_requested", "manual_play", "manual_video", "starting", "recovered"};
    private static final String[] SYSTEM_TYPES = {"boot", "package_updated", "archive_corrupt", "archive_oversized", "archive_backup_recovered"};
    private static final String[] METRICS = {"stage_duration_ms", "network_wait_ms", "network_internet",
            "network_validated", "network_wifi", "network_ethernet", "candidate_attempt",
            "candidate_count", "candidate_fetch_ms", "candidate_http_status", "progress_count",
            "progress_attempts", "progress_fetch_ms", "playback_checks", "dial_http_status",
            "dial_fetch_ms", "marker_present", "state_playing", "video_matches", "sample_age_ms",
            "sample_since_run_ms", "sample_delta_ms", "position_delta_ms", "first_position_ms",
            "second_position_ms", "interactive", "device_idle", "power_save", "thermal_status",
            "available_memory_mb", "total_memory_mb", "memory_threshold_mb", "low_memory",
            "helper_heap_used_kb", "helper_heap_max_kb", "storage_available_mb", "wake_held",
            "run_remaining_ms", "snapshot_age_ms", "snapshot_elapsed_ms", "snapshot_ok",
            "snapshot_duration_ms", "alarm_handoff_ms", "account_progress_percent", "playback_check_ms"};
    private static final List<String> SUMMARY_METRICS = Arrays.asList("network_wait_ms", "candidate_attempt",
            "candidate_count", "candidate_fetch_ms", "candidate_http_status", "progress_count",
            "progress_attempts", "playback_checks", "available_memory_mb", "low_memory",
            "thermal_status", "storage_available_mb", "alarm_handoff_ms");

    static final class Event {
        final long atMs, elapsedMs;
        final String phase, result;
        final Map<String, Long> values;
        Event(long at, long elapsed, String phase, String result, Map<String, Long> values) {
            this.atMs = at; this.elapsedMs = elapsed; this.phase = phase; this.result = result;
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    static class Summary {
        String runId, trigger, phase = "unknown", videoId = "", outcome = "running", reason = "";
        int version, attempt;
        long startedAtMs, updatedAtMs, elapsedMs = -1L, droppedEvents;
        final Map<String, Long> stages = new LinkedHashMap<>();
        final Map<String, Long> values = new LinkedHashMap<>();
        Summary(String id, long wall, int version, String trigger) {
            runId = id; startedAtMs = wall; updatedAtMs = wall; this.version = version; this.trigger = trigger;
        }
        void copyInto(Summary out) {
            out.phase = phase; out.videoId = videoId; out.outcome = outcome; out.reason = reason;
            out.attempt = attempt; out.updatedAtMs = updatedAtMs; out.elapsedMs = elapsedMs;
            out.droppedEvents = droppedEvents; out.stages.putAll(stages); out.values.putAll(values);
        }
        Summary copy() {
            Summary out = new Summary(runId, startedAtMs, version, trigger); copyInto(out); return out;
        }
    }

    static final class Trace extends Summary {
        final List<Event> events = new ArrayList<>();
        Trace(String id, long wall, int version, String trigger) { super(id, wall, version, trigger); }
        @Override Trace copy() {
            Trace out = new Trace(runId, startedAtMs, version, trigger); copyInto(out); out.events.addAll(events); return out;
        }
    }

    static final class SystemEvent {
        final String type, previousRunId, previousOutcome;
        final long atMs, uptimeMs;
        SystemEvent(String type, long at, long uptime, String previousRunId, String previousOutcome) {
            this.type = type; atMs = at; uptimeMs = uptime;
            this.previousRunId = previousRunId; this.previousOutcome = previousOutcome;
        }
    }

    private Trace current;
    private final List<Summary> recent = new ArrayList<>();
    private final List<Trace> failures = new ArrayList<>();
    private final List<SystemEvent> systems = new ArrayList<>();
    private long droppedFields, droppedEvents;

    void start(String id, long wall, int version, String trigger) {
        if (!validId(id)) { droppedFields = increment(droppedFields); return; }
        if (current != null && id.equals(current.runId)) return;
        if (current != null && "running".equals(current.outcome)) {
            finish(current.runId, wall, -1L, "interrupted", "process_interrupted");
        }
        current = new Trace(id, Math.max(0L, wall), Math.max(1, Math.min(10000, version)),
                known(TRIGGERS, trigger, "unknown"));
    }

    void phase(String id, long wall, long elapsed, String phase, String video, int attempt) {
        if (!accepts(id)) return;
        String safePhase = known(PHASES, phase, "unknown");
        current.phase = safePhase;
        current.updatedAtMs = Math.max(0L, wall); current.elapsedMs = elapsed;
        current.attempt = Math.max(0, Math.min(3, attempt));
        if (video != null && video.matches("[A-Za-z0-9_-]{11}")) current.videoId = video;
        Map<String, Long> timings = new LinkedHashMap<>();
        long previous = -1L;
        for (Long time : current.stages.values()) if (time != null && time >= 0L) previous = time;
        if (!current.stages.containsKey(safePhase)) current.stages.put(safePhase, elapsed);
        if (previous >= 0L && elapsed >= previous) timings.put("stage_duration_ms", elapsed - previous);
        if ("starting".equals(safePhase) && elapsed >= 0L) current.values.put("alarm_handoff_ms", elapsed);
        append(new Event(Math.max(0L, wall), elapsed, safePhase, "none", timings));
    }

    void event(String id, long wall, long elapsed, String phase, String result, Map<String, Long> values) {
        if (!accepts(id)) return;
        Map<String, Long> safe = new LinkedHashMap<>();
        if (values != null) for (Map.Entry<String, Long> entry : values.entrySet()) {
            if (entry.getValue() == null || index(METRICS, entry.getKey()) < 0 || safe.size() >= MAX_METRICS) {
                droppedFields = increment(droppedFields); continue;
            }
            safe.put(entry.getKey(), entry.getValue());
            if (SUMMARY_METRICS.contains(entry.getKey())) current.values.put(entry.getKey(), entry.getValue());
        }
        append(new Event(Math.max(0L, wall), elapsed, known(PHASES, phase, "unknown"),
                known(RESULTS, result, "unknown_error"), safe));
    }

    void finish(String id, long wall, long elapsed, String outcome, String reason) {
        if (!accepts(id)) return;
        current.updatedAtMs = Math.max(0L, wall); current.elapsedMs = elapsed;
        current.outcome = known(OUTCOMES, outcome, "none");
        current.reason = known(REASONS, reason, "unknown");
        append(new Event(Math.max(0L, wall), elapsed, "completed", "none", Collections.emptyMap()));
        recent.removeIf(item -> id.equals(item.runId));
        Summary compact = new Summary(current.runId, current.startedAtMs, current.version, current.trigger);
        current.copyInto(compact);
        recent.add(compact);
        while (recent.size() > MAX_RECENT) recent.remove(0);
        if ("failed".equals(current.outcome)
                || ("interrupted".equals(current.outcome) && !"superseded".equals(current.reason))) {
            failures.removeIf(item -> id.equals(item.runId));
            failures.add(current.copy());
            while (failures.size() > MAX_FAILURES) failures.remove(0);
        }
    }

    void system(String type, long wall, long uptime, String previousRunId, String previousOutcome) {
        if (index(SYSTEM_TYPES, type) < 0) return;
        systems.add(new SystemEvent(type, Math.max(0L, wall), Math.max(0L, uptime),
                validId(previousRunId) ? previousRunId : "", known(OUTCOMES, previousOutcome, "none")));
        while (systems.size() > MAX_SYSTEM) systems.remove(0);
    }

    private boolean accepts(String id) {
        return id != null && current != null && id.equals(current.runId) && "running".equals(current.outcome);
    }
    private void append(Event event) {
        if (current.events.size() >= MAX_EVENTS) {
            // Preserve the first observation and the latest 31.
            current.events.remove(1); current.droppedEvents = increment(current.droppedEvents);
            droppedEvents = increment(droppedEvents);
        }
        current.events.add(event);
    }
    int recentCount() { return recent.size(); }
    int failureCount() { return failures.size(); }
    int systemCount() { return systems.size(); }
    long droppedFields() { return droppedFields; }
    long droppedEvents() { return droppedEvents; }
    Trace current() { return current == null ? null : current.copy(); }
    Trace latestFailure() { return failures.isEmpty() ? null : failures.get(failures.size() - 1).copy(); }
    Trace findTrace(String id) {
        if (current != null && current.runId.equals(id)) return current.copy();
        for (Trace trace : failures) if (trace.runId.equals(id)) return trace.copy();
        return null;
    }
    List<Summary> recentSummaries() {
        List<Summary> out = new ArrayList<>(); for (Summary s : recent) out.add(s.copy()); return out;
    }
    List<Trace> failureTraces() {
        List<Trace> out = new ArrayList<>(); for (Trace t : failures) out.add(t.copy()); return out;
    }
    List<SystemEvent> systemEvents() { return new ArrayList<>(systems); }
    DiagnosticArchive copy() {
        DiagnosticArchive out = new DiagnosticArchive(); out.current = current == null ? null : current.copy();
        for (Summary s : recent) out.recent.add(s.copy());
        for (Trace t : failures) out.failures.add(t.copy());
        out.systems.addAll(systems); out.droppedFields = droppedFields; out.droppedEvents = droppedEvents; return out;
    }

    byte[] encode() throws IOException {
        LimitedOutput bytes = new LimitedOutput(MAX_BYTES - 4);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeShort(FORMAT); out.writeLong(droppedFields); out.writeLong(droppedEvents);
        out.writeBoolean(current != null); if (current != null) writeTrace(out, current);
        out.writeByte(recent.size()); for (Summary summary : recent) writeSummary(out, summary);
        out.writeByte(failures.size()); for (Trace trace : failures) writeTrace(out, trace);
        out.writeByte(systems.size()); for (SystemEvent event : systems) {
            out.writeByte(index(SYSTEM_TYPES, event.type)); out.writeLong(event.atMs); out.writeLong(event.uptimeMs);
            writeString(out, event.previousRunId, 64); writeEnum(out, OUTCOMES, event.previousOutcome);
        }
        out.flush(); byte[] body = bytes.toByteArray(); CRC32 crc = new CRC32(); crc.update(body);
        ByteArrayOutputStream framed = new ByteArrayOutputStream(body.length + 4);
        framed.write(body); DataOutputStream trailer = new DataOutputStream(framed); trailer.writeInt((int) crc.getValue());
        return framed.toByteArray();
    }

    static DiagnosticArchive decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 30 || bytes.length > MAX_BYTES) throw new IOException("archive_size");
        int length = bytes.length - 4;
        long stored = ((bytes[length] & 255L) << 24) | ((bytes[length + 1] & 255L) << 16)
                | ((bytes[length + 2] & 255L) << 8) | (bytes[length + 3] & 255L);
        CRC32 crc = new CRC32(); crc.update(bytes, 0, length);
        if (stored != crc.getValue()) throw new IOException("archive_checksum");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, length));
        if (in.readInt() != MAGIC || in.readUnsignedShort() != FORMAT) throw new IOException("archive_format");
        DiagnosticArchive result = new DiagnosticArchive();
        result.droppedFields = nonnegative(in.readLong()); result.droppedEvents = nonnegative(in.readLong());
        if (in.readBoolean()) result.current = readTrace(in);
        int count = count(in, MAX_RECENT); for (int i = 0; i < count; i++) result.recent.add(readSummary(in));
        count = count(in, MAX_FAILURES); for (int i = 0; i < count; i++) result.failures.add(readTrace(in));
        count = count(in, MAX_SYSTEM); for (int i = 0; i < count; i++) {
            int type = in.readUnsignedByte(); if (type >= SYSTEM_TYPES.length) throw new IOException("system_type");
            long at = nonnegative(in.readLong()), uptime = nonnegative(in.readLong());
            String id = readString(in, 64); if (!id.isEmpty() && !validId(id)) throw new IOException("run_id");
            result.systems.add(new SystemEvent(SYSTEM_TYPES[type], at, uptime, id, readEnum(in, OUTCOMES)));
        }
        if (in.available() != 0) throw new IOException("archive_trailing_data");
        return result;
    }

    private static void writeSummary(DataOutputStream out, Summary s) throws IOException {
        writeString(out, s.runId, 64); out.writeInt(s.version); writeEnum(out, TRIGGERS, s.trigger);
        writeEnum(out, PHASES, s.phase); writeString(out, s.videoId, 11);
        writeEnum(out, OUTCOMES, s.outcome); writeEnum(out, REASONS, s.reason);
        out.writeByte(s.attempt); out.writeLong(s.startedAtMs); out.writeLong(s.updatedAtMs);
        out.writeLong(s.elapsedMs); out.writeLong(s.droppedEvents);
        out.writeByte(s.stages.size()); for (Map.Entry<String, Long> e : s.stages.entrySet()) {
            writeEnum(out, PHASES, e.getKey()); out.writeLong(e.getValue());
        }
        writeMetrics(out, s.values);
    }
    private static Summary readSummary(DataInputStream in) throws IOException {
        String id = readString(in, 64); if (!validId(id)) throw new IOException("run_id");
        int version = in.readInt(); if (version < 1 || version > 10000) throw new IOException("helper_version");
        Summary s = new Summary(id, 0L, version, readEnum(in, TRIGGERS));
        s.phase = readEnum(in, PHASES); s.videoId = readString(in, 11);
        if (!s.videoId.isEmpty() && !s.videoId.matches("[A-Za-z0-9_-]{11}")) throw new IOException("video_id");
        s.outcome = readEnum(in, OUTCOMES); s.reason = readEnum(in, REASONS);
        s.attempt = in.readUnsignedByte(); if (s.attempt > 3) throw new IOException("attempt");
        s.startedAtMs = nonnegative(in.readLong()); s.updatedAtMs = nonnegative(in.readLong());
        s.elapsedMs = in.readLong(); s.droppedEvents = nonnegative(in.readLong());
        int stages = count(in, PHASES.length); for (int i = 0; i < stages; i++) s.stages.put(readEnum(in, PHASES), in.readLong());
        s.values.putAll(readMetrics(in)); return s;
    }
    private static void writeTrace(DataOutputStream out, Trace trace) throws IOException {
        writeSummary(out, trace); out.writeByte(trace.events.size());
        for (Event event : trace.events) {
            out.writeLong(event.atMs); out.writeLong(event.elapsedMs); writeEnum(out, PHASES, event.phase);
            writeEnum(out, RESULTS, event.result); writeMetrics(out, event.values);
        }
    }
    private static Trace readTrace(DataInputStream in) throws IOException {
        Summary s = readSummary(in); Trace trace = new Trace(s.runId, s.startedAtMs, s.version, s.trigger); s.copyInto(trace);
        int events = count(in, MAX_EVENTS); for (int i = 0; i < events; i++) {
            long at = nonnegative(in.readLong()), elapsed = in.readLong();
            trace.events.add(new Event(at, elapsed, readEnum(in, PHASES), readEnum(in, RESULTS), readMetrics(in)));
        }
        return trace;
    }
    private static void writeMetrics(DataOutputStream out, Map<String, Long> values) throws IOException {
        if (values.size() > MAX_METRICS) throw new IOException("metric_count");
        out.writeByte(values.size()); for (Map.Entry<String, Long> e : values.entrySet()) {
            int id = index(METRICS, e.getKey()); if (id < 0) throw new IOException("metric_id");
            out.writeByte(id); out.writeLong(e.getValue());
        }
    }
    private static Map<String, Long> readMetrics(DataInputStream in) throws IOException {
        Map<String, Long> out = new LinkedHashMap<>(); int size = count(in, MAX_METRICS);
        for (int i = 0; i < size; i++) {
            int id = in.readUnsignedByte(); if (id >= METRICS.length) throw new IOException("metric_id");
            out.put(METRICS[id], in.readLong());
        }
        return out;
    }
    private static int count(DataInputStream in, int maximum) throws IOException {
        int count = in.readUnsignedByte(); if (count > maximum) throw new IOException("count_limit"); return count;
    }
    private static void writeString(DataOutputStream out, String value, int max) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > max) throw new IOException("string_limit"); out.writeByte(bytes.length); out.write(bytes);
    }
    private static String readString(DataInputStream in, int max) throws IOException {
        int length = in.readUnsignedByte(); if (length > max || length > in.available()) throw new IOException("string_limit");
        byte[] bytes = new byte[length]; in.readFully(bytes); return new String(bytes, StandardCharsets.US_ASCII);
    }
    private static void writeEnum(DataOutputStream out, String[] values, String value) throws IOException {
        int id = index(values, value); if (id < 0) throw new IOException("enum_value"); out.writeByte(id);
    }
    private static String readEnum(DataInputStream in, String[] values) throws IOException {
        int id = in.readUnsignedByte(); if (id >= values.length) throw new IOException("enum_value"); return values[id];
    }
    private static int index(String[] values, String value) {
        if (value != null) for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }
    private static String known(String[] values, String value, String fallback) { return index(values, value) >= 0 ? value : fallback; }
    private static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,64}"); }
    private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1L; }
    private static long nonnegative(long value) throws IOException { if (value < 0L) throw new IOException("negative_value"); return value; }
    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(); private final int limit;
        LimitedOutput(int limit) { this.limit = limit; }
        @Override public void write(int value) throws IOException { if (bytes.size() >= limit) throw new IOException("archive_size"); bytes.write(value); }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            if (length < 0 || bytes.size() > limit - length) throw new IOException("archive_size"); bytes.write(value, offset, length);
        }
        byte[] toByteArray() { return bytes.toByteArray(); }
    }
}
