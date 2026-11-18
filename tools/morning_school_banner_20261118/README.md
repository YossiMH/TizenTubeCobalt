# Morning Full Episodes selection and school reminder — November 18, 2026

This workstream applies the revised episode rule and adds the supplied video and music to the existing school reminder. The scheduled morning, episode protection, phone override and school-calendar policy keep their installed owners. Both packages are installed and their payloads verified. Live checks passed for the rich-media cycle, timed release, authenticated phone dismissal and the installed player's fresh account-history refresh. The device was restored asleep after acceptance.

## User rules

At 7:00 AM, select the most recently **uploaded** qualifying video in the exact [Sesame Street Full Episodes playlist](https://www.youtube.com/playlist?list=PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA). The supplied [show link](https://www.youtube.com/show/VLPLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA?sbp=Kgt2R1B2Y1FDRFZHWUAB) opens a personalized “Top episodes for you” view, so its displayed order is not the upload-order source.

An episode must be strictly longer than 40 minutes and at least 50% unwatched: its actual YouTube watched percentage must be at most 50, inclusive. The existing six-hour ceiling remains because that is the maximum supported by the installed episode-protection policy. There is no longest-video or history-cycling fallback when the eligible set is exhausted.

Eligibility follows fresh **signed-in YouTube account history**. Local playback measurements, previous launch receipts and “started today/yesterday” records have no eligibility authority. A launch or a locally detected end cannot manufacture an account percentage. Consequently, a current account value of 10% can qualify even if a previous local record marked the episode played; a current account value of 100% excludes it regardless of local records. Missing, stale, incomplete or signed-out account evidence does not become an unwatched episode.

Except on Sundays and U.S. national holidays, after the scheduled episode actually ends, the full-screen reminder appears through 9:30 AM with exactly:

> No more TV, Bernie -- it's time to leave for school!

The installed calendar uses `America/Los_Angeles` and includes the customary Friday/Monday observance of fixed-date federal holidays. Saturday remains eligible unless it is a holiday. The reminder transition requires actual completion evidence; a nominal duration or the clock passing 8:00 does not establish that the episode ended.

During the protected episode and reminder, the existing physical-remote and voice/Google Home protections remain in force. Phone virtual-remotes retain dismissal precedence. Dismissal and the 9:30 cutoff release the reminder's media, callbacks and audio focus. The existing authorization, observer proof and bounded recovery continue to determine whether protection is active.

## Exact reminder sequence

The first video frame appears beneath the sentence. The music loops continuously across reminder cycles, pausing during video playback and resuming from its retained position afterward. On initial activation, music fades in over five seconds inside the first hold. Each normal cycle draws a fresh uniform hold of 30–45 seconds, inclusive.

The following offsets are relative to **actual video completion**, except the initial hold and fade-out rows:

| Stage | Picture and text | Audio |
| --- | --- | --- |
| Initial hold, 30–45 seconds | First frame and full sentence | Looping music; initial five-second fade-in |
| Hold ends, +0 to +2 seconds | Text fades out over the first frame | Music begins its five-second fade-out |
| Hold ends, +5 seconds | Video starts | Music pauses; the video's own audio plays |
| Video completion, +0 seconds | Freeze on the exact last frame; text remains absent | Music resumes at its retained position and fades in over five seconds |
| Completion, +3 to +5 seconds | Text fades in over the last frame | Music finishes fading in |
| Completion, +5 to +8 seconds | Full text remains over the last frame for three seconds | Full music |
| Completion, +8 to +11 seconds | Last frame crossfades to the first frame over three seconds; text stays visible | Full music |
| Completion, +11 seconds | Begin the next independently selected 30–45 second first-frame hold | Music continues |

The three-second wait starts when the text fade-in finishes at +5 seconds. The frame transition therefore starts at +8 seconds and finishes at +11 seconds. The first image is composited over an opaque last image. Video aspect ratio is preserved. The exact first and last decoded frames come from the supplied video.

Only the actual video-completion callback advances to the return sequence. Focus loss pauses both media players and freezes the animation clock; authorized focus regain resumes the current sequence. Dismissal, observer loss, service detach or expiry cancel callbacks and release the players and surface. A decoder, asset or surface failure retains the existing protected static sentence and reports the media error.

The existing `test_banner` diagnostic remains capped at 60 seconds. It uses a deterministic 30-second hold to exercise a full cycle with the supplied short video; scheduled reminders use random holds. A diagnostic does not enroll or persist a new scheduled morning.

## Source ownership and account contract

| Source | Responsibility |
| --- | --- |
| Repository-root `x.js` | Fetch every authenticated page of the exact playlist; publish the authoritative account snapshot; retain actual-end reporting and phone-first banner lease checks |
| `tools/morning_sesame_wake_helper/recovery/playlist_selection_20261118/` | Validate account evidence and eligibility; retrieve complete public playlist membership; intersect with the positively verified Classics **Latest** upload shelf |
| `tools/onn_key_guard/recovery/school_reminder_20261118/` | Local video/music renderer, precise animation timeline, private sidecar verification and narrow Guard builder |
| `tools/onn_key_guard/recovery/school_banner_20261117/` | Retained school calendar, actual-completion authorization, phone precedence and existing build/test support |
| `tools/onn_session_policy/` | Retained controller, native input proof, canonical status and media authorization ownership |

`x.js` publishes `__youtube_history` inside the DIAL `ms_progress` value. The snapshot requires schema 1, source `youtube_playlist`, exact playlist ID `PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA`, `signed_in:true`, `complete:true`, a nonfuture capture time at most five minutes old, and matching `covered_ids` plus numeric 0–100 `progress` entries. The player verifies signed-in status and exact playlist scope on every page, then publishes only after all continuations complete. A card without a resume overlay becomes 0 only when it was explicitly observed in that authenticated playlist response. Numeric partial progress is preserved even when YouTube also displays a generic WATCHED label.

The helper requires full public playlist membership before it returns candidates. It can retain a verified newest upload prefix if a later upload page fails, but only exact playlist members with explicit eligible account progress can be selected. Network failure never activates the retired local-history fallback. The two source-specific READMEs document request budgets and precise error outcomes.

## Private media sidecars

The supplied media and concrete media manifest stay in private deployment storage. They are not repository source or public test fixtures. The Guard expects four fixed filenames beneath its own `getExternalFilesDir(null)/school_reminder_v1/` directory:

- `reminder.mp4`
- `music.flac`
- `first-frame.png`
- `last-frame.png`

The Guard builder embeds only the small byte-count/SHA256 manifest into `assets/school_reminder_manifest.json`. The runtime validates every sidecar before use and passes verified seekable descriptors to the native media players. No HTTP playback or additional storage permission is introduced. The private signing keys, account snapshots, actual sidecars and concrete manifest do not belong in the public source inventory.

**Required deployment permission:** the sidecar directory must be traversable by the Guard app. On this device, the ADB-created directory initially had mode `2770` and shell ownership, preventing app traversal. Set the directory to `0755` after copying the media:

```sh
adb shell chmod 755 /sdcard/Android/data/dev.yossi.onnkeyguard/files/school_reminder_v1
```

The four sidecar files remain `0644`. The observed repair changed only the directory permission. This step is required before the runtime can verify and open the supplied files.

## Installed bases and selective preservation

The repository's older full helper/Guard Java snapshots predate unpublished changes in the November 17 installed helper v12 and Guard v8. Rebuilding those older snapshots would lose their startup, authorization and phone-precedence behavior. These recovery builders instead compile the owned current sources and merge them into the preserved, hash-pinned November 17 APKs. Those original inputs remain the reproduction bases even after the newly signed packages are installed.

| Input | Required SHA256 |
| --- | --- |
| November 17 signed helper APK | `e9353160b28c323581ae82504507c73df2030123a2a2e90303683af276e40a8f` |
| Helper input DEX | `b09b50cce6562f0ff0e2f7b6619f3350ffa08e7dd87451439bbad60fd51ece95` |
| November 17 signed Guard APK | `b86e4d42e8533291c316bc4ae98bf09d3d94e9453b00ad996ea09a9c077104e6` |
| Guard input DEX | `5dae22b5dcfad6996559480b2deb6f6067b469a4315fa6ef12c232f4fe03c32c` |
| Guard's input player asset | `299bcd8c478d739a086d692c78f82d0f481b14d92a417ead92e43c5b2da66290` |

The helper builder replaces three selector classes, adds three classes, and changes one existing fetch adapter. Its final DEX round trip proves 351 existing methods remain unchanged and verifies that manifest/resources match the input. The Guard reminder builder replaces only `SchoolBannerRuntime` and its nested runtime classes, adds `SchoolReminder*`, preserves its public hook ABI, and proves all retained classes/methods unchanged. Compile-only API stubs do not enter either DEX. The reminder builder initially retains the input `assets/player.js`; release integration then replaces that asset with repository-root `x.js` before signing.

## Reproduction

Run these from a full repository checkout. Java with the `jdk.compiler` module, Python, Node, Android API 35, D8 and the Smali classes in the pinned JADX distribution are required. The helper host regressions also use an `org.json` JAR. Existing November 17 source/test prerequisites remain at their repository paths.

```sh
node tools/tizentube_allowed_only_test.js
node tools/morning_school_banner_20261118/player/player_morning_test.js
python tools/morning_sesame_wake_helper/recovery/playlist_selection_20261118/run_regression.py \
  --json-jar /path/to/json.jar --output /tmp/playlist-regression
python tools/onn_key_guard/recovery/school_reminder_20261118/run_regression.py \
  --output /tmp/reminder-regression
```

Build both unsigned candidates against the pinned inputs:

```sh
python tools/morning_sesame_wake_helper/recovery/playlist_selection_20261118/patch_playlist_selection.py \
  --base /private/base/morning-signed.apk \
  --android-jar /path/to/android-35/android.jar \
  --d8-jar /path/to/build-tools/35.0.0/lib/d8.jar \
  --smali-jar /path/to/jadx-1.5.6-all.jar \
  --output /tmp/playlist-build

python tools/onn_key_guard/recovery/school_reminder_20261118/build_guard_reminder.py \
  --base /private/base/guard-signed.apk \
  --android-jar /path/to/android-35/android.jar \
  --d8-jar /path/to/build-tools/35.0.0/lib/d8.jar \
  --smali-jar /path/to/jadx-1.5.6-all.jar \
  --media-manifest /private/media/media-manifest.json \
  --output /tmp/reminder-build
```

For the integrated Guard, copy the reminder builder's unsigned APK payloads while replacing `assets/player.js` with the current root script. The following step preserves all other ZIP entries and verifies the exact script bytes before signing:

```sh
python - <<'PY'
from pathlib import Path
import zipfile
source = Path('/tmp/reminder-build/guard-reminder-unsigned.apk')
target = Path('/tmp/reminder-build/guard-integrated-unsigned.apk')
player = Path('x.js').read_bytes()
with zipfile.ZipFile(source) as old, zipfile.ZipFile(target, 'w') as new:
    assert old.namelist().count('assets/player.js') == 1
    for entry in old.infolist():
        new.writestr(entry, player if entry.filename == 'assets/player.js' else old.read(entry.filename))
with zipfile.ZipFile(source) as old, zipfile.ZipFile(target) as new:
    assert new.read('assets/player.js') == player
    for name in old.namelist():
        if name != 'assets/player.js':
            assert new.read(name) == old.read(name)
PY
```

Retain both builders' invariants reports, record the integrated asset/APK hashes, then use the existing private signing and in-place installation procedure. Install the matching media sidecars before live reminder acceptance. The deployed hashed player route and readback must be checked against the packaged `x.js`. The source inventory deliberately excludes signing material, private account evidence, media and compiled APKs.

## Verification and deployment record

The selector's 177 host assertions cover exact thresholds, account freshness and scope, removal of local/start vetoes, exhausted history, complete membership, upload ordering, cancellation and pagination beyond the original first page. Its public read-only live check retrieved all 83 currently qualifying playlist episodes in upload order in 12,258 ms. These public-source results do not substitute for the signed-in account check or Android media acceptance.

The reminder regression entrypoint exercises the actual timeline and the retained banner/calendar/asset tests. Detailed source-specific evidence is retained with each recovery directory.

### Installed payloads

Both APKs installed successfully with the existing signing certificates, manifests and resources preserved. The deployed player asset was served and its bytes verified.

| Installed or served payload | Bytes | SHA256 |
| --- | ---: | --- |
| Signed helper | 70,035 | `4b378a10bebb6e6030f271cc7eb19f53abbc708d884afcb1cdc7a7c5d4da9999` |
| Signed integrated Guard | 119,633 | `1a56676eb5eaef7fda9a0d8a33543e6002accb2ec49bb26fd3d7133ea3b0c2a3` |
| Packaged and served player script | 83,041 | `b42d6c054448ea37d691751466aee224b77d8b29d89e81f7a2a4df99ad589b11` |

### Completed rich-media acceptance

The second bounded live cycle passed all 11 checks across 115 samples. It observed hold → fade-out → video → return → next hold, retained protection during the reminder, paused music during the video, no simultaneous audio sources, absent text during video playback, the frame crossfade, and media/audio-focus/native-grab release at diagnostic expiry. The actual first-frame screenshot showed the supplied Big Bird frame and the exact sentence legibly.

The music playhead stayed at 34,669 ms while paused and resumed at 34,796 ms in the first sampled return frame. These are **positions within the music track**, rather than elapsed reminder-phase times. Their 127 ms difference reflects playback resumed before that sample; it does not shift the +3/+5/+8/+11 timeline.

The sequence offsets refer to the monotonic animation clock and actual video-completion callback. Live status is sampled, and `phase`, `phase_elapsed_ms` and displayed/played fields can straddle one animation tick at a transition. The first sampled evidence of a transition can therefore follow its exact timeline boundary. The 60-second diagnostic expiry proves bounded release; the normal reminder's deadline remains 9:30 AM.

### Phone dismissal and installed account refresh

An authenticated synthetic phone-key dismissal while music was playing released the media players, audio focus and native grabs, then restored the canonical session identity. This verifies the existing phone-event path and precedence; the event was submitted by the bounded acceptance tool.

The actual Cobalt player was reloaded with the installed script, verified ready and signed in, and completed six authenticated playlist pages covering 83 episodes with zero reported refresh errors. Its fresh complete `__youtube_history` envelope was read back through DIAL. Account percentages and watched-video IDs remain in private evidence. The next scheduled alarm was verified for November 18 at 7:00 AM in `America/Los_Angeles`, and the device was restored asleep.

All three captured states were visually checked: the correct first frame with the exact legible sentence, video playback with text absent, and the return crossfade. The public summary of installed hashes and check counts is [verification/deployment.json](verification/deployment.json); full receipts and images remain private.

The public reproduction tools are `recovery/acceptance.py` and `recovery/rich_acceptance.py`. They retain bounded diagnostics and authenticated event handling. Receipts and screenshots remain private deployment evidence.
