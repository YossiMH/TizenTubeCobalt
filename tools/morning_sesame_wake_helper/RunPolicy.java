package dev.yossi.morningsesame;

/** Pure timing rules shared by the activity, alarms, and their worker callbacks. */
final class RunPolicy {
    static final long MAX_RUN_MS = 5 * 60_000L;
    static final long WAKE_LEASE_MS = 130_000L;

    private RunPolicy() {}

    static long remaining(long startedElapsed, long nowElapsed) {
        // An elapsed clock that moved backwards belongs to a different boot.
        if (startedElapsed < 0L || nowElapsed < startedElapsed) return 0L;
        long age = nowElapsed - startedElapsed;
        return age >= MAX_RUN_MS ? 0L : MAX_RUN_MS - age;
    }

    static boolean mayContinue(String expected, String current, String outcome,
            long startedElapsed, long nowElapsed) {
        return expected != null && expected.equals(current) && "running".equals(outcome)
                && remaining(startedElapsed, nowElapsed) > 0L;
    }

    static long wakeLease(long startedElapsed, long nowElapsed) {
        return Math.min(WAKE_LEASE_MS, remaining(startedElapsed, nowElapsed));
    }
}
