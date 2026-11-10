package dev.yossi.morningsesame;

/** Only confirmed, normally scheduled episodes may start a bounded protected session. */
final class ProtectionStartPolicy {
    private ProtectionStartPolicy() {}

    static int durationFor(String trigger, String videoId, int knownDurationSeconds) {
        if (!"wake_requested".equals(trigger) || videoId == null
                || !videoId.matches("[A-Za-z0-9_-]{11}")
                || knownDurationSeconds <= 1800 || knownDurationSeconds > 21600) return 0;
        return knownDurationSeconds;
    }
}
