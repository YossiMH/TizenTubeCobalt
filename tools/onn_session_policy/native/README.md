# Onn physical input helper

This small Linux/Android process observes the known Onn and phone input devices.
The daily playback controller can give it a short lease to take exclusive
ownership of the physical remote's kernel input stream. Phone and unclassified
input devices are never grabbed. Hardware volume keys pass through a dedicated
volume-only virtual input device, without launching processes per key.

## Build

The Onn already has Termux clang. No additional packages are required.

```sh
clang -std=c11 -Wall -Wextra -Werror -Wconversion -Wshadow -pedantic -O2 \
  onn_input_guard.c policy.c -o onn_input_guard
clang -std=c11 -Wall -Wextra -Werror -pedantic -O2 -I. \
  tests/test_policy.c policy.c -o test_policy
./test_policy
clang -std=c11 -Wall -Wextra -Werror -Wconversion -Wshadow -pedantic -O2 -I. \
  tests/test_drain.c policy.c -o test_drain
./test_drain
```

The resulting ARM Android PIE depends only on system `libc.so` and `libdl.so`.
Place it in `/data/local/tmp/onn-policy-20261110/onn_input_guard` and run it as
the shell user through the already authenticated local ADB connection.

## Controller protocol

Start an owned subprocess with `adb -s emulator-5554 shell -T
/data/local/tmp/onn-policy-20261110/onn_input_guard --run`. Keep its stdin and
stdout pipes open. The inherited stdin is the only control channel; there is no
network listener or externally writable control file.

Send one complete line per command:

| Command | Effect |
| --- | --- |
| `ARM <ttl_ms> <session_end_unix_ms>` | Grants 1–15000 ms of protection, bounded by the daily session end. |
| `DISARM` | Immediately releases hardware input and clears the current session. |
| `STATUS` | Returns current lease and volume-device status. |
| `DRAIN <nonce>` | Emits an ordered phone-input barrier for positive decimal nonce 1–2147483647. |
| `QUIT` | Releases input and exits. |

Renew every five seconds with the same session end. Changing a session end
without `DISARM` first is rejected and releases input. A six-hour upper bound
guards against an erroneous distant end time. TTL uses Android boot time,
including suspend, and the session end is also converted into a fixed boot-time
deadline so a backward wall-clock change cannot extend it.

JSON lines on stdout include:

- `ready`: process PID, protocol version (2 with `DRAIN`) and maximum lease.
- `device`: confirmed identity and source (`physical`, `phone`, `cec`, or
  `other_power`). `input_id` means the Linux `eventN` number, not Android's
  independently assigned InputDevice ID.
- `key`: Linux `EV_KEY` code and value (0 release, 1 press, 2 repeat), source,
  monotonic/boot/wall timestamps and action (`observed`, `blocked`, or
  `volume_forwarded`). All phone keys are observed and passed through.
- `volume_ready`, `grabbed`, `released`, `lease`, `disarmed`, `error`, `stopped`:
  transitions for the controller to verify and record.
- `drain`: integer `nonce` and `result` (`clear`, `busy`, or `unavailable`),
  following all phone-key records read for that barrier.

Before a power-recovery decision, send `STATUS` then `DRAIN <nonce>` in one
pipe write. The controller must process every preceding event and await the
matching marker. `DRAIN` reads each currently known, verified phone descriptor
using the normal bounded input reader (at most four batches of 32 records per
device), then polls those descriptors with a zero timeout. An unread backlog
returns `busy`; no phone, disappearance, EOF, or a poll error returns
`unavailable`. Terminal input loss or output backpressure may end the helper
without a marker. None of those outcomes authorizes recovery. An older helper
without protocol version 2 cannot provide this barrier.

`clear` describes the known descriptors at that instant. It does not promise
that no later key or newly connected device will arrive. Recheck the controller
mode, session generation, and fresh state immediately before each wake or HDMI
action. Malformed nonces fail open through the normal invalid-command path;
signs, extra whitespace, and trailing tokens are rejected.

Only the observed Bluetooth identity `Onn-Remote Keyboard`, bus 5,
vendor `0957`, product `0031` is physical. Only `virtual-remote`, bus 6,
vendor `18d1`, product `0100` is identified as phone control. Other power
devices are explicitly unclassified; their events do not grant phone overrides.
Discovery repeats so Linux node renumbering and reconnection are handled.

The helper waits for a volume device and for released physical keys before
grabbing. Hardware volume presses maintain paired down/up events, including
overlapping presses from identical devices. Disconnect, disarm, expiry, EOF,
signal, input overflow, volume failure, and output backpressure release the
grab. Kernel file descriptor cleanup releases it after forced termination.
The single-instance lock prevents competing guard processes.

`--probe` performs one immediate physical grab/release and a volume virtual-device
create/destroy, without sending keys. `--monitor-ms N` (1–30000) observes only.

## Tests

The permanent pure-C behavior tests cover exact identity matching, TTL and
session expiry, clock shifts, paired volume events, disconnect cleanup, and
phone/unknown input bypass. They run on host GCC and on the actual Onn clang.

`tests/test_drain.c` uses ordinary anonymous pipes containing real Linux
`input_event` records, the production command parser/input reader, zero-time
polling, and an actual stdout pipe. It verifies queued power keys precede the
barrier, bounded backlogs cannot acknowledge clear, every known phone is read,
missing/disconnected/overflowed sources deny recovery, nonce validation, and
safe handling of a closed device from an older poll snapshot. These tests make
no writes to actual input devices and perform no device grabs.

For cross-language reader tests, the same test executable accepts
`--protocol-stream`. A verified-phone fixture uses an anonymous input pipe
with one queued power key; stdin goes through the production command reader,
parser, input reader, and output writer. `STATUS`, `DRAIN <nonce>`, and `QUIT`
therefore exercise actual C protocol output rather than a substitute peer. The
fixture remains disarmed, creates no volume device, and exits within ten seconds.

The real-device checks require explicit coordination with the main controller:

```sh
python tests/test_live.py --allow-bounded-grab --report live-results.json
```

They briefly activate real kernel grabs to check TTL expiry, controller EOF,
SIGTERM cleanup, and a fresh successful probe after every process has exited.
They generate no keypresses. Actual hardware-volume and phone-control checks
belong to the integrated playback tests; the pure-C tests alone do not establish
TV behavior.
