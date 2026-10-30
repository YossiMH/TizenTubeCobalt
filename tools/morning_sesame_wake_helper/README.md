# Morning Sesame version 7


## Windows build workspace

The helper build accepts `-WorkRoot` to select a short temporary build directory when a Windows sandbox provides a very long default temporary path. The build cleans only its newly created GUID directory after verifying that the path remains under the selected WorkRoot.

The existing 07:00 Sunday–Friday schedule, Diaspora Yom Tov exclusions, Sesame Street Classics channel, videos longer than 30 minutes, account-progress preference, and local-history fallback remain unchanged. The Key Guard signature-protected handoff and account filtering remain required.

## Startup reliability

The helper waits up to 60 seconds for validated internet before requesting the channel page. It retains four bounded candidate-fetch attempts. A simple startup message identifies internet/startup waiting; the helper activity finishes immediately after the warm-up handoff and phase scheduling, so an empty translucent activity is not left behind the player.

One run owns a renewable wake lease and a five-minute deadline measured with Android's elapsed clock. Internal phase/deadline alarms use that clock; the regular 07:00 schedule still uses local calendar time. A new run supersedes an older run, and callbacks and phase alarms carry a generation that must still be current before they update state, schedule another phase, or request playback. Timeout, cancellation, failure, and confirmed success cancel the helper's remaining phase alarms and release its owned wake lock. Destroying an unfinished startup activity cancels its worker. Successful activity handoff transfers wake ownership to the run rather than releasing it early.

Local DIAL status reads use 800 ms connect/read timeouts and a 6.5-second work budget. A blocked final read can add at most one such timeout. Network waits therefore do not keep an asynchronous broadcast receiver busy for the old multi-attempt 30-second interval. Playback keeps the original three bounded retries and two fresh observations of the selected authorized video with advancing timestamps and playback position. Only confirmed playback updates played history; PlaybackEvidence.java is unchanged.

The helper does not force-stop another app, restart Android, or navigate Home after a failure. It also cannot revoke a playback broadcast already queued to the unchanged Key Guard. Generation protection applies to helper callbacks, phase alarms, and new sends. A whole-device freeze still requires separate evidence.

## Read-only diagnostics

The helper persists the latest phase, timestamps, selected video ID, attempt, outcome/reason, and at most 32 history events. It stores no URLs, cookies, or account details in diagnostics. Boot/package-replacement handling marks an unfinished version-7 run interrupted and restores the normal schedule.

Android shell can read this state without opening an activity or changing the schedule:

    adb shell am broadcast -a dev.yossi.morningsesame.STATUS -n dev.yossi.morningsesame/.DiagnosticsReceiver

The explicit receiver is exported with android.permission.DUMP; ordinary apps cannot call it. It only reads preferences and returns JSON as the ordered-broadcast result. If an in-place upgrade still has version-6 pending fields, diagnostics may include a legacy object whose outcome is explicitly unknown. Version 6 did not record historical failure reasons.

## Validation and installation

Run the pure Java rules without contacting the TV:

    python tools/morning_sesame_wake_helper/tests/run_tests.py

The original 12 playback-evidence cases still reject stale, paused, frozen, wrong-video, and uninitialized evidence. Seventeen additional timing/generation checks simulate a slow successful network startup, renewed wake coverage, the absolute deadline, superseded callbacks, terminal outcomes, old alarms without generations, and elapsed-clock rollback. These tests do not establish physical TV wake or playback behavior.

Use the existing tools/build_morning_sesame_wake_helper.ps1 to build with the established signing key. Update in place and preserve sign-in and app data. This helper-only version does not change the installed TizenSub+ player or its script; its existing fresh advancing evidence contract remains compatible. Preserve the existing installed player and verify helper package version/signing identity before and after updating.

Live acceptance should use a real alarm from TV sleep, including a cold player start and an already-running or paused player. To request an acceptance alarm, launch .WakeAndPlayActivity with dev.yossi.morningsesame.SETUP and a positive test_delay_ms long extra. Bare RUN is an internal Key Guard handoff that only adopts a pending alarm run; it cannot create a new run after a delayed handoff or timeout. Direct video URI and explicit PLAY requests still initialize their own run.

Require fresh Playback confirmed evidence, independently advancing video position, and visible video, then verify the regular morning alarm. Also test that the read-only STATUS broadcast leaves the current viewing app and regular alarm unchanged. Do not remove account filtering or the Key Guard/PIN safeguard.

The existing tools/tizentube_startup_recovery_test.ps1 invokes device setup/recovery, clears logcat, and restarts the player. It is not a read-only diagnostic command.
