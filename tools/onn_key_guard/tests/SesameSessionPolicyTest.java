package dev.yossi.onnkeyguard;

import dev.yossi.onnkeyguard.SesameSessionPolicy.BeginResult;
import dev.yossi.onnkeyguard.SesameSessionPolicy.Phase;
import dev.yossi.onnkeyguard.SesameSessionPolicy.PlaybackPhase;
import dev.yossi.onnkeyguard.SesameSessionPolicy.Reason;
import dev.yossi.onnkeyguard.SesameSessionPolicy.RestoreResult;
import dev.yossi.onnkeyguard.SesameSessionPolicy.Snapshot;

/** Real state-machine transitions without Android or device substitutes. */
public final class SesameSessionPolicyTest {
    private static final String VIDEO = "AbcD_12-xyz";
    private static final String OTHER_VIDEO = "ZyxW_98-cba";
    private static final String TARGET = "org.smarttube.stable";
    private static int assertions;

    public static void main(String[] args) {
        validationAndFiniteDeadline();
        noPhoneCannotStopSession();
        phonePowerAndHomeOverride();
        volumeDoesNotAuthorizeShutdown();
        explicitPauseAndResume();
        genericKeyObservationWindow();
        completionRequiresExactVideoAndEvidence();
        phoneChangeToKnownDifferentVideo();
        duplicatesPreserveStopsAndDeadlines();
        expiredRunsReleaseAndNewRunsStart();
        assistantOverrideIsTerminal();
        immutableSnapshotsAndUnknownValues();
        restoreActiveWithoutExtendingDeadlineOrPhoneIntent();
        restorePausedAndTerminalSessions();
        restoreExpiredRetainsRunIdentity();
        restoreRejectsCorruptOrUnboundedState();
        restoreSanitizesOptionalObservations();
        delayedGenericPauseAndMissingSample();
        stalePlayingCannotResolveGenericIntent();
        boundedPreparedTestExpires();
        observerAndPrestartStopsAreTerminal();
        System.out.println("PASS SesameSessionPolicyTest: " + assertions + " assertions");
    }

    private static void validationAndFiniteDeadline() {
        SesameSessionPolicy p = new SesameSessionPolicy();
        equal(Phase.INACTIVE, p.status(0).phase, "starts inactive");
        truth(!p.needsRecovery(0) && !p.shouldGrab(0), "inactive does nothing");
        for (String id : new String[] {"", "bad id", "../outside", null}) {
            equal(BeginResult.REJECTED, p.begin(id, VIDEO, 100, 1000), "invalid run ID");
        }
        for (String video : new String[] {"", "short", "abcdefghijkl", "bad/video!!", null}) {
            equal(BeginResult.REJECTED, p.begin("run", video, 100, 1000), "invalid video ID");
        }
        equal(BeginResult.REJECTED, p.begin("run", VIDEO, 0, 1000), "zero duration");
        equal(BeginResult.REJECTED, p.begin("run", VIDEO, 21601, 1000), "too long");
        equal(BeginResult.REJECTED, p.begin("run", VIDEO, 100, -1), "negative clock");
        equal(BeginResult.REJECTED, p.begin("run", VIDEO, 100, Long.MAX_VALUE), "overflow");
        equal(BeginResult.STARTED, p.begin("daily:2026-11-10", VIDEO, 100, 1000), "valid start");
        equal(401000L, p.status(1000).expiresAtMs, "duration plus five minute grace");
        equal(100, p.status(1000).durationSeconds, "requested duration retained");
        equal(BeginResult.STARTED, p.begin("six-hour-run", VIDEO, 21600, 2000), "max duration");
        equal(21602000L, p.status(2000).expiresAtMs, "total lifetime capped at six hours");
    }

    private static void noPhoneCannotStopSession() {
        SesameSessionPolicy p = active();
        p.observeForeground("com.google.android.apps.tv.launcherx", TARGET, 1200);
        p.observePlayback(VIDEO, 10, 100, PlaybackPhase.PAUSED, 1300);
        equal(Phase.ACTIVE, p.status(1300).phase, "physical or voice disturbance cannot authorize stop");
        truth(p.needsRecovery(1300) && p.shouldGrab(1300), "active protection remains");
        p.observePlayback(VIDEO, Double.NaN, Double.NaN, PlaybackPhase.UNKNOWN, 1400);
        p.observeForeground(null, TARGET, 1500);
        p.observeForeground("", TARGET, 1500);
        equal(Phase.ACTIVE, p.status(1500).phase, "missing state is not a stop");
    }

