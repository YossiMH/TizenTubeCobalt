# Disabled controller startup package

This directory is staged preparation. `service.json` has `activation: "disabled"`
and `recovery_mode: "observe"`. The Boot candidate lives here, outside
`~/.termux/boot`. Validation and a disabled run cannot start the controller,
contact the policy app, acquire a token, or wake the television.

## Observed Termux setup, November 11

The actual device has one Boot entry, `00-openai-tunnel`. It already acquires the
Termux wake lock and executes the existing tunnel supervisor. Its SHA-256 at
inspection was
`1c1888652ca46ea6aef7b38c8ad053bdb07dd65843a90f292f3837674ea2b666`.
This package does not edit that entry or tunnel code, and it does not acquire a
second wake lock.

The `crond`, `ssh-agent`, and `sshd` service directories exist. `runit` and
`termux-services` are not installed, and `sv`, `runsv`, `runsvdir`, and `svlogd`
are unavailable. This implementation therefore uses a separate stdlib Python
supervisor launched by TermuxBoot after acceptance. It neither installs runit
nor adds a second supervisor around the tunnel.

## Lifecycle and ownership

The supervisor has a private single-instance file lock and private Unix control
socket. The controller retains its own separate lock. A manually running
controller is not adopted or killed; a duplicate child exits through the
controller's existing lock and restart attempts back off.

Before each child start, the supervisor performs only a four-second bounded
`adb -s emulator-5554 get-state` check. It does not reconnect, pair, change ADB
settings, or run an input/power command. A missing transport or failed child
start backs off for 5, 10, 20, 40, then at most 60 seconds. A child that runs for
at least two minutes resets the crash counter. No restart creates a canonical
Sesame session or changes rollout authorization.

One controller child owns its native helper. The helper remains in passive,
disarmed observation outside an authorized session; the existing controller's
fresh phone-source proofs, terminal source-loss fence, finite native lease, and
canonical app authorization govern grabbing and recovery. Restarting supervision
does not resume a run that lost continuous input provenance.

Stop sends SIGTERM to the exact owned controller handle. The controller can
release its native helper normally. After eight seconds, a noncooperative child
and its verified owned process group are killed, with a further two-second reap
bound. There is no PID-file kill, process-name kill, adoption, or global ADB stop.
Native EOF/lease expiry remains an independent fail-open path.

A config change, including setting activation back to disabled, ends the current
supervisor cleanly. `stop` stops the current supervisor; the config remains as
written. If activation is enabled and the Boot hook remains installed, the next
reboot can start it again. For a durable disable, set activation to disabled and
remove only the hook installed from this package. The normal app `override`
command is the appropriate way to end one session while retaining observation.

## Bounded diagnostics

The private supervision state directory contains one lock, one live socket, a
cached status snapshot, and at most three 64-KiB audit files: `service.jsonl`,
`.1`, and `.2`, totaling at most 192 KiB. Audits run at most once per minute;
other entries describe startup, exit, backoff, and existing controller events.
Only a fixed list of diagnostic fields is retained. Unexpected child output is
recorded as a byte count, without its contents. Tokens, environment contents,
HTTP bodies, and raw command output are not copied into logs. Cached status is
explicitly labeled `cache`; `status` reads the live private socket.

## Validation before activation

On the actual Onn, using the existing isolated Python environment:

```sh
PY=/data/data/com.termux/files/home/onn-policy-20261110/venv/bin/python
SUP=/data/data/com.termux/files/home/onn-policy-20261110/controller/supervision
"$PY" "$SUP/supervise.py" --config "$SUP/service.json" validate
"$PY" "$SUP/supervise.py" --config "$SUP/service.json" run
"$PY" -m unittest discover -s "$SUP" -p 'test_*.py' -v
```

The second command reports `disabled` and exits. Tests exercise private config,
bounded logs, restart delays, owned subprocess cleanup, and an isolated Unix
control socket with an intentionally unavailable ADB executable. Those tests
cannot start a real controller or contact a real Android device.

After the separate real-input/playback/power/audio acceptance work succeeds,
review the exact paths and existing private token in `service.json`. Activation
requires explicitly changing `activation` to `enabled`, choosing the accepted
recovery mode, and installing the Boot candidate as an executable file named
`20-onn-policy`. Keep the config mode `0600` and the Boot hook mode `0700`.
Perform one supervised manual start and verify fresh passive phone-source
heartbeats, no unauthorized grab, single-instance behavior, and clean stop
before relying on reboot startup. This task does not perform those activation
steps.

Once installed, live service controls use:

```sh
"$PY" "$SUP/supervise.py" --config "$SUP/service.json" status
"$PY" "$SUP/supervise.py" --config "$SUP/service.json" stop
```

## Input-origin evidence and allowed remaining checks

Read-only `getevent -lp` on November 11 reported eight devices:

| Current node | Device | Attribution used by existing native policy |
| --- | --- | --- |
| event0 | cec_input | CEC; actor unknown |
| event1 | gpio_keypad | Other; never inferred to be phone |
| event2 | adc_keypad | Other; never inferred to be phone |
| event3 | virtual-remote | Google TV phone candidate; native exact identity still required |
| event4 | input_ethrcu | Other power; actor unknown |
| event5 | input_btrcu | Other power; actor unknown |
| event6 | vad_keypad | Other power; actor unknown |
| event7 | Onn-Remote Keyboard | Physical candidate; native exact identity still required |

The virtual remote advertises Power, volume/mute, navigation, Home/Back,
playback, and Assistant capabilities. The Onn device independently advertises
those families of keys. `virtual-remote-2` is not currently present. Event numbers
are dynamic and are not identity rules. A capability list proves available keys,
not that a real phone action generated them.

The read attempt for `/proc/bus/input/devices` was denied and was not retried.
The successful capability query uses the ordinary read-only input metadata
interface. No input write, pairing, settings change, or synthetic key was used.
The prior ordinary `sendevent` denial remains a hard test boundary; this package
adds no alternate injection path.

Autonomous checks can verify fresh native STATUS responses, exact discovered
device identities, passive lease state, isolated authenticated HTTP policy,
bounded TEST grab/release without keys, and owned shutdown. They cannot prove
that a person's Google TV phone Power button or direct-TV SmartThings command
has a particular origin. Complete that final attribution by observing an actual
authorized phone action with the passive monitor, separately for Google TV and
every direct-TV remote path. Unattributed direct-TV/voice/CEC power events remain
unknown and do not gain the phone exemption. No physical-impossibility exception
is asserted.
