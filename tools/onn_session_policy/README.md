# Onn Sesame session controller

Python 3.10+ controller intended to run in private Termux storage. The Android
policy app owns the daily session and authorization state. This process is an
adapter: it supervises one native input helper, relays positively identified
phone keys, observes playback/power/input, and requests bounded recovery.

## Run

Keep `onn_session_policy/` beside `onn_session_controller.py`, or run the package
with `python3 -m onn_session_policy` from this directory. Select the existing
authorized ADB target explicitly. The example serial is the integration target;
use the serial shown by the Termux ADB installation on the actual device.

```sh
python3 onn_session_controller.py --serial emulator-5554 get-token
python3 onn_session_controller.py --serial emulator-5554 status
python3 onn_session_controller.py --serial emulator-5554 run \
  --native-path /data/local/tmp/onn-policy-20261110/onn_input_guard
```

The first command obtains the token through the protected admin receiver. It
saves the token atomically in a mode `0600` file inside a mode `0700` directory.
It prints only `token_saved`. Default state directory:
`~/.local/state/onn-session-policy`. `--state-dir` and `--token-file` can select
other private paths. Raw broadcast output, tokens, and HTTP bodies are never
logged. The HTTP client can send the bearer token only to `127.0.0.1`.

Recovery defaults to observation until real phone power classification and
integrated operation have been verified. Enable it explicitly afterward:

```sh
python3 onn_session_controller.py --serial emulator-5554 run \
  --native-path /data/local/tmp/onn-policy-20261110/onn_input_guard \
  --recovery-mode enabled
```

Both modes require canonical app status `rollout: "enabled"` before grabbing
hardware for scheduled sessions. Protected `session_mode: "test"` permits a
bounded test while rollout remains prepared, only when expiring within 60 seconds;
it never wakes, changes input, launches, or resumes playback. Missing rollout
authorization defaults to prepared. These commands do not enable app rollout.

The default native binary path without `--native-path` is
`/data/local/tmp/onn-policy/onn_input_guard`. The date-specific path above matches
the currently built integration binary; deployment should choose a stable path.

Run under the existing Termux service supervisor for reboot persistence. The
controller runs in the foreground, handles SIGINT/SIGTERM, holds a private
single-instance lock, and does not detach or create a second watchdog process.

## Assistant controls

```sh
python3 onn_session_controller.py --serial emulator-5554 running
python3 onn_session_controller.py --serial emulator-5554 probe
python3 onn_session_controller.py --serial emulator-5554 override
python3 onn_session_controller.py --serial emulator-5554 stop
```

`running` uses the private controller Unix socket and reports live process and
state-source information. `status` reads fresh canonical app status. `probe`
uses the protected read-only app health command. `override` stops the current app
session and immediately requests a controller refresh; the future daily schedule
remains app-owned. `stop` attempts that same assistant override and then shuts
down this controller, including its owned native subprocess, even if the app
override request fails. It never kills processes by name or by an unverified PID.

Stopping is not an implicit rearm. A subsequent daily session follows the app's
normal schedule and rollout state. Starting the controller again does not create
a playback session.

## State and timing

- Only fresh, live canonical state can authorize hardware grabbing. Failed
  requests retain a snapshot labeled `cache`, which cannot authorize actions.
- Grabbing requires phase `active` or `phone_paused`, an unexpired session,
  enabled rollout (or a protected test of at most sixty seconds), a proven live
  input observer, and no locally observed phone
  power stop or unresolved provenance fault.
- Canonical status requests begin every four seconds during active/paused
  sessions and every five seconds while idle. Idle polls use only loopback HTTP;
  they do not spawn ADB commands or wake the device. Phone POST completion wakes the
  status loop immediately. A status older than five seconds cannot authorize
  recovery or a lease renewal.
- One native helper passively observes input throughout the controller lifetime,
  including prepared and idle periods. Normal inactivity and service failures
  send `DISARM` while keeping the observer alive. Session changes reset the lease
  with `DISARM`, without interrupting phone observation. Only allowed sessions
  receive `ARM 12000 <session_end_unix_ms>`. Native process failure has a
  ten-second restart backoff.
- Every three seconds the controller requests actual native `STATUS`. A
  solicited reply must arrive within four seconds and follow discovery of the
  verified phone input identity. The startup `ready` message alone provides no
  proof. Local observer proof expires after five seconds; process existence and
  remembered health never extend it. Native read errors, phone source removal or
  replacement, and missing/stale replies create an immediate provenance fence.