    private static void phonePowerAndHomeOverride() {
        for (int key : new int[] {116, 142, 172}) {
            SesameSessionPolicy p = active();
            p.phoneKey(key, 1, 1100);
            equal(Phase.PHONE_STOPPED, p.status(1100).phase, "phone stop key " + key);
            truth(!p.needsRecovery(1100) && !p.shouldGrab(1100), "phone stop releases protections");
            p.phoneKey(207, 1, 1200);
            p.observePlayback(VIDEO, 11, 100, PlaybackPhase.PLAYING, 1250);
            equal(Phase.PHONE_STOPPED, p.status(1250).phase, "phone play cannot revive a stopped run");
        }
        SesameSessionPolicy navigationHome = active();
        navigationHome.phoneKey(102, 1, 1100);
        equal(Phase.ACTIVE, navigationHome.status(1100).phase, "Linux 102 not presumed launcher HOME");
        SesameSessionPolicy release = active();
        release.phoneKey(116, 0, 1100);
        release.phoneKey(116, 2, 1200);
        equal(Phase.ACTIVE, release.status(1200).phase, "release/repeat cannot create stop intent");
    }

    private static void volumeDoesNotAuthorizeShutdown() {
        for (int key : new int[] {113, 114, 115}) {
            SesameSessionPolicy p = active();
            p.phoneKey(key, 1, 1100);
            p.observeForeground("com.google.android.apps.tv.launcherx", TARGET, 1200);
            p.observePlayback(VIDEO, 5, 100, PlaybackPhase.PAUSED, 1250);
            equal(Phase.ACTIVE, p.status(1250).phase, "volume does not exempt voice-off " + key);
        }
    }

    private static void explicitPauseAndResume() {
        SesameSessionPolicy p = active();
        long deadline = p.status(1000).expiresAtMs;
        p.phoneKey(164, 1, 1100);
        equal(Phase.PHONE_PAUSED, p.status(1100).phase, "toggle pauses");
        truth(!p.needsRecovery(1100) && p.shouldGrab(1100), "pause keeps physical guard without forcing play");
        p.phoneKey(164, 2, 1110);
        p.phoneKey(164, 0, 1120);
        p.observePlayback(VIDEO, 10, 100, PlaybackPhase.PLAYING, 1150);
        equal(Phase.PHONE_PAUSED, p.status(1150).phase, "delayed playing does not undo pause key");
        p.observePlayback(VIDEO, 10, 100, PlaybackPhase.UNKNOWN, 1160);
        equal(Phase.PHONE_PAUSED, p.status(1160).phase, "unknown preserves pause");
        p.phoneKey(164, 1, 1200);
        equal(Phase.ACTIVE, p.status(1200).phase, "second press resumes");
        p.observePlayback(VIDEO, 10, 100, PlaybackPhase.PAUSED, 1250);
        equal(Phase.ACTIVE, p.status(1250).phase, "delayed paused does not undo play key");
        p.phoneKey(201, 1, 1300);
        equal(Phase.PHONE_PAUSED, p.status(1300).phase, "explicit pause");
        p.observePlayback(VIDEO, 10, 100, PlaybackPhase.PLAYING, 5000);
        equal(Phase.PHONE_PAUSED, p.status(5000).phase, "old intent cannot resume pause");
        p.phoneKey(207, 1, 5100);
        equal(Phase.ACTIVE, p.status(5100).phase, "explicit play");
        equal(deadline, p.status(5100).expiresAtMs, "pause/resume never extends deadline");
    }

