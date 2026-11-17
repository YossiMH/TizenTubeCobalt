# Morning Sesame selection and school reminder

## Status and source baseline

**Installed on the Onn and verified with bounded device checks.** Both APKs
retain the existing signing identity and app data. The controller is running
with enabled rollout. See [deployment evidence](deployment.json) for exact
installed hashes, the rendered-banner check, phone-event dismissal, natural
diagnostic expiry, and the remaining direct physical observations.

The device has newer Android fixes than the full Java snapshots previously on
the repository's main branch. The repair therefore compiles readable new policy
classes and applies narrowly pinned changes to the actual installed APKs.

| Component | Exact installed baseline | Base SHA-256 |
| --- | --- | --- |
| Morning Sesame helper | Version code 12, version name 12.0 | `4bfbeebfde3d308bd9dfd4039d0d759f80bbd1618efac59d56bc062a3fdb1f53` |
| Onn Key Guard | Version code 8, version name 3.2-prepared | `2a63048d454e7b66407e42de5bdce1d46586399bba9ccee4169727d36601190f` |
| Player asset inside Guard | Installed asset recovered from that APK | `1fe2154764b819ad81036d04882cca104f3c28787b789b8b465cdfc23546a3a3` |

The helper's earlier full source describes version 9, and the Guard's earlier
full source describes version code 7. Rebuilding either entire APK from those
snapshots would discard installed startup, worker, authentication, and phone
intent fences. The recovery builders pin the APK, DEX, and affected methods,
then verify every untouched method and retained resource after reassembly.

## Maintained source locations

| Area | Source and verification |
| --- | --- |
| Episode selection | [Selection repair](../morning_sesame_wake_helper/recovery/selection_repair_20261117/README.md) |
| Android banner and calendar | [Guard integration](../onn_key_guard/recovery/school_banner_20261117/INTEGRATION.md) |
| Player | Repository root [x.js](../../x.js), with [tests and protocol notes](player/README.md) |
| Controller and native input adapter | [onn_session_policy](../onn_session_policy/README.md) |
| APK asset integration and signing recovery | [recovery](recovery/) |

Future changes should begin in these readable sources, with their behavioral
tests. Use the pinned builders until the newer installed Android baseline has
been reconciled into a complete buildable source tree. Compile stubs and recovered
host fixtures exist only for ABI compilation or testing and never enter DEX.
The player has one production copy, root `x.js`.

## Resulting behavior

The 07:00 selector reads Sesame Street Classics' **newest channel uploads first**.
It accepts a duration strictly greater than 40 minutes and no more than six
hours, the existing protection limit. Known account and measured local progress
must each be at most 25% watched. A candidate rejected by that rule cannot enter
a later longest-remaining fallback.

The player persists actual observed progress independently of account markers,
using the maximum measured percentage. It records the crossing above 25%
immediately and records actual completion as 100%. Legacy startup receipts remain
conservative when trustworthy measured progress is missing. Confirmed starts from
today and yesterday in America/Los_Angeles are skipped; an older interrupted
episode can qualify again if its actual measured progress remains at most 25%.
The channel reader can follow bounded continuation pages. Exhaustion produces an
explicit `no_eligible_candidate` result.

After the selected episode actually ends, the banner displays:

> No more TV, Bernie -- it's time to leave for school!

It ends at exactly 09:30 America/Los_Angeles and is excluded on Sundays and U.S.
federal actual/observed holidays. The original episode scheduler retains its
existing Saturday and religious-holiday exclusions. A paused player, near-end
position, startup failure, or timeout does not create the banner.

The app owns visible overlay and calendar authorization; the controller requires
fresh app and native phone-source proof before restricting physical input or
renewing the finite media lease. The existing voice/input protection remains in
effect during the banner. Banner recovery is limited to restoring power/input.
A recognized nonvolume phone-remote action dismisses the banner, and the existing
volume exceptions remain in effect. Parent dismissal persists for that morning.
Observer loss releases protection. The media lease expires automatically and
never resumes playback when cleared or expired.

If buffering carries an episode past its original duration-plus-five-minute
protection deadline, WAITING can retain bounded actual-end observation through
09:30. It does not renew expired episode recovery authority. A phone action or
lost observer cancels that waiting reminder.

## Host verification

Run from the repository root. Use a JDK with the `jdk.compiler` module and the
JSON test dependency documented in the selection README. Keep generated outputs
outside the checkout.

```sh
python tools/morning_sesame_wake_helper/recovery/selection_repair_20261117/run_regression.py \
  --json-jar /path/to/json-20240303.jar --output /path/to/selection-tests
python tools/onn_key_guard/recovery/school_banner_20261117/run_regression.py \
  --output /path/to/banner-tests
node tools/morning_school_banner_20261117/player/player_morning_test.js
node tools/morning_school_banner_20261117/player/legacy/media_startup_policy_test.js
node tools/morning_school_banner_20261117/player/legacy/morning_progress_payload_test.js
node tools/morning_school_banner_20261117/player/legacy/tizentube_morning_playback_test.js
PYTHONPATH=tools/onn_session_policy python -m unittest discover \
  -s tools/onn_session_policy/tests -v
```

