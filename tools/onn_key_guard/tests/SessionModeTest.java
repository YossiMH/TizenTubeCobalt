package dev.yossi.onnkeyguard;

/** Exact administrative capability boundaries, using the real finite session policy. */
public final class SessionModeTest {
    private static int assertions;
    private static final String VIDEO = "dQw4w9WgXcQ";

    public static void main(String[] args) {
        boundedPowerProbe();
        preservedTestScope();
        scheduledRolloutStillRequired();
        System.out.println("PASS SessionModeTest: " + assertions + " assertions");
    }

    private static void boundedPowerProbe() {
        SesameSessionPolicy session = exact(60);
        truth(SessionMode.POWER_PROBE.isBoundedProbe(), "power probe has explicit bounded scope");
        truth(SessionMode.POWER_PROBE.authorizesPreparedGuard(session, 1000), "sixty-second prepared probe accepted");
        truth(SessionMode.POWER_PROBE.authorizesPreparedGuard(session, 60999), "guard permitted before exact deadline");
        truth(!SessionMode.POWER_PROBE.authorizesPreparedGuard(session, 61000), "guard expires without controller cleanup");
        truth(!SessionMode.POWER_PROBE.permitsMediaRecovery(), "power probe never launches or resumes media");
        truth(!SessionMode.POWER_PROBE.authorizesPreparedGuard(exact(61), 1000), "requested duration above sixty seconds refused");
        truth(!SessionMode.POWER_PROBE.authorizesPreparedGuard(exact(60), 999), "clock before start cannot lengthen authorization");
        truth(!SessionMode.POWER_PROBE.authorizesPreparedGuard(null, 1000), "missing session has no authorization");

        SesameSessionPolicy phoneStopped = exact(30);
        phoneStopped.phoneKey(116, 1, 1100);
        truth(!SessionMode.POWER_PROBE.authorizesPreparedGuard(phoneStopped, 1100), "phone power immediately releases probe");
        SesameSessionPolicy fault = exact(30);
        fault.inputObserverLost(1100);
        truth(!SessionMode.POWER_PROBE.authorizesPreparedGuard(fault, 1100), "observer loss releases probe");
        SesameSessionPolicy overridden = exact(30);
        overridden.override(1100);
        truth(!SessionMode.POWER_PROBE.authorizesPreparedGuard(overridden, 1100), "assistant override releases probe");
    }

    private static void preservedTestScope() {
        truth(SessionMode.TEST.authorizesPreparedGuard(exact(10), 1000), "existing prepared TEST guard retained");
        truth(!SessionMode.TEST.permitsMediaRecovery(), "TEST remains unable to recover media");
        SesameSessionPolicy extended = new SesameSessionPolicy();
        extended.begin("ordinary-grace", VIDEO, 10, 1000);
        truth(!SessionMode.TEST.authorizesPreparedGuard(extended, 1000), "ordinary grace is not exact TEST authorization");
        truth(!SessionMode.POWER_PROBE.authorizesPreparedGuard(extended, 1000), "ordinary grace is not exact power-probe authorization");
    }

    private static void scheduledRolloutStillRequired() {
        truth(!SessionMode.SCHEDULED.isBoundedProbe(), "daily playback is not a probe");
        truth(!SessionMode.SCHEDULED.authorizesPreparedGuard(exact(10), 1000), "scheduled mode has no prepared exception");
        truth(SessionMode.SCHEDULED.permitsMediaRecovery(), "scheduled mode remains subject to runtime rollout and authentication gates");
    }

    private static SesameSessionPolicy exact(int seconds) {
        SesameSessionPolicy result = new SesameSessionPolicy();
        SesameSessionPolicy.RestoreResult restored = result.restore("probe-" + seconds, VIDEO,
                SesameSessionPolicy.Phase.ACTIVE, 1000 + seconds * 1000L, seconds,
                null, null, SesameSessionPolicy.Reason.DAILY_START, 1000);
        truth(restored == SesameSessionPolicy.RestoreResult.RESTORED, "valid exact finite session fixture");
        return result;
    }

    private static void truth(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