    private static void genericKeyObservationWindow() {
        SesameSessionPolicy p = active();
        p.phoneKey(28, 1, 1100);
        p.observePlayback(VIDEO, 10, 100, PlaybackPhase.PAUSED, 1200);
        equal(Phase.PHONE_PAUSED, p.status(1200).phase, "phone UI pause recognized");
        p.phoneKey(28, 1, 1300);
        p.observeForeground(TARGET, TARGET, 1350);
        p.observePlayback(VIDEO, 11, 100, PlaybackPhase.PLAYING, 1400);
        equal(Phase.ACTIVE, p.status(1400).phase, "phone UI play recognized");
        p.phoneKey(28, 1, 1500);
        p.observeForeground("com.netflix.ninja", TARGET, 9500);
        equal(Phase.PHONE_STOPPED, p.status(9500).phase, "departure at window boundary allowed");

        SesameSessionPolicy expired = active();
        expired.phoneKey(28, 1, 1100);
        expired.observeForeground("com.netflix.ninja", TARGET, 9101);
        expired.observePlayback(VIDEO, 10, 100, PlaybackPhase.PAUSED, 9102);
        equal(Phase.PHONE_STOPPED, expired.status(9102).phase, "unresolved expired intent releases protection");
        equal("phone_intent_unresolved", expired.status(9102).reason, "missing evidence cannot clear intent silently");
        SesameSessionPolicy future = active();
        future.phoneKey(28, 1, 5000);
        future.observeForeground("com.netflix.ninja", TARGET, 4999);
        equal(Phase.ACTIVE, future.status(4999).phase, "future event not considered recent");
    }

    private static void completionRequiresExactVideoAndEvidence() {
        SesameSessionPolicy p = active();
        p.observePlayback(OTHER_VIDEO, 100, 100, PlaybackPhase.ENDED, 1100);
        equal(Phase.ACTIVE, p.status(1100).phase, "other video cannot complete this run");
        p.observePlayback(VIDEO, 100, 100, PlaybackPhase.UNKNOWN, 1200);
        equal(Phase.ACTIVE, p.status(1200).phase, "unknown phase cannot confirm completion");
        p.observePlayback(VIDEO, 99, Double.NaN, PlaybackPhase.PLAYING, 1300);
        equal(Phase.ACTIVE, p.status(1300).phase, "configured or stale duration cannot complete");
        p.observePlayback(VIDEO, Double.NaN, 100, PlaybackPhase.PLAYING, 1400);
        equal(Phase.ACTIVE, p.status(1400).phase, "stale position cannot complete");
        p.observePlayback(VIDEO, 99, 100, PlaybackPhase.PLAYING, 1500);
        equal(Phase.COMPLETED, p.status(1500).phase, "confirmed end position completes");
        equal("playback_complete", p.status(1500).reason, "completion reason");
        truth(!p.needsRecovery(1500) && !p.shouldGrab(1500), "completed run releases protection");

        SesameSessionPolicy ended = active();
        ended.observePlayback(VIDEO, Double.NaN, Double.NaN, PlaybackPhase.ENDED, 1100);
        equal(Phase.COMPLETED, ended.status(1100).phase, "exact-video ended event sufficient");
        equal("playback_ended", ended.status(1100).reason, "ended reason");

        SesameSessionPolicy shortClip = new SesameSessionPolicy();
        shortClip.begin("short", VIDEO, 1, 1000);
        shortClip.observePlayback(VIDEO, 0, 1, PlaybackPhase.PLAYING, 1100);
        equal(Phase.ACTIVE, shortClip.status(1100).phase, "zero position does not instantly complete one-second clip");
    }

    private static void duplicatesPreserveStopsAndDeadlines() {
        SesameSessionPolicy p = active();
        long deadline = p.status(1000).expiresAtMs;
        p.phoneKey(116, 1, 1100);
        equal(BeginResult.DUPLICATE, p.begin("daily-run", VIDEO, 200, 1200), "retry recognized");
        equal(Phase.PHONE_STOPPED, p.status(1200).phase, "retry cannot undo phone override");
        equal(deadline, p.status(1200).expiresAtMs, "retry cannot extend deadline");
        equal(100, p.status(1200).durationSeconds, "retry cannot rewrite initial duration");
        equal(BeginResult.REJECTED, p.begin("daily-run", OTHER_VIDEO, 100, 1300), "same run conflicting video rejected");
        equal(VIDEO, p.status(1300).videoId, "rejected data preserves video");

        SesameSessionPolicy paused = active();
        paused.phoneKey(201, 1, 1100);
        paused.begin("daily-run", VIDEO, 100, 1200);
        equal(Phase.PHONE_PAUSED, paused.status(1200).phase, "retry preserves pause");
    }

