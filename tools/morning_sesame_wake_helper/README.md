# Morning Sesame version 8


## Windows build workspace

The helper build accepts `-WorkRoot` to select a short temporary build directory when a Windows sandbox provides a very long default temporary path. The build cleans only its newly created GUID directory after verifying that the path remains under the selected WorkRoot.

The existing 07:00 Sunday–Friday schedule, Diaspora Yom Tov exclusions, Sesame Street Classics channel, videos longer than 30 minutes, account-progress preference, and local-history fallback remain unchanged. The Key Guard signature-protected handoff and account filtering remain required.

## Startup reliability

The helper waits up to 60 seconds for validated internet before requesting the channel page. It retains four bounded candidate-fetch attempts. A simple startup message identifies internet/startup waiting; the helper activity finishes immediately after the warm-up handoff and phase scheduling, so an empty translucent activity is not left behind the player.

One run owns a renewable wake lease and a five-minute deadline measured with Android's elapsed clock. Internal phase/deadline alarms use that clock; the regular 07:00 schedule still uses local calendar time. A new run supersedes an older run, and callbacks and phase alarms carry a generation that must still be current before they update state, schedule another phase, or request playback. Timeout, cancellation, failure, and confirmed success cancel the helper's remaining phase alarms and release its owned wake lock. Destroying an unfinished startup activity cancels its worker. Successful activity handoff transfers wake ownership to the run rather than releasing it early.

Local DIAL status reads use 800 ms connect/read timeouts and a 6.5-second work budget. Connect/header/body reads retain the existing short individual timeouts. Network waits therefore do not keep an asynchronous broadcast receiver busy for the old multi-attempt 30-second interval. Playback keeps the original three bounded retries and two fresh observations of the selected authorized video with advancing timestamps and playback position. Only confirmed playback updates played history; PlaybackEvidence.java is unchanged.

The helper does not force-stop another app, restart Android, or navigate Home after a failure. It also cannot revoke a playback broadcast already queued to the unchanged Key Guard. Generation protection applies to helper callbacks, phase alarms, and new sends. A whole-device freeze still requires separate evidence.

## Bounded diagnostics (version 8)

Diagnostics run only when an existing startup, phase, HTTP attempt, playback check, completion, boot, package update, or explicit STATUS request occurs. They create no periodic polling or additional playback/network requests. The private journal has one fixed name, morning-diagnostics.bin, and a versioned, checksummed binary format; STATUS renders its records as JSON.

Hard limits are 64 compact recent-run summaries, eight independently retained failed/interrupted traces, 32 events per trace, eight system observations, and 128 KiB for the entire serialized journal. A successful run never evicts a protected failure. Intentional supersession stays in recent summaries and does not fill the failure ring. The fixed AtomicFile base, .new and legacy .bak slots are each capped at 128 KiB, for at most 384 KiB including all transient slots, plus the existing small bounded helper preferences. No per-run files or version-specific filenames accumulate. Corrupt, truncated and oversized data is rejected before unbounded allocation; safe fields from the last control state remain recoverable.

The writer has at most 64 pending operations, coalesces each pending batch into one atomic write, and exits when idle. Queue overflow is counted and favors retaining start/completion/system operations. Failed writes do not schedule retries: only another existing stage event provides a later opportunity. Playback never waits for the journal writer. Before replacing a prior run, an immutable copy of its last control state is queued for reconciliation; this recovers a terminal outcome committed before a lost asynchronous journal write. A previous durable running checkpoint is retained as interrupted when its final outcome is unknown.

A separate event-driven telemetry worker gathers memory, power, thermal and storage values. There is at most one telemetry worker and one coalesced pending request, with at most eight requests per run. If a system Binder call stalls, no replacement worker is started and playback/journal persistence continue. Final records attach the cached snapshot's age or mark it unavailable. Diagnostic snapshots never run on the activity/receiver main thread, on playback/selection workers, or while holding the run coordinator lock.

