# School reminder with local video and music

This is a selective extension of the installed November 17 Guard. It replaces
`SchoolBannerRuntime` and adds the `SchoolReminder*` classes. The existing
calendar, actual episode completion requirement, phone override, native input
proof, media lease, voice protection, deadline, and bounded audio-focus recovery
retain their existing owners and behavior. This work does not change the
independently owned native migration.

## Sequence

The exact existing sentence appears over the first video frame. At first
activation, looping music begins with a five-second fade inside the first hold.
Every normal cycle selects a fresh uniformly distributed hold from 30,000 to
45,000 milliseconds inclusive. Music is paused during the video and resumes at
its retained position afterward; it is never rewound between these transitions.

| Relative time | Picture and text | Audio |
| --- | --- | --- |
| Initial hold: 30–45 s | First frame, full text | Looping music |
| Hold ends +0..2 s | Text fades out over first frame | Music fades out over 5 s |
| Hold ends +5 s | Video starts | Music pauses; video's own audio plays |
| Actual video completion +0 s | Last frame; text absent | Resume music, fading in for 5 s |
| Completion +3..5 s | Text fades in over last frame | Music fade continues |
| Completion +5..8 s | Full text over last frame | Full music |
| Completion +8..11 s | Last frame crossfades to first over 3 s | Full music |
| Completion +11 s | Next first-frame hold begins | Music continues |

The three-second wait starts **after** the text fade finishes at completion +5 s.
The frame crossfade therefore starts at +8 s. The first image is composited over
an opaque last image, preventing a brightness dip from fading both layers away.
The video is letterboxed to preserve its aspect ratio. A modest dark scrim and
text shadow keep the existing white message readable; the scrim fades with text.

Only `MediaPlayer.OnCompletionListener` advances the video phase. Its declared
duration, a stalled clock, or a pause cannot manufacture completion. A surface
update or rendering-start event removes the first-frame cover once the video
actually draws. First and last stills are exact decoded endpoint frames prepared
from the supplied video, not a guessed near-end frame.

## Media and audio ownership

`SchoolBannerRuntime` retains the existing transient-exclusive audio focus and
balanced media STOP pair. The new players do not register a media session or
alter system volume. Focus loss immediately pauses both sources and freezes the
animation clock. A valid focus regain resumes the same sequence and music
position. Existing delayed/coalesced recovery still gives phone dismissal
precedence and has its original retry bounds.

Dismissal, observer loss, service detach, and the existing 09:30 cutoff cancel
all callbacks, interrupt asset loading, and release both players and the Surface
before abandoning focus. Late prepared, seek, rendering, completion, and focus
callbacks cannot revive a closed renderer. Each animation callback rechecks the
existing banner authority. An asset/decoder/surface error retains the protected
static message and exposes the failure in status.

The TextureView uses a hardware-accelerated accessibility overlay. Android's
documented APIs support its alpha/transform composition and safe closure of
source file descriptors after `MediaPlayer.setDataSource` returns:

- https://developer.android.com/reference/android/view/TextureView
- https://developer.android.com/reference/android/media/MediaPlayer

## Private media contract

Store these four fixed filenames under the Guard's own
`getExternalFilesDir(null)/school_reminder_v1/` directory:

- `reminder.mp4`
- `music.flac`
- `first-frame.png`
- `last-frame.png`

The media and their concrete manifest are private deployment sidecars, not
repository source. The builder embeds the small manifest as
`assets/school_reminder_manifest.json` in the signed APK. Its schema is:

```json
{
  "schema": 1,
  "assets": {
    "reminder.mp4": {"bytes": 123, "sha256": "64 lowercase hexadecimal characters"},
    "music.flac": {"bytes": 123, "sha256": "64 lowercase hexadecimal characters"},
    "first-frame.png": {"bytes": 123, "sha256": "64 lowercase hexadecimal characters"},
    "last-frame.png": {"bytes": 123, "sha256": "64 lowercase hexadecimal characters"}
  }
}
```

The example values are placeholders. Every actual media file is checked against
the manifest's byte count and SHA-256 on a background loader. Paths cannot escape
the fixed directory. Each audio/video file is capped at 64 MiB; each PNG at 12 MiB
and 1920×1080 decoded pixels. Endpoint images must have matching dimensions.
Already verified, seekable file descriptors go to the native media players;
no storage permission or HTTP playback is added.

