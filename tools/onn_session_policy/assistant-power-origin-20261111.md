# Assistant power origin and ordinary text-query entry

## Scope and installed evidence — 2026-11-11

This inspection used the actual installed Onn APKs and read-only package/service queries. No Assistant query, power command, keypress, or UI launch was performed.

| Package | Version / code | Base APK SHA-256 |
| --- | --- | --- |
| `com.google.android.katniss` | `7.52.14+906055584.00` / `15034224` | `da3cbbf7cf4ca66d93ddc7b0f03ec6add8c10082e9e25bfbeef4f61913b203c6` |
| `com.google.android.tv.remote.service` | `6.7.838642963` / `95855530` (Nov10 exact APK retained) | `5f120ec214dac0ec1cd1e0746ccdcae9b56f227e1f4a4756d67e1af8c79b91f2` |

Katniss is the configured voice interaction service and assistant. The installed package has `android.permission.HDMI_CEC` and `android.permission.BIND_TV_REMOTE_SERVICE` granted. The observed HDMI power-control setting remains `broadcast`; no settings were changed.

## Assistant direct standby path

The exact primary DEX contains this execution chain:

1. `DeviceClientOpHandler` (`defpackage.jwn`) handles its `DEVICE_POWER` client operation and parses `PowerControlArgs` from `device_setting_params`.
2. It invokes `DevicePerformerImpl` (`defpackage.hau.d`). Its off/standby branches invoke `ScreenControllerImpl` (`defpackage.jew.d(true)`).
3. `ScreenControllerImpl` calls `HdmiControlManager.setStandbyMode(true)`. When a playback client exists, it then calls `HdmiPlaybackClient.sendStandby()`.
4. Separately, the installed smart-home handler `defpackage.jfl` accepts `action.devices.commands.OnOff`; `on=false` also invokes `jew.d(true)`.

These inspected paths do not inject a `virtual-remote` power event. They call HDMI power control directly. A policy that relies only on suppressing raw remote keys cannot intercept that Binder/CEC route; it must handle off-state recovery or another supported power-control mechanism while preserving explicit phone/admin overrides.

This is evidence about installed code paths. No actual spoken Onn or Google Home command was run, so it does not establish which branch a particular utterance will choose in the current account/device state.

## Assistant key injection is also a separate source

Katniss `KeyCodeInjectorImpl` (`defpackage.gyu`) binds the normal `AtvSearchAssistantService`. The exact Remote Service implementation checks the calling package against `com.google.android.katniss` and `com.google.android.apps.mediashell`, and its allowed Android key list includes `KEYCODE_POWER` 26.

Accepted Assistant keys travel through `RemoteService.searchAssistantKeyEvent` to a bridge explicitly named `virtual-search`. The phone remote keyboard bridge is explicitly named `virtual-remote`. Consequently a `virtual-search` key must not grant the phone override. No `virtual-search` input node was present during this read-only snapshot; its absence does not remove the direct HDMI standby branch.

## Ordinary exported text-query candidate — not executed

Read-only `cmd package query-activities` resolves `android.intent.action.ASSIST`, `android.search.action.GLOBAL_SEARCH`, and `com.google.android.googlequicksearchbox.GOOGLE_SEARCH` to:

```text
com.google.android.katniss/com.google.android.apps.tvsearch.app.launch.trampoline.SearchActivityTrampoline
process: com.google.android.katniss:interactor
enabled=true exported=true
```

The installed trampoline forwards the string extra `query` into the voice interaction service. `AssistantWrapperImpl.processSavedIntent` (`defpackage.juy`) reads a nonempty `query` and calls its text-query interaction method. Default `search_type` 1 reaches that normal service route; no private service call, hidden receiver, keyguard flag, or account token is needed.

A candidate for a separately coordinated power-only test is:

```sh
adb -s emulator-5554 shell am start -W \
  -a android.intent.action.ASSIST \
  -p com.google.android.katniss \
  --es query 'turn off the TV'
```

The command is derived from the installed exported entry and code. It has not been executed or established as a public stable API. Normal profile/security gates remain authoritative. A text-query result would verify that controlled Assistant path, not every spoken Google Home route.

## Phone connector availability

Before any phone UI access, Yossi Phone Shell diagnostics and one bounded `id` attempt both failed with `MCP SSE probe returned 429 from openai.org`. Narrow phone UI app-list and current-screen metadata reads returned the same transport failure. No phone state, installed-app inventory, pairing, or lock status was obtained; no phone mutation occurred. Further attempts were stopped to respect the rate limit. This is an availability blocker, not evidence that phone control or source separation is physically impossible.

## Artifact handling

Decompiled Google implementation and pulled APKs remain inspection-only scratch artifacts. Commit this behavioral evidence record, not the APKs or decompiled source.

