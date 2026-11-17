package dev.yossi.onnkeyguard;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/** Behavioral tests runnable with a plain JDK. */
public final class SchoolBannerPolicyTest {
    private static final String RUN = "morning-20261116-070000";
    private static final String VIDEO = "abcdefghijk";
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        normalCompletionAndDeadline();
        eventIdentityAndFreshness();
        approximateCompletionIsNotEnd();
        phoneOverrideAndVolume();
        phonePausePreservesEndBanner();
        inputObserverFailure();
        restartRestoresOnlyWithFreshProof();
        cancellationSurvivesRestartAndDuplicateRuns();
        newDayAllowsNewRun();
        calendarAndHolidayFixtures();
        timeWindowsAndTimezone();
        invalidPersistenceFailsOpen();
        disabledAndExpiredSessions();
        deferredAudioRecovery();
        System.out.println("SchoolBannerPolicy: " + checks + " behavioral checks passed");
    }

    private static void normalCompletionAndDeadline() throws Exception {
        long start = time("2026-11-16 07:00:00.000");
        SchoolBannerPolicy p = begin(start);
        phase(p, start, SchoolBannerPolicy.Phase.WAITING, "start waits for actual end");
        long end = time("2026-11-16 07:43:00.000");
        check(p.ended(RUN, VIDEO, end, end), "matching actual end accepted");
        check(p.snapshot(end).protectionReady, "active banner can protect with observer proof");
        phase(p, time("2026-11-16 08:00:00.000"), SchoolBannerPolicy.Phase.ACTIVE, "old eight AM cutoff does not dismiss banner");
        phase(p, time("2026-11-16 09:29:59.999"), SchoolBannerPolicy.Phase.ACTIVE, "one millisecond before deadline remains active");
        phase(p, time("2026-11-16 09:30:00.000"), SchoolBannerPolicy.Phase.EXPIRED, "exact deadline releases");
        check(!p.snapshot(time("2026-11-16 09:30:00.000")).protectionReady, "no physical lock after deadline");
        check(!p.ended(RUN, VIDEO, end, time("2026-11-16 09:31:00.000")), "late end cannot resurrect");
    }

    private static void eventIdentityAndFreshness() throws Exception {
        long start = time("2026-11-16 07:00:00.000");
        long end = time("2026-11-16 07:45:00.000");
        SchoolBannerPolicy p = begin(start);
        check(!p.ended("previous-run", VIDEO, end, end), "wrong run rejected");
        check(!p.ended(RUN, "zyxwvutsrqp", end, end), "wrong video rejected");
        check(!p.ended(RUN, VIDEO, end - 3001L, end), "stale end rejected");
        check(!p.ended(RUN, VIDEO, end + 1001L, end), "future end rejected");
        check(!p.ended(RUN, VIDEO, start - 1L, start), "pre-enrollment sample rejected");
        check(!new SchoolBannerPolicy().ended(RUN, VIDEO, end, end), "end alone never enrolls run");
        phase(p, end, SchoolBannerPolicy.Phase.WAITING, "rejected events do not change waiting state");
        p.observeSession("bad run", VIDEO, "active", "daily_start", true, true, start);
        check(p.snapshot(end).runId.equals(RUN), "invalid identity cannot replace enrollment");
    }

    private static void approximateCompletionIsNotEnd() throws Exception {
        long start = time("2026-11-16 07:00:00.000");
        long nearEnd = time("2026-11-16 07:44:59.000");
        SchoolBannerPolicy p = begin(start);
        p.observeSession(RUN, VIDEO, "completed", "playback_complete", true, true, nearEnd);
        phase(p, nearEnd, SchoolBannerPolicy.Phase.WAITING, "position near duration does not display banner");
        check(p.ended(RUN, VIDEO, nearEnd + 1000L, nearEnd + 1000L), "real end still works after canonical approximate complete");
        SchoolBannerPolicy paused = begin(start);
        paused.observeSession(RUN, VIDEO, "phone_paused", "phone_pause", true, true, nearEnd);
        phase(paused, nearEnd, SchoolBannerPolicy.Phase.WAITING, "pause is not completion");
    }

    private static void phoneOverrideAndVolume() throws Exception {
        long start = time("2026-11-16 07:00:00.000");
        long end = time("2026-11-16 07:43:00.000");
        SchoolBannerPolicy p = begin(start);
        p.ended(RUN, VIDEO, end, end);
        p.phoneKey(115, 1, end, end);
        phase(p, end, SchoolBannerPolicy.Phase.ACTIVE, "phone volume preserves legacy behavior");
        p.phoneKey(158, 0, end, end);
        phase(p, end, SchoolBannerPolicy.Phase.ACTIVE, "key release alone does not dismiss");
        p.phoneKey(158, 1, end - 5000L, end);
        phase(p, end, SchoolBannerPolicy.Phase.ACTIVE, "stale phone event cannot dismiss");
        p.phoneKey(158, 1, end, end);
        phase(p, end, SchoolBannerPolicy.Phase.DISMISSED, "phone back dismisses banner");
        p.observeSession(RUN, VIDEO, "completed", "playback_ended", true, true, end + 1000L);
        phase(p, end + 1000L, SchoolBannerPolicy.Phase.DISMISSED, "completion resync cannot undo phone dismissal");
    }

    private static void phonePausePreservesEndBanner() throws Exception {
        long start = time("2026-11-16 07:00:00.000");
        long pause = time("2026-11-16 07:20:00.000");
        SchoolBannerPolicy p = begin(start);
        p.phoneKey(201, 1, pause, pause);
        p.observeSession(RUN, VIDEO, "phone_paused", "phone_pause", true, true, pause);
        phase(p, pause, SchoolBannerPolicy.Phase.WAITING, "pausing episode does not cancel school reminder");
        p.observeSession(RUN, VIDEO, "active", "phone_play", true, true, pause + 1000L);
        long end = time("2026-11-16 08:05:00.000");
        check(p.ended(RUN, VIDEO, end, end), "resumed episode ending later still triggers");
        SchoolBannerPolicy home = begin(start);
        home.phoneKey(172, 1, pause, pause);
        check(!home.ended(RUN, VIDEO, end, end), "phone Home during episode prevents late banner");
        SchoolBannerPolicy leave = begin(start);
        leave.observeSession(RUN, VIDEO, "phone_stopped", "phone_leave", true, true, pause);
        check(!leave.ended(RUN, VIDEO, end, end), "canonical phone leave prevents late banner");
    }

    private static void inputObserverFailure() throws Exception {
        long start = time("2026-11-16 07:00:00.000");
        long end = time("2026-11-16 07:43:00.000");
        SchoolBannerPolicy p = begin(start);
        p.ended(RUN, VIDEO, end, end);
        p.observeInputProof(false, end + 1000L);
        phase(p, end + 1000L, SchoolBannerPolicy.Phase.DISMISSED, "observer loss terminally releases active lock");
        p.observeInputProof(true, end + 2000L);
        check(!p.snapshot(end + 2000L).protectionReady, "proof recovery does not resurrect released lock");
        SchoolBannerPolicy waiting = begin(start);
        waiting.observeInputProof(false, start + 1000L);
        check(!waiting.ended(RUN, VIDEO, end, end), "observer loss while waiting prevents later lock");
    }

    private static void restartRestoresOnlyWithFreshProof() throws Exception {
        long start = time("2026-11-16 07:00:00.000");
        long end = time("2026-11-16 07:43:00.000");
        SchoolBannerPolicy original = begin(start);
        original.ended(RUN, VIDEO, end, end);
        String saved = original.save();
        SchoolBannerPolicy restored = new SchoolBannerPolicy();
        check(restored.restore(saved, end + 1000L), "active state restores within current morning");
        check(!restored.snapshot(end + 1000L).protectionReady, "disk never supplies live observer proof");
        restored.observeSession("", "", "inactive", "not_started", true, false, end + 1000L);
        phase(restored, end + 1000L, SchoolBannerPolicy.Phase.ACTIVE, "initially unproven restart preserves pending restoration");
        restored.observeInputProof(true, end + 2000L);
        check(restored.snapshot(end + 2000L).protectionReady, "fresh proof restores legitimate active banner");
        SchoolBannerPolicy afterDeadline = new SchoolBannerPolicy();
        check(afterDeadline.restore(saved, time("2026-11-16 09:30:00.000")), "expired record can be read safely");
        phase(afterDeadline, time("2026-11-16 09:30:00.000"), SchoolBannerPolicy.Phase.EXPIRED, "restart after deadline cannot show banner");
    }

    private static void cancellationSurvivesRestartAndDuplicateRuns() throws Exception {
        long start = time("2026-11-16 07:00:00.000");
        long later = time("2026-11-16 07:20:00.000");
        SchoolBannerPolicy p = begin(start);
        p.dismiss("parent_override", later);
        SchoolBannerPolicy r = new SchoolBannerPolicy();
        check(r.restore(p.save(), later + 1000L), "parent override restores");
        r.observeSession(RUN, VIDEO, "active", "daily_start", true, true, later + 2000L);
        phase(r, later + 2000L, SchoolBannerPolicy.Phase.DISMISSED, "same-run retry cannot override parent");
        r.observeSession("new-run-same-day", "zyxwvutsrqp", "active", "daily_start", true, true, later + 3000L);
        phase(r, later + 3000L, SchoolBannerPolicy.Phase.DISMISSED, "new-run retry same day cannot override parent");
        SchoolBannerPolicy d = begin(start);
        d.observeSession(RUN, VIDEO, "active", "daily_start", true, true, later);
        check(d.snapshot(later).startedAtMs == start, "duplicate enrollment retains original start");
    }

    private static void newDayAllowsNewRun() throws Exception {
        long start = time("2026-11-16 07:00:00.000");
        SchoolBannerPolicy p = begin(start);
        p.dismiss("parent_override", start + 1000L);
        long tomorrow = time("2026-11-17 07:00:00.000");
        p.observeSession("next-day-run", "zyxwvutsrqp", "active", "daily_start", true, true, tomorrow);
        phase(p, tomorrow, SchoolBannerPolicy.Phase.WAITING, "yesterday's override does not suppress new day");
        SchoolBannerPolicy clean = new SchoolBannerPolicy();
        SchoolBannerPolicy yesterday = begin(start);
        check(clean.restore(yesterday.save(), tomorrow), "old persisted record ignored safely");
        phase(clean, tomorrow, SchoolBannerPolicy.Phase.INACTIVE, "old record cannot enroll today's banner");
    }

    private static void calendarAndHolidayFixtures() throws Exception {
        String[] federal = {"2026-01-01", "2026-01-19", "2026-02-16", "2026-05-25",
                "2026-06-19", "2026-07-03", "2026-07-04", "2026-09-07", "2026-10-12",
                "2026-11-11", "2026-11-26", "2026-12-25", "2027-06-18", "2027-07-05",
                "2027-12-24", "2027-12-31", "2028-01-01"};
        for (String day : federal)
            check(SchoolBannerPolicy.isNationalHoliday(time(day + " 07:00:00.000")), "holiday fixture " + day);
        check(!SchoolBannerPolicy.isNationalHoliday(time("2026-11-27 07:00:00.000")), "day after Thanksgiving not silently added as federal holiday");
        check(!SchoolBannerPolicy.isBannerDay(time("2026-11-22 07:00:00.000")), "Sunday excluded");
        check(SchoolBannerPolicy.isBannerDay(time("2026-11-17 07:00:00.000")), "ordinary Tuesday included");
        SchoolBannerPolicy sunday = begin(time("2026-11-22 07:00:00.000"));
        phase(sunday, time("2026-11-22 07:45:00.000"), SchoolBannerPolicy.Phase.SKIPPED, "Sunday session cannot create reminder");
        SchoolBannerPolicy thanksgiving = begin(time("2026-11-26 07:00:00.000"));
        check(!thanksgiving.ended(RUN, VIDEO, time("2026-11-26 07:45:00.000"), time("2026-11-26 07:45:00.000")), "holiday completion cannot show banner");
    }

    private static void timeWindowsAndTimezone() throws Exception {
        SchoolBannerPolicy before = begin(time("2026-11-16 06:59:59.999"));
        phase(before, time("2026-11-16 06:59:59.999"), SchoolBannerPolicy.Phase.INACTIVE, "before7 never enrolls");
        SchoolBannerPolicy late = begin(time("2026-11-16 09:30:00.000"));
        phase(late, time("2026-11-16 09:30:00.000"), SchoolBannerPolicy.Phase.INACTIVE, "at930 never enrolls");
        long start = time("2026-11-16 07:00:00.000");
        SchoolBannerPolicy changed = begin(start);
        phase(changed, start - 2000L, SchoolBannerPolicy.Phase.EXPIRED, "clock moving behind session fails open");
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            check(SchoolBannerPolicy.date(time("2026-11-16 23:45:00.000")).equals("2026-11-16"), "host timezone does not change household date");
            check(SchoolBannerPolicy.deadline(time("2027-03-08 07:00:00.000")) == time("2027-03-08 09:30:00.000"), "spring DST week deadline uses local930");
            check(SchoolBannerPolicy.deadline(time("2026-11-02 07:00:00.000")) == time("2026-11-02 09:30:00.000"), "fall DST week deadline uses local930");
        } finally { TimeZone.setDefault(previous); }
    }

    private static void invalidPersistenceFailsOpen() throws Exception {
        long start = time("2026-11-16 07:00:00.000");
        String good = begin(start).save();
        check(!new SchoolBannerPolicy().restore(good.replace("WAITING", "INVALID"), start), "invalid phase rejected");
        check(!new SchoolBannerPolicy().restore(good.replace(VIDEO, "bad"), start), "invalid video rejected");
        check(!new SchoolBannerPolicy().restore(good.replace("|" + SchoolBannerPolicy.deadline(start) + "|", "|" + (SchoolBannerPolicy.deadline(start) + 1L) + "|"), start), "persisted arbitrary deadline rejected");
        check(!new SchoolBannerPolicy().restore(good.replace("|WAITING|", "|ACTIVE|"), start), "active requires completion evidence");
        check(!new SchoolBannerPolicy().restore("corrupt", start), "corrupt record rejected");
    }

    private static void disabledAndExpiredSessions() throws Exception {
        long start = time("2026-11-16 07:00:00.000");
        SchoolBannerPolicy p = begin(start);
        p.observeSession(RUN, VIDEO, "active", "daily_start", false, true, start + 1000L);
        phase(p, start + 1000L, SchoolBannerPolicy.Phase.DISMISSED, "disable releases pending reminder");
        SchoolBannerPolicy expired = begin(start);
        expired.observeSession(RUN, VIDEO, "completed", "expired", true, true, start + 3000000L);
        phase(expired, start + 3000000L, SchoolBannerPolicy.Phase.WAITING, "episode guard timeout retains bounded banner wait");
        check(!expired.snapshot(start + 3000000L).protectionReady, "waiting after episode guard timeout supplies no protection authority");
        long delayedEnd = time("2026-11-16 08:15:00.000");
        check(expired.ended(RUN, VIDEO, delayedEnd, delayedEnd), "buffered episode can activate banner after its original guard expired");
        SchoolBannerPolicy cancelledAfterExpiry = begin(start);
        cancelledAfterExpiry.observeSession(RUN, VIDEO, "completed", "expired", true, true, start + 3000000L);
        cancelledAfterExpiry.phoneKey(172, 1, delayedEnd - 1000L, delayedEnd - 1000L);
        check(!cancelledAfterExpiry.ended(RUN, VIDEO, delayedEnd, delayedEnd), "phone cancellation remains terminal after original guard timeout");
        for (int key : new int[]{158, 103, 201, 207, 164}) {
            SchoolBannerPolicy generic = begin(start);
            generic.observeSession(RUN, VIDEO, "completed", "expired", true, true, start + 3000000L);
            check("waiting_after_episode_expiry".equals(generic.snapshot(start + 3000000L).reason), "post-expiry wait is explicit");
            generic.phoneKey(key, 1, delayedEnd - 1000L, delayedEnd - 1000L);
            check(!generic.ended(RUN, VIDEO, delayedEnd, delayedEnd), "every nonvolume phone key cancels after expiry: " + key);
        }
        SchoolBannerPolicy volumeAfterExpiry = begin(start);
        volumeAfterExpiry.observeSession(RUN, VIDEO, "completed", "expired", true, true, start + 3000000L);
        volumeAfterExpiry.phoneKey(115, 1, delayedEnd - 1000L, delayedEnd - 1000L);
        check(volumeAfterExpiry.ended(RUN, VIDEO, delayedEnd, delayedEnd), "volume remains nondismissing after expiry");
        SchoolBannerPolicy lostAfterExpiry = begin(start);
        lostAfterExpiry.observeSession(RUN, VIDEO, "completed", "expired", true, true, start + 3000000L);
        lostAfterExpiry.observeInputProof(false, delayedEnd - 1000L);
        check(!lostAfterExpiry.ended(RUN, VIDEO, delayedEnd, delayedEnd), "observer loss remains terminal after original guard timeout");
        SchoolBannerPolicy tooLate = begin(start);
        tooLate.observeSession(RUN, VIDEO, "completed", "expired", true, true, start + 3000000L);
        long afterDeadline = time("2026-11-16 09:30:00.000");
        check(!tooLate.ended(RUN, VIDEO, afterDeadline, afterDeadline), "late end cannot extend banner wait beyond930");
        SchoolBannerPolicy actual = begin(start);
        actual.observeSession(RUN, VIDEO, "completed", "playback_ended", true, true, start + 3000000L);
        phase(actual, start + 3000000L, SchoolBannerPolicy.Phase.ACTIVE, "canonical true ended transition activates");
    }

    private static void deferredAudioRecovery() {
        SchoolBannerPolicy.AudioRecovery recovery = new SchoolBannerPolicy.AudioRecovery();
        recovery.gained(1000L);
        recovery.lost(2000L);
        check(recovery.dueAtMs() == 5500L, "audio recovery waits beyond valid phone delivery window");
        recovery.lost(3000L);
        check(recovery.dueAtMs() == 5500L, "repeated focus callbacks coalesce without a second task");
        check(!recovery.claim(5499L, true), "audio recovery cannot run early");
        check(recovery.claim(5500L, true), "first due audio recovery claimed once");
        check(!recovery.claim(5500L, true), "claimed recovery cannot replay");
        recovery.lost(5500L);
        check(recovery.claim(9000L, true), "one bounded retry after failed first recovery");
        recovery.lost(9000L);
        check(recovery.exhausted() && recovery.dueAtMs() < 0, "two attempts exhaust same incident");
        check(!recovery.claim(20000L, true), "exhausted incident cannot spin");
        recovery.gained(20000L);
        recovery.lost(30000L);
        check(recovery.attempts() == 0 && recovery.dueAtMs() == 33500L, "stable focus starts a separate bounded incident");
        check(!recovery.claim(33500L, false), "parent dismissal cancels pending work before STOP");
        check(recovery.dueAtMs() < 0 && recovery.attempts() == 0, "dismissal clears pending recovery");
        recovery.lost(40000L);
        recovery.gained(41000L);
        check(recovery.dueAtMs() < 0, "spontaneous focus restoration cancels unnecessary STOP");
        recovery.lost(42000L);
        recovery.cancel();
        check(!recovery.claim(50000L, true), "service detach cancels pending recovery");
    }

    private static SchoolBannerPolicy begin(long now) {
        SchoolBannerPolicy p = new SchoolBannerPolicy();
        p.observeSession(RUN, VIDEO, "active", "daily_start", true, true, now);
        return p;
    }

    private static long time(String value) throws ParseException {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
        return format.parse(value).getTime();
    }

    private static void phase(SchoolBannerPolicy policy, long now, SchoolBannerPolicy.Phase expected, String message) {
        check(policy.snapshot(now).phase == expected, message + ": expected " + expected + ", got " + policy.snapshot(now).phase);
    }

    private static void check(boolean truth, String message) {
        checks++;
        if (!truth) throw new AssertionError(message);
    }
}
