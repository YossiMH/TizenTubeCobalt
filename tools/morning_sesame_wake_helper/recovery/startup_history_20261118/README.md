# Morning startup history bridge

This recovery patch is based on the exact installed Nov 18 helper APK, SHA-256
`4b378a10bebb6e6030f271cc7eb19f53abbc708d884afcb1cdc7a7c5d4da9999`.
It retains the prior playlist selector and the installed startup and worker fences.
The older main helper source does not reconstruct every installed v12 behavior;
this pinned patch preserves those methods and verifies their bytecode after assembly.

## Failure and repair

The natural morning run retrieved the complete 83-video candidate set, then failed
before choosing an episode because its six successful HTTP reads contained no usable
account-history marker. A subsequent foreground investigation found a complete,
fresh account snapshot inside the player while YouTube's DIAL response contained
only its ordinary fields. Successful history collection therefore did not establish
successful history delivery.

The admitted morning run now obtains its account snapshot through the existing
authenticated controller and Guard. The controller invokes the player's bounded
run request and immutable accessor. Guard validates the snapshot and holds it only
in memory while that exact startup run is authorized. The helper asks the existing
signature-protected startup receiver for the same run's cache. The helper validates
the full nested reply, exact run/start/deadline, and capture time before passing the
account envelope to the unchanged strict history validator and playlist selector.

The eligibility rules remain the exact Full Episodes playlist, newest upload first,
strictly longer than 40 minutes, and at most 50% watched according to complete signed-in
YouTube playlist history. Local watched percentages and prior-start receipts do not
participate. The supported six-hour maximum remains aligned with episode protection.

The player waits for foreground and settled authentication, retries a failed account
read within a 60-second window, and allows at most six attempts for the admitted run.
An account change after a successful capture can trigger another bounded refresh
within that same attempt budget and original five-minute run deadline. Superseded
asynchronous writes cannot publish a completed diagnostic for a new run.

## Narrow helper changes

- `AlarmReceiver.selectAndVerify` calls `MorningHistoryReader.read` instead of the
  DIAL history reader. The old reader remains in the APK as unchanged bytecode.
- `MorningHistoryReader` makes at most six ordered requests within the existing
  6.5-second worker budget. It checks current-run cancellation before accepting a
  reply and records safe failure reasons and counts.
- `MorningHistoryReply` unwraps the nested account envelope and then invokes the
  existing `MorningYouTubeHistory` validator. It rejects snapshots captured before
  the current run and discards unrelated envelope fields.
- `MorningSelectionRepair` defers the selected route through the installed
  `WorkerFenceContinuation`, allowing the worker receiver to finish before the
  existing post-release fresh-input check permits playback.
- `MorningOrderedBroadcast` shares the actual sender identity on API 34 and newer
  for existing startup prepare/status calls and the new history command. It preserves
  the original callback, scheduler, permission and result parameters. Older Android
  versions use the original overload.

The existing moving-playback confirmation still requires two fresh advancing
observations. Its existing deferred confirmation and canonical episode enrollment
are preserved. Whole-alarm acceptance must reach confirmed episode enrollment,
in addition to successful history selection.

## Reply contract

The helper sends `dev.yossi.onnkeyguard.MORNING_STARTUP_AUTH` explicitly to the
existing `MorningPlaybackReceiver`, with `command=history`, `run_generation`,
`run_started_wall_ms`, and `run_deadline_wall_ms`. A usable reply has this shape:

```json
{
  "schema": 1,
  "ok": true,
  "state": "ready",
  "run_id": "the exact admitted run",
  "started_at_ms": 1000000,
  "deadline_ms": 1300000,
  "progress": {
    "__youtube_history": {
      "schema": 1,
      "source": "youtube_playlist",
      "playlist_id": "PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA",
      "captured_at_ms": 1005000,
      "signed_in": true,
      "complete": true,
      "covered_ids": ["aaaaaaaaaaa"],
      "progress": {"aaaaaaaaaaa": 49}
    }
  }
}
```

All illustrated timestamps and the video ID are synthetic. `pending`, `unavailable`,
and `cancelled` replies cannot supply eligibility evidence. The cache also requires
a fresh controller observation, separate from the account snapshot's five-minute
maximum age.

## Reproduce the helper APK

Use JDK 21, Android API 35's `android.jar`, D8 from build-tools 35.0.0, and
smali/baksmali 3.0.9. The original installed APK remains a private build input.

```sh
python tools/morning_sesame_wake_helper/recovery/startup_history_20261118/patch_startup_history.py \
  --base /path/to/verified-installed-morning.apk \
  --android-jar /path/to/android-35/android.jar \
  --d8-jar /path/to/build-tools/35.0.0/lib/d8.jar \
  --smali-jar /path/to/jadx-1.5.6-all.jar \
  --output /path/to/empty-build-output
```

The builder checks the base APK and DEX hashes, pins each changed class or adapter,
compiles only the new sources into DEX, and compares every other installed class
after assembly. Compile stubs never enter the final DEX. It retains the manifest and
resources byte for byte and emits `invariants.json` alongside an unsigned APK.
The output requires the existing private signing process before installation.

The companion player host test runs from the repository as:

```sh
node tools/morning_school_banner_20261118/player/player_history_wake_test.js
python tools/morning_sesame_wake_helper/recovery/startup_history_20261118/tests/boundary/run.py \
  --json-jar /path/to/json.jar
```

An explicit source path may be passed as the first argument when evaluating an
isolated candidate. Host tests exercise the actual parser and player code with
platform fakes; they do not substitute for the real alarm, receiver, and canonical
episode acceptance check on the device.

`verification/host_boundary.json` records 100 independent boundary assertions and
their source hashes. `verification/preservation.json` records that the candidate
preserves 383 of the installed helper's 388 methods exactly; the remaining scope is
the three-method selector adapter class and two single-call adapter replacements.
Four new classes carry the reader, reply validator, API-level shim and callback.
