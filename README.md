# 💠 TizenTube Cobalt — Liked + Subscriptions Only

<p align="center">
    <img width="700px" src=".github/assets/TizenTube_Cobalt-Official_Banner.png">
    <br>
</p>

This fork of **TizenTube Cobalt** keeps the original TizenTube experience while adding an account-based allowlist: **a video is available only when you have liked that video or you are subscribed to its channel** on the signed-in YouTube account.

The restriction is applied at document start and filters YouTube discovery/playback responses used by search results, home/recommendation shelves, related suggestions, queues/autoplay, and player responses. The allowlist is rebuilt from the signed-in account's Liked videos and subscriptions, so likes/subscriptions made on other devices are picked up from the same account.

> This is an independent fork. The upstream project is [reisxd/TizenTubeCobalt](https://github.com/reisxd/TizenTubeCobalt).

## ⬇️ Download the allowed-only build

**Current release: [TizenTube Liked + Subscriptions v1](https://github.com/YossiMH/TizenTubeCobalt/releases/tag/allowed-only-v1)**

Choose the APK for your device:

- **ARM64 / `arm64-v8a` (most modern Android TV / Google TV devices):** [TizenTube-Liked-Subs-arm64.apk](https://github.com/YossiMH/TizenTubeCobalt/releases/download/allowed-only-v1/TizenTube-Liked-Subs-arm64.apk)
- **32-bit ARM / `armeabi-v7a` (older devices):** [TizenTube-Liked-Subs-arm.apk](https://github.com/YossiMH/TizenTubeCobalt/releases/download/allowed-only-v1/TizenTube-Liked-Subs-arm.apk)
- [SHA-256 checksums](https://github.com/YossiMH/TizenTubeCobalt/releases/download/allowed-only-v1/SHA256SUMS.txt)

The fork APK uses package ID `io.gh.yossim.tizentube.cobalt`, so it can be installed beside upstream TizenTube Cobalt rather than replacing it.

## Updates

Starting with the next release, every release is signed with the same permanent key, so a future release can be installed directly over the previous one (no need to uninstall first). The release pipeline now refuses to build an installer with a temporary signing key, because that would silently break future updates for everyone who installed it.

One-time note for the current release: the first release was signed with a one-time key that no longer exists. If you installed that first release and later install the next one, Android may ask you to uninstall the old app first. That is a one-time step; after that, future releases update in place.

## 🔒 What “allowed only” means

A video passes the filter when **either** condition is true:

1. Its video ID is present in the signed-in account's **Liked videos** list, or
2. Its channel ID is present in the signed-in account's **subscriptions**.

Everything else is removed from video-bearing discovery responses. A disallowed direct/player response has its streaming data removed and is returned as unavailable instead of being allowed to play.

The filter covers the YouTube internal API surfaces used for browse/home feeds, search, next/related/autoplay, player responses, reels, and queue responses. Non-video UI such as search text suggestions is not intentionally removed.

## ✅ Verification included in this fork

- Unit tests cover liked videos, subscribed-channel videos, disallowed videos, player blocking, subscription extraction, liked-video extraction, and continuation handling.
- The release builder verifies the exact upstream APK hashes before patching.
- The produced APKs are zip-aligned, signed, checked for the fork package ID/label, and checked to contain the document-start loader pinned to the exact source commit used for the release.
- The release contains both ARM and ARM64 APKs plus SHA-256 checksums.

Run the JavaScript filter tests locally with:

```bash
node tools/tizentube_allowed_only_test.js
```

## ✨ Upstream TizenTube features

The fork retains TizenTube Cobalt features including:

- 🛑 Ad blocking
- ❗ SponsorBlock support
- ⏭️ Video speed control
- 🔺 DeArrow support

## ❔ Install and sign in

1. Download the correct APK above.
2. Sideload/install it on the Android TV / Google TV device.
3. Launch **TizenSub+**.
4. Sign into the same YouTube account whose Likes and subscriptions should define the allowed catalog.
5. Reload/relaunch the app after making account changes elsewhere when you want to force a fresh allowlist sync immediately.

## ℹ️ Upstream community & support

For upstream TizenTube questions, see the original project's community links:

- [Upstream repository](https://github.com/reisxd/TizenTubeCobalt)
- [Discord Server](https://discord.gg/m2P7v8Y2qR)
- [Telegram Channel](https://t.me/tizentubecobaltofficial)
- [Matrix Space](https://matrix.to/#/!BLE5ubNYktI30e8K0j:matrix.6513006.xyz)
