# Morning Sesame episode-selection repair

This repair starts from the installed Morning Sesame v12 APK, SHA256
`4bfbeebfde3d308bd9dfd4039d0d759f80bbd1618efac59d56bc062a3fdb1f53`.
It retains the unpublished startup authentication, phone-intent, worker, and
fence-delivery repairs in that exact APK. Rebuilding the older GitHub-main helper
would discard those repairs; this patch changes five existing methods and adds
five readable production classes instead.

## Behavior

`MorningEpisodePolicy` chooses the first eligible item in newest-first channel
order. Duration must be strictly greater than 2,400 seconds and at most six hours,
matching the installed session-protection limit. Longer compilations are excluded
so they cannot launch without the requested remote protections. Known account and
local watched percentages must each be finite, nonnegative, and at most 25.
A known disqualifying percentage never enters a later fallback.

The existing signed-in progress reader, including its v12 freshness check, is
unchanged. The progress JSON gains an optional `__local_observed` object supplied
by the player repair. Its values are durable measured playback percentages, and
ordinary video-ID entries remain the effective maximum of account and local
observations. This reserved metadata object is read only for candidate video IDs.

Legacy `played_ids` means a startup was confirmed, not that the video was watched
in full. Such an ID stays excluded unless fresh local observation proves that no
more than 25 percent has been watched. An independent, bounded start ledger skips
anything started today or yesterday in America/Los_Angeles even when the actual observation is still
low. An older interrupted episode can therefore qualify again after that window.
For a video with neither account evidence nor any local start receipt, the
existing local-history fallback remains available; no progress is fabricated.

The start ledger is updated only inside the existing independently confirmed
playback checkpoint, after startup fencing has passed. It migrates existing
receipts before the old 250-entry reset, stores at most 1,024 IDs, and occupies at
most 33,792 characters. It stores dates of starts, separately from watch progress.

`MorningChannelPage` reads only the selected Videos shelf for the pinned Sesame
Street Classics channel. It correctly decodes the current single-quoted mobile
JSON form, also accepts the desktop object form, handles minute/hour durations,
deduplicates IDs, and rejects a wrong channel or a visibly Popular/Oldest sort.
It follows only the selected shelf's continuation, never another tab's videos.

`MorningChannelSource` follows the same channel's public browse continuation:
at most 12 total pages, 512 qualifying IDs, 40 seconds of extra retrieval, and
3 MB per response. It retains verified newer pages if an older page fails. It
checks the existing startup/phone fence and wake lease throughout retrieval and
leaves at least 90 seconds of the original run budget for normal player startup.
Requests send only public client identifiers and locale, not sign-in credentials.

If every available candidate is ineligible, the helper records
`no_eligible_candidate` and schedules no repeating playback. That reason is
appended to the diagnostic enum without changing prior ordinals. The account
event uses the existing `no_candidates` result.

## Exact bytecode changes

1. Replace `AlarmReceiver.selectAndVerify` with a small adapter calling the
   existing fresh account-progress reader and the new selector.
2. Replace `WakeAndPlayActivity.fetchCandidates` with a small adapter calling
   the original bounded initial HTTP request and the new page parser/paginator.
3. Insert one confirmed-start-ledger call into `RunCoordinator.confirmPlayed`,
   after its original current/fenced-run check and before the old history reset.
4. Append one diagnostic reason in `DiagnosticArchive.<clinit>`.
5. Update the candidate-retrieval error text from 30 to 40 minutes.

The patcher pins the input APK, DEX, and all five original method hashes. It
reassembles and disassembles the candidate and verifies every other existing
method/class byte-for-byte, along with the unchanged manifest and resources.
Compile-only Android adapter stubs are explicitly excluded from the DEX.
The result retains the installed package name/version/signature requirements;
the parent task owns signing, deployment, and live acceptance.

## Reproduction

Run from this directory with a JDK, Android API 35, D8, and the Smali 3.0.9
classes. Build outputs and the pinned base APK stay outside this checkout.
See the shared [integration guide](../../../morning_school_banner_20261117/README.md)
for player packaging, signing, and deployment status.

```sh
python patch_selection.py \
  --base /path/to/MorningSesame-v12-worker-fences.apk \
  --smali-jar /path/to/jadx-1.5.6-all.jar \
  --d8-jar /path/to/android-sdk/build-tools/35.0.0/lib/d8.jar \
  --android-jar /path/to/android-sdk/platforms/android-35/android.jar \
  --output /path/to/new-selection-build
python run_regression.py --json-jar /path/to/json-20240303.jar --output /path/to/selection-test-classes
```

The host-only JSON test dependency is `org.json:json:20240303`; Android supplies
the production implementation. Retrieve it from
`https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar`;
SHA256 `3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`.
Test fixtures under `tests/fixtures` retain only
the relevant public channel renderers from the November 17 UTC response.
They omit unrelated visitor and tracking metadata. Java fixture classes are
narrow host test adapters and never enter the APK.

## Verification

All **135 assertions** pass: 62 eligibility/rotation assertions, 14 bounded
start-history/date assertions, 11 receiver-adapter assertions, 18 source parser
assertions, and 30 bounded pagination/cancellation assertions. They exercise the
actual new production classes. Examples include exactly 40:00 versus 40:01,
exactly 25 percent versus a fraction above it, stale account zeros, actual local
progress, no candidate after repeated exhausted mornings, yesterday across DST,
the legacy receipt reset, public continuation paging, changing/repeated tokens,
network failure, budget exhaustion, and cancellation during response reads.

The pinned APK build verifies 73 existing classes and 344 existing methods:
**339 methods are unchanged**, five are intentionally changed, and five new
classes are added. DEX checksums/signatures and the assembly round trip pass.
The untouched startup/fence methods retain the exact live implementation.

This directory contains no signing key. No PC or TV mutation was made while
preparing and testing this component.
