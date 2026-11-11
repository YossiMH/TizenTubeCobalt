# Acceptance boundaries

The current rollout is **prepared**, and recovery defaults to **observe**.
These are implementation and validation checkpoints, not a claim that the
requested household behavior is enabled or complete.

## November 11 update

Key Guard v7 / 3.1-prepared and native protocol 2 are installed. The controller
now drains pending, positively identified phone events before each wake, HDMI,
or media-recovery command. The production C implementation, actual OS pipes,
phone-policy path, and real shutdown signals cover the two review findings.
The separate startup package remains disabled outside TermuxBoot.

The final finite power-only check passed all 15 checks on the actual Onn and
Samsung television. TV standby and Onn sleep were observed, followed by TV on,
Onn awake, and the Onn HDMI source active. The normal HDMI shell command returned
its callback-deadline error before the TV finished waking. Only that exact error
may use a six-second passive confirmation requiring all three explicit current
states; the result is `power_probe_confirmed`, with the original command error
retained. It is not reported as a successful command acknowledgement.

The probe stopped, its owned controller exited, and no native helper or private
control socket remained. TV volume 24, mute 0, and Android music volume 25 were
unchanged. Input dispatch remained enabled/unfrozen and accessibility settings
were retained. The device was returned to its initial asleep state. These checks
do not establish sustained playback stability or real handset-button behavior.

The earlier combined volume/Assistant test stopped at an HTTP error from the
network mute request. Separate reads confirmed all original volume/mute values.
It never reached volume keys or the Assistant query. No voice-command success is
claimed; the final test deliberately exercised power only.

The current screen after wake is still `ENTER GOOGLE PIN`. Audio adapters for
Netflix, Disney+, and Prime remain absent/inactive. Actual app menus, normal
profile authentication, real hardware/phone buttons, and spoken Google commands
are still required before production activation. Phone connectors returned 429;
no already authorized phone ADB target was available. This does not establish
physical impossibility of separating phone and voice controls.

Exact installed hashes and current status are in
[deployment-evidence.json](deployment-evidence.json). Full power-test attempts,
including failures and cleanup, are in
[power-recovery-tests-20261111.json](power-recovery-tests-20261111.json).

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

See [deployment-evidence-20261110.json](deployment-evidence-20261110.json) for the November 10 versions,
hashes, checks and limitations.

| Requirement | Implemented mechanism | Evidence still required before enabling |
| --- | --- | --- |
| Block the physical Onn remote during confirmed daily Sesame playback | Signature-bound session activation; exact physical-device input grab; balanced accessibility fallback | Real hardware navigation, app buttons, microphone and power tests during a disposable test session |
| Preserve volume | Native volume-only virtual device; volume excluded from session intent; existing CEC settings retained | Real hardware volume/mute and the owner's phone volume controls, with final volume restored |
| Preserve phone controls | Positively identified Google TV `virtual-remote`; phone intent precedes recovery; passive input observation before activation | Real Google TV phone navigation, pause, resume, Home and Power; identify any other phone remote apps in use |
| Restore power/input and the same episode | Bounded fresh power/CEC observations, one-touch-play, normal authenticated same-video recovery; finite power-only check passed November 11 | Actual switch to another input; same-video position behavior; completion releases protection; every real phone power/input path; normal profile authentication |
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

The latest inspected normal Google TV screen was `ENTER GOOGLE PIN`. No owner PIN
was provided or retrieved. App-menu inspection and playback acceptance require
the owner's normal authentication. November 11 power tests used normal sleep and
wake commands; the device was subsequently returned to its initial asleep state.

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
