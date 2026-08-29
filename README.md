# TizenTube Cobalt — Account-Library Allowlist

<p align="center">
    <img width="700px" src=".github/assets/TizenTube_Cobalt-Official_Banner.png">
    <br>
</p>

This fork of **TizenTube Cobalt** keeps the TizenTube experience while adding a fail-closed account allowlist: **a video is available only when the signed-in YouTube account supplies trustworthy account-library proof for it**. That includes liked videos, videos from subscribed channels, videos in account-owned playlists, supported YouTube Music library/artist-album collections, and explicitly owned purchased titles.

The restriction is applied at document start and filters YouTube discovery/playback responses used by search results, home/recommendation shelves, related and post-playback suggestions, queues/autoplay, and player responses. The allowlist is rebuilt from signed-in account data, so supported library changes made on other devices are picked up from the same account. Signed-out/guest mode remains fail-closed.

> This is an independent fork. The upstream project is [reisxd/TizenTubeCobalt](https://github.com/reisxd/TizenTubeCobalt).

## Download the allowed-only build

**Current release: [TizenTube Liked + Subscriptions v3 (hardened rebuild)](https://github.com/YossiMH/TizenTubeCobalt/releases/tag/allowed-only-v3-rebuild)**

Choose the APK for your device:

- **ARM64 / `arm64-v8a` (most modern Android TV / Google TV devices):** [TizenTube-Liked-Subs-arm64.apk](https://github.com/YossiMH/TizenTubeCobalt/releases/download/allowed-only-v3-rebuild/TizenTube-Liked-Subs-arm64.apk)
- **32-bit ARM / `armeabi-v7a` (older devices):** [TizenTube-Liked-Subs-arm.apk](https://github.com/YossiMH/TizenTubeCobalt/releases/download/allowed-only-v3-rebuild/TizenTube-Liked-Subs-arm.apk)
- [SHA-256 checksums](https://github.com/YossiMH/TizenTubeCobalt/releases/download/allowed-only-v3-rebuild/SHA256SUMS.txt)

The fork APK uses package ID `io.gh.yossim.tizentube.cobalt`, so it can be installed beside upstream TizenTube Cobalt rather than replacing it.

## Updates

Every release from v2 onward is signed with the same permanent key, so a future release can be installed directly over the previous one (no need to uninstall first). The release pipeline refuses to build an installer with a temporary signing key, because that would silently break future updates for everyone who installed it.

One-time note: the first release (v1) was signed with a one-time key that no longer exists. If you installed v1 and now install v2 or v3, Android may ask you to uninstall the old app first. That is a one-time step; from v2 onward, future releases update in place.

## What "allowed only" means

A video passes the filter only when one of these trusted account relationships is proven:

1. Its video ID is present in the signed-in account's **Liked videos** list.
2. Its channel ID is present in the signed-in account's **subscriptions**.
3. Its video ID was discovered inside an **account-owned playlist**.
4. Its video ID was discovered through the supported **YouTube Music account-library / artist-album collection** paths.
5. It is explicitly marked as **purchased/owned** by the signed-in account on the trusted purchase-library path.

Everything else is removed from video-bearing discovery responses. A disallowed direct/player response has its streaming data removed and is returned as unavailable instead of being allowed to play. Account-library extraction is provenance-sensitive: an orphan video ID or a lookalike response is not enough to authorize playback.

The filter covers the YouTube internal API surfaces used for browse/home feeds, search, next/related/autoplay, player responses, reels, notification inbox items, and queue responses, plus a DOM safety sweep for post-playback suggestions that can be inserted after the API response. Non-video UI such as search text suggestions is not intentionally removed.

**Signed out or guest mode is fully blocked.** The allowed list comes only from trustworthy signed-in account data, so when no account is signed in every video is removed from home/search/related/post-playback surfaces and every player response is returned as unavailable. Nothing is watchable until the account relationship needed by the allowlist is proven.

## Verification included in this fork

- Permanent JavaScript tests cover liked/subscribed and account-library authorization, disallowed/player blocking, post-playback suggestion filtering, guest fail-closed behavior, Ad Blocker payload sanitization, SponsorBlock segment handling, DeArrow title/thumbnail behavior, playback-speed controls, boot recovery, visible status, and loader double-load protection.
- APK patcher tests verify dex integrity, Trusted-Types-safe document-start loading, same-length native CDN pinning, disabled Evergreen update endpoints, and release verification.
- The release builder verifies the exact upstream APK hashes before patching.
- Produced APKs are zip-aligned, signed with the persistent update key, checked for the fork package ID/label, and checked to contain the document-start loader pinned to the exact source commit used for the build.

Run the focused JavaScript suites locally with:

~~~bash
node tools/tizentube_watch_suggestions_test.js
node tools/tizentube_feature_settings_test.js
node tools/tizentube_adblock_test.js
node tools/tizentube_sponsorblock_test.js
node tools/tizentube_dearrow_test.js
node tools/tizentube_speed_control_test.js
node tools/tizentube_allowed_only_test.js
node tools/tizentube_library_allowlist_test.js
node tools/tizentube_boot_recovery_test.js
node tools/tizentube_status_ui_test.js
~~~

## TizenTube features

The APK's single document-start script slot loads one combined fork runtime. The allow-only/media guard and TizenTube extras therefore do not compete by wrapping the same network APIs twice.

The combined runtime restores these controls while keeping allow-only enforcement non-disableable:

- **Ad Blocker** — enabled by default; strips YouTube ad placement/player-ad fields and ad-only cards.
- **SponsorBlock** — enabled by default; automatically skips the supported SponsorBlock categories, with per-category on/off controls.
- **DeArrow** — enabled by default; applies community titles and thumbnails to matching allowed videos when branding is available.
- **Playback speed** — 0.25× through 5× in 0.25× steps, plus the upstream 1.0001× stutter workaround.

To open the controls with an ordinary TV remote, enter **YouTube TV Settings**. TizenSub+ automatically opens its TizenTube Settings overlay there. Use Up/Down and Select to change toggles, Left/Right on playback speed, and Back to return to YouTube Settings. Colored-key shortcuts remain optional rather than required.

## TV setup and phone remote control

- Install the APK from the download section above (ARM64 for modern boxes like the Onn 4K; ARM for older devices).
- On the box, one-time step: enable Developer options (Settings > About > tap Build 7 times), enable USB/network debugging, and allow this PC when it asks.
- Optional: run the included setup script to remove every other YouTube-capable app on the box (stock YouTube, browsers including AnExplorer's embedded browser, SmartTube) without changing the OS or launcher. It picks the right app build automatically (ARM64 for modern boxes like the Onn 4K Pro, 32-bit ARM for older devices):

```powershell
pwsh tools/onn_setup.ps1 -Serial 192.168.1.172:5555
```

- The setup script refuses to install anything but the verified guarded
  build, cold-starts the app, and confirms from the device log that the
  guardian reached boot-ready. If startup hits a network-error screen, it uses
  a bounded wake-display-and-restart recovery because Android TV can block an
  idle app's network through Doze even while Wi-Fi remains healthy. Recovery
  fails setup instead of warning-only success. Re-run it anytime with
  `-VerifyApp` to re-assert the lock-down and re-check the guardian, e.g. after
  the box's Google account re-verifies.

- Sign in on the TV with the Google account whose Likes/subscriptions define the allowed catalog. Signed-out/guest mode shows nothing, by design.
- Phone remote control: open the YouTube app on your phone, tap the cast/remote icon, and pair with this TV (standard YouTube TV pairing). Pairing passes through the filter untouched; only disallowed playback is blocked, so phone control of the TV works while the TV still refuses to play anything outside the allowed list.

## Install and sign in

1. Download the correct APK above.
2. Sideload/install it on the Android TV / Google TV device.
3. Launch **TizenSub+**.
4. Sign into the same YouTube account whose Likes and subscriptions should define the allowed catalog.
5. Reload/relaunch the app after making account changes elsewhere when you want to force a fresh allowlist sync immediately.

## Upstream community & support

For upstream TizenTube questions, see the original project's community links:

- [Upstream repository](https://github.com/reisxd/TizenTubeCobalt)
- [Discord Server](https://discord.gg/m2P7v8Y2qR)
- [Telegram Channel](https://t.me/tizentubecobaltofficial)
- [Matrix Space](https://matrix.to/#/!BLE5ubNYktI30e8K0j:matrix.6513006.xyz)