    private static void phoneChangeToKnownDifferentVideo() {
        SesameSessionPolicy p = active();
        p.phoneKey(28, 1, 1100);
        p.observePlayback(null, 0, Double.NaN, PlaybackPhase.UNKNOWN, 1150);
        p.observePlayback("", 0, Double.NaN, PlaybackPhase.UNKNOWN, 1160);
        p.observePlayback("unknown", 0, Double.NaN, PlaybackPhase.UNKNOWN, 1170);
        equal(Phase.ACTIVE, p.status(1170).phase, "unknown identity cannot authorize leaving video");
        p.observePlayback(OTHER_VIDEO, 0, 200, PlaybackPhase.PLAYING, 1200);
        equal(Phase.PHONE_STOPPED, p.status(1200).phase, "phone may select a different video in same app");
        equal("phone_leave", p.status(1200).reason, "different video is phone departure");

        SesameSessionPolicy volume = active();
        volume.phoneKey(115, 1, 1100);
        volume.observePlayback(OTHER_VIDEO, 0, 200, PlaybackPhase.PLAYING, 1200);
        equal(Phase.ACTIVE, volume.status(1200).phase, "volume cannot authorize a different video");

        SesameSessionPolicy stale = active();
        stale.phoneKey(28, 1, 1100);
        stale.observePlayback(OTHER_VIDEO, 0, 200, PlaybackPhase.PLAYING, 9101);
        equal(Phase.PHONE_STOPPED, stale.status(9101).phase, "unresolved stale intent cannot retain protection");
        equal("phone_intent_unresolved", stale.status(9101).reason, "late video cannot be misattributed");

        SesameSessionPolicy shorterActualDuration = new SesameSessionPolicy();
        shorterActualDuration.begin("actual-duration", VIDEO, 1000, 1000);
        shorterActualDuration.observePlayback(VIDEO, 99, 100, PlaybackPhase.PLAYING, 1100);
        equal(Phase.COMPLETED, shorterActualDuration.status(1100).phase,
                "actual duration confirms completion independently of estimated candidate duration");
    }

    private static void expiredRunsReleaseAndNewRunsStart() {
        SesameSessionPolicy p = active();
        p.phoneKey(201, 1, 1100);
        long deadline = p.status(1100).expiresAtMs;
        truth(p.shouldGrab(deadline - 1), "paused run protected before deadline");
        truth(!p.shouldGrab(deadline) && !p.needsRecovery(deadline), "expiry releases all protections");
        equal(Phase.COMPLETED, p.status(deadline).phase, "expired phase");
        equal("expired", p.status(deadline).reason, "expired reason");
        equal(BeginResult.DUPLICATE, p.begin("daily-run", VIDEO, 100, deadline + 1), "expired duplicate remains duplicate");
        equal(Phase.COMPLETED, p.status(deadline + 1).phase, "old run cannot rearm");
        equal(BeginResult.STARTED, p.begin("next-daily-run", VIDEO, 100, deadline + 100), "next daily run starts");
        equal(Phase.ACTIVE, p.status(deadline + 100).phase, "new run active");
        truth(p.status(deadline + 100).positionSeconds == null, "new run clears prior observed position");
    }

    private static void assistantOverrideIsTerminal() {
        SesameSessionPolicy p = active();
        p.override(1100);
        equal(Phase.PHONE_STOPPED, p.status(1100).phase, "assistant override stops");
        equal("assistant_override", p.status(1100).reason, "assistant reason exact");
        p.observePlayback(VIDEO, 5, 100, PlaybackPhase.PLAYING, 1200);
        p.phoneKey(207, 1, 1250);
        p.begin("daily-run", VIDEO, 100, 1300);
        equal(Phase.PHONE_STOPPED, p.status(1300).phase, "same run stays overridden");
        equal(BeginResult.STARTED, p.begin("assistant-new-run", VIDEO, 100, 1400), "distinct explicit run may rearm");
    }

    private static void immutableSnapshotsAndUnknownValues() {
        SesameSessionPolicy p = active();
        Snapshot before = p.status(1000);
        truth(before.positionSeconds == null && before.observedDurationSeconds == null, "unknown observations remain null");
        p.observePlayback(VIDEO, 20, 100, PlaybackPhase.PLAYING, 1100);
        Snapshot after = p.status(1100);
        equal(Double.valueOf(20), after.positionSeconds, "real position retained");
        equal(Double.valueOf(100), after.observedDurationSeconds, "real duration retained");
        truth(before.positionSeconds == null, "earlier snapshot is immutable");
        p.observePlayback(VIDEO, Double.POSITIVE_INFINITY, Double.NaN, PlaybackPhase.UNKNOWN, 1200);
        equal(Double.valueOf(20), p.status(1200).positionSeconds, "invalid values do not replace last observation");
        equal(Double.valueOf(100), p.status(1200).observedDurationSeconds, "invalid duration not fabricated");
        p.phoneKey(201, 1, 1300);
        equal(Phase.ACTIVE, before.phase, "snapshot phase unchanged by later transition");
    }

