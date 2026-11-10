package dev.yossi.onnkeyguard;

import java.util.regex.Pattern;

/** Finite daily-playback session policy. All input provenance is supplied by the Android runtime. */
public final class SesameSessionPolicy {
    public enum Phase { INACTIVE, ACTIVE, PHONE_PAUSED, PHONE_STOPPED, COMPLETED }
    public enum PlaybackPhase { PLAYING, PAUSED, ENDED, UNKNOWN }
    public enum BeginResult { STARTED, DUPLICATE, REJECTED }
    public enum RestoreResult { RESTORED, EXPIRED, REJECTED }
    public enum Reason {
        NOT_STARTED("not_started"), DAILY_START("daily_start"), PHONE_POWER("phone_power"),
        PHONE_HOME("phone_home"), PHONE_PAUSE("phone_pause"), PHONE_PLAY("phone_play"),
        PHONE_LEAVE("phone_leave"), PLAYBACK_ENDED("playback_ended"),
        PLAYBACK_COMPLETE("playback_complete"), EXPIRED("expired"), ASSISTANT_OVERRIDE("assistant_override"),
        INPUT_OBSERVER_LOST("input_observer_lost"), PHONE_PRESTART_INTENT("phone_prestart_intent"),
        PHONE_INTENT_UNRESOLVED("phone_intent_unresolved");

        public final String value;

        Reason(String value) {
            this.value = value;
        }

        /** Returns null for missing/unrecognized persisted values; never invents an active reason. */
        public static Reason fromValue(String value) {
            for (Reason reason : values()) {
                if (reason.value.equals(value)) {
                    return reason;
                }
            }
            return null;
        }
    }

    private enum PhoneIntent { NONE, GENERIC, PAUSE, PLAY, STOP }

    public static final int MAX_DURATION_SECONDS = 21600;
    public static final long MAX_SESSION_MILLIS = MAX_DURATION_SECONDS * 1000L;
    public static final long RECOVERY_GRACE_MILLIS = 300000L;
    public static final long PHONE_INTENT_WINDOW_MILLIS = 8000L;
    public static final long INTENT_EVIDENCE_MAX_AGE_MILLIS = 4000L;

    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    public static final class Snapshot {
        public final String id;
        public final String videoId;
        public final Phase phase;
        public final long expiresAtMs;
        /** Requested duration used for the deadline, not a fabricated player observation. */
        public final int durationSeconds;
        /** Null until an exact-video observation supplies a finite, nonnegative position. */
        public final Double positionSeconds;
        /** Null until an exact-video observation supplies a valid player duration. */
        public final Double observedDurationSeconds;
        public final String reason;
        public final Reason reasonCode;

        private Snapshot(String id, String videoId, Phase phase, long expiresAtMs,
                int durationSeconds, Double positionSeconds, Double observedDurationSeconds, Reason reason) {
            this.id = id;
            this.videoId = videoId;
            this.phase = phase;
            this.expiresAtMs = expiresAtMs;
            this.durationSeconds = durationSeconds;
            this.positionSeconds = positionSeconds;
            this.observedDurationSeconds = observedDurationSeconds;
            this.reason = reason.value;
            this.reasonCode = reason;
        }
    }

    private String id = "";
    private String videoId = "";
    private Phase phase = Phase.INACTIVE;
    private Reason reason = Reason.NOT_STARTED;
    private long expiresAtMs;
    private int durationSeconds;
    private Double positionSeconds;
    private Double observedDurationSeconds;
    private long lastPhoneKeyAtMs = -1;
    private long lastTargetForegroundAtMs = -1;
    private long lastPlayingEvidenceAtMs = -1;
    private PhoneIntent phoneIntent = PhoneIntent.NONE;

