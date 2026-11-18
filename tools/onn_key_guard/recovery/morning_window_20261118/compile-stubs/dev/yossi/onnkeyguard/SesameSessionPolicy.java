package dev.yossi.onnkeyguard;

/** Compile-only ABI. NEVER package this stub; preserve the installed class bytecode. */
public final class SesameSessionPolicy {
    public enum Phase { INACTIVE, ACTIVE, PHONE_PAUSED, PHONE_STOPPED, COMPLETED }
    public static final class Snapshot {
        public String id;
        public String videoId;
        public Phase phase;
        public String reason;
    }
}
