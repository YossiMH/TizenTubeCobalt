# Acceptance boundaries

The current rollout is **prepared**, and recovery defaults to **observe**.
These are implementation and validation checkpoints, not a claim that the
requested household behavior is enabled or complete.

## Prepared installation verified on November 10, 2026

Morning Sesame v10 and Key Guard v6 are installed with their original signing
identity. The installed APK hashes match the tested builds. The Java policy
suites passed 484 assertions, the morning helper passed 135 assertions, and all
40 controller tests passed on the actual Onn's Termux Python.

Eighteen production-wiring checks passed with rollout remaining prepared. Two
explicit 10-second TEST sessions reported native grabbing and release; a
simulated HTTP phone-power event and the protected assistant override ended
them. No real phone/hardware button, volume, power, input-selection, media, or
PIN command was sent by that test. Cleanup confirmed no native helper process
and a fresh successful kernel grab/release probe. The controller is stopped.

Input dispatch remained enabled and unfrozen. The existing accessibility service
list was retained, and the Onn remained asleep. Thirty authenticated local status
requests had a median latency of 8.99 ms and maximum of 26.46 ms. These limited
measurements do not establish real remote latency or long-term stability.

See [deployment-evidence.json](deployment-evidence.json) for exact versions,
hashes, checks and limitations.

| Requirement | Implemented mechanism | Evidence still required before enabling |
| --- | --- | --- |
| Block the physical Onn remote during confirmed daily Sesame playback | Signature-bound session activation; exact physical-device input grab; balanced accessibility fallback | Real hardware navigation, app buttons, microphone and power tests during a disposable test session |
| Preserve volume | Native volume-only virtual device; volume excluded from session intent; existing CEC settings retained | Real hardware volume/mute and the owner's phone volume controls, with final volume restored |
| Preserve phone controls | Positively identified Google TV `virtual-remote`; phone intent precedes recovery; passive input observation before activation | Real Google TV phone navigation, pause, resume, Home and Power; identify any other phone remote apps in use |
| Restore power/input and the same episode | Bounded fresh power/CEC observations, one-touch-play, normal authenticated same-video recovery | Actual off/input-change recovery; same-video position behavior; completion releases protection; verify every phone power/input path first |
| Reject Google voice power-off | Unattributed power loss does not receive the proven Google TV phone exemption | Voice commands from the Onn and another device, tested separately from each phone remote path |
| Unlock normally when required | Authentication classification blocks recovery at the Google TV profile PIN | Normal owner authentication, then determine whether off/on locks the profile again; no unattended PIN method is configured |
| Prefer Hebrew in Netflix, Disney+ and Prime Video | Pure language recognition and per-item preference/override rules | Normal authenticated access to all three apps, real item/audio-menu identity adapters, positive language selection and origin attribution |
| Keep a phone-selected audio language | Pure per-item software override; subsequent physical changes target that choice | Real app integration and physical/phone selection tests in each of the three apps |
| Preserve responsiveness and stability | No shell/network work in key callback; bounded leases, IO, queues, recovery and session lifetime; fail open on source-monitor loss | Integrated checks plus normal playback with real remote inputs; current kernel and policy tests do not establish sustained viewing behavior |

## Proven and unproven input paths

The observed Onn Bluetooth remote and Google TV phone remote are separate Linux
input devices. The installed Google TV RemoteService's standard Power key uses
the observed `virtual-remote` scan code 116. Exact installed-version evidence is
in [phone-power-origin-20261110.md](native/tests/phone-power-origin-20261110.md).
This does not establish the behavior of a Samsung SmartThings phone command
sent directly to the television.

Samsung documents that SmartThings can control TV volume, apps, navigation and
input selection directly. Its ordinary device-event documentation describes
state changes caused by both app commands and physical controls; the documented
event fields alone do not provide a universal phone-versus-voice actor identity.
The enterprise event schema can include a command ID, which is not evidence
that this household has access to actor attribution for this television.
Consequently, physical impossibility has **not** been established, and no such
exception to the user's phone-control requirement has been invoked.

Primary references:

- [Samsung: control a TV with SmartThings](https://www.samsung.com/us/support/answer/ANS10007047/)
- [SmartThings: webhook device events](https://developer.smartthings.com/docs/service-integrations/webhook-events)
- [SmartThings: enterprise event types](https://developer.smartthings.com/docs/enterprise/enterprise-api-overview/eventing/event-types)

## Current external blockers

The last inspected normal Google TV screen was `ENTER GOOGLE PIN`. No owner PIN
was provided or retrieved. App-menu inspection and playback acceptance require
the owner's normal authentication. The device later entered its ordinary asleep
state; no test switched it off.

The attempted ordinary `sendevent` write was denied by the OS. That test stopped
immediately and emitted no key. Kernel lifecycle checks passed; actual physical
and smartphone inputs remain a coordinated hands-on acceptance step.

## Rollback and assistant override

Use the protected `override` command to end the current session. Use
`disable_session` to leave rollout prepared. The controller's `stop` command
overrides the session and shuts down its owned subprocess. No global pairing,
input-dispatch, CEC or accessibility configuration changes are part of rollback.

Original signed APK backups are kept on the Onn in the task's private rollback
directory. They preserve the original Morning Sesame v9 and Key Guard v5 files;
do not uninstall/reset app data to roll back. A same-signature rollback build
with an appropriately increasing version code is preferable if Android refuses
a normal version downgrade.
