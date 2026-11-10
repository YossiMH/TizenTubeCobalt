# Google TV phone remote power origin

## Verified on the installed Onn, 2026-11-10

- Package: `com.google.android.tv.remote.service`
- Version name: `6.7.838642963`
- Version code: `95855530`
- APK SHA-256: `5f120ec214dac0ec1cd1e0746ccdcae9b56f227e1f4a4756d67e1af8c79b91f2`
- Examined the actual installed APK, pulled with the existing authenticated local ADB connection. Parsed primary DEX using official JADX 1.5.6; checked the power dispatch branch against official Android SDK `dexdump` disassembly.
- No keypress, phone UI interaction, power command, pairing change, remote-service configuration change, or session-policy activation occurred during this inspection.

## Exact installed code path

`KeyboardFeature` (`defpackage.bpm`) handles incoming remote protocol field 10, the key injection request. Its `d(bvq)` dispatches Android key code 26 (`KEYCODE_POWER`) through its alternate input bridge only when that bridge exists. Otherwise it follows the ordinary key press/release path through the standard bridge.

The standard bridge is explicitly named `virtual-remote`. The alternate bridge is explicitly named `virtual-remote-2`. `KeyboardFeature` listener `defpackage.bpk.onInputDeviceAdded` creates the alternate only if the discovered standard `virtual-remote` reports `hasKeys(26)` false. Bridge wrapper `defpackage.bqf` uses `openRemoteInputBridge` and `sendKeyDown` / `sendKeyUp`; it does not use a direct PowerManager off call. The on-screen bridge handler `defpackage.bpp` likewise sends its key through `virtual-remote`.

`PowerFeature` (`defpackage.bpw` and `defpackage.bpv`) observes screen state and sends outgoing protocol field 40 notifications. Its incoming-message handler returns false and provides no separate off path.

## Runtime confirmation

A one-shot read-only `app_process` probe queried `InputDevice.getDeviceIds`, `getName`, `getDescriptor`, and `hasKeys`. It returned:

```text
name=virtual-remote id=8
 descriptor=d76b0dc9b6d844ab25ed6d04f6d43a45d3806d72
 vendor=6353 product=256
 hasKeys_POWER_TVPOWER_SLEEP=[true, false, false]
```

Current EventHub device identity from `dumpsys input`:

```text
name=virtual-remote
path=/dev/input/event3
bus=0x0006 vendor=0x18d1 product=0x0100 version=0x0001
KeyLayoutFile=/product/usr/keylayout/virtual-remote.kl
```

That exact installed keylayout contains `key 116 POWER`. Current native `getevent` capabilities include Linux `KEY_POWER` 116. The remote service diagnostic dump reports its keyboard feature available and omits the alternative-bridge flag. No `virtual-remote-2` currently exists.

**Conclusion:** For this installed version and current input configuration, ordinary Google TV phone remote power-button key injection travels through the standard `virtual-remote` node and appears as Linux EV_KEY code 116. The native helper already identifies this source by exact name/bus/vendor/product, independently of dynamic event number. `input_ethrcu`, `input_btrcu`, and `vad_keypad` are not evidence of a phone override and remain unclassified.

## Controller implications and limits

- A genuine `source=phone`, `code=116`, `value=1` event may grant the explicit phone power override; arbitrary recent phone navigation or volume must not grant it.
- Drain queued native input notifications and allow a short settling interval before acting on a new off-state observation, to avoid a race with the phone event arriving on the helper pipe.
- This proves the installed standard Google TV remote service path. It is not an actual phone-button end-to-end test and makes no claim about direct Samsung/SmartThings TV commands, third-party ADB injection, or assistant speech through a phone.
- Restrict automatic power recovery to the supported input configuration. Revalidate after remote-service updates or an input-layout change; if a different phone path appears, preserve phone control while revalidating.
- An alternate `virtual-remote-2` may be relevant on another firmware/version. It must not be confused with `input_ethrcu`; current helper intentionally does not infer either one as phone without a verified identity.
- Do not commit or redistribute the pulled APK or decompiled Google implementation. This record and the read-only probe source are sufficient to reproduce the finding.

## Reproduction artifacts

- Local inspection directory: `/workspace/scratch/899b0b2f5648/remote-inspect`
- Read-only probe source: `InputPowerOrigin.java`
- Probe runtime output: `phone-origin-runtime.json`
- Exact dispatch disassembly: `bpm.disassembly.txt` (inspection only)