    /**
     * Initialize a fresh policy from same-boot private persisted state. The runtime MUST verify
     * Android BOOT_COUNT before calling this method. Existing in-memory state cannot be rolled
     * back through restore. The original deadline is copied exactly and never extended.
     *
     * Valid expired state is retained as a completed run and returns EXPIRED: it is not restored
     * as live, but its run ID continues to prevent a duplicate begin from reviving the session.
     */
    public synchronized RestoreResult restore(String savedId, String savedVideoId, Phase savedPhase,
            long savedExpiresAtMs, int savedDurationSeconds, Double savedPositionSeconds,
            Double savedObservedDurationSeconds, Reason savedReason, long nowWallMs) {
        if (phase != Phase.INACTIVE || id.length() != 0
                || savedId == null || !RUN_ID.matcher(savedId).matches()
                || savedVideoId == null || !VIDEO_ID.matcher(savedVideoId).matches()
                || savedDurationSeconds < 1 || savedDurationSeconds > MAX_DURATION_SECONDS
                || savedExpiresAtMs < 0 || nowWallMs < 0
                || !validSavedState(savedPhase, savedReason)) {
            return RestoreResult.REJECTED;
        }
        long maximumRemaining = Math.min(MAX_SESSION_MILLIS,
                savedDurationSeconds * 1000L + RECOVERY_GRACE_MILLIS);
        if (savedExpiresAtMs > nowWallMs && savedExpiresAtMs - nowWallMs > maximumRemaining) {
            return RestoreResult.REJECTED;
        }
        id = savedId;
        videoId = savedVideoId;
        durationSeconds = savedDurationSeconds;
        expiresAtMs = savedExpiresAtMs;
        positionSeconds = savedPositionSeconds != null && finite(savedPositionSeconds.doubleValue())
                && savedPositionSeconds.doubleValue() >= 0 ? savedPositionSeconds : null;
        observedDurationSeconds = savedObservedDurationSeconds != null
                && finite(savedObservedDurationSeconds.doubleValue())
                && savedObservedDurationSeconds.doubleValue() >= 1 ? savedObservedDurationSeconds : null;
        phase = savedPhase;
        reason = savedReason;
        lastPhoneKeyAtMs = -1;
        lastTargetForegroundAtMs = -1;
        lastPlayingEvidenceAtMs = -1;
        phoneIntent = PhoneIntent.NONE;
        if (savedExpiresAtMs <= nowWallMs) {
            complete(Reason.EXPIRED);
            return RestoreResult.EXPIRED;
        }
        return RestoreResult.RESTORED;
    }

    /**
     * A new run starts once. Retrying the same run cannot reset its deadline or phone override.
     * A different video under an existing run ID is rejected as conflicting identity.
     */
    public synchronized BeginResult begin(String runId, String newVideoId, int requestedDurationSeconds,
            long nowWallMs) {
        if (runId == null || !RUN_ID.matcher(runId).matches()
                || newVideoId == null || !VIDEO_ID.matcher(newVideoId).matches()
                || requestedDurationSeconds < 1 || requestedDurationSeconds > MAX_DURATION_SECONDS
                || nowWallMs < 0) {
            return BeginResult.REJECTED;
        }
        long lifetime = Math.min(MAX_SESSION_MILLIS,
                requestedDurationSeconds * 1000L + RECOVERY_GRACE_MILLIS);
        if (nowWallMs > Long.MAX_VALUE - lifetime) {
            return BeginResult.REJECTED;
        }
        expire(nowWallMs);
        if (id.equals(runId)) {
            return videoId.equals(newVideoId) ? BeginResult.DUPLICATE : BeginResult.REJECTED;
        }
        id = runId;
        videoId = newVideoId;
        durationSeconds = requestedDurationSeconds;
        expiresAtMs = nowWallMs + lifetime;
        positionSeconds = null;
        observedDurationSeconds = null;
        lastPhoneKeyAtMs = -1;
        lastTargetForegroundAtMs = -1;
        lastPlayingEvidenceAtMs = -1;
        phoneIntent = PhoneIntent.NONE;
        phase = Phase.ACTIVE;
        reason = Reason.DAILY_START;
        return BeginResult.STARTED;
    }

    /**
     * Feed only events positively attributed to a smartphone software remote.
     * Linux input key values: 1=press; 0=release and 2=repeat never toggle state or renew intent.
     */
    public synchronized void phoneKey(int linuxCode, int value, long nowWallMs) {
        expire(nowWallMs);
        if (!live() || nowWallMs < 0 || value != 1 || linuxCode < 0
                || linuxCode == 113 || linuxCode == 114 || linuxCode == 115) {
            return;
        }
        lastPhoneKeyAtMs = nowWallMs;
        if (linuxCode == 116 || linuxCode == 142) {
            phoneIntent = PhoneIntent.STOP;
            phase = Phase.PHONE_STOPPED;
            reason = Reason.PHONE_POWER;
        } else if (linuxCode == 172) {
            phoneIntent = PhoneIntent.STOP;
            phase = Phase.PHONE_STOPPED;
            reason = Reason.PHONE_HOME;
        } else if (linuxCode == 164) {
            if (phase == Phase.ACTIVE) {
                pauseByPhone();
            } else {
                playByPhone();
            }
        } else if (linuxCode == 201) {
            pauseByPhone();
        } else if (linuxCode == 207) {
            playByPhone();
        } else {
            // KEY_HOME 102 is intentionally generic; its actual mapping is not assumed.
            phoneIntent = PhoneIntent.GENERIC;
            lastTargetForegroundAtMs = -1;
            lastPlayingEvidenceAtMs = -1;
        }
    }

