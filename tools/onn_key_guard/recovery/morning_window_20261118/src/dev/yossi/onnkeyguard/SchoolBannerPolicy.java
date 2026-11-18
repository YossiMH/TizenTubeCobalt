package dev.yossi.onnkeyguard;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Dated, persistent morning-banner state. This class has no Android dependencies. */
public final class SchoolBannerPolicy {
    public static final String MESSAGE = "No more TV, Bernie -- it's time to leave for school!";
    public static final String TIME_ZONE = "America/Los_Angeles";
    public static final long EVENT_MAX_AGE_MS = 3000L;
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern REASON = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final TimeZone ZONE = TimeZone.getTimeZone(TIME_ZONE);

    public enum Phase { INACTIVE, STARTING, WAITING, ACTIVE, DISMISSED, EXPIRED, SKIPPED }

    /** Deferred, bounded recovery from another app stealing the reminder's audio focus. */
    public static final class AudioRecovery {
        public static final long DELAY_MS = EVENT_MAX_AGE_MS + 500L;
        public static final long STABLE_FOCUS_MS = 10000L;
        public static final int MAX_ATTEMPTS = 2;
        private long dueAtMs = -1L;
        private long heldSinceMs = -1L;
        private int attempts;
        private boolean exhausted;

        public synchronized void gained(long now) {
            dueAtMs = -1L;
            heldSinceMs = now;
            exhausted = false;
        }

        public synchronized void lost(long now) {
            if (now < 0 || now > Long.MAX_VALUE - DELAY_MS) { cancel(); return; }
            if (heldSinceMs >= 0 && now >= heldSinceMs && now - heldSinceMs >= STABLE_FOCUS_MS)
                attempts = 0;
            heldSinceMs = -1L;
            if (attempts >= MAX_ATTEMPTS) {
                exhausted = true;
                dueAtMs = -1L;
            } else if (dueAtMs < 0) {
                dueAtMs = now + DELAY_MS;
            }
        }

        public synchronized boolean claim(long now, boolean bannerStillActive) {
            if (!bannerStillActive) { cancel(); return false; }
            if (dueAtMs < 0 || now < dueAtMs || attempts >= MAX_ATTEMPTS) return false;
            dueAtMs = -1L;
            attempts++;
            return true;
        }

        public synchronized void cancel() {
            dueAtMs = heldSinceMs = -1L;
            attempts = 0;
            exhausted = false;
        }

        public synchronized long dueAtMs() { return dueAtMs; }
        public synchronized int attempts() { return attempts; }
        public synchronized boolean exhausted() { return exhausted; }
    }

    public static final class Snapshot {
        public final String localDate;
        public final String runId;
        public final String videoId;
        public final Phase phase;
        public final String reason;
        public final long startedAtMs;
        public final long expiresAtMs;
        public final long completedAtMs;
        public final boolean observerReady;
        public final boolean protectionReady;
        public final boolean startupProtectionReady;
        public final long startupDeadlineMs;
        public final long fallbackAtMs;
        public final String candidateVideoId;
        public final boolean scheduledAdmission;
        public final boolean diagnostic;

        private Snapshot(SchoolBannerPolicy p) {
            localDate = p.localDate;
            runId = p.runId;
            videoId = p.videoId;
            phase = p.phase;
            reason = p.reason;
            startedAtMs = p.startedAtMs;
            expiresAtMs = p.expiresAtMs;
            completedAtMs = p.completedAtMs;
            observerReady = p.observerReady;
            protectionReady = phase == Phase.ACTIVE && observerReady;
            startupProtectionReady = phase == Phase.STARTING && observerReady && !p.canonicalEpisodeActive;
            startupDeadlineMs = p.startupDeadlineMs;
            fallbackAtMs = p.fallbackAtMs;
            candidateVideoId = p.candidateVideoId;
            scheduledAdmission = p.scheduledAdmission;
            diagnostic = p.diagnostic;
        }
    }

    private String localDate = "";
    private String runId = "";
    private String videoId = "";
    private Phase phase = Phase.INACTIVE;
    private String reason = "not_started";
    private long startedAtMs = -1L;
    private long expiresAtMs = -1L;
    private long completedAtMs = -1L;
    // Never restore live input proof from disk. A restarted process must earn fresh proof.
    private boolean observerReady;
    private boolean observerWasReady;
    private long startupDeadlineMs = -1L;
    private long fallbackAtMs = -1L;
    private String candidateVideoId = "";
    private boolean scheduledAdmission;
    private boolean diagnostic;
    // Ephemeral proof only: restoring STARTING never assumes that no episode is active.
    private boolean canonicalEpisodeActive;
    private long canonicalObservedAtMs = -1L;

