package dev.yossi.morningsesame;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** Long-lived retention, bounded parsing and privacy checks without Android. */
public final class DiagnosticArchiveTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void success(DiagnosticArchive archive, String id, long at) {
        archive.start(id, at, 8, "wake_requested");
        archive.phase(id, at + 1, 1, "waiting_for_network", "", 0);
        archive.phase(id, at + 65_000, 65_000, "confirming_playback", "4BakiCgKOZM", 0);
        archive.finish(id, at + 79_000, 79_000, "success", "playback_confirmed");
    }

    private static void failure(DiagnosticArchive archive, String id, long at) {
        archive.start(id, at, 8, "wake_requested");
        archive.phase(id, at + 65_000, 65_000, "confirming_playback", "4BakiCgKOZM", 3);
        archive.finish(id, at + 120_000, 120_000, "failed", "playback_not_confirmed");
    }

    private static void rejected(byte[] bytes, String message) throws Exception {
        boolean failed = false;
        try { DiagnosticArchive.decode(bytes); }
        catch (IOException expected) { failed = true; }
        check(failed, message);
    }

    private static byte[] withChecksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        long value = crc.getValue();
        for (int i = 0; i < 4; i++) bytes[bytes.length - 4 + i] = (byte) (value >>> (24 - 8 * i));
        return bytes;
    }

    private static void retentionAcrossDecades() throws Exception {
        DiagnosticArchive archive = new DiagnosticArchive();
        failure(archive, "rare-failure", WALL);
        for (int day = 1; day <= 7_300; day++) {
            success(archive, "day-" + day, WALL + day * 86_400_000L);
            // Simulate ordinary process restart and persistent reload throughout twenty years.
            if (day % 31 == 0) archive = DiagnosticArchive.decode(archive.encode());
        }
        check(archive.recentCount() == DiagnosticArchive.MAX_RECENT,
                "twenty years of daily summaries stay at the fixed count");
        check(archive.failureCount() == 1,
                "successful mornings do not evict a rare failure");
        check("rare-failure".equals(archive.latestFailure().runId),
                "rare failure remains the latest failure after twenty years");
        check(archive.findTrace("rare-failure") != null,
                "old failure detail remains directly retrievable");
        check(archive.encode().length <= DiagnosticArchive.MAX_BYTES,
                "twenty-year archive remains under the hard serialized-byte cap");
    }

    private static void boundedRepeatedFailures() throws Exception {
        DiagnosticArchive archive = new DiagnosticArchive();
        for (int i = 0; i < 1_000; i++) failure(archive, "failure-" + i, WALL + i * 86_400_000L);
        check(archive.failureCount() == DiagnosticArchive.MAX_FAILURES,
                "repeated failures rotate to the fixed failure count");
        check(archive.recentCount() == DiagnosticArchive.MAX_RECENT,
                "repeated failures also bound ordinary summaries");
        check("failure-999".equals(archive.latestFailure().runId), "newest failure retained");
        check(archive.findTrace("failure-0") == null, "oldest excess failure evicted");
        check(archive.findTrace("failure-992") != null, "oldest failure inside retention retained");
        check(archive.encode().length <= DiagnosticArchive.MAX_BYTES,
                "thousand-failure archive remains under byte cap");

        int summaries = archive.recentCount(), failures = archive.failureCount();
        archive.finish("failure-999", WALL + 1_000, 200_000, "success", "playback_confirmed");
        check(archive.recentCount() == summaries && archive.failureCount() == failures,
                "duplicate terminal callback does not add a summary or failure");
        check("failed".equals(archive.latestFailure().outcome),
                "late success does not rewrite an already archived failure");
    }

    private static void eventAndSystemCaps() throws Exception {
        DiagnosticArchive archive = new DiagnosticArchive();
        archive.start("event-storm", WALL, 8, "wake_requested");
        Map<String, Long> numbers = new HashMap<>();
        numbers.put("stage_duration_ms", Long.MAX_VALUE);
        numbers.put("candidate_count", Long.MAX_VALUE);
        for (int i = 0; i < 10_000; i++) {
            archive.event("event-storm", WALL + i, i,
                    "confirming_playback", "timeout", numbers);
        }
        check(archive.current().events.size() <= DiagnosticArchive.MAX_EVENTS,
                "an event storm cannot grow the active trace");
        archive.finish("event-storm", WALL + 10_001, 10_001, "failed", "playback_not_confirmed");
        check(archive.latestFailure().events.size() <= DiagnosticArchive.MAX_EVENTS,
                "terminal event also respects the trace cap");
        for (int i = 0; i < 1_000; i++) {
            archive.system("boot", WALL + i, i, "event-storm", "failed");
        }
        check(archive.systemCount() == DiagnosticArchive.MAX_SYSTEM,
                "reboot observations rotate at a fixed count");
        check(archive.encode().length <= DiagnosticArchive.MAX_BYTES,
                "event and reboot storms retain the hard byte cap");
    }

    private static void staleRunIsolation() throws Exception {
        DiagnosticArchive archive = new DiagnosticArchive();
        failure(archive, "previous-run", WALL);
        int oldEvents = archive.latestFailure().events.size();
        archive.start("current-run", WALL + 100_000, 8, "wake_requested");
        int currentEvents = archive.current().events.size();
        archive.phase("previous-run", WALL + 100_001, 100_001,
                "confirming_playback", "4BakiCgKOZM", 99);
        archive.event("previous-run", WALL + 100_002, 100_002,
                "confirming_playback", "timeout", new HashMap<String, Long>());
        archive.finish("previous-run", WALL + 100_003, 100_003, "success", "playback_confirmed");
        check("current-run".equals(archive.current().runId), "stale callbacks cannot change run ownership");
        check(archive.current().events.size() == currentEvents,
                "stale telemetry cannot contaminate the current trace");
        check(archive.latestFailure().events.size() == oldEvents,
                "stale telemetry cannot modify the retained failure trace");
        check(archive.failureCount() == 1, "stale completion cannot duplicate the old failure");
    }

    private static void interruptionAndClockSemantics() throws Exception {
        DiagnosticArchive archive = new DiagnosticArchive();
        archive.start("prior-boot-run", WALL, 8, "wake_requested");
        archive.phase("prior-boot-run", WALL + 65_000, 65_000,
                "confirming_playback", "4BakiCgKOZM", 0);
        archive = DiagnosticArchive.decode(archive.encode());
        archive.start("after-restart", WALL + 300_000, 8, "wake_requested");
        DiagnosticArchive.Trace interrupted = archive.latestFailure();
        check(interrupted != null && "prior-boot-run".equals(interrupted.runId),
                "new run preserves an unfinished durable predecessor as a retained interruption");
        check("interrupted".equals(interrupted.outcome) && "process_interrupted".equals(interrupted.reason),
                "orphaned run is not misclassified as successful or silently discarded");
        check(interrupted.elapsedMs == -1L,
                "unknown interruption duration is not fabricated from the next boot's clock");
        check(interrupted.stages.get("confirming_playback") == 65_000L,
                "last known pre-interruption phase timing remains available");
        check(interrupted.events.get(0).elapsedMs == 65_000L,
                "last known pre-interruption event elapsed time survives restart");
        archive.finish("after-restart", WALL + 400_000, 79_000, "success", "playback_confirmed");
        archive.system("package_updated", WALL + 500_000, 100_000, "after-restart", "success");
        archive.system("boot", WALL + 600_000, 5_000, "after-restart", "success");
        check(archive.systemCount() == 2 && "package_updated".equals(archive.systemEvents().get(0).type)
                        && "boot".equals(archive.systemEvents().get(1).type),
                "upgrade and boot observations remain distinct even after a successful run");
        check("success".equals(archive.current().outcome),
                "recording system observations does not rewrite a completed run");

        DiagnosticArchive unusualClock = new DiagnosticArchive();
        unusualClock.start("clock-test", -10L, 8, "wake_requested");
        unusualClock.phase("clock-test", -5L, 1L, "waiting_for_network", "", 0);
        unusualClock.finish("clock-test", -1L, 2L, "failed", "playback_not_confirmed");
        check(DiagnosticArchive.decode(unusualClock.encode()).failureCount() == 1,
                "unusual wall-clock inputs cannot create an archive that rejects its own bytes");
    }

    private static void boundedPrivacyInputs() throws Exception {
        DiagnosticArchive archive = new DiagnosticArchive();
        archive.start("privacy-test", WALL, 8, "wake_requested");
        String sentinel = "SECRET_SESSION_TOKEN_SHOULD_NEVER_BE_RECORDED";
        StringBuilder huge = new StringBuilder("https://private.invalid/?cookie=" + sentinel);
        for (int i = 0; i < 100_000; i++) huge.append('x');
        Map<String, Long> data = new HashMap<>();
        data.put(sentinel, 5L);
        data.put("cookie", 123L);
        archive.phase("privacy-test", WALL + 1, 1, huge.toString(), huge.toString(), Integer.MAX_VALUE);
        archive.event("privacy-test", WALL + 2, 2, huge.toString(), huge.toString(), data);
        archive.system(huge.toString(), WALL + 3, 3, huge.toString(), huge.toString());
        archive.finish("privacy-test", WALL + 4, 4, "failed", huge.toString());
        byte[] bytes = archive.encode();
        String encoded = new String(bytes, StandardCharsets.ISO_8859_1);
        check(!encoded.contains(sentinel), "untrusted text and metric keys cannot leak into the archive");
        check(!encoded.contains("private.invalid"), "raw URLs are excluded from structured diagnostic fields");
        check(bytes.length <= DiagnosticArchive.MAX_BYTES, "oversized inputs cannot exceed the byte cap");
        check(DiagnosticArchive.decode(bytes).failureCount() == 1,
                "sanitizing oversized detail preserves the failure outcome");
    }

    private static void maximumRetainedFootprint() throws Exception {
        DiagnosticArchive archive = new DiagnosticArchive();
        String[] phases = {"unknown", "wake_requested", "starting", "manual_play", "manual_video",
                "waiting_for_network", "fetching_candidates", "warming_player", "waiting_for_account_progress",
                "selecting_account_progress", "play_requested", "confirming_playback", "waiting_for_playback",
                "network_ready", "candidate_fetch", "account_progress", "playback_check", "runtime_snapshot",
                "completed", "recovered"};
        String[] metrics = {"stage_duration_ms", "network_wait_ms", "network_internet", "network_validated",
                "network_wifi", "network_ethernet", "candidate_attempt", "candidate_count", "candidate_fetch_ms",
                "candidate_http_status", "progress_count", "progress_attempts", "progress_fetch_ms", "playback_checks",
                "dial_http_status", "dial_fetch_ms", "marker_present", "state_playing", "video_matches",
                "sample_age_ms", "sample_since_run_ms", "sample_delta_ms", "position_delta_ms", "first_position_ms",
                "second_position_ms", "interactive", "device_idle", "power_save", "thermal_status", "available_memory_mb",
                "total_memory_mb", "memory_threshold_mb", "low_memory", "helper_heap_used_kb", "helper_heap_max_kb",
                "storage_available_mb", "wake_held", "run_remaining_ms", "snapshot_age_ms", "snapshot_elapsed_ms",
                "snapshot_ok", "snapshot_duration_ms", "alarm_handoff_ms", "account_progress_percent"};
        Map<String, Long> first = new LinkedHashMap<>(), rest = new LinkedHashMap<>();
        for (int i = 0; i < metrics.length; i++) {
            (i < DiagnosticArchive.MAX_METRICS ? first : rest).put(metrics[i], Long.MAX_VALUE);
        }
        for (int run = 0; run < 73; run++) {
            StringBuilder id = new StringBuilder("max-" + run + "-");
            while (id.length() < 64) id.append('x');
            String runId = id.toString();
            archive.start(runId, WALL + run, 10_000, "wake_requested");
            for (int p = 0; p < phases.length; p++) archive.phase(runId, WALL + p, p, phases[p], "4BakiCgKOZM", 3);
            archive.event(runId, WALL + 30, 30, "runtime_snapshot", "ok", rest);
            for (int event = 0; event < 64; event++) {
                archive.event(runId, WALL + 100 + event, 100 + event, "playback_check", "timeout", first);
            }
            if (run < 72) archive.finish(runId, WALL + 1_000, 1_000,
                    run < 64 ? "success" : "failed",
                    run < 64 ? "playback_confirmed" : "playback_not_confirmed");
        }
        for (int i = 0; i < DiagnosticArchive.MAX_SYSTEM; i++) {
            archive.system("boot", Long.MAX_VALUE, Long.MAX_VALUE, archive.current().runId, "failed");
        }
        byte[] bytes = archive.encode();
        check(archive.recentCount() == 64 && archive.failureCount() == 8 && archive.systemCount() == 8,
                "all retention categories can be full simultaneously");
        check(archive.current().events.size() == 32,
                "fully occupied archive also supports a full active trace");
        check(bytes.length <= DiagnosticArchive.MAX_BYTES,
                "maximum strings, phases and full metric maps fit under the hard byte ceiling");
        check(Arrays.equals(bytes, DiagnosticArchive.decode(bytes).encode()),
                "fully occupied archive preserves canonical durable round trip");
        System.out.println("DiagnosticArchive maximum occupied test: " + bytes.length + " / "
                + DiagnosticArchive.MAX_BYTES + " serialized bytes.");
    }

    private static void strictFramingAndRoundTrip() throws Exception {
        DiagnosticArchive archive = new DiagnosticArchive();
        failure(archive, "persisted-failure", WALL);
        success(archive, "persisted-success", WALL + 86_400_000L);
        byte[] valid = archive.encode();
        DiagnosticArchive restored = DiagnosticArchive.decode(valid);
        check(restored.recentCount() == 2 && restored.failureCount() == 1,
                "durable round trip preserves both outcomes and their independent retention");
        check("persisted-failure".equals(restored.latestFailure().runId),
                "durable round trip preserves failed run identity");
        check("playback_not_confirmed".equals(restored.latestFailure().reason),
                "durable round trip preserves the diagnostic reason");
        check(Arrays.equals(valid, restored.encode()), "canonical serialized state round-trips byte-for-byte");
        rejected(new byte[DiagnosticArchive.MAX_BYTES + 1], "oversized encoded input rejected before parsing");
        rejected(new byte[0], "empty encoded input rejected");
        rejected(Arrays.copyOf(valid, valid.length - 1), "truncated encoded input rejected");
        rejected(Arrays.copyOf(valid, valid.length + 1), "trailing encoded bytes rejected");
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length / 2] ^= 0x40;
        rejected(corrupt, "checksum detects payload corruption");
        byte[] wrongHeader = valid.clone();
        wrongHeader[0] ^= 0x7f;
        rejected(wrongHeader, "invalid framing is rejected");

        // A valid checksum is not sufficient: reject impossible declared structure before allocation.
        byte[] empty = new DiagnosticArchive().encode();
        byte[] tooManyRecent = empty.clone();
        tooManyRecent[23] = (byte) (DiagnosticArchive.MAX_RECENT + 1);
        rejected(withChecksum(tooManyRecent), "valid-CRC excessive summary count rejected");
        byte[] tooManyFailures = empty.clone();
        tooManyFailures[24] = (byte) (DiagnosticArchive.MAX_FAILURES + 1);
        rejected(withChecksum(tooManyFailures), "valid-CRC excessive failure count rejected");
        byte[] tooManyBoots = empty.clone();
        tooManyBoots[25] = (byte) (DiagnosticArchive.MAX_SYSTEM + 1);
        rejected(withChecksum(tooManyBoots), "valid-CRC excessive system count rejected");
        byte[] unsupportedVersion = empty.clone();
        unsupportedVersion[4] = 127;
        rejected(withChecksum(unsupportedVersion), "valid-CRC unsupported format rejected");
        byte[] negativeCounter = empty.clone();
        negativeCounter[6] = (byte) 128;
        rejected(withChecksum(negativeCounter), "valid-CRC negative counter rejected");
        DiagnosticArchive pending = new DiagnosticArchive();
        pending.start("framing", WALL, 8, "wake_requested");
        byte[] oversizedId = pending.encode();
        oversizedId[23] = (byte) 255;
        rejected(withChecksum(oversizedId), "valid-CRC oversized string declaration rejected before allocation");
    }

    public static void main(String[] args) throws Exception {
        retentionAcrossDecades();
        boundedRepeatedFailures();
        eventAndSystemCaps();
        staleRunIsolation();
        interruptionAndClockSemantics();
        boundedPrivacyInputs();
        maximumRetainedFootprint();
        strictFramingAndRoundTrip();
        System.out.println("DiagnosticArchive: " + checks
                + " long-term retention, isolation, cap, privacy and corruption checks passed.");
    }
}