    private static SesameSessionPolicy active() {
        SesameSessionPolicy p = new SesameSessionPolicy();
        equal(BeginResult.STARTED, p.begin("daily-run", VIDEO, 100, 1000), "start fixture");
        return p;
    }

    private static void restoreActiveWithoutExtendingDeadlineOrPhoneIntent() {
        SesameSessionPolicy original = active();
        original.phoneKey(28, 1, 1100);
        original.observePlayback(VIDEO, 20, 100, PlaybackPhase.PLAYING, 1150);
        Snapshot saved = original.status(1150);
        SesameSessionPolicy restored = new SesameSessionPolicy();
        equal(RestoreResult.RESTORED, restore(restored, saved, 1200), "active restored");
        Snapshot status = restored.status(1200);
        equal(saved.id, status.id, "run ID restored");
        equal(saved.videoId, status.videoId, "video restored");
        equal(saved.expiresAtMs, status.expiresAtMs, "original deadline copied exactly");
        equal(saved.durationSeconds, status.durationSeconds, "initial duration restored");
        equal(saved.positionSeconds, status.positionSeconds, "observed position restored");
        equal(saved.observedDurationSeconds, status.observedDurationSeconds, "observed duration restored");
        truth(restored.needsRecovery(1200) && restored.shouldGrab(1200), "active protection restored");
        restored.observeForeground("com.netflix.ninja", TARGET, 1250);
        restored.observePlayback(VIDEO, 20, 100, PlaybackPhase.PAUSED, 1300);
        equal(Phase.ACTIVE, restored.status(1300).phase, "pre-restart phone key intent is never restored");
        equal(BeginResult.DUPLICATE, restored.begin(saved.id, VIDEO, 200, 1400), "restored run remains duplicate");
        equal(saved.expiresAtMs, restored.status(1400).expiresAtMs, "duplicate cannot extend restored deadline");
        equal(RestoreResult.REJECTED, restore(restored, saved, 1500), "existing memory cannot be rolled back");
    }

    private static void restorePausedAndTerminalSessions() {
        SesameSessionPolicy paused = active();
        paused.phoneKey(201, 1, 1100);
        SesameSessionPolicy restoredPause = new SesameSessionPolicy();
        equal(RestoreResult.RESTORED, restore(restoredPause, paused.status(1100), 1200), "paused restored");
        equal(Phase.PHONE_PAUSED, restoredPause.status(1200).phase, "pause preserved");
        truth(!restoredPause.needsRecovery(1200) && restoredPause.shouldGrab(1200), "restored pause retains only input guard");
        restoredPause.observePlayback(VIDEO, 10, 100, PlaybackPhase.PLAYING, 1300);
        equal(Phase.PHONE_PAUSED, restoredPause.status(1300).phase, "restore never invents resume intent");
        restoredPause.phoneKey(28, 1, 1400);
        restoredPause.observeForeground(TARGET, TARGET, 1450);
        restoredPause.observePlayback(VIDEO, 11, 100, PlaybackPhase.PLAYING, 1500);
        equal(Phase.ACTIVE, restoredPause.status(1500).phase, "new post-restart phone intent works");

        SesameSessionPolicy stopped = active();
        Snapshot activeSnapshot = stopped.status(1000);
        stopped.phoneKey(116, 1, 1100);
        SesameSessionPolicy restoredStop = new SesameSessionPolicy();
        equal(RestoreResult.RESTORED, restore(restoredStop, stopped.status(1100), 1200), "stopped restored");
        equal(Phase.PHONE_STOPPED, restoredStop.status(1200).phase, "stop preserved");
        equal("phone_power", restoredStop.status(1200).reason, "stop reason preserved");
        truth(!restoredStop.shouldGrab(1200) && !restoredStop.needsRecovery(1200), "restored stop stays released");
        equal(BeginResult.DUPLICATE, restoredStop.begin("daily-run", VIDEO, 100, 1300), "restart cannot erase stopped run ID");
        equal(Phase.PHONE_STOPPED, restoredStop.status(1300).phase, "duplicate remains stopped");
        equal(RestoreResult.REJECTED, restore(restoredStop, activeSnapshot, 1400), "stale persisted ACTIVE cannot resurrect stop");

        SesameSessionPolicy completed = active();
        completed.observePlayback(VIDEO, Double.NaN, Double.NaN, PlaybackPhase.ENDED, 1100);
        SesameSessionPolicy restoredComplete = new SesameSessionPolicy();
        equal(RestoreResult.RESTORED, restore(restoredComplete, completed.status(1100), 1200), "completed restored");
        equal(Phase.COMPLETED, restoredComplete.status(1200).phase, "completion preserved");
        equal("playback_ended", restoredComplete.status(1200).reason, "completion reason preserved");
        truth(!restoredComplete.shouldGrab(1200), "completed restore releases guard");
        equal(BeginResult.STARTED, restoredComplete.begin("next-day", VIDEO, 100, 1300), "new run may follow restored completion");
    }