    /** Called only after the existing signature-owned scheduled admission succeeds. */
    public synchronized boolean scheduledStart(String id, long start, long workDeadline,
            boolean observerLive, long now) {
        if (!validScheduledBounds(id, start, workDeadline, now) || !observerLive
                || !inMorningWindow(start) || !inMorningWindow(now)
                || !date(start).equals(date(now))) return false;
        advance(now);
        if (!localDate.equals(date(now))) clear();
        if (phase != Phase.INACTIVE)
            return phase == Phase.STARTING && runId.equals(id) && startedAtMs == start
                    && startupDeadlineMs == workDeadline;
        beginScheduled(id, start, workDeadline, fallback(now), deadline(now), false, now);
        return true;
    }

    /** A separate, never persisted instance may consume a DUMP-only exact-run test grant. */
    public synchronized boolean diagnosticStart(String id, long start, long workDeadline,
            long fallbackAt, long cutoff, boolean observerLive, long now) {
        if (phase != Phase.INACTIVE || !observerLive || !validScheduledBounds(id, start, workDeadline, now)
                || fallbackAt <= now || cutoff <= fallbackAt || cutoff - now > 180000L) return false;
        beginScheduled(id, start, workDeadline, fallbackAt, cutoff, true, now);
        return true;
    }

    private void beginScheduled(String id, long start, long workDeadline, long fallbackAt,
            long cutoff, boolean test, long now) {
        localDate = date(start);
        runId = id;
        startedAtMs = start;
        startupDeadlineMs = workDeadline;
        fallbackAtMs = fallbackAt;
        expiresAtMs = cutoff;
        scheduledAdmission = true;
        diagnostic = test;
        observerReady = observerWasReady = true;
        canonicalEpisodeActive = false;
        canonicalObservedAtMs = now; // Existing startup admission has just checked no active session.
        phase = Phase.STARTING;
        reason = "preparing_episode";
        advance(now);
    }

    private static boolean validScheduledBounds(String id, long start, long workDeadline, long now) {
        return id != null && RUN_ID.matcher(id).matches() && start >= 0 && now >= 0
                && workDeadline > start && workDeadline - start == 300000L
                && start <= now + 1000L && now < workDeadline;
    }

    /** The selected candidate is not evidence that an episode actually began. */
    public synchronized boolean candidate(String id, String video, long now) {
        advance(now);
        if (!startupWorkAllowed(id, now) || video == null || !VIDEO_ID.matcher(video).matches()) return false;
        candidateVideoId = video;
        return true;
    }

    public synchronized boolean startupWorkAllowed(String id, long now) {
        advance(now);
        return scheduledAdmission && phase == Phase.STARTING && runId.equals(id)
                && observerReady && now < startupDeadlineMs && now < fallbackAtMs;
    }

    public synchronized void expireDiagnostic() {
        if (diagnostic && (phase == Phase.STARTING || phase == Phase.WAITING || phase == Phase.ACTIVE)) {
            phase = Phase.EXPIRED;
            reason = "school_window_finished";
            observerReady = false;
        }
    }