The sidecar directory must have mode `0755` so the Guard can traverse it. The
ADB-created directory on the deployed device initially had mode `2770` with
shell ownership and prevented asset loading. The required deployment repair is:

```sh
adb shell chmod 755 /sdcard/Android/data/dev.yossi.onnkeyguard/files/school_reminder_v1
```

The four files remain mode `0644`; only the directory permission needed changing.
Apply this directory permission after copying the sidecars and before live
acceptance.

## Build and tests

The builder requires the preserved November 17 Guard base SHA-256
`b86e4d42e8533291c316bc4ae98bf09d3d94e9453b00ad996ea09a9c077104e6`.
It compiles Java 8 bytecode against API 35, then packages only the replacement
runtime and reminder classes. Existing policy classes and ABI stubs are used
for compilation and are never packaged. A Smali round trip checks every retained
class, field, and method. The public runtime hook ABI, existing manifest, player
asset, resources, and other non-code payloads remain byte-for-byte unchanged.

```sh
python tools/onn_key_guard/recovery/school_reminder_20261118/run_regression.py \
  --output /path/to/reminder-tests
python tools/onn_key_guard/recovery/school_reminder_20261118/build_guard_reminder.py \
  --base /path/to/guard-signed.apk \
  --android-jar /path/to/android-35/android.jar \
  --d8-jar /path/to/build-tools/35.0.0/lib/d8.jar \
  --smali-jar /path/to/jadx-1.5.6-all.jar \
  --media-manifest /private/path/to/media-manifest.json \
  --output /path/to/empty-build-directory
```

The builder output APK is unsigned. The integrated release has since been
signed and installed with APK SHA-256
`1a56676eb5eaef7fda9a0d8a33543e6002accb2ec49bb26fd3d7133ea3b0c2a3`
(119,633 bytes). Its existing signing certificate, manifest and resources were
preserved. Release integration also replaced `assets/player.js` with the revised
account-history script; that served asset was verified at SHA-256
`b42d6c054448ea37d691751466aee224b77d8b29d89e81f7a2a4df99ad589b11`
(83,041 bytes). Future reproduction still requires the original pinned input,
the private sidecars/manifest and the signing procedure.

## Bounded acceptance

The unchanged `test_banner` diagnostic remains capped at 60 seconds. Its media
renderer uses a deterministic 30-second hold, permitting one full cycle of the
provided short video within that existing bound. Scheduled reminders always use
fresh random holds. Diagnostic playback does not enroll or persist a morning.

`school_banner.reminder_media` exposes `phase`, `ready`, `error`, `cycle`,
`hold_ms`, `phase_elapsed_ms`, `music_playing`, `music_position_ms`, `music_volume`,
`video_playing`, `video_position_ms`, `text_alpha`, `first_frame_alpha`,
`frame_crossfade_alpha`, and `focus_held`. Phase and displayed/played fields can
differ by one animation tick during a boundary. The existing canonical banner
status remains authoritative for protection and expiry.

Live acceptance should observe the media sequence, retained music position,
last-frame pause and crossfade, audio silence and player release at timed expiry,
and phone dismissal. Existing physical/voice enforcement is preserved rather
than reclassified by this media renderer.

The second live bounded cycle passed all 11 media checks across 115 samples:
hold, fade-out, video, return and the next hold were observed; music paused at
34,669 ms and resumed at 34,796 ms; no audio overlap occurred; text stayed absent
during video playback; the frame crossfade was observed; protection remained
active; expiry released the players, focus and native grabs. The actual initial
frame and exact sentence were also inspected for legibility. The music numbers
are track positions, not phase offsets. Samples can fall after a timeline
boundary and status fields may span one animation tick, so they do not replace
the precise +3/+5/+8/+11 sequence above.

An authenticated synthetic phone-key dismissal while music was playing also
released the players, focus and native grabs and restored the canonical session
identity. The actual installed player completed a fresh six-page, 83-episode
authenticated history refresh with zero reported errors; the complete envelope
was verified through DIAL. All three screenshot states were visually checked,
the next 7:00 AM alarm was verified, and the device was restored asleep. The
combined release record and sanitized deployment summary are in
`tools/morning_school_banner_20261118/README.md` and its
`verification/deployment.json`. Full receipts and media remain private.
