package dev.yossi.onnkeyguard;

/** Ephemeral provenance fence. It is intentionally never restored from preferences. */
public final class InputObserverPolicy {
    public enum State { UNKNOWN, LIVE, LOST }
    public enum Health { MONITORING, PHYSICAL_GRABBED, UNAVAILABLE, STOPPED, STARTING, DISARMED, LEASE_ARMED }
    public enum Proof { NATIVE_STATUS, NONE }
    public enum Activation { ALLOWED, UNPROVEN, WARMING_UP, STALE_PROOF, RECENT_PHONE }
    public static final long MAX_PROOF_AGE_MS = 12000L;
    public static final long START_PROOF_MAX_AGE_MS = 4000L;
    public static final long PRESTART_WINDOW_MS = 8000L;

    public static final class Snapshot {
        public final State state;
        public final Long ageMs;
        public final Activation activation;

        private Snapshot(State state, Long ageMs, Activation activation) {
            this.state = state;
            this.ageMs = ageMs;
            this.activation = activation;
        }
    }

    private State state = State.UNKNOWN;
    private long lastProofAtMs = -1;
    private long monitoringSinceMs = -1;
    private long lastNonvolumePhoneAtMs = -1;
    private long lossCutoffWallMs = -1;
    private long lastProofSampleWallMs = -1;

    public synchronized void observeHealth(Health health, Proof proof, long elapsedMs) {
        observeHealth(health, proof, elapsedMs, elapsedMs, elapsedMs);
    }

    /** Source timestamps fence HTTP work delayed until after an observed monitor failure. */
    public synchronized boolean observeHealth(Health health, Proof proof, long elapsedMs,
            long sampleWallMs, long receiptWallMs) {
        expire(elapsedMs);
        if (health == null || proof == null || elapsedMs < 0 || receiptWallMs < 0) return false;
        if (health == Health.UNAVAILABLE || health == Health.STOPPED
                || (health == Health.STARTING && state == State.LIVE)) {
            invalidate(receiptWallMs);
            return true;
        }
        if (proof == Proof.NATIVE_STATUS && (health == Health.MONITORING || health == Health.PHYSICAL_GRABBED)) {
            if (sampleWallMs <= lossCutoffWallMs || sampleWallMs <= lastProofSampleWallMs
                    || sampleWallMs > receiptWallMs + 1000L) return false;
            lastProofSampleWallMs = sampleWallMs;
            if (state != State.LIVE) monitoringSinceMs = elapsedMs;
            lastProofAtMs = elapsedMs;
            state = State.LIVE;
        }
        return true;
    }

    /** Record proven phone input even before a daily session exists. Volume never creates this fence. */
    public synchronized void observePhoneKey(int linuxCode, int value, long elapsedMs) {
        if (elapsedMs < 0 || linuxCode <= 0 || (value != 1 && value != 2)
                || linuxCode == 113 || linuxCode == 114 || linuxCode == 115) return;
        lastNonvolumePhoneAtMs = elapsedMs;
    }

    public synchronized void invalidate(long receiptWallMs) {
        if (receiptWallMs > lossCutoffWallMs) lossCutoffWallMs = receiptWallMs;
        invalidate();
    }

    public synchronized void invalidate() {
        state = State.LOST;
        monitoringSinceMs = -1;
    }

    public synchronized boolean isLive(long elapsedMs) {
        expire(elapsedMs);
        return state == State.LIVE;
    }

    public synchronized Activation activation(long elapsedMs) {
        expire(elapsedMs);
        if (state != State.LIVE) return Activation.UNPROVEN;
        if (lastNonvolumePhoneAtMs >= 0 && elapsedMs >= lastNonvolumePhoneAtMs
                && elapsedMs - lastNonvolumePhoneAtMs <= PRESTART_WINDOW_MS) return Activation.RECENT_PHONE;
        if (lastProofAtMs < 0 || elapsedMs - lastProofAtMs > START_PROOF_MAX_AGE_MS) return Activation.STALE_PROOF;
        if (monitoringSinceMs < 0 || elapsedMs - monitoringSinceMs < PRESTART_WINDOW_MS) return Activation.WARMING_UP;
        return Activation.ALLOWED;
    }

    public synchronized Snapshot status(long elapsedMs) {
        Activation admission = activation(elapsedMs);
        Long age = lastProofAtMs < 0 || elapsedMs < lastProofAtMs ? null
                : Long.valueOf(elapsedMs - lastProofAtMs);
        return new Snapshot(state, age, admission);
    }

    private void expire(long elapsedMs) {
        if (state == State.LIVE && (elapsedMs < lastProofAtMs
                || elapsedMs - lastProofAtMs >= MAX_PROOF_AGE_MS)) invalidate();
    }
}
