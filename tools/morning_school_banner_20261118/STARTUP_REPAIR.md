# Scheduled startup integration repair

The natural November 18 morning run stopped before selecting an episode. Candidate
retrieval succeeded with 83 entries, but six successful DIAL HTTP reads yielded no
usable account history. The run ended after 80.238 seconds with
`account_selection_failed`. Since episode enrollment happened only after playback
confirmation, that failure also left the morning without episode protection or a
reminder. The earlier renderer and account-refresh tests had not exercised this
complete scheduled path.

A subsequent foreground check reproduced a delivery gap: the signed-in player held
a fresh, complete six-page account snapshot while YouTube's DIAL response contained
only its standard fields. The repair establishes a separate authenticated delivery
path and admits the morning's protection before candidate selection.

## Resulting behavior

An eligible scheduled start admits one bounded morning run. A prior run's pending
phone handoff can clear only after its acknowledged phone intent, the established
grace period, a new admitted request and current observer proof. A fresh phone event
or unacknowledged pending event retains precedence. Physical-input proof,
startup authorization and phone dismissal retain their existing owners. While the
helper warms the player and selects an episode, the controller renews a short browser
media lease and the native input protection. The preselection lease pauses all browser
media; after the signature-owned launch binds a candidate, the lease permits only that
candidate through the normal allowed-video rules.

The full alarm test then exposed two playback handoff races. A play request could
arrive while the lease still had an empty candidate, or a renewal could pause an
authorized native play whose promise had not yet resolved. The player now retains
only a play request actually intercepted by the empty startup lease, for the
remaining duration of that lease. The exact candidate and positive account/video
authorization must arrive before it dispatches once. Repeated warming renewals do
not erase that request. Run, route, source, visibility, pause, load, release and
expiry changes cancel it. An in-flight authorized native play is recognized before
playback tracking is installed, so candidate renewals no longer pause it. Granting
or releasing a lease never starts an arbitrary paused element.

The controller requests fresh signed-in playlist history only after the player is
foreground and the admitted run has current authorization. The player waits for
authentication to settle, retries within a finite window, and exposes an immutable
snapshot bound to the run's start and deadline. Guard accepts it only through its
existing authenticated controller channel. The helper reads it through the existing
signature-protected startup receiver, then checks the full nested account envelope.
Losing DIAL's custom fields no longer prevents history delivery.

The selector keeps the exact Full Episodes playlist, newest upload order, duration
strictly greater than 40 minutes and actual account watched percentage at most 50.
Local progress and prior launch receipts have no eligibility role. Missing or invalid
account evidence remains a selection failure.

The selected route waits until the worker receiver releases and the installed fresh
input fence permits playback. Existing moving-video confirmation must then enroll the
canonical episode session. A confirmed episode transitions to the school reminder
when it actually ends. If the admitted scheduled morning never confirms an episode,
the separate failure reminder starts at 8:00 AM on eligible school-calendar days.
Phone dismissal and the 9:30 AM deadline take precedence. The fallback's active
browser lease explicitly supports an empty episode ID and blocks all browser media;
awaiting an episode's end still requires its exact video ID.

The rich reminder's picture, music, video and +3/+5/+8/+11-second return sequence
remain the implementation documented in [README.md](README.md). Private media sidecars
remain outside the repository and require the existing directory mode `0755` and
file mode `0644`.

## Source and build ownership

| Product path | Current responsibility |
| --- | --- |
| `x.js` | Bounded foreground/account refresh, run-bound direct history accessor, startup and unbound active-reminder media leases, selected-play handoff |
| `tools/morning_sesame_wake_helper/recovery/startup_history_20261118/` | Strict helper IPC reader, API 34 caller identity, deferred route, narrow pinned helper builder and boundary tests |
| `tools/onn_key_guard/recovery/morning_window_20261118/` | Admitted startup protection, ephemeral history cache, failure reminder, bounded test grant and narrow Guard builder |
| `tools/onn_session_policy/onn_session_policy/` | Existing controller's current startup, native lease, history transport and phone-precedence integration |

