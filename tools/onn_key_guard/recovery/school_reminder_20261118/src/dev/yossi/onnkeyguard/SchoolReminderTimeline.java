package dev.yossi.onnkeyguard;

/** Monotonic, independently testable animation clock. Video ends only by callback. */
public final class SchoolReminderTimeline {
    public static final long MIN_HOLD_MS = 30000L;
    public static final long MAX_HOLD_MS = 45000L;
    public static final long TEXT_FADE_MS = 2000L;
    public static final long MUSIC_FADE_MS = 5000L;
    public static final long TEXT_RETURN_DELAY_MS = 3000L;
    public static final long FRAME_RETURN_DELAY_MS = 8000L;
    public static final long FRAME_FADE_MS = 3000L;
    public interface HoldSource { long nextHoldMs(); }
    public enum Phase { LOADING, HOLD, FADE_OUT, VIDEO, RETURN, STOPPED }

    public static final class Frame {
        public final Phase phase;
        public final int cycle;
        public final long elapsedMs;
        public final long holdMs;
        public final float textAlpha;
        public final float firstAlpha;
        public final boolean lastVisible;
        public final float musicVolume;
        public final boolean musicPlaying;
        public final boolean videoPlaying;
        public final boolean suspended;
        Frame(Phase phase, int cycle, long elapsedMs, long holdMs, float textAlpha,
                float firstAlpha, boolean lastVisible, float musicVolume,
                boolean musicPlaying, boolean videoPlaying, boolean suspended) {
            this.phase = phase; this.cycle = cycle; this.elapsedMs = elapsedMs;
            this.holdMs = holdMs; this.textAlpha = textAlpha; this.firstAlpha = firstAlpha;
            this.lastVisible = lastVisible; this.musicVolume = musicVolume;
            this.musicPlaying = musicPlaying; this.videoPlaying = videoPlaying;
            this.suspended = suspended;
        }
    }

    private final HoldSource holds;
    private Phase phase = Phase.LOADING;
    private long phaseAtMs;
    private long pauseAtMs = -1L;
    private long holdMs;
    private int cycle;

    public SchoolReminderTimeline(HoldSource holds) {
        if (holds == null) throw new IllegalArgumentException("hold_source_missing");
        this.holds = holds;
    }

    public void ready(long now) {
        if (phase != Phase.LOADING) return;
        phase = Phase.HOLD;
        phaseAtMs = clock(now);
        holdMs = nextHold();
    }

    public void suspend(boolean suspended, long now) {
        if (phase == Phase.STOPPED) return;
        if (suspended && pauseAtMs < 0L) pauseAtMs = now;
        else if (!suspended && pauseAtMs >= 0L) {
            phaseAtMs += Math.max(0L, now - pauseAtMs);
            pauseAtMs = -1L;
        }
    }

    public boolean videoCompleted(long now) {
        if (phase != Phase.VIDEO) return false;
        phase = Phase.RETURN;
        phaseAtMs = clock(now);
        return true;
    }

    public void stop() { phase = Phase.STOPPED; pauseAtMs = -1L; }

    public Frame frame(long now) {
        long at = clock(now);
        // At most HOLD -> FADE_OUT -> VIDEO or RETURN -> HOLD -> FADE_OUT -> VIDEO.
        for (int n = 0; n < 4; n++) {
            long e = Math.max(0L, at - phaseAtMs);
            if (phase == Phase.HOLD && e >= holdMs) {
                phaseAtMs += holdMs; phase = Phase.FADE_OUT;
            } else if (phase == Phase.FADE_OUT && e >= MUSIC_FADE_MS) {
                phaseAtMs += MUSIC_FADE_MS; phase = Phase.VIDEO;
            } else if (phase == Phase.RETURN && e >= FRAME_RETURN_DELAY_MS + FRAME_FADE_MS) {
                phaseAtMs += FRAME_RETURN_DELAY_MS + FRAME_FADE_MS;
                cycle++; holdMs = nextHold(); phase = Phase.HOLD;
            } else break;
        }
        long elapsed = Math.max(0L, at - phaseAtMs);
        float text = 1f, first = 1f, volume = 0f;
        boolean last = false, music = false, video = false;
        if (phase == Phase.HOLD) {
            volume = cycle == 0 ? fraction(elapsed, MUSIC_FADE_MS) : 1f;
            music = true;
        } else if (phase == Phase.FADE_OUT) {
            text = 1f - fraction(elapsed, TEXT_FADE_MS);
            volume = 1f - fraction(elapsed, MUSIC_FADE_MS);
            music = true;
        } else if (phase == Phase.VIDEO) {
            text = 0f; first = 0f; video = true;
        } else if (phase == Phase.RETURN) {
            text = fraction(elapsed - TEXT_RETURN_DELAY_MS, TEXT_FADE_MS);
            first = fraction(elapsed - FRAME_RETURN_DELAY_MS, FRAME_FADE_MS);
            // First is composited over an opaque last frame, preventing a dark dip.
            last = true; volume = fraction(elapsed, MUSIC_FADE_MS); music = true;
        }
        boolean paused = pauseAtMs >= 0L;
        return new Frame(phase, cycle, elapsed, holdMs, text, first, last, volume,
                music && !paused, video && !paused, paused);
    }

    private long clock(long now) { return pauseAtMs >= 0L ? pauseAtMs : now; }
    private long nextHold() {
        long value = holds.nextHoldMs();
        if (value < MIN_HOLD_MS || value > MAX_HOLD_MS)
            throw new IllegalArgumentException("hold_out_of_bounds");
        return value;
    }
    private static float fraction(long elapsed, long duration) {
        return Math.max(0f, Math.min(1f, (float) elapsed / duration));
    }
}
