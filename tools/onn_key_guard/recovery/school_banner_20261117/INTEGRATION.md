# Morning school banner patch

## Deliverable

`build-v6/OnnKeyGuard-v8-school-banner-unsigned.apk` was created from the pinned
Guard 8 startup-window APK by `build_guard_banner.py`. It is unsigned. The parent
task owns adding the corrected player asset, signing with the existing identity,
device installation, and live acceptance.

The builder checks the base APK/DEX and ten exact method hashes, compiles only
new `SchoolBanner*` classes, adds one private bridge, and round-trips the DEX.
It checks all 546 untouched existing methods, register counts, class/field
inventories, and retained non-code APK payloads. `verification/invariants-v6.json` is the
verification record. Compile-only ABI stubs are isolated and never packaged.

## Reproduction from this checkout

Use `python run_regression.py --output /path/to/banner-host-tests` for the 109
pure policy checks and 19 pinned asset-route checks. The latter run the readable
host fixtures under `test/fixtures/`, with hashes checked before substitution.
`build_guard_banner.py --help` lists the explicit pinned APK, Android API, D8,
and Smali inputs. New build outputs stay outside this checkout.

The shared [integration guide](../../../morning_school_banner_20261117/README.md)
describes how to package root `x.js` into this unsigned Guard, sign with the
existing identity, and track deployment acceptance. Deployment is pending.

## Runtime contract

`school_banner` is an additional canonical status object. Original `session`
fields remain unchanged. The controller should use both
`school_banner.phase == "active"` and `protection_ready == true` before applying
banner-specific native/voice/power restrictions. The useful fields are:

- `phase`: inactive, waiting, active, awaiting_observer, dismissed, expired, skipped.
- `protection_ready`: visible overlay, attached service, current live input proof.
- `run_id`, `video_id`, `local_date`, `expires_at_ms`, `completed_at_ms`.
- `overlay_visible`, `observer_ready`, `audio_focus`, `reason`, `diagnostic`.
- `last_diagnostic_result`, `message`.

Continue native observer monitoring while `awaiting_observer`; a restored banner
cannot show or restrict controls until fresh native proof arrives. Continue
playback telemetry while `waiting`. During an active banner keep existing power,
input, physical-remote and voice protection, but do not request playback recovery.
Prevent Cobalt autoplay and stop background media through the controller's
existing mechanisms. The overlay requests transient-exclusive audio focus and
dispatches one balanced MEDIA_STOP pair; it relinquishes focus on release.
If another app steals focus, one coalesced recovery is deferred 3.5 seconds so
accepted phone dismissal takes precedence. Each incident permits at most two
recovery attempts, with another incident allowed after ten seconds of stable
focus. Every deferred attempt rechecks visible/proven banner state before
requesting focus or sending STOP; dismissal/detach cancels it. Status exposes
`audio_recovery_attempts`, `audio_recovery_pending`, and
`audio_recovery_exhausted`. An app that ignores audio focus and media STOP is
still a controller-level enforcement concern; no forced-Home loop was added.

The opaque accessibility overlay is nonfocusable and nontouchable. It shows the
exact requested sentence and lets existing phone key events through. The native
phone event observer is authoritative: a fresh nonvolume phone key dismisses an
active banner before session resync. Phone pause/play during the episode retains
the existing episode semantics. Phone Home/power/canonical leave cancels that
morning's pending banner. Existing volume exceptions remain unchanged.

## Completion, calendar, and persistence

Only a matching actual ENDED event activates the banner. The old session's
position-within-one-second completion branch is removed so it cannot discard the
subsequent true end or release protection early. Timeouts do not create banners.
When the original episode guard expires after duration plus five minutes, the
banner stays WAITING until 09:30 for authenticated actual-end evidence. This
allows an episode delayed by buffering to finish. WAITING supplies no expired
episode playback, power, or recovery authority; the controller retains only
bounded observer prearming and read-only playback telemetry. This state has
reason `waiting_after_episode_expiry`. Every nonvolume phone key cancels it,
because the expired canonical episode can no longer resolve generic navigation
into a phone-leave event. Observer loss remains terminal.

Banner time is local America/Los_Angeles, from episode completion to exactly
09:30. Sunday and U.S. federal actual/observed holiday dates are excluded.
Existing playback scheduling still owns Saturday and religious-holiday skips.
Dec 31 observation of next year's New Year's Day is included.

One bounded private preference record stores the run/video/date, completion and
deadline, and terminal parent dismissal. It stores no input proof or secrets.
Restart restoration waits for fresh observer proof. Once a proven observer is
lost, protection is terminally released for the morning. A same-day duplicate run
cannot undo parent dismissal. An old day's record cannot create a new banner.

## Explicit diagnostic

The existing DUMP-permission administrative receiver accepts
`command=test_banner`, `duration_seconds=1..60`. It requires an attached service
and current live native observer proof, rejects an existing real morning banner
or waiting session, and never persists diagnostic state or changes clock/time.
The exact banner, native controller route, phone dismissal, and timed release
can therefore be checked without waiting until morning.

Acceptance should cover: actual screenshot readability; active canonical status;
physical Home/power remaining blocked; real phone remote dismissing; voice/Google
Home maintaining the banner; release at a short diagnostic deadline; native
observer loss releasing protection; original daily alarm restored. A software
injected key alone does not prove the physical/phone identity distinction.

## Verified locally

`SchoolBannerPolicyTest` passes 109 behavioral checks covering completion identity,
staleness, approximate-end rejection, parent precedence, persisted dismissal,
observer loss, restart proof, exact cutoff, holiday fixtures, DST and host timezone,
bad records, duplicate runs, next-day reset, and deferred/bounded audio recovery.
The asset-route host regression passes 19 checks against the original readable
asset class with only the exact constructor-path delta. Both runtime classes compile
against Android API 35. The final builder passed its DEX/payload invariants.

## Existing player URL compatibility

The installed Cobalt APK already points to
`/1fe2154764b819ad81036d04882cca104f3c28787.js`. This exact path is computed from
the original player digest's first 41 characters and confirmed by the recovered
installed-APK repin record. One additional constructor patch keeps that route
stable while `PlayerScriptAsset` validates the new asset's actual SHA-256 and
returns the actual new hash in the ETag. Its original GET-only response handling,
strict bounded `?v=` matching, size bound, digest validation, and response bytes
are unchanged. The parent can replace the player.js and matching player.sha256
inside the small Guard APK without replacing the large Cobalt APK.

U.S. observed-date reference:
https://www.opm.gov/policy-data-oversight/pay-leave/federal-holidays/