The repair starts from the exact installed November 18 packages. Rebuilding the older
full Java snapshots would omit unpublished installed fencing behavior. The builders
compile the current owned sources and prove the preservation of unrelated installed
methods and resources. The helper manifest stays byte-identical. The Guard manifest
adds one exact package-visibility query for `dev.yossi.morningsesame`, preserving every
existing XML node and string index; this permits its existing helper UID and signing
identity checks on Android 14. The query adds no permission or exported component.

| Required input | SHA-256 |
| --- | --- |
| Installed helper APK | `4b378a10bebb6e6030f271cc7eb19f53abbc708d884afcb1cdc7a7c5d4da9999` |
| Installed helper DEX | `a653862bc7acae0689647bfa7b80c5526a9abf75251f574045389d793404f9e2` |
| Installed Guard APK | `1a56676eb5eaef7fda9a0d8a33543e6002accb2ec49bb26fd3d7133ea3b0c2a3` |
| Guard input player asset | `b42d6c054448ea37d691751466aee224b77d8b29d89e81f7a2a4df99ad589b11` |

The first live grant check exposed Android's package-visibility filter before the
alarm run began. Guard revision 3 adds the exact helper query described above.
Its Java/controller sources, player bytes and checksum are unchanged from revision 2;
the reassembled DEX is canonically identical. Actual shared caller-UID verification
remains required at grant consumption.

Guard revision 4 packages the selected-play handoff repair. Compared with verified
unsigned revision 3, only `assets/player.js` and `assets/player.sha256` change; the
manifest, DEX and every other ZIP entry remain byte-identical. The main builder
above reproduces the updated source directly. `repackage_player.py` additionally
supports the exact pinned unsigned revision 3 input and requires the reviewed
new player hash, for the smaller asset-only update.

The private signing keys and installed base APKs remain private build inputs.

```sh
python tools/morning_sesame_wake_helper/recovery/startup_history_20261118/patch_startup_history.py \
  --base /private/nov18/morning-signed.apk \
  --android-jar /path/to/android-35/android.jar \
  --d8-jar /path/to/build-tools/35.0.0/lib/d8.jar \
  --smali-jar /path/to/jadx-1.5.6-all.jar \
  --output /tmp/morning-history-build

python tools/onn_key_guard/recovery/morning_window_20261118/build_guard_morning.py \
  --base /private/nov18/guard-signed.apk \
  --android-jar /path/to/android-35/android.jar \
  --d8-jar /path/to/build-tools/35.0.0/lib/d8.jar \
  --smali-jar /path/to/jadx-1.5.6-all.jar \
  --player-script x.js \
  --output /tmp/morning-window-build
```

The Guard builder must replace **both** `assets/player.js` and
`assets/player.sha256`. The digest file contains the SHA-256 of the exact script bytes
followed by a newline. Its readback assertion checks the pair before signing. The
existing player-serving route class remains unchanged. The reminder README's older
manual ZIP recipe has also been corrected to update both files.

## Verification

Player checks pass 113 retained history/media assertions and 65 focused startup
assertions. The new checks cover an early wake before authentication, first-request
failure, account changes during and after retrieval, a snapshot predating the run,
foreground gating, a stalled optional DIAL post, stale asynchronous completion,
finite retries, and startup/reminder media lease precedence.

The independent playback-handoff suite adds 18 scenarios and 99 assertions. It
reproduces both failures on the previous player and passes with the repaired script:
empty-candidate interception, exact-candidate dispatch once, unchanged warming
renewals, unresolved native play, wrong candidates, positive channel permission,
phone-style release, both clocks, route/source/load/pause changes, hidden pages and
reminder precedence. The source-hashed before/after receipts are in `verification/`.
These are host regressions; the complete alarm path still requires device evidence.