Records contain fixed enums and whitelisted numbers: monotonic stage timings; network validation/transport flags; candidate attempt/count/HTTP status/duration; account-progress availability and selection method; playback rejection, sample age, matching-video flag and timestamp/position advancement; and memory/power/thermal/storage observations. They retain the selected video ID and helper version. They contain no raw XML, HTML, URLs, tokens, cookies, SSIDs, IP addresses, or arbitrary exception text. Boot and package-update observations are separate and are retained even if the previous run succeeded, allowing a later reboot to be correlated with its last successful morning. A completely frozen OS cannot produce a new diagnostic sample.

## Read-only STATUS

The DUMP-protected explicit receiver returns compact JSON without opening an activity, modifying playback, flushing the writer or changing the schedule:

    adb shell am broadcast -a dev.yossi.morningsesame.STATUS -n dev.yossi.morningsesame/.DiagnosticsReceiver

Top-level run_id, outcome, reason, started_at_ms and updated_at_ms retain their version-7 meanings. Counts, storage/writer state, retained latest-failure summary, latest system observation and exact limits are added. Check logging.state, writer_active and pending_operations to distinguish in-memory observations from a completed journal write.

Optional selectors use the same command with --es detail followed by one of:

- current: current or most recent run's detailed trace.
- latest_failure: most recently retained failure's detailed trace.
- failures: all retained failure IDs and summaries, newest first.
- recent: recent summaries, newest first, eight per page.
- system: the bounded boot/package-update/recovery observations.
- run:RUN_ID: a retained trace or compact summary for that exact run ID.
- legacy: the existing version-7 32-event preference history, preserving upgrade access.

Detailed traces return their latest eight events by default. Add --ei offset 0 for the first eight, then follow next_offset/previous_offset. Summary pages also accept --ei offset, in increments of eight. Every response is capped at 32 KiB. STATUS uses at most one reader worker and a five-second response deadline; repeated requests while a reader is stuck return diagnostics_busy without spawning more workers. Reads do not repair or rewrite files. Ordinary apps cannot call the endpoint because the receiver requires android.permission.DUMP.

Example retained failure query:

    adb shell am broadcast -a dev.yossi.morningsesame.STATUS -n dev.yossi.morningsesame/.DiagnosticsReceiver --es detail latest_failure

Maintainers: FORMAT=1 serializes enum and metric ordinals. Existing arrays must remain in their current order; append new entries or explicitly migrate/bump the format. The fixed filename and file-count limit remain part of the storage contract across later helper versions.

## Validation and installation

Run the pure Java rules without contacting the TV:

    python tools/morning_sesame_wake_helper/tests/run_tests.py

The original playback-evidence cases still reject stale, paused, frozen, wrong-video, and uninitialized evidence. Timing/generation tests simulate slow startup, renewed wake coverage, hard deadlines, stale callbacks and elapsed-clock rollback. Archive tests simulate 7,300 successful daily runs while retaining a rare failure, repeated failures, queue-sized event histories, full simultaneous retention, duplicate/stale callbacks, restart reconciliation, oversized input, corruption, truncation, and CRC-valid malformed length/count declarations. The maximum-shape fixture serializes to 95,152 of 131,072 allowed bytes. Playback diagnostic tests check descriptive rejection reasons and privacy boundaries independently of unchanged playback acceptance. These tests do not establish physical TV wake or playback behavior.

Use the existing tools/build_morning_sesame_wake_helper.ps1 to build with the established signing key. Update in place and preserve sign-in and app data. This helper-only version does not change the installed TizenSub+ player or its script; its existing fresh advancing evidence contract remains compatible. Preserve the existing installed player and verify helper package version/signing identity before and after updating.

Live acceptance should use a real alarm from TV sleep, including a cold player start and an already-running or paused player. To request an acceptance alarm, launch .WakeAndPlayActivity with dev.yossi.morningsesame.SETUP and a positive test_delay_ms long extra. Bare RUN is an internal Key Guard handoff that only adopts a pending alarm run; it cannot create a new run after a delayed handoff or timeout. Direct video URI and explicit PLAY requests still initialize their own run.

Require fresh Playback confirmed evidence, independently advancing video position, and visible video, then verify the regular morning alarm. Also test that the read-only STATUS broadcast leaves the current viewing app and regular alarm unchanged. Do not remove account filtering or the Key Guard/PIN safeguard.

The existing tools/tizentube_startup_recovery_test.ps1 invokes device setup/recovery, clears logcat, and restarts the player. It is not a read-only diagnostic command.
