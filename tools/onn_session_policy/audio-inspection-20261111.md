# Streaming audio inspection — 11 November 2026

## Current result

The Hebrew preference policy is implemented and tested as a pure policy. Automatic adapters for Netflix, Disney+, and Prime Video remain **unverified and inactive**. This inspection read installed APK metadata, resource tables, selected layouts, and relevant DEX code through ordinary authorized access. It did not open apps, change profiles or language settings, select tracks, or bypass the Google TV profile PIN.

Normal unlocked playback is required to verify the actual menu hierarchy, exposed selection state, stable item identity, and real phone/physical selection events. The currently observed Google TV PIN screen prevents that validation. Static resources alone do not establish which UI implementation a server-configured player is using.

## Installed versions and evidence fingerprints

| App | Package | Installed version | Base APK SHA-256 |
| --- | --- | --- | --- |
| Netflix | `com.netflix.ninja` | `12.2.3 build 24023` (`24023`) | `823bb1c9685888a949770853ebc5c59e9beed87ddae80d96789377a2041b9993` |
| Disney+ | `com.disney.disneyplus` | `26.0.1+rc1-2026.01.26` (`1769444680`) | `1cf7a50f18543d9fde9a554b047c145a3a4117b8559c6e8d0167e0eb32502d4b` |
| Prime Video | `com.amazon.amazonvideo.livingroom` | `6.23.23+v15.5.0.70-armv7a` (`606023231`) | `043bf53987aa139f9a45d5a0924aa34416a6cf1a845332ca0484d84f94735861` |

Disney's relevant `classes3.dex` SHA-256 is `4922fafad5a8ea9d29d32e704fc834dcdc1d105c463c0523362952dd8a8fec37`. Its decoded manifest version agrees with the installed package manager result. These fingerprints identify inspected builds, not a promise that app UI is immutable.

## Disney+ legacy TV menu

The installed `res/layout/view_audio_and_subtitles_tv.xml` and `res/layout-television-v13/playback_track_item.xml` establish the following resource names. All IDs below have the prefix `com.disney.disneyplus:id/`.

| Resource | Evidence-backed meaning | Required runtime check |
| --- | --- | --- |
| `audioAndSubtitlesContainer` | Audio/subtitle menu container | Correct package and active menu window |
| `audioTabText` | Audio tab label | Its focus or selected flag alone does not prove the active tab |
| `subtitleTabText` | Subtitle tab label | Do not treat its rows as audio |
| `audioIndicator` | Visible when the active tab is Audio | Positive visibility evidence is needed |
| `subtitleIndicator` | Visible when the active tab is Subtitles | An active subtitle indicator prevents audio selection |
| `subTitleAudioTvRecyclerView` | Shared horizontal list for either tab | It contains audio **or** subtitle rows, depending on the active tab |
| `playTrackTvConstraintLayout` | Focusable row and click target | Confirm enabled state and parent relationship to the active list |
| `rowTitleText` | Track display label | Associate with the exact row; preserve duplicate/ambiguous labels |
| `checkBoxImageView` | Selection indicator for that track | Selected state is on this ImageView, not reliably on the row |

The inspected row binding assigns the track's selected state to `checkBoxImageView`; its drawable uses `state_selected`. The tab controller changes indicator visibility and text appearance when switching between the shared audio and subtitle adapters. It does not establish tab state by assigning the tab TextView's selected flag. Consequently, a recognizer based only on the shared list ID, row focus, a checkmark-looking label, or tab text selection could act on subtitles or misread the selected audio.

`view_audio_settings_menu.xml`, `audioSettingsMenuTitle`, `dtsxOffButton`, and `dtsxOnButton` control DTS:X settings. Their presence does not identify the language menu. A separate Compose player layout also exists, so the legacy layout must be verified against the actual playback experience before use.

Some indicators are decorative views. Whether the normal accessibility tree exposes them has not been established. The service's global accessibility flags remain unchanged. If required state is absent, the adapter must stay inactive until a supported observable representation is demonstrated.

The APK also contains `contentTitle` and `content_title` resources, but this inspection does not establish either as a unique title/episode identity. Do not reset a phone override on a pause, buffering event, menu rebuild, or guessed title transition.

## Netflix and Prime Video

The inspected APK resource tables do not provide an evidenced native audio-language list/row hierarchy comparable to Disney's legacy layouts. Netflix includes its native NRD runtime, and Prime bundles an Ignite UI bootstrap and native UI assets. Android/Media3 track-selection classes in a binary do not expose those objects to another app or establish a supported external language-selection command.

No Netflix or Prime selector is enabled or inferred from generic text matching. Their actual player controls, audio rows, selected markers, and stable content identifiers must be captured after normal unlock.

Official UI documentation establishes the intended menus but does not supply accessibility selectors or event provenance:

- [Netflix language settings](https://help.netflix.com/en/node/13245): the TV player's Audio/Subtitle choices save automatically; profile audio/subtitle preferences are separately configurable.
- [Netflix language availability](https://help.netflix.com/en/node/101798): available tracks depend on the title, device, location, and profile preferences.
- [Disney+ audio and subtitle controls](https://help.disneyplus.com/en-GB/article/disneyplus-en-nl-video-language): in-playback audio/subtitle selections save for the profile and can affect later titles.
- [Prime Video audio tracks on connected devices](https://www.primevideo.com/help?nodeId=GYWG45TK599P4KUL): open the in-playback Audio & Languages menu and select an available track.

Profile defaults alone do not implement the requested physical-versus-software override behavior or a per-item reset to Hebrew after a phone override.

## Bounded diagnostic prerequisite

The existing DUMP-only `audio_tree` command now returns a capture-local `index`, `parent_index` (`-1` for the root), `window_id`, `visible_to_user`, `checkable`, `focusable`, and `bounds_in_screen`, alongside package/class/resource/text/description and selected/checked/focused/enabled/clickable state. Parent indices refer to entries in that same capture and must never be reused as stable node identities.

The diagnostic remains manual, limited to 250 nodes and a 350 ms traversal budget, with password text redacted. It adds no event subscription, polling loop, global accessibility flag, or automatic click. Truncation includes child slots omitted because of the node cap; a partial tree cannot prove that an audio track or selection indicator is absent.

The next normal UI captures needed for each app are: playback with a stable item identity; its Audio & Languages menu with one known selected track; a Hebrew row where the current title offers it; the corresponding subtitle pane to verify separation; and before/after captures of real phone and physical selections. A title/episode transition and pause/resume check are then needed to confirm override lifetime. These are acceptance requirements still outstanding, not claimed test results.