    /** Observe only the canonical scheduled session, after its own validation and safety checks. */
    public synchronized void observeSession(String id, String video, String sessionPhase,
            String sessionReason, boolean scheduledEnabled, boolean observerLive, long now) {
        if (now < 0) return;
        boolean liveSession = "active".equals(sessionPhase) || "phone_paused".equals(sessionPhase);
        canonicalEpisodeActive = liveSession && validIdentity(id, video);
        canonicalObservedAtMs = now;
        // Reconcile trusted canonical state before a deadline decision. This also repairs
        // a restart between canonical confirmation persistence and banner binding persistence.
        if (phase == Phase.STARTING && canonicalEpisodeActive && runId.equals(id)
                && now >= startedAtMs - 1000L && now < expiresAtMs && localDate.equals(date(now))
                && (candidateVideoId.length() == 0 || candidateVideoId.equals(video))) {
            videoId = video;
            candidateVideoId = video;
            phase = diagnostic || isBannerDay(now) ? Phase.WAITING : Phase.SKIPPED;
            reason = phase == Phase.WAITING ? "waiting_for_episode_end" : "non_school_banner_day";
        }
        advance(now);
        if (!scheduledEnabled) {
            dismiss("session_disabled", now);
            observerReady = false;
            return;
        }

        if (liveSession && validIdentity(id, video) && (inMorningWindow(now) || diagnostic)) {
            if (!localDate.equals(date(now))) {
                clear();
            }
            // One daily run owns the banner. A retry or late duplicate cannot reset an override.
            if (phase == Phase.INACTIVE) {
                localDate = date(now);
                runId = id;
                videoId = video;
                startedAtMs = now;
                expiresAtMs = deadline(now);
                completedAtMs = -1L;
                phase = isBannerDay(now) ? Phase.WAITING : Phase.SKIPPED;
                reason = phase == Phase.WAITING ? "waiting_for_episode_end" : "non_school_banner_day";
            }
        }

        observeInputProof(observerLive, now);
        if (phase == Phase.STARTING && runId.equals(id) && "phone_stopped".equals(sessionPhase))
            dismiss(normalizedReason(sessionReason, "session_cancelled"), now);
        if (!runId.equals(id) || !videoId.equals(video) || phase != Phase.WAITING) return;
        if ("phone_stopped".equals(sessionPhase)) {
            dismiss(normalizedReason(sessionReason, "session_cancelled"), now);
        } else if ("completed".equals(sessionPhase)) {
            if ("playback_ended".equals(sessionReason)) {
                ended(id, video, now, now);
            } else if ("expired".equals(sessionReason)) {
                reason = "waiting_after_episode_expiry";
            }
            // Neither approximate position completion nor the episode guard's
            // duration-plus-five-minutes timeout establishes the actual end.
            // Keep only this bounded banner wait; no episode recovery authority
            // is recreated. A later authenticated end may still arrive by 09:30.
        }
    }

    /** Accept only a fresh, real end for the enrolled run and video. */
    public synchronized boolean ended(String id, String video, long sampledAtMs, long now) {
        advance(now);
        if (phase != Phase.WAITING || !runId.equals(id) || !videoId.equals(video)
                || !fresh(sampledAtMs, now) || sampledAtMs < startedAtMs
                || !localDate.equals(date(sampledAtMs)) || (!inMorningWindow(now) && !diagnostic)) return false;
        if (!observerReady) {
            dismiss("input_observer_unavailable_at_end", now);
            return false;
        }
        completedAtMs = sampledAtMs;
        phase = Phase.ACTIVE;
        reason = "episode_ended";
        return true;
    }

    /** Called only for authenticated, validated phone-origin events, using Linux key codes. */
    public synchronized void phoneKey(int linuxCode, int value, long sampledAtMs, long now) {
        advance(now);
        if (!fresh(sampledAtMs, now) || (value != 1 && value != 2)
                || linuxCode <= 0 || linuxCode > 767 || volume(linuxCode)) return;
        if (phase == Phase.STARTING) {
            dismiss("phone_cancelled_startup", now);
        } else if (phase == Phase.ACTIVE) {
            dismiss("phone_dismissed", now);
        } else if (phase == Phase.WAITING
                && (linuxCode == 116 || linuxCode == 142 || linuxCode == 172
                    || "waiting_after_episode_expiry".equals(reason))) {
            dismiss("phone_cancelled_episode", now);
        }
        // Pause/play and navigation within the episode retain normal canonical session semantics.
    }

    /** An explicit parent/admin override is terminal for this morning, including before completion. */
    public synchronized void dismiss(String why, long now) {
        advance(now);
        if (phase == Phase.STARTING || phase == Phase.WAITING || phase == Phase.ACTIVE) {
            phase = Phase.DISMISSED;
            reason = normalizedReason(why, "parent_override");
            observerReady = false;
        }
    }

    public synchronized void observeInputProof(boolean live, long now) {
        advance(now);
        if (live) {
            observerReady = true;
            observerWasReady = true;
        } else {
            observerReady = false;
            if (observerWasReady) dismiss("input_observer_lost", now);
        }
    }

    public synchronized Snapshot snapshot(long now) {
        advance(now);
        return new Snapshot(this);
    }

