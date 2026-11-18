# Full Episodes playlist and actual YouTube history — 2026-11-18

This replaces the November 17 channel-wide, 75%-unwatched selector. It uses exactly the user's Full Episodes playlist `PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA`, considers videos in recent **upload** order, and requires strictly more than 40 minutes and at least 50% unwatched according to fresh signed-in YouTube account progress.

The canonical playlist is <https://www.youtube.com/playlist?list=PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA>. The supplied `/show/VL...` route initially presents “Top episodes for you,” which does not establish upload order. Public playlist membership is fully paginated, then intersected with the Classics channel's positively verified **Latest** Videos shelf. A newer channel video outside the exact playlist can never become a candidate. Current public WEB/MWEB lockup cards and `continuationItemViewModel` are supported, alongside legacy playlist video cards.

## Eligibility and account contract

The player supplies this envelope inside `ms_progress`:

```json
{
  "__youtube_history": {
    "schema": 1,
    "source": "youtube_playlist",
    "playlist_id": "PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA",
    "captured_at_ms": 1805580000000,
    "signed_in": true,
    "complete": true,
    "covered_ids": ["aaaaaaaaaaa"],
    "progress": {"aaaaaaaaaaa": 49}
  }
}
```

`captured_at_ms` is the actual completed capture time, an integer no more than 300 seconds old and never in the future. `covered_ids` and the numeric 0–100 progress map must match exactly. The player must have verified the signed-in response and exact playlist scope on **every** page and finished all continuations before publishing `complete:true`. A card with no resume overlay may be represented as 0 only when it was explicitly observed in that authenticated playlist response. Numeric partial progress takes precedence over a generic WATCHED label.

The helper accepts watched percentages through 50 inclusive. Exactly 40:00 is excluded; 40:01 qualifies. The existing protection policy supports videos through 6 hours, so the existing 6-hour ceiling remains. Missing account progress never means unwatched. Missing, stale, future, malformed, signed-out, wrong-playlist or incomplete history ends the run with `account_selection_failed`. A valid history with no qualifying candidate ends with `no_eligible_candidate`; there is no longest-video or cycling fallback.

The old outer numeric progress map, `__local_observed`, `played_ids`, and the confirmed-start ledger have no eligibility authority. The retained `recordConfirmedStart` ABI is a no-op. Existing per-run idempotency, phone override, startup authorization, wake deadlines and actual playback confirmation remain in their installed code.

## Bounded fetching

Public playlist membership must finish within 16 pages, 512 unique IDs and the shared 40-second additional-fetch budget. Failure, repeated continuation tokens or truncation yields no candidate map. The verified Latest upload prefix is bounded to 24 pages within the same budget and preserves 90 seconds of the helper's remaining startup lease. A failure on an older upload page may retain only the already verified newer playlist entries; it cannot add an unrelated or unknown-history fallback.

The mobile user agent receives a 302 from the `www` channel route. The new channel GET therefore uses the verified `https://m.youtube.com/@SesameStreetClassics/videos` destination directly, with redirects disabled. Continuation POSTs use the fixed YouTube browse endpoint and parsed public client identity. The initial playlist fetch continues through the installed helper's existing fetch method.

## Source and installed base

The full Java helper snapshot elsewhere in this repository predates the actual installed v12 worker/startup fences. This recovery source is the editable authority for the new selector. It is compiled and merged narrowly into the **actual November 17 signed helper**, not rebuilt from the older full helper snapshot.

Pinned input APK SHA256: `e9353160b28c323581ae82504507c73df2030123a2a2e90303683af276e40a8f`.

Pinned input DEX SHA256: `b09b50cce6562f0ff0e2f7b6619f3350ffa08e7dd87451439bbad60fd51ece95`.

`patch_playlist_selection.py` replaces the three selector classes `MorningEpisodePolicy`, `MorningSelectionRepair`, and `MorningChannelPage`, adds the history and playlist source/parser classes, and changes only `WakeAndPlayActivity.fetchCandidates()` outside those classes. It verifies the input hashes, disassembles the final DEX, proves every other existing class/method unchanged, checks the compiled class inventory, and preserves manifest/resources byte-for-byte. Compile-only API stubs are generated internally and never enter the output DEX. It produces an unsigned APK and an invariants report; signing and deployment are separate.

The resulting helper has been signed and installed. Installed APK SHA256: `4b378a10bebb6e6030f271cc7eb19f53abbc708d884afcb1cdc7a7c5d4da9999` (70,035 bytes), with the existing signing certificate, manifest and resources preserved. The actual installed player completed six authenticated playlist pages covering 83 episodes with zero refresh errors; its fresh complete account-history envelope was verified through DIAL. The next 7:00 AM alarm was verified and the device restored asleep. The combined release record is in `tools/morning_school_banner_20261118/README.md` and its `verification/deployment.json`.

## Verification

Run the actual production parsers, policy, adapter and bounded fetch implementation against synthetic account and transport fixtures:

```sh
python run_regression.py --json-jar /path/to/json.jar --output /tmp/playlist-regression
```

The suite covers exact duration/watch boundaries, explicit account coverage, future/stale history, account resets, removal of local/recent-start vetoes, no repeat fallback, partial membership, true upload order, playlist exclusion, cancellation during fetch, continued startup fencing, pagination beyond 21 entries and strict request budgets. HTTPS and Android persistence/run services are the fake boundaries. Account history from the household is never committed as a fixture.

Optional `tests/MorningPlaylistLiveCheck.java` performs public read-only membership/upload fetching using the same production Java source. It can also write the public candidate order for comparison with a separate private account snapshot. It never starts playback or reads account data. The November 18 public check found 83 qualifying full episodes in verified upload order; private account comparison belongs in the deployment evidence, not public fixtures.

Build against the preserved, pinned November 17 base and an Android SDK plus D8/smali. The newly installed output is not a replacement for this original input:

```sh
python patch_playlist_selection.py \
  --base /path/to/pinned-nov17-morning.apk \
  --android-jar /path/to/android.jar \
  --d8-jar /path/to/d8.jar \
  --smali-jar /path/to/jadx-1.5.6-all.jar \
  --output /tmp/playlist-build
```

The pinned original remains necessary for reproducible future recovery. Make future selector edits in these Java files, rerun the meaningful regressions, rebuild with the pinned patcher, and preserve its final invariants report with the accepted deployment evidence.
