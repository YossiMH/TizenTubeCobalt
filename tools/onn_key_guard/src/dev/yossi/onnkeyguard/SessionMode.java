package dev.yossi.onnkeyguard;

/** Administrative scope, independent of rollout and observer health. */
public enum SessionMode {
    SCHEDULED, TEST, POWER_PROBE;

    public boolean isBoundedProbe() {
        return this == TEST || this == POWER_PROBE;
    }

    /** The runtime must first enforce proven live input observation. */
    public boolean authorizesPreparedGuard(SesameSessionPolicy session, long nowWallMs) {
        return isBoundedProbe() && session != null && session.shouldGrabBoundedTest(nowWallMs);
    }

    /** Both explicit probe modes are categorically barred from media launch/resume. */
    public boolean permitsMediaRecovery() {
        return this == SCHEDULED;
    }
}