    private static void restoreExpiredRetainsRunIdentity() {
        SesameSessionPolicy original = active();
        Snapshot saved = original.status(1100);
        SesameSessionPolicy restored = new SesameSessionPolicy();
        equal(RestoreResult.EXPIRED, restore(restored, saved, saved.expiresAtMs), "expired snapshot does not restore live");
        equal(Phase.COMPLETED, restored.status(saved.expiresAtMs).phase, "expired state retained as completed");
        equal("expired", restored.status(saved.expiresAtMs).reason, "expired restore reason");
        equal(saved.id, restored.status(saved.expiresAtMs).id, "expired run ID retained");
        equal(saved.expiresAtMs, restored.status(saved.expiresAtMs).expiresAtMs, "expired original deadline retained");
        truth(!restored.needsRecovery(saved.expiresAtMs) && !restored.shouldGrab(saved.expiresAtMs), "expired restore releases guard");
        equal(BeginResult.DUPLICATE, restored.begin(saved.id, VIDEO, 100, saved.expiresAtMs + 1), "expired ID cannot rearm");
        equal(Phase.COMPLETED, restored.status(saved.expiresAtMs + 1).phase, "expired duplicate stays completed");
    }

    private static void restoreRejectsCorruptOrUnboundedState() {
        equal(Reason.ASSISTANT_OVERRIDE, Reason.fromValue("assistant_override"), "reason parser exact");
        truth(Reason.fromValue("bogus") == null && Reason.fromValue(null) == null, "unknown reason rejected");
        SesameSessionPolicy p = new SesameSessionPolicy();
        equal(RestoreResult.REJECTED, p.restore("", VIDEO, Phase.ACTIVE, 401000, 100,
                null, null, Reason.DAILY_START, 1200), "bad saved ID");
        equal(RestoreResult.REJECTED, p.restore("daily-run", "bad", Phase.ACTIVE, 401000, 100,
                null, null, Reason.DAILY_START, 1200), "bad saved video");
        equal(RestoreResult.REJECTED, p.restore("daily-run", VIDEO, Phase.ACTIVE, 401000, 0,
                null, null, Reason.DAILY_START, 1200), "bad saved duration");
        equal(RestoreResult.REJECTED, p.restore("daily-run", VIDEO, Phase.ACTIVE, -1, 100,
                null, null, Reason.DAILY_START, 1200), "negative saved deadline");
        equal(RestoreResult.REJECTED, p.restore("daily-run", VIDEO, Phase.ACTIVE, 401000, 100,
                null, null, Reason.DAILY_START, -1), "bad current time");
        equal(RestoreResult.REJECTED, p.restore("daily-run", VIDEO, Phase.INACTIVE, 401000, 100,
                null, null, Reason.NOT_STARTED, 1200), "inactive is not restorable session");
        equal(RestoreResult.REJECTED, p.restore("daily-run", VIDEO, Phase.ACTIVE, 401000, 100,
                null, null, Reason.PHONE_PAUSE, 1200), "inconsistent phase/reason rejected");
        equal(RestoreResult.REJECTED, p.restore("daily-run", VIDEO, Phase.ACTIVE, 401000, 100,
                null, null, null, 1200), "missing reason rejected");
        equal(RestoreResult.REJECTED, p.restore("daily-run", VIDEO, Phase.ACTIVE, 21601201L, 21600,
                null, null, Reason.DAILY_START, 1200), "more than six hours future rejected");
        equal(RestoreResult.REJECTED, p.restore("daily-run", VIDEO, Phase.ACTIVE, Long.MAX_VALUE, 21600,
                null, null, Reason.DAILY_START, 1200), "overflow-size deadline rejected");
        equal(RestoreResult.REJECTED, p.restore("daily-run", VIDEO, Phase.ACTIVE, 401201L, 100,
                null, null, Reason.DAILY_START, 1200), "remaining time cannot exceed original configured lifetime");
        equal(Phase.INACTIVE, p.status(1200).phase, "invalid restores leave pristine policy unchanged");
        equal(RestoreResult.RESTORED, p.restore("daily-run", VIDEO, Phase.ACTIVE, 21601200L, 21600,
                null, null, Reason.DAILY_START, 1200), "exact six hour future boundary accepted");
        equal(21601200L, p.status(1200).expiresAtMs, "boundary copied without extension");
    }