    /** Stable bounded record; never persists input proof or tokens. */
    public synchronized String save() {
        if (diagnostic) return "";
        if (phase == Phase.INACTIVE) return "";
        return "2|" + localDate + "|" + runId + "|" + videoId + "|" + phase.name()
                + "|" + startedAtMs + "|" + expiresAtMs + "|" + completedAtMs + "|" + reason
                + "|" + startupDeadlineMs + "|" + fallbackAtMs + "|" + candidateVideoId
                + "|" + (scheduledAdmission ? "1" : "0");
    }

    /** Restore today's state only, with identity/deadline checks. Returns false on corrupt data. */
    public synchronized boolean restore(String saved, long now) {
        if (saved == null || saved.length() == 0) return true;
        if (saved.length() > 1024 || now < 0 || phase != Phase.INACTIVE) return false;
        String[] fields = saved.split("\\|", -1);
        boolean legacy = fields.length == 9 && "1".equals(fields[0]);
        if (!legacy && (fields.length != 13 || !"2".equals(fields[0]))) return false;
        try {
            Phase restored = Phase.valueOf(fields[4]);
            long start = Long.parseLong(fields[5]);
            long end = Long.parseLong(fields[6]);
            long complete = Long.parseLong(fields[7]);
            long workDeadline = legacy ? -1L : Long.parseLong(fields[9]);
            long fallbackAt = legacy ? -1L : Long.parseLong(fields[10]);
            String candidate = legacy ? "" : fields[11];
            boolean admitted = !legacy && "1".equals(fields[12]);
            boolean fallbackActive = admitted && fields[3].length() == 0
                    && "scheduled_fallback".equals(fields[8]);
            if (restored == Phase.INACTIVE || !REASON.matcher(fields[8]).matches()
                    || !RUN_ID.matcher(fields[2]).matches()
                    || (!validIdentity(fields[2], fields[3]) && !(admitted && fields[3].length() == 0))
                    || !inMorningWindow(start) || !date(start).equals(fields[1])
                    || end != deadline(start) || start > now + 1000L
                    || (restored == Phase.ACTIVE && !fallbackActive && (complete < start || complete >= end))
                    || (fallbackActive && (complete != -1L || now < fallbackAt))
                    || (admitted && (workDeadline - start != 300000L || fallbackAt != fallback(start)))
                    || (!legacy && !"0".equals(fields[12]) && !"1".equals(fields[12]))
                    || (!admitted && (workDeadline != -1L || fallbackAt != -1L || candidate.length() != 0))
                    || (candidate.length() != 0 && !VIDEO_ID.matcher(candidate).matches())
                    || (restored == Phase.STARTING && (!admitted || fields[3].length() != 0))
                    || (restored == Phase.WAITING && !validIdentity(fields[2], fields[3]))
                    || (complete != -1L && (complete < start || complete > now + 1000L))) return false;
            // Old state is harmless; it does not block the next day's new session.
            if (!fields[1].equals(date(now))) return true;
            localDate = fields[1];
            runId = fields[2];
            videoId = fields[3];
            phase = restored;
            startedAtMs = start;
            expiresAtMs = end;
            completedAtMs = complete;
            startupDeadlineMs = workDeadline;
            fallbackAtMs = fallbackAt;
            candidateVideoId = candidate;
            scheduledAdmission = admitted;
            reason = fields[8];
            observerReady = false;
            observerWasReady = false;
            if ((phase == Phase.ACTIVE || phase == Phase.WAITING) && !isBannerDay(now)) {
                phase = Phase.SKIPPED;
                reason = "non_school_banner_day";
            }
            advance(now);
            return true;
        } catch (IllegalArgumentException badRecord) {
            return false;
        }
    }

    private void advance(long now) {
        if (now < 0) return;
        if ((phase == Phase.STARTING || phase == Phase.WAITING || phase == Phase.ACTIVE)
                && (now >= expiresAtMs || !localDate.equals(date(now)) || now < startedAtMs - 1000L)) {
            phase = Phase.EXPIRED;
            reason = now < startedAtMs - 1000L ? "clock_changed" : "school_window_finished";
            observerReady = false;
        }
        if (phase == Phase.STARTING && now >= fallbackAtMs && !canonicalEpisodeActive
                && canonicalObservedAtMs >= 0 && canonicalObservedAtMs <= now + 1000L
                && now - canonicalObservedAtMs <= 5000L) {
            phase = diagnostic || isBannerDay(now) ? Phase.ACTIVE : Phase.SKIPPED;
            reason = phase == Phase.ACTIVE ? "scheduled_fallback" : "non_school_banner_day";
            // The fallback is a scheduled deadline, never synthetic completion evidence.
            completedAtMs = -1L;
            candidateVideoId = "";
        }
    }

