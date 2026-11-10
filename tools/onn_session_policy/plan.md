# Onn playback policy — 2026-11-10

## Required behavior

During the existing daily Sesame Street session, block the physical Onn remote except volume; preserve smartphone controls and explicit assistant overrides. Restore power, the Onn HDMI input, and the same episode after an unapproved interruption. Voice power-off must not defeat the session when its origin can be distinguished from a phone command. Prefer Hebrew audio in Netflix, Disney+, and Prime Video; preserve a software-selected language for that item and reject subsequent physical changes. Preserve captions, existing profile safeguards, the 07:00 Sunday–Friday schedule and holiday exclusions, and device responsiveness.

## Approach

The existing signed Morning Sesame helper identifies actual scheduled runs and selected episodes. Onn Key Guard owns the session and audio policy, with a private loopback controller interface. A short-lease native input helper grabs only the observed physical Onn device; it relays volume through a volume-only virtual device and leaves phone inputs untouched. A lightweight Termux controller uses the existing authenticated local ADB connection for CEC/power recovery. No pairing changes, global input-dispatch changes, or blanket CEC restrictions are required.

The physical input identity is bus 5, vendor 0x0957, product 0x0031, name `Onn-Remote Keyboard`; event-node numbers are dynamic. The phone is an independently observed `virtual-remote` device, bus 6, vendor 0x18d1, product 0x0100. Unknown devices do not become physical controls by inference.

## Work

| ID | Status | Primary files / command | Prerequisite | Result and next step | Failure handling |
|---|---|---|---|---|---|
| native-input | implemented; button acceptance blocked | native/onn_input_guard.c | Live input identity | Kernel grab, volume-device creation, TTL/EOF/exit cleanup passed; actual hardware/phone/volume buttons remain unverified | Release immediately on lease, IPC, or volume-path failure; leave normal input working |
| session-state | prepared installed and wiring-tested | ../onn_key_guard/src/dev/yossi/onnkeyguard/PolicyRuntime.java | Existing scheduled-run source | Signed activation, finite session, phone/assistant precedence; independent review PASS; live finite TEST wiring passed | Remain inactive unless activation is valid and input observation is fresh; no stale-day restart |
| recovery | implemented; observation only | onn_session_policy/controller.py | native-input, session-state | Bounded CEC/power/episode recovery; persistent passive input observation and phone-intent fencing; real recovery acceptance remains blocked | Suppress after deliberate phone/assistant action or missing input provenance; cap retry rate; fail open on controller loss |
| audio-policy | pure rules tested; adapters blocked | ../onn_key_guard/src/dev/yossi/onnkeyguard/AudioPreferencePolicy.java | Actual app menus | 161 pure assertions passed; Netflix/Disney+/Prime adapters still absent | Preserve unknown-origin choice; no subtitle changes; stop unrecognized UI automation |
| app-inspection | blocked | Normal Google TV PIN entry, then installed player menus | Owner's normal profile authentication | Capture actual Netflix/Disney+/Prime UI and validate adapters | No PIN guess, reset, or profile-protection bypass |
| validation | independent checks passed; acceptance blocked | Native, Java, Python tests and live Onn checks | Previous rows | Java 484 assertions, Morning 135 assertions, actual-Termux controller 40 tests and 18 prepared production-wiring checks passed; real input/playback/audio acceptance remains open | Do not enable unsupported behavior or claim unobserved playback success |
| publication | done | Source commits and existing handoff | Reviewed coherent code and exact validation state | Reviewed source, deployed hashes, acceptance limits and continuation are published; requested behavior remains blocked on owner authentication and real acceptance | Keep unfinished work explicit |

## Current observations

- Existing product main: `b39e2eb90e64009397e0af86f6f3bb27d1697ddb`.
- Morning Sesame v10 and Onn Key Guard v6 / 3.0-prepared are installed using the original signing certificate. Installed SHA-256 values match the tested APKs. Original signed v9/v5 APK backups remain available.
- Native shell `EVIOCGRAB` and volume-only `uinput` creation were verified on the Onn with immediate cleanup and no emitted keypresses.
- A bounded behavioral test attempted ordinary `sendevent` on the freshly identified physical event node. The OS denied that write. The test stopped immediately without any keypress, phone injection, volume change, or alternate injection route; native cleanup and a fresh post-exit probe passed. Actual button behavior needs a coordinated hands-on test.
- Installed Google TV RemoteService 6.7.838642963 and the live `InputDevice.hasKeys` result establish that its standard phone power command uses the observed `virtual-remote` scan code 116. A real network phone-button test has not yet been performed. Other TV-network remote apps have not been classified.
- The single independent reviewer accepted the fixed frozen candidate for prepared deployment. Listener binding is generation-safe; a passive observer supplies real STATUS proof before daily activation; missing provenance or unresolved phone intent terminally releases protection. TEST sessions are explicitly authorized and time-bounded while rollout remains prepared.
- The production wiring check passed 18 checks, including authenticated HTTP, rejected unauthenticated requests, live native observation, two finite TEST grabs, a simulated phone-power policy stop, assistant override and owned cleanup. It sent no actual key, volume, power, input-selection or playback command.
- Cleanup verified no native helper process, a fresh successful grab/release and volume-device create/destroy probe, enabled/unfrozen input dispatch, the same accessibility services, and normal asleep state. The next ordinary RUN alarm is November 11 at 07:00 local time.
- Final rollout is prepared, audio adapters unverified, controller stopped, and recovery inactive. Thirty authenticated status requests had 8.99 ms median and 26.46 ms maximum latency; these endpoint samples do not establish real remote responsiveness or long-term stability.
- Today's scheduled run selected `EP2tuoBV184` and failed all four playback checks with `state_idle`; this is a real unresolved startup condition.
- On wake, Google TV shows the normal `ENTER GOOGLE PIN` profile screen. Retrieved prior records contain no supplied PIN; previous successful authentication was performed by the owner.
- PC source writes were denied in the restricted worker, and the one reviewed isolated-clone command did not run because standalone review was unavailable. Source/build preparation continues in the cloud and Onn Termux. Existing PC checkouts remain untouched.

## Required continuation if authentication stays unavailable

UNBLOCK_CONDITION: normal owner entry of the Google TV profile PIN. Complete all independent source, compile, review, pure-policy and non-interactive device checks first. App playback/language acceptance and unattended PIN entry remain unverified until that condition is met.
