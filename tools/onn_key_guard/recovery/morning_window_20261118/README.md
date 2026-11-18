# Scheduled morning integration repair — 2026-11-18

The actual 07:00 run failed before the previous implementation enrolled protection. The helper found 83 playlist candidates, then received zero usable account-history entries through DIAL and finished `account_selection_failed`. The old reminder existed only after confirmed episode playback; consequently that upstream failure left no new protected morning and no reminder.

## Runtime transitions

The existing signature-owned `MORNING_STARTUP_AUTH prepare` path still validates trigger, recent phone intent, input provenance, the finite PIN grant, replay protection, and the five-minute startup-work bound. Only its accepted branch calls the new scheduled-window delegate.

That delegate persists a `STARTING` morning with the admitted run ID, original run start, work deadline, 08:00 fallback, and 09:30 final cutoff. Physical protection starts before history selection. A finite browser media lease blocks warmup media; signature-owned `MORNING_PLAY` binds the chosen candidate and permits only that video through the existing allowed-only gates. A fresh canonical `ACTIVE`/`PHONE_PAUSED` episode binds the real video and moves the reminder to `WAITING`. A fresh true end moves it to the existing rich reminder.

At 08:00, an unbound `STARTING` run becomes the reminder only with a fresh canonical observation proving there is no authorized active episode. Restored startup state waits for that observation. A same-run canonical episode is reconciled before any fallback decision, including the persistence boundary between confirmation and reminder binding. A different authorized episode suppresses fallback without changing run identity. Previously bound episode expiry retains its existing actual-end wait; approximate position or timeout never fabricates an end.

Sunday and U.S. federal actual/observed holidays suppress the reminder/fallback. They do not cancel valid pre-episode protection. A validated nonvolume phone press/repeat during `STARTING` cancels the whole morning; volume keeps its existing exception. Parent override, disable, and input-provenance loss retain terminal release semantics. The real-day veto survives retries and process restart. Fresh input proof is never restored from disk.

The controller recognizes the separately authenticated `MORNING_STARTING` field and arms the existing native guard. It may recover power/input through the existing phone-drain barrier. Foreground recovery preserves helper/Guard activities, requires clear authentication, and launches only the established Cobalt home activity. Normal PIN entry still requires the original five-second startup grant.

## Account-history transport

While the admitted startup is live, the controller calls these player APIs over the existing loopback CDP connection:

```javascript
requestMorningProgress({run_id, started_at_ms, deadline_ms})
morningHistoryForRun({run_id, started_at_ms, deadline_ms})
setMorningStartupLease(deadlineMs, expectedVideoId)
```

It begins history work only when fresh app state says Cobalt is foreground and authentication is clear. The player independently rejects hidden-page refresh requests. The controller posts `type: "morning_history"` through the existing authenticated event endpoint, with exact run/time bounds and `ready`, `pending`, or `unavailable` state. A ready event carries this exact envelope:

```json
{"__youtube_history":{"schema":1,"source":"youtube_playlist","playlist_id":"PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA","captured_at_ms":1,"signed_in":true,"complete":true,"covered_ids":["abcdefghijk"],"progress":{"abcdefghijk":49}}}
```

The timestamp above is illustrative; actual acceptance requires capture at or after the run start, no future capture, and at most five minutes of age. Coverage must contain 1–512 unique valid video IDs and exactly matching numeric 0–100 percentages. Missing coverage is never treated as zero. The Guard cache is memory-only, copied immutably, and expires each controller observation after three seconds. Pending/unavailable observations clear previous ready data; phone/proof/run/window changes revoke it.

The helper reads the existing signature-owned startup receiver with `command=history`, `run_generation`, `run_started_wall_ms`, and `run_deadline_wall_ms`. A ready reply has `schema:1`, `ok:true`, `state:"ready"`, matching `run_id`, `started_at_ms`, `deadline_ms`, and `progress` containing the envelope above. Pending/unavailable/cancelled replies contain no progress. The existing receiver returns result code `-1` for successfully serialized replies and `0` on an exception; eligibility uses the strict JSON envelope and signature route.