    private static void restoreSanitizesOptionalObservations() {
        SesameSessionPolicy p = new SesameSessionPolicy();
        equal(RestoreResult.RESTORED, p.restore("daily-run", VIDEO, Phase.ACTIVE, 401000, 100,
                Double.valueOf(Double.NaN), Double.valueOf(Double.POSITIVE_INFINITY),
                Reason.DAILY_START, 1200), "invalid optional observations do not destroy valid session");
        truth(p.status(1200).positionSeconds == null && p.status(1200).observedDurationSeconds == null,
                "invalid observations represented as missing");
        SesameSessionPolicy negative = new SesameSessionPolicy();
        negative.restore("daily-run", VIDEO, Phase.PHONE_STOPPED, 401000, 100,
                Double.valueOf(-1), Double.valueOf(0), Reason.ASSISTANT_OVERRIDE, 1200);
        truth(negative.status(1200).positionSeconds == null && negative.status(1200).observedDurationSeconds == null,
                "negative or zero invalid observations are not invented");
        equal("assistant_override", negative.status(1200).reason, "assistant override restores exact reason");
    }

    private static RestoreResult restore(SesameSessionPolicy policy, Snapshot saved, long now) {
        return policy.restore(saved.id, saved.videoId, saved.phase, saved.expiresAtMs,
                saved.durationSeconds, saved.positionSeconds, saved.observedDurationSeconds,
                Reason.fromValue(saved.reason), now);
    }

    private static void delayedGenericPauseAndMissingSample() {
        SesameSessionPolicy delayed = active();
        delayed.phoneKey(28, 1, 1100);
        truth(!delayed.needsRecovery(1200), "unresolved select never authorizes recovery");
        delayed.observeForeground(TARGET, TARGET, 5050);
        delayed.observePlayback(VIDEO, 20, 100, PlaybackPhase.PAUSED, 5100);
        equal(Phase.PHONE_PAUSED, delayed.status(5100).phase, "four-second delayed pause remains phone choice");
        truth(!delayed.needsRecovery(12000), "delayed pause is preserved beyond grace");

        SesameSessionPolicy missing = active();
        missing.phoneKey(28, 1, 1100);
        missing.observePlayback(VIDEO, Double.NaN, Double.NaN, PlaybackPhase.UNKNOWN, 5100);
        truth(!missing.needsRecovery(9000), "one missing four-second sample keeps intent pending");
        equal(Phase.PHONE_STOPPED, missing.status(9101).phase, "unresolved evidence gap releases protection");
        equal("phone_intent_unresolved", missing.status(9101).reason, "gap reason explicit");
        missing.observePlayback(VIDEO, 20, 100, PlaybackPhase.PAUSED, 10100);
        missing.observeForeground(TARGET, TARGET, 10200);
        missing.observePlayback(VIDEO, 21, 100, PlaybackPhase.PLAYING, 10300);
        equal(Phase.PHONE_STOPPED, missing.status(10300).phase, "late evidence never rearms ambiguous run");

        SesameSessionPolicy navigation = active();
        navigation.phoneKey(106, 1, 1100);
        navigation.observePlayback(VIDEO, 24, 100, PlaybackPhase.PLAYING, 5150);
        truth(!navigation.needsRecovery(5200), "playing alone without canonical foreground does not resolve");
        navigation.observeForeground(TARGET, TARGET, 5300);
        truth(!navigation.needsRecovery(6000), "positive evidence keeps full eight-second courtesy window");
        truth(navigation.needsRecovery(9101), "positive post-key playing and foreground resolve after grace");
        equal(Phase.ACTIVE, navigation.status(9101).phase, "observed navigation permits continued protection");
    }