Prepared verification covers 135 selection assertions, 109 banner-policy checks,
19 original asset-route checks, 81 new player assertions, the retained player
regressions, and 66 controller tests. One existing controller startup test skips
when the host forbids AF_UNIX sockets; the device acceptance must cover that
process path. Controller tests include both races found during review: an episode
changing into the banner while wake is in flight, and phone intent arriving while
the debugger forward is being checked.

The selection and Guard `verification/` records contain the final unsigned build
invariants. The Guard record describes builder revision v6. Those are build
records, not proof that an APK was installed or that physical controls passed.
The staged-source reruns are recorded in [host-verification.json](host-verification.json).
Both Android builds passed their invariants, and every disassembled class was
identical to the reviewed candidate. DEX serialization can produce a different
whole-file hash; always sign and deploy the specifically reviewed artifact pin.
The asset integration step reproduced the reviewed final unsigned Guard hash
exactly. The native C build and both pure behavior/real-pipe harnesses also pass.

## Build and signing sequence

1. Run each recovery builder with its exact installed base APK, Android API 35,
   D8, and the Smali 3.0.9 classes. The builders reject another baseline. The
   helper changes five existing methods and preserves 339; the Guard changes ten
   existing methods, adds one bridge, and preserves 546.
2. Put root `x.js` into the unsigned Guard with the bounded asset-integrator:

```sh
python tools/morning_school_banner_20261117/recovery/integrate_player_asset.py \
  --guard /path/to/OnnKeyGuard-v8-school-banner-unsigned.apk \
  --guard-sha256 55e2b45f3e44d440a25f441a38ad5259a18878fca44bb178bf36294a99159f65 \
  --output /path/to/guard-unsigned.apk --report /path/to/integration.json
```

The integrator defaults to root `x.js`, checks the supplied Guard pin, changes
only `assets/player.js` and `assets/player.sha256`, and verifies all other ZIP
payloads. If a future reviewed source change produces a new Guard, use its
verified new pin. The existing installed Cobalt request route remains stable;
the Guard verifies and advertises the new player asset's actual hash.

3. Copy `recovery/sign-two-apks.template.ps1` to a private staging directory,
   fill every placeholder, and provide keystore passwords only through the
   local `APK_SIGN_STOREPASS` and `APK_SIGN_KEYPASS` environment variables. Pin
   both unsigned APK hashes and the installed signing-certificate hash. The
   template preserves the existing local key, uses a one-use claim, bounds child
   processes and transfers, verifies both signed APKs and their unchanged
   payloads, then uploads each exactly once. Inspect receipts before retrying a
   partially completed operation.
4. If the scoped LAN exchange is needed, run its helper with an explicit private
   staging directory and authorized bind address. That directory supplies a new
   64-hex `exchange.token`, the two pinned unsigned APKs, and the filled
   `sign-two-apks.ps1`. The helper expires after 15 minutes and allows only its
   fixed download/upload filenames. The committed helper requires those runtime
   settings and contains no token or household LAN address.
5. Verify signer identity, install in place, preserve app data, update the exact
   controller service sources, and complete live acceptance. Record signed
   hashes and acceptance evidence here afterward.

Private keystores, filled signing scripts, tokens, archives, compiled classes,
DEX files, and APKs are not source deliverables. The existing signing and device
processes remain separately owned; these tools do not start a deployment merely
by being imported or tested.

## Device acceptance and remaining direct observations

The exact installed hashes and new player bytes/ETag were read back. All 66
controller tests passed in the actual Termux environment. A bounded diagnostic
showed the exact full-screen message and an armed native lease with a real
physical input-device grab. An authenticated synthetic phone Back event
dismissed it and released all grabs. A separate 12-second diagnostic expired
naturally and released the overlay and lease. The original canonical episode
and enabled configuration were preserved, and the Onn was returned to sleep.
The next normal alarm remains 2026-11-17 at 07:00 America/Los_Angeles.

Actual handset presses and spoken Google Home/Assistant commands were not
generated during these checks. The first natural morning selection and
episode-to-banner transition occur at the next scheduled run. The software
checks cover their policy, calendar, media-event ordering, and phone precedence;
the finite diagnostics are recorded separately from those future observations.
The existing native input classification is retained.

Use [acceptance.py](recovery/acceptance.py) with an explicit existing controller
state directory for bounded inspection/diagnostics. Its receipts distinguish
synthetic authenticated phone events from actual handset inputs.
