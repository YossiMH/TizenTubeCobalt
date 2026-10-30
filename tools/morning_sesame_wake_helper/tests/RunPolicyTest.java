package dev.yossi.morningsesame;

/** Simulated wake/startup timelines, independent of Android or wall-clock changes. */
public final class RunPolicyTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long start = 50_000L;
        String current = "morning-B", old = "morning-A";
        check(RunPolicy.wakeLease(start, start) == 130_000L, "initial wake lease");

        // A slow but successful fetch must leave a fresh lease for the 65-second warm-up.
        long networkReady = start + 90_000L;
        long renewedUntil = networkReady + RunPolicy.wakeLease(start, networkReady);
        long verifyAt = networkReady + 65_000L;
        check(verifyAt > start + 130_000L, "scenario exceeds the old initial wake lease");
        check(renewedUntil > verifyAt, "renewed lease covers verification after slow startup");
        check(RunPolicy.mayContinue(current, current, "running", start, verifyAt),
                "valid slow-network startup may continue");

        long late = start + 245_000L;
        check(RunPolicy.wakeLease(start, late) == 55_000L, "last lease is capped at hard deadline");
        check(RunPolicy.remaining(start, start + 300_000L) == 0L, "deadline is exclusive");
        check(!RunPolicy.mayContinue(current, current, "running", start, start + 300_001L),
                "HTTP completion after deadline cannot launch player");
        check(RunPolicy.wakeLease(start, start + 400_000L) == 0L,
                "late callbacks cannot renew the wake lease");

        // An old callback can arrive while a newer run is healthy.
        check(!RunPolicy.mayContinue(old, current, "running", start, start + 1_000L),
                "old phase cannot mutate or schedule work for a newer generation");
        check(RunPolicy.mayContinue(current, current, "running", start, start + 1_000L),
                "current generation remains usable after stale callback");
        check(!RunPolicy.mayContinue(current, current, "success", start, start + 1_000L),
                "retry arriving after success cannot relaunch playback");
        check(!RunPolicy.mayContinue(current, current, "failed", start, start + 1_000L),
                "failed run cannot be revived by a late phase");
        check(!RunPolicy.mayContinue(current, current, "interrupted", start, start + 1_000L),
                "boot-interrupted run cannot continue");
        check(!RunPolicy.mayContinue(null, current, "running", start, start + 1_000L),
                "old v6 alarms without a generation are ignored");
        check(!RunPolicy.mayContinue(current, current, "running", start, start - 1L),
                "elapsed-clock rollback invalidates an old boot");
        check(RunPolicy.remaining(-1L, start) == 0L, "missing persisted start is invalid");
        check(RunPolicy.remaining(0L, Long.MAX_VALUE) == 0L, "very old timestamps cannot wrap into validity");
        System.out.println("RunPolicy: " + checks + " startup/deadline/generation cases passed.");
    }
}