An active unbound fallback uses `setSchoolBannerLease(deadlineMs, "", false)`. An awaiting-end lease still requires a real video ID. Both media leases expire within 15 seconds, clear at zero, and never resume media automatically.

## Bounded real-alarm acceptance

The existing DUMP-only admin endpoint accepts `test_morning_window` with:

| Extra | Type | Bound |
| --- | --- | --- |
| `scheduled_wall_ms` | long | Exact next actual helper alarm; from 1 second ago to 60 seconds ahead |
| `helper_uid` | int | Must match installed helper UID and signing identity |
| `fallback_after_seconds` | int | At least 5; default 90; must leave time before cutoff |
| `duration_seconds` | int | 30–180; monotonic cutoff starts when armed |

This one-shot grant is consumed only by the normal accepted signature prepare callback. API 34 caller identity sharing is required; the actual sending UID must match. Run start must be within scheduled time −1/+5 seconds; issued time must be between run start and scheduled time +5 seconds. The actual generated UUID is recorded in status. An accepted phone action or observer loss before consumption cancels the grant.

The consumed grant uses a separate, nonpersisted policy instance with the same production transitions. It never changes device time, the real alarm rules, or the real-day phone veto. Its cutoff only shortens the exact canonical diagnostic run's episode deadline. Expiry also cancels the matching helper startup fence; late callbacks cannot revive it. No unrelated run is cancelled or restored.

Read `school_banner.pipeline_diagnostic`, `diagnostic_run_id`, `diagnostic_alarm_wall_ms`, `started_at_ms`, `startup_deadline_ms`, `fallback_at_ms`, `expires_at_ms`, `candidate_video_id`, and `morning_history` for the admission/selection/handoff receipt. The existing renderer status exposes media phases and positions.

## Build and verification

The builder pins the exact installed Guard APK SHA-256 `1a56676eb5eaef7fda9a0d8a33543e6002accb2ec49bb26fd3d7133ea3b0c2a3` and DEX SHA-256 `cd97484ea6911cd0395bef60060a57fde50e31ed8656f5e7a29b5e67ab2ea566`.

```sh
python build_guard_morning.py \
  --base /path/to/exact/guard-signed.apk \
  --smali-jar /path/to/jadx-1.5.6-all.jar \
  --d8-jar /path/to/build-tools/35.0.0/lib/d8.jar \
  --android-jar /path/to/platforms/android-35/android.jar \
  --player-script /path/to/final/x.js \
  --output /path/to/empty/build-directory
```

Only four owned Java class families are compiled. Three compile-only stubs are excluded from the DEX. Seven existing methods receive narrow delegates; one exact-run cancellation wrapper is added. Every other existing method is compared canonically after full Smali assembly/disassembly. Rich media classes and the native engine remain unchanged.

The manifest adds only `<queries><package android:name="dev.yossi.morningsesame"/></queries>` so Android's package-visibility filter permits the Guard to verify that helper's UID and signing identity. The patch preserves all existing binary XML nodes and string indexes. It adds no permission, receiver, exported component, or broad package query. Actual API 34 sender UID verification remains mandatory when consuming a test grant.

When a player script is supplied, the builder replaces **both** `assets/player.js` and `assets/player.sha256`, retaining the existing newline convention and verifying the packaged checksum against the packaged bytes. The existing stable player URL/class is unchanged. The builder produces an unsigned APK and an invariants report; it performs no device access or signing.

```sh
python run_regression.py \
  --json-jar /path/to/org-json.jar \
  --controller-dir /path/to/tools/onn_session_policy \
  --output /path/to/test-classes
```

The Java runner exercises the real policy, cache, diagnostic grant, and existing banner/calendar suite. Controller tests replace only Android/CDP/native I/O boundaries and cover startup protection, selected-candidate lease handoff, phone races, foreground/authentication limits, history transport identity, stale work, and unbound fallback. Device acceptance must separately exercise actual alarm admission, history, selection, moving playback, native input protection, fallback and timed/phone release; a renderer-only diagnostic does not establish the whole morning flow.