    private void clear() {
        localDate = "";
        runId = "";
        videoId = "";
        phase = Phase.INACTIVE;
        reason = "not_started";
        startedAtMs = expiresAtMs = completedAtMs = -1L;
        startupDeadlineMs = fallbackAtMs = -1L;
        candidateVideoId = "";
        scheduledAdmission = diagnostic = false;
        canonicalEpisodeActive = false;
        canonicalObservedAtMs = -1L;
        observerReady = observerWasReady = false;
    }

    public static boolean fresh(long sampledAtMs, long now) {
        return sampledAtMs >= 0 && now >= 0 && sampledAtMs <= now + 1000L
                && sampledAtMs >= now - EVENT_MAX_AGE_MS;
    }

    private static boolean validIdentity(String id, String video) {
        return id != null && video != null && RUN_ID.matcher(id).matches() && VIDEO_ID.matcher(video).matches();
    }

    private static String normalizedReason(String value, String fallback) {
        return value != null && REASON.matcher(value).matches() ? value : fallback;
    }

    private static boolean volume(int linuxCode) {
        return linuxCode == 113 || linuxCode == 114 || linuxCode == 115;
    }

    public static boolean inMorningWindow(long now) {
        if (now < 0) return false;
        Calendar c = calendar(now);
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return minute >= 7 * 60 && minute < 9 * 60 + 30;
    }

    public static long deadline(long now) {
        Calendar c = calendar(now);
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 30);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long fallback(long now) {
        Calendar c = calendar(now);
        c.set(Calendar.HOUR_OF_DAY, 8);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static String date(long now) {
        Calendar c = calendar(now);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    public static boolean isBannerDay(long now) {
        Calendar c = calendar(now);
        return c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY && !isNationalHoliday(now);
    }

    /** U.S. federal holiday dates plus the usual Friday/Monday observed dates. */
    public static boolean isNationalHoliday(long now) {
        Calendar c = calendar(now);
        int year = c.get(Calendar.YEAR);
        int month = c.get(Calendar.MONTH);
        int day = c.get(Calendar.DAY_OF_MONTH);
        int weekday = c.get(Calendar.DAY_OF_WEEK);
        int occurrence = (day - 1) / 7 + 1;
        if (weekday == Calendar.MONDAY && (
                (month == Calendar.JANUARY && occurrence == 3)
                || (month == Calendar.FEBRUARY && occurrence == 3)
                || (month == Calendar.MAY && day + 7 > c.getActualMaximum(Calendar.DAY_OF_MONTH))
                || (month == Calendar.SEPTEMBER && occurrence == 1)
                || (month == Calendar.OCTOBER && occurrence == 2))) return true;
        if (month == Calendar.NOVEMBER && weekday == Calendar.THURSDAY && occurrence == 4) return true;
        int[][] fixed = {{Calendar.JANUARY, 1}, {Calendar.JUNE, 19}, {Calendar.JULY, 4},
                {Calendar.NOVEMBER, 11}, {Calendar.DECEMBER, 25}};
        // Adjacent years cover Dec 31 observing a following year's Saturday New Year's Day.
        for (int holidayYear = year - 1; holidayYear <= year + 1; holidayYear++) {
            for (int[] holiday : fixed) {
                Calendar h = new GregorianCalendar(ZONE, Locale.ROOT);
                h.clear();
                h.set(holidayYear, holiday[0], holiday[1], 12, 0, 0);
                if (sameDate(c, h)) return true;
                int holidayWeekday = h.get(Calendar.DAY_OF_WEEK);
                if (holidayWeekday == Calendar.SATURDAY) h.add(Calendar.DAY_OF_MONTH, -1);
                else if (holidayWeekday == Calendar.SUNDAY) h.add(Calendar.DAY_OF_MONTH, 1);
                if (sameDate(c, h)) return true;
            }
        }
        return false;
    }

    private static Calendar calendar(long time) {
        Calendar c = new GregorianCalendar(ZONE, Locale.ROOT);
        c.setTimeInMillis(time);
        return c;
    }

    private static boolean sameDate(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