- A provenance fault cancels recovery and disarms first. The controller POSTs
  `native_health:unavailable` to terminally stop the app's current run, using the
  protected assistant override only if the POST fails. Until terminal stop is
  acknowledged, no fresh native response can rearm or recover. Status responses
  begun before that acknowledgement are invalidated. The affected run id remains
  blocked even after observation recovers; a distinct normal run is required.
- Shutdown sends `DISARM`, `QUIT`, then closes stdin. It waits at most one second
  before terminating its owned ADB child, then one further second before killing
  that same child if necessary. Native EOF and the maximum twelve-second lease
  provide independent release paths if transport shutdown is delayed.
- Every phone key from a positively identified phone input is POSTed in order.
  A non-volume key press fences recovery synchronously before the HTTP request.
  Navigation remains pending until its POST is acknowledged, subsequent status
  is fresh, and positive post-key PLAYING evidence matches the expected video
  and foreground player. Recovery also observes a minimum eight-second grace.
  The resolving PLAYING sample must itself be taken after that grace and be at
  most three seconds old when considered; an early playing sample cannot clear
  intent and then silently age through a later pause or missing telemetry.
  Missing, paused, or unknown telemetry never times out into a blind resume.
  Prestart phone input is retained. Linux `KEY_POWER` (`116`) or `KEY_SLEEP` (`142`) latches a
  stop for the current session, surviving old active status responses.
- Phone key release/repeat events and all volume events are relayed. Unknown,
  CEC, network, and physical sources never become phone intent by inference.
- Recovery requires phase `active`, scheduled session mode, fresh canonical
  status, and no phone fence. Power/input observation uses one fresh combined
  dumpsys command with a five-second timeout. Wake, one-touch-play, and protected
  app recover commands each have a four-second timeout. Phone intent cancels
  in-flight owned recovery commands before any subsequent command is issued.
- An explicitly sleeping Onn/TV or inactive local playback4 CEC source permits
  `KEYCODE_WAKEUP` and `cmd hdmi_control onetouchplay`. A paused matching video or
  a known different foreground package can request normal app recovery. Missing
  player markers and idle XML alone do not imply completion or launch playback.
- Recovery attempts are separated by at least ten seconds, limited to three in
  sixty seconds, followed by a sixty-second cooldown after the third attempt.
  Protected app recovery requires positively clear authentication status.
  Unknown or required authentication prevents app recovery. Power/input
  restoration may finish for an existing active session and reports
  `auth_required` or `auth_unknown`, leaving authentication to the normal app flow.

The controller never changes pairing, Bluetooth, global HDMI/CEC settings,
accessibility settings, or input-device configuration. The native helper owns
physical-device identification, selective grabbing, volume forwarding, and its
independent fail-open lease.

## Actual media telemetry

`--media-source auto` (default) tries the existing Cobalt debugger and DIAL
concurrently. CDP is optional and requires `websocket-client==1.9.2` in the
controller's Python environment (`requirements-cdp.txt`). With that dependency
unavailable, auto mode continues using DIAL. `--media-source dial` disables CDP;
`--media-source cdp` requires it. `--debugger-port` defaults to `9222` and
`--player-port` to `8012`.

The CDP adapter discovers one YouTube TV page at `127.0.0.1:9222/json` or
`/json/list`, validates a loopback-only websocket URL at the same port, and reuses
the connection. Websocket redirects are disabled (`redirect_limit=0`) so the
loopback allowlist also holds after connection setup. Each whole sample has a
two-second budget. It sends only
`Runtime.evaluate` with this fixed read-only expression and `returnByValue:true`:

```javascript
JSON.stringify((function(){var a=window.__ttAllowedOnly,s=a&&a.state,m=s&&s.morningMedia;return m&&a.routeVideoId()===s.morningTrackedVideo?{video_id:s.morningTrackedVideo,position_seconds:Number(m.currentTime),duration_seconds:Number(m.duration),playing:m.ended?'ended':m.paused?'paused':'playing',sample_wall_ms:Date.now()}:null})())
```

Only actual `HTMLMediaElement.ended` evidence is sent as `playing:"ended"`.
Reaching a duration or seeing paused/idle/missing state never synthesizes ended.
Missing/invalid/stale samples are ignored. An ended sample suppresses recovery
while the app incorporates completion.