    /** Root supplies canonical target departures, excluding transient/system overlays. */
    public synchronized void observeForeground(String packageName, String targetPackage, long nowWallMs) {
        expire(nowWallMs);
        if (!live() || !hasText(packageName) || !hasText(targetPackage)) {
            return;
        }
        if (packageName.equals(targetPackage)) {
            lastTargetForegroundAtMs = nowWallMs;
            resolveGenericPlaying(nowWallMs);
            return;
        }
        if (phoneKeyRecent(nowWallMs)) {
            phase = Phase.PHONE_STOPPED;
            reason = Reason.PHONE_LEAVE;
            phoneIntent = PhoneIntent.STOP;
        }
    }

    /**
     * Missing numbers are NaN, not zero. Exact-video observations may change playback state;
     * a known different video immediately after phone intent releases the daily session.
     * UNKNOWN never confirms completion or phone play/pause. An unavailable video ID is null/empty,
     * never a fabricated 11-character placeholder.
     */
    public synchronized void observePlayback(String observedVideoId, double observedPosition,
            double observedDuration, PlaybackPhase playbackPhase, long nowWallMs) {
        expire(nowWallMs);
        if (!live() || nowWallMs < 0 || playbackPhase == null) {
            return;
        }
        if (phoneIntent == PhoneIntent.GENERIC
                && (playbackPhase == PlaybackPhase.UNKNOWN || !videoId.equals(observedVideoId))) {
            lastPlayingEvidenceAtMs = -1;
        }
        if (!videoId.equals(observedVideoId)) {
            if (observedVideoId != null && VIDEO_ID.matcher(observedVideoId).matches()
                    && phoneKeyRecent(nowWallMs)) {
                phase = Phase.PHONE_STOPPED;
                reason = Reason.PHONE_LEAVE;
                phoneIntent = PhoneIntent.STOP;
            }
            return;
        }
        if (finite(observedPosition) && observedPosition >= 0) {
            positionSeconds = Double.valueOf(observedPosition);
        }
        if (finite(observedDuration) && observedDuration >= 1) {
            observedDurationSeconds = Double.valueOf(observedDuration);
        }
        if (playbackPhase == PlaybackPhase.UNKNOWN) {
            return;
        }
        if (playbackPhase == PlaybackPhase.ENDED) {
            complete(Reason.PLAYBACK_ENDED);
            return;
        }
        // Both figures must be present in this observation; do not combine stale position/duration.
        if (finite(observedPosition) && observedPosition > 0
                && finite(observedDuration) && observedDuration >= 1
                && observedPosition >= observedDuration - 1) {
            complete(Reason.PLAYBACK_COMPLETE);
            return;
        }
        if (!phoneKeyRecent(nowWallMs)) {
            return;
        }
        if (playbackPhase == PlaybackPhase.PLAYING) lastPlayingEvidenceAtMs = nowWallMs;
        if (playbackPhase == PlaybackPhase.PAUSED && phoneIntent != PhoneIntent.PLAY) {
            pauseByPhone();
        } else if (playbackPhase == PlaybackPhase.PLAYING) {
            resolveGenericPlaying(nowWallMs);
        }
    }

    /** A missing proven input observer cannot safely authorize recovery or physical blocking. */
    public synchronized void inputObserverLost(long nowWallMs) {
        expire(nowWallMs);
        if (nowWallMs >= 0 && live()) stop(Reason.INPUT_OBSERVER_LOST);
    }

    /** Remember a declined daily enrollment so a retry cannot forget recent prestart phone intent. */
    public synchronized void recentPhoneBeforeStart(long nowWallMs) {
        expire(nowWallMs);
        if (nowWallMs >= 0 && live()) stop(Reason.PHONE_PRESTART_INTENT);
    }

    public synchronized boolean hasPendingGenericIntent(long nowWallMs) {
        expire(nowWallMs);
        return live() && phoneIntent == PhoneIntent.GENERIC;
    }

    /** Explicit assistant override is terminal for this run; a distinct run may be started later. */
    public synchronized void override(long nowWallMs) {
        expire(nowWallMs);
        if (nowWallMs >= 0 && (live() || phase == Phase.PHONE_STOPPED)) {
            phase = Phase.PHONE_STOPPED;
            reason = Reason.ASSISTANT_OVERRIDE;
            phoneIntent = PhoneIntent.STOP;
        }
    }

    public synchronized boolean needsRecovery(long nowWallMs) {
        expire(nowWallMs);
        return nowWallMs >= 0 && phase == Phase.ACTIVE && phoneIntent != PhoneIntent.GENERIC;
    }

    public synchronized boolean shouldGrab(long nowWallMs) {
        expire(nowWallMs);
        return nowWallMs >= 0 && live();
    }