The helper boundary harness passes 100 assertions through the actual reply parser,
ordered reader, Android-version shim, existing account validator, selector adapter
and pinned worker continuation. It verifies late cancellation, exact run and deadline
binding, threshold edges, fresh post-worker fencing, API 34 identity options and
API 33 compatibility. Its platform fixtures are limited to transport, storage,
scheduling and the surrounding run coordinator.

```sh
node tools/morning_school_banner_20261118/player/player_morning_test.js
node tools/morning_school_banner_20261118/player/player_history_wake_test.js
node tools/morning_school_banner_20261118/player/player_startup_handoff_test.js
python tools/morning_sesame_wake_helper/recovery/startup_history_20261118/tests/boundary/run.py \
  --json-jar /path/to/json.jar
```

The decisive device gate is a real helper alarm using the ordinary startup path.
The bounded after-hours diagnostic grant is one-use, tied to the exact scheduled
alarm and actual helper UID, and expires within three minutes. It must reach history
selection, advancing-playback confirmation, `MORNING_SESSION` and canonical episode
protection. Separate bounded checks cover the reminder and its release. The alarm
test does not write a fake session or change the normal daily alarm policy.

The staged Guard entrypoint passed 91 morning-window checks and 109 retained
calendar/banner checks. The latest controller suite discovered 88 tests: 85 passed and
three platform-dependent checks were skipped (the host Unix-socket smoke and two
native-drain harness checks). [Host receipts](verification/startup_host_regressions.json)
record these counts and distinguish them from device evidence.

## Completed device verification

The repaired packages were installed with the existing signing certificate. The
final bounded episode test used the ordinary helper alarm from an asleep device
and a fresh Cobalt process. It retrieved complete account history for 83 videos,
selected an eligible episode, reached `playback_confirmed` in 100.547 seconds,
and recorded nine samples of advancing playback. The canonical episode and armed
native lease remained active past the accelerated failure-reminder deadline.
Launching Netflix triggered recovery to Cobalt.

An authenticated synthetic Home event then produced `phone_stopped` and
`phone_cancelled_episode`, followed by a disarmed native lease. This exercised the
existing authenticated phone-event path. Actual handset input and native evdev
phone delivery were not observed: an earlier raw-input acceptance attempt failed
on its first command. No physical remote was connected, and voice/Google Home
commands were not directly exercised.

A separate ordinary alarm exercised the scheduled failure reminder with a short
bounded diagnostic deadline. Across 120 observations, it completed hold → fade-out
→ video → return → next hold. The two random holds were 35.815 and 40.733 seconds.
All 84 active samples retained the overlay, audio focus and armed native lease.
The music position remained at 40,573 ms throughout ten video samples, with text
absent and no simultaneous music/video playback. The first sampled return had
resumed music at 41,384 ms. Text appeared during the sampled +3-second fade,
was fully visible after +5 seconds, and the frame crossfade appeared after +8
seconds before the next hold. These sampled values follow the documented
+3/+5/+8/+11-second sequence; music playhead offsets are separate from phase time.
The actual screenshot was visually checked for the complete legible sentence
over the first frame. At the accelerated cutoff, the overlay and media were
released, audio focus was released, and the native lease was disarmed.

The final read-only device verification passed all 23 checks, including installed
package/controller hashes, the actual served script digest and ETag, enabled
supervision and boot wiring, the live released observer state, and the unchanged
next alarm for November 19 at 07:00 in America/Los_Angeles. The device was left
awake in Netflix, with no active episode protection or reminder. The controller
supervisor remained running with one start throughout these final tests.

These bounded alarm runs establish the repaired startup and fallback paths. The
next unattended natural 7 AM run, a full episode reaching its natural end, and the
natural 9:30 AM cutoff have not yet been observed after this repair. Detailed
before/after acceptance scope and the earlier harness limitations are retained
in the deployment record; private watch values and receipt details remain private.

Current signed payload hashes and live results are recorded in
[verification/startup_deployment.json](verification/startup_deployment.json).
Account watch values, private run receipts, media files and credentials remain private.