DIAL samples read only `ms_state`, `ms_playing`, `ms_playing_at`, and
`ms_position` from `/apps/YouTube`. Freshness is at most five seconds with at most
one second of future clock tolerance. DIAL reports duration as null and cannot
report ended. The default player package is `io.gh.yossim.tizentube.cobalt`.

## App HTTP protocol

All requests use `Authorization: Bearer <private token>` on the local app port
`8768`. GET `/status` requires schema `1` and these fields:

```json
{
  "schema": 1,
  "rollout": "enabled",
  "session_mode": "scheduled",
  "authentication": "clear",
  "session": {
    "id": "daily-session-id",
    "video_id": "abcdefghijk",
    "phase": "active",
    "expires_at_ms": 1800000100000,
    "duration_seconds": null,
    "position_seconds": null
  },
  "foreground_package": "io.gh.yossim.tizentube.cobalt",
  "native_lease_status": "unknown"
}
```

Phases: `inactive`, `active`, `phone_paused`, `phone_stopped`, `completed`.
Rollout: `prepared`, `enabled`. Session mode: `scheduled`, `test`.
Authentication: `unknown`, `clear`, `required`. Other app diagnostic fields are
ignored without changing the canonical app data.

POST `/event` sends JSON bodies no larger than 4096 bytes:

```json
{"type":"phone_key","linux_code":116,"value":1,"wall_ms":1800000000000}
{"type":"playback","video_id":"abcdefghijk","position_seconds":12.5,"duration_seconds":30.0,"playing":"playing","sample_wall_ms":1800000000000}
{"type":"recovery","result":"requested","reason":"input"}
{"type":"native_health","status":"physical_grabbed"}
{"type":"native_health","status":"monitoring","proof":"native_status","sample_wall_ms":1800000000000}
```

Playback state values: `playing`, `paused`, `ended`, `unknown`. Native health
values: `stopped`, `starting`, `lease_armed`, `physical_grabbed`, `disarmed`,
`unavailable`, `monitoring`. Only `monitoring` or `physical_grabbed` with explicit
`proof:native_status` and the actual fresh response receipt time establishes an
app heartbeat. Identical proof heartbeats are sent again after each STATUS reply;
remembered state is never replayed as proof. Other transitions are coalesced.
The app permits startup only with fresh proof and terminally stops active/paused
sessions when proof is lost or older than twelve seconds. Recovery reasons:
`power`, `input`, `playback`; results include `requested`, `auth_required`,
`cancelled`, `failed`, `auth_unknown`.

Protected admin receiver:

```sh
adb -s SERIAL shell am broadcast \
  -a dev.yossi.onnkeyguard.POLICY_ADMIN \
  -n dev.yossi.onnkeyguard/.PolicyAdminReceiver \
  --es command controller_token
```

The token command returns resultData JSON
`{"schema":1,"token":"<64 hexadecimal characters>","port":8768}`.
The controller also uses `status`, `probe`, `override`, and `recover` commands
according to their documented app behavior.

## Native protocol

Start one owned subprocess:

```sh
adb -s SERIAL shell -T /data/local/tmp/onn-policy/onn_input_guard --run
```

Write newline-delimited commands on its stdin:

```text
ARM 12000 <session_end_unix_ms>
DISARM
STATUS
QUIT
```

TTL must be from `1` through `15000` milliseconds; this controller uses `12000`.
Native JSON key lines use `kind:"key"`, `source:"phone"|"physical"|...`,
`input_id`, `code`, `value`, `wall_ms`, plus native diagnostic clocks. This
controller additionally accepts the source aliases `software` and `hardware`
and a `linux_code` alias. It forwards only positively identified phone/software
events. `cec`, `other_power`, `other`, `volume_bridge`, and unknown source labels
stay unknown to the phone policy.

## Verification

```sh
PYTHONPATH=. python3 -m unittest discover -s tests -v
```

Tests exercise the real pure reducers/parsers, the sanitized actual HDMI
playback4 dump, real private token files, and a real local HTTP test server.
The controller smoke test starts the actual coordinator in inactive/prepared
state, reads its private socket, and shuts it down using an unavailable Android
executable so it cannot reach a real device. That
test skips specifically when host policy forbids AF_UNIX; a Termux smoke test
is then still required. This suite does not prove physical remote behavior,
real phone source classification, real Cobalt websocket/media access, TV wake,
or playback through an authentication gate.