    private static void stalePlayingCannotResolveGenericIntent() {
        SesameSessionPolicy early = active();
        early.phoneKey(28, 1, 1100);
        early.observeForeground(TARGET, TARGET, 1200);
        early.observePlayback(VIDEO, 12, 100, PlaybackPhase.PLAYING, 1300);
        equal(Phase.PHONE_STOPPED, early.status(9101).phase, "early playing followed by missing samples cannot resolve intent");
        truth(!early.needsRecovery(9101), "old playing never authorizes blind resume");

        SesameSessionPolicy unknown = active();
        unknown.phoneKey(28, 1, 1100);
        unknown.observeForeground(TARGET, TARGET, 7100);
        unknown.observePlayback(VIDEO, 18, 100, PlaybackPhase.PLAYING, 7200);
        unknown.observePlayback(VIDEO, Double.NaN, Double.NaN, PlaybackPhase.UNKNOWN, 8000);
        equal(Phase.PHONE_STOPPED, unknown.status(9101).phase, "later unknown invalidates earlier recent playing");
        equal("phone_intent_unresolved", unknown.status(9101).reason, "unknown sample preserves explicit failure reason");
    }

    private static void boundedPreparedTestExpires() {
        SesameSessionPolicy test = new SesameSessionPolicy();
        equal(RestoreResult.RESTORED, test.restore("test-bounded", VIDEO, Phase.ACTIVE,
                11000, 10, null, null, Reason.DAILY_START, 1000), "explicit test uses exact deadline");
        truth(test.shouldGrabBoundedTest(1000), "prepared test authorizes only its short live window");
        truth(test.shouldGrabBoundedTest(10999), "test active just before exact expiry");
        truth(!test.shouldGrabBoundedTest(11000), "test releases at deadline without external cleanup");
        equal(Phase.COMPLETED, test.status(11000).phase, "bounded test naturally completes");
        SesameSessionPolicy ordinary = new SesameSessionPolicy();
        ordinary.begin("ordinary", VIDEO, 10, 1000);
        truth(!ordinary.shouldGrabBoundedTest(1000), "ordinary recovery grace is not bounded test authorization");
    }

    private static void observerAndPrestartStopsAreTerminal() {
        SesameSessionPolicy lost = active();
        lost.inputObserverLost(1100);
        equal(Phase.PHONE_STOPPED, lost.status(1100).phase, "lost source observer releases protection");
        equal("input_observer_lost", lost.status(1100).reason, "observer reason exact");
        truth(!lost.needsRecovery(1100) && !lost.shouldGrab(1100), "blind session cannot enforce");
        equal(BeginResult.DUPLICATE, lost.begin("daily-run", VIDEO, 100, 1200), "healthy observer cannot restart same run");
        equal(Phase.PHONE_STOPPED, lost.status(1200).phase, "duplicate stays terminal after observer returns");
        SesameSessionPolicy restored = new SesameSessionPolicy();
        equal(RestoreResult.RESTORED, restore(restored, lost.status(1200), 1300), "terminal observer reason persists restart");
        equal("input_observer_lost", restored.status(1300).reason, "restored terminal observer reason");

        SesameSessionPolicy paused = active();
        paused.phoneKey(201, 1, 1100);
        paused.inputObserverLost(1200);
        equal(Phase.PHONE_STOPPED, paused.status(1200).phase, "paused session also releases blind input guard");

        SesameSessionPolicy prestart = active();
        prestart.recentPhoneBeforeStart(1100);
        equal("phone_prestart_intent", prestart.status(1100).reason, "recent inactive phone intent declines enrollment");
        truth(!prestart.shouldGrab(1100), "declined enrollment never arms");
        equal(BeginResult.DUPLICATE, prestart.begin("daily-run", VIDEO, 100, 1200), "retry cannot forget prestart phone intent");
    }

    private static void truth(boolean condition, String label) {
        assertions++;
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