    /** Explicit DUMP tests use an exact short deadline and never authorize playback recovery. */
    public synchronized boolean shouldGrabBoundedTest(long nowWallMs) {
        expire(nowWallMs);
        return nowWallMs >= 0 && live() && durationSeconds >= 1 && durationSeconds <= 60
                && expiresAtMs > nowWallMs && expiresAtMs - nowWallMs <= 60000L;
    }

    public synchronized Snapshot status(long nowWallMs) {
        expire(nowWallMs);
        return new Snapshot(id, videoId, phase, expiresAtMs, durationSeconds,
                positionSeconds, observedDurationSeconds, reason);
    }

    private void pauseByPhone() {
        phase = Phase.PHONE_PAUSED;
        reason = Reason.PHONE_PAUSE;
        phoneIntent = PhoneIntent.PAUSE;
    }

    private void playByPhone() {
        phase = Phase.ACTIVE;
        reason = Reason.PHONE_PLAY;
        phoneIntent = PhoneIntent.PLAY;
    }

    private boolean live() {
        return phase == Phase.ACTIVE || phase == Phase.PHONE_PAUSED;
    }

    private boolean phoneKeyRecent(long nowWallMs) {
        return lastPhoneKeyAtMs >= 0 && nowWallMs >= lastPhoneKeyAtMs
                && nowWallMs - lastPhoneKeyAtMs <= PHONE_INTENT_WINDOW_MILLIS;
    }

    private void expire(long nowWallMs) {
        if (live() && nowWallMs >= expiresAtMs) {
            complete(Reason.EXPIRED);
        } else if (live() && phoneIntent == PhoneIntent.GENERIC && lastPhoneKeyAtMs >= 0
                && nowWallMs >= lastPhoneKeyAtMs
                && nowWallMs - lastPhoneKeyAtMs > PHONE_INTENT_WINDOW_MILLIS) {
            if (freshIntentEvidence(lastPlayingEvidenceAtMs, nowWallMs)
                    && freshIntentEvidence(lastTargetForegroundAtMs, nowWallMs)) {
                phoneIntent = PhoneIntent.NONE;
                lastPhoneKeyAtMs = -1;
            } else {
                stop(Reason.PHONE_INTENT_UNRESOLVED);
            }
        }
    }

    private boolean freshIntentEvidence(long evidenceAtMs, long nowWallMs) {
        return evidenceAtMs >= lastPhoneKeyAtMs && evidenceAtMs <= nowWallMs
                && nowWallMs - evidenceAtMs <= INTENT_EVIDENCE_MAX_AGE_MILLIS;
    }

    private void resolveGenericPlaying(long nowWallMs) {
        if (phoneIntent != PhoneIntent.GENERIC || !phoneKeyRecent(nowWallMs)
                || lastPlayingEvidenceAtMs < lastPhoneKeyAtMs
                || lastTargetForegroundAtMs < lastPhoneKeyAtMs) return;
        if (phase == Phase.PHONE_PAUSED) {
            phase = Phase.ACTIVE;
            reason = Reason.PHONE_PLAY;
        }
        // Preserve the complete grace window even after positive evidence; expire() resolves it.
    }

    private void stop(Reason stopReason) {
        phase = Phase.PHONE_STOPPED;
        reason = stopReason;
        phoneIntent = PhoneIntent.STOP;
        lastPhoneKeyAtMs = -1;
    }

    private void complete(Reason completionReason) {
        phase = Phase.COMPLETED;
        reason = completionReason;
        phoneIntent = PhoneIntent.NONE;
        lastPhoneKeyAtMs = -1;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean hasText(String text) {
        return text != null && text.trim().length() != 0;
    }

    private static boolean validSavedState(Phase savedPhase, Reason savedReason) {
        if (savedPhase == null || savedReason == null) {
            return false;
        }
        switch (savedPhase) {
            case ACTIVE:
                return savedReason == Reason.DAILY_START || savedReason == Reason.PHONE_PLAY;
            case PHONE_PAUSED:
                return savedReason == Reason.PHONE_PAUSE;
            case PHONE_STOPPED:
                return savedReason == Reason.PHONE_POWER || savedReason == Reason.PHONE_HOME
                        || savedReason == Reason.PHONE_LEAVE || savedReason == Reason.ASSISTANT_OVERRIDE
                        || savedReason == Reason.INPUT_OBSERVER_LOST || savedReason == Reason.PHONE_PRESTART_INTENT
                        || savedReason == Reason.PHONE_INTENT_UNRESOLVED;
            case COMPLETED:
                return savedReason == Reason.PLAYBACK_ENDED || savedReason == Reason.PLAYBACK_COMPLETE
                        || savedReason == Reason.EXPIRED;
            case INACTIVE:
            default:
                return false;
        }
    }
}
