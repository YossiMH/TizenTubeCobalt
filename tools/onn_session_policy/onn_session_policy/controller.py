"""Asynchronous coordinator; phone intent is reduced before any network await."""
import asyncio
from dataclasses import dataclass
from enum import Enum
import json
import os
from pathlib import Path
import time

from .adapters import AdapterError, AndroidCommandError, GuardPhase, NativeGuard, NativeHealth, private_directory, read_player
from .media import CobaltMediaReader, MediaSource
from .native_protocol import observer_live as native_observer_live
from .policy import (
    Authentication, Availability, FaultFence, Intent, Observer, Phase, Playback, PolicyState, Recovery, Rollout, SessionMode, Source, VOLUME_CODES,
    apply_status, begin_phone_key, failed_status, guard_required, live_session,
    mark_phone_posted, parse_observation, parse_player, recovery_reason,
    observe_phone_playback, observer_fresh, observer_proven, provenance_gap,
    record_recovery, recovery_context_allowed, restored_power_state, terminal_stop_confirmed,
)


ACTIVE_POLL_SECONDS = 4.0
IDLE_POLL_SECONDS = 5.0


class RecoveryMode(str, Enum):
    OBSERVE = "observe"
    ENABLED = "enabled"


class Confirmation(str, Enum):
    NONE = "none"
    STATE = "state_confirmed"


@dataclass(frozen=True)
class Config:
    state_directory: Path
    native_path: str
    player_port: int = 8012
    player_package: str = "io.gh.yossim.tizentube.cobalt"
    recovery_mode: RecoveryMode = RecoveryMode.OBSERVE
    media_source: MediaSource = MediaSource.AUTO
    debugger_port: int = 9222


def mono_ms():
    return time.monotonic_ns() // 1_000_000


def wall_ms():
    return time.time_ns() // 1_000_000


def log(event, **values):
    # Fixed diagnostic classifications only; no raw HTTP/adb/token output.
    print(json.dumps({"event": event, "wall_ms": wall_ms(), **values}, separators=(",", ":")), flush=True)


class Controller:
    def __init__(self, config, android, client):
        self.config, self.android, self.client = config, android, client
        self.state = PolicyState()
        self.stop_requested = asyncio.Event()
        self.status_wakeup = asyncio.Event()
        self.guard_wakeup = asyncio.Event()
        self.health_wakeup = asyncio.Event()
        self._health_post_lock = asyncio.Lock()
        self.fault_wakeup = asyncio.Event()
        self.phone_outbox = asyncio.Queue(maxsize=512)
        self.guard = NativeGuard(android, config.native_path, self.on_key, self.on_native_fault, self.on_native_health, self.on_native_proof)
        self.native_health = NativeHealth.STOPPED
        self._health_event = None
        self._health_generation = 0
        self._posted_health_generation = 0
        self._proof_min_generation = 0
        self.media = CobaltMediaReader(config.debugger_port)
        self.media_evidence_source = "unobserved"
        self.recovery_task = None
        self._last_native_start_ms = -10_000
        self._last_notice = None

    async def on_key(self, key):
        if key.source != Source.PHONE:
            return
        old = self.state
        self.state = begin_phone_key(self.state, key, mono_ms())
        # Updating the fence and cancelling in-flight owned commands happens
        # before waiting for POST /event, even while status GET is in flight.
        if self.state != old:
            if self.recovery_task is not None:
                self.recovery_task.cancel()
            self.guard_wakeup.set()
        try:
            self.phone_outbox.put_nowait((self.state.phone_generation, key.event()))
        except asyncio.QueueFull:
            # Keep the local fence. Continuing blind would discard phone intent.
            self.state = failed_status(self.state)
            self._provenance_gap("phone_queue_overflow")
            self.stop_requested.set()
            log("phone_queue_overflow_releasing")

    async def on_native_fault(self, reason):
        self._provenance_gap(reason)
        log(reason)
        self.guard_wakeup.set()

    async def on_native_health(self, health):
        self.native_health = health
        self._health_generation += 1
        self._health_event = {"type":"native_health", "status":health.value}
        self.health_wakeup.set()

    async def on_native_proof(self, health, received_at_ms):
        self.state = observer_proven(self.state, received_at_ms)
        self._health_generation += 1
        self._health_event = {"type":"native_health", "status":health.value,
                              "proof":"native_status", "sample_wall_ms":wall_ms()}
        self.health_wakeup.set()
        self.guard_wakeup.set()

    def _provenance_gap(self, reason):
        if self.state.fault_fence != FaultFence.STOP_REQUIRED or self.state.observer != Observer.LOST:
            self.state = provenance_gap(self.state)
        if self.recovery_task is not None:
            self.recovery_task.cancel()
        self.fault_wakeup.set()
        self.guard_wakeup.set()

    async def _disarm(self):
        try:
            await self.guard.disarm()
        except (AdapterError, OSError, asyncio.TimeoutError):
            self._provenance_gap("native_disarm_failed")

    async def run(self):
        directory = private_directory(self.config.state_directory)
        socket = directory / "control.sock"
        # A private lock prevents a second controller from unlinking a live socket.
        import fcntl
        descriptor = os.open(directory / "controller.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise AdapterError("controller_already_running") from exc
            socket.unlink(missing_ok=True)
            server = await asyncio.start_unix_server(self._control, path=str(socket), limit=4096)
            os.chmod(socket, 0o600)
            tasks = [asyncio.create_task(function()) for function in (
                self._status_loop, self._phone_loop, self._guard_loop, self._observe_loop, self._health_loop, self._fault_loop)]
            stop_task = asyncio.create_task(self.stop_requested.wait())
            log("controller_started", recovery_mode=self.config.recovery_mode.value)
            try:
                done, _ = await asyncio.wait([*tasks, stop_task], return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    if task is not stop_task:
                        task.result()  # Unexpected loop failure releases in finally.
            finally:
                self.stop_requested.set()
                if self.recovery_task is not None:
                    self.recovery_task.cancel()
                for task in [*tasks, stop_task]:
                    task.cancel()
                await asyncio.gather(*tasks, stop_task, *([self.recovery_task] if self.recovery_task else []), return_exceptions=True)
                await self.guard.close()
                self.media.close()
                try:
                    await self.client.event({"type": "native_health", "status": NativeHealth.STOPPED.value})
                except (AdapterError, ValueError, OSError):
                    pass
                server.close()
                await server.wait_closed()
                socket.unlink(missing_ok=True)
                log("controller_stopped")
        finally:
            os.close(descriptor)

    async def _pause(self, seconds, event=None):
        event = self.stop_requested if event is None else event
        try:
            await asyncio.wait_for(event.wait(), seconds)
        except asyncio.TimeoutError:
            pass
        if event is not self.stop_requested:
            event.clear()

    async def _status_loop(self):
        while not self.stop_requested.is_set():
            cycle_started = mono_ms()
            self.status_wakeup.clear()
            generation = self.state.phone_generation
            observer_generation = self.state.observer_generation
            try:
                snapshot = await self.client.status()
                self.state = apply_status(self.state, snapshot, mono_ms(), generation, observer_generation)
            except (AdapterError, ValueError, OSError):
                self.state = failed_status(self.state)
                if self.recovery_task is not None:
                    self.recovery_task.cancel()
                await self._disarm()
                log("status_unavailable", state_source=self.state.availability.value)
            self.guard_wakeup.set()
            notice = (self.state.availability, self.state.snapshot.session.phase if self.state.snapshot else None)
            if notice != self._last_notice:
                log("session_status", state_source=self.state.availability.value,
                    phase=notice[1].value if notice[1] else "unknown")
                self._last_notice = notice
            current = self.state.snapshot.session if self.state.snapshot else None
            # Idle polling is loopback HTTP only. A new scheduled session must
            # not wait thirty seconds before native protection can begin.
            interval = ACTIVE_POLL_SECONDS if current and current.phase in (Phase.ACTIVE, Phase.PHONE_PAUSED) else IDLE_POLL_SECONDS
            await self._pause(max(0.05, interval - (mono_ms() - cycle_started) / 1000), self.status_wakeup)

    async def _phone_loop(self):
        while not self.stop_requested.is_set():
            generation, event = await self.phone_outbox.get()
            try:
                while not self.stop_requested.is_set():
                    try:
                        await self.client.event(event)
                        self.state = mark_phone_posted(self.state, generation)
                        self.status_wakeup.set()
                        break
                    except (AdapterError, ValueError, OSError) as exc:
                        # Retry this same key before later keys. Releases cannot
                        # overtake presses while the endpoint is unavailable.
                        self.state = failed_status(self.state)
                        await self._disarm()
                        self.status_wakeup.set()
                        log("phone_post_unavailable", state_source=self.state.availability.value)
                        if str(exc) == "service_http_400" or wall_ms() - event["wall_ms"] > 3000:
                            if event["value"] == 1 and event["linux_code"] not in VOLUME_CODES:
                                self._provenance_gap("phone_event_delivery_lost")
                            break
                        await self._pause(1)
            finally:
                self.phone_outbox.task_done()

    async def _guard_loop(self):
        while not self.stop_requested.is_set():
            self.guard_wakeup.clear()
            now = mono_ms()
            if self.guard.phase == GuardPhase.FAILED:
                await self.guard.close()
            try:
                if self.guard.phase != GuardPhase.RUNNING and now - self._last_native_start_ms >= 10_000:
                    self._last_native_start_ms = now
                    await self.guard.start()  # Passive DISARMED observation even while prepared/idle.
                await self.guard.check_liveness()
                await self.guard.request_status()
                if guard_required(self.state, mono_ms(), wall_ms()):
                    session = self.state.snapshot.session
                    await self.guard.arm(session.id, session.expires_at_ms)
                else:
                    await self._disarm()
            except (AdapterError, OSError, asyncio.TimeoutError):
                self._provenance_gap("native_monitor_failed")
                await self.guard.close()
                log("native_monitor_failed")
            await self._pause(0.5, self.guard_wakeup)

    async def _health_loop(self):
        while not self.stop_requested.is_set():
            self.health_wakeup.clear()
            event, generation = self._health_event, self._health_generation
            if (event is not None and generation != self._posted_health_generation
                    and self.state.fault_fence == FaultFence.CLEAR
                    and ("proof" not in event or (generation >= self._proof_min_generation
                         and -1000 <= wall_ms() - event["sample_wall_ms"] <= 3000))):
                try:
                    async with self._health_post_lock:
                        if self.state.fault_fence != FaultFence.CLEAR:
                            continue
                        await self.client.event(event)
                    self._posted_health_generation = generation
                except (AdapterError, ValueError, OSError):
                    self.state = failed_status(self.state)
                    self.guard_wakeup.set()
            await self._pause(1, self.health_wakeup)

    async def _fault_loop(self):
        while not self.stop_requested.is_set():
            self.fault_wakeup.clear()
            if self.state.fault_fence == FaultFence.STOP_REQUIRED:
                await self._disarm()
                generation = self.state.observer_generation
                try:
                    try:
                        async with self._health_post_lock:
                            await self.client.event({"type":"native_health", "status":"unavailable"})
                    except (AdapterError, ValueError, OSError):
                        # Explicit terminal fallback only; never launch/resume.
                        result = await self.android.admin("override")
                        if "error" in result:
                            raise AdapterError("terminal_override_rejected")
                    self.state = terminal_stop_confirmed(self.state, generation)
                    self._proof_min_generation = self._health_generation + 1
                    self.status_wakeup.set()
                    log("provenance_terminal_stop", result="confirmed")
                except (AdapterError, ValueError, OSError, asyncio.TimeoutError):
                    log("provenance_terminal_stop", result="pending")
            await self._pause(1, self.fault_wakeup)

    async def _sample_player(self):
        if self.config.media_source == MediaSource.DIAL:
            result = await read_player(self.config.player_port)
            evidence, source = parse_player(result, wall_ms()), "dial"
        elif self.config.media_source == MediaSource.CDP:
            evidence, source = await asyncio.to_thread(self.media.sample), "cdp"
        else:
            results = await asyncio.gather(asyncio.to_thread(self.media.sample),
                read_player(self.config.player_port), return_exceptions=True)
            if isinstance(results[0], Playback):
                evidence, source = results[0], "cdp"
            else:
                evidence = parse_player(results[1], wall_ms()) if isinstance(results[1], str) else None
                source = "dial" if evidence is not None else "unavailable"
        if source != self.media_evidence_source:
            self.media_evidence_source = source
            log("media_source", source=source)
        return evidence

    async def _observe_loop(self):
        while not self.stop_requested.is_set():
            cycle_started = mono_ms()
            session = live_session(self.state, mono_ms(), wall_ms())
            if (session is not None and session.phase == Phase.ACTIVE
                    and observer_fresh(self.state, mono_ms())
                    and self.state.fault_fence == FaultFence.CLEAR
                    and native_observer_live(self.guard.monitor, mono_ms())):
                observed_at = mono_ms()
                if self.state.snapshot.session_mode == SessionMode.POWER_PROBE:
                    try:
                        results = [await self.android.observation(), None]
                    except (AdapterError, OSError, asyncio.TimeoutError) as exc:
                        results = [exc, None]
                else:
                    results = await asyncio.gather(self.android.observation(), self._sample_player(), return_exceptions=True)
                observation = parse_observation(results[0]) if isinstance(results[0], str) else parse_observation("")
                playback = results[1] if isinstance(results[1], Playback) else None
                if playback is not None:
                    try:
                        await self.client.event(playback.event())
                        self.state = observe_phone_playback(self.state, playback, mono_ms(), wall_ms(), self.config.player_package)
                        self.status_wakeup.set()
                    except (AdapterError, ValueError, OSError):
                        self.state = failed_status(self.state)
                        await self._disarm()
                        self.status_wakeup.set()
                now = mono_ms()
                if now - observed_at <= 5000:
                    reason = recovery_reason(self.state, now, wall_ms(), observation,
                                             playback, self.config.player_package)
                    if reason is not None and (self.recovery_task is None or self.recovery_task.done()):
                        if self.config.recovery_mode == RecoveryMode.ENABLED:
                            self.state = record_recovery(self.state, now)
                            self.recovery_task = asyncio.create_task(self._recover(reason))
                        else:
                            log("recovery_observed", reason=reason.value)
            await self._pause(max(0.05, 4 - (mono_ms() - cycle_started) / 1000))

    def _may_continue(self, identity, generation, session_mode=None):
        now = mono_ms()
        session = live_session(self.state, now, wall_ms())
        return (recovery_context_allowed(self.state, now, wall_ms())
                and not self.stop_requested.is_set()
                and session.id == identity and self.state.phone_generation == generation
                and (session_mode is None or self.state.snapshot.session_mode == session_mode)
                and native_observer_live(self.guard.monitor, now) and self.guard.phase == GuardPhase.RUNNING)

    async def _confirm_power_state(self, identity, generation, session_mode):
        """Six seconds of passive proof only; no additional power/CEC command."""
        deadline = asyncio.get_running_loop().time() + 6.0
        while self._may_continue(identity, generation, session_mode):
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                return False
            started_at = mono_ms()
            try:
                sample = await asyncio.wait_for(self.android.observation(), min(5.0, remaining))
            except (AdapterError, OSError, asyncio.TimeoutError):
                return False
            if (not self._may_continue(identity, generation, session_mode)
                    or mono_ms() - started_at > 5000 or asyncio.get_running_loop().time() > deadline):
                return False
            if restored_power_state(parse_observation(sample)):
                return True
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining > 0:
                await self._pause(min(0.5, remaining))
        return False

    async def _recover(self, reason):
        identity = self.state.snapshot.session.id
        generation = self.state.phone_generation
        session_mode = self.state.snapshot.session_mode
        result = "cancelled"
        operation = "context"
        diagnostics = {}
        confirmation = Confirmation.NONE
        try:
            if not self._may_continue(identity, generation, session_mode):
                return
            if session_mode == SessionMode.POWER_PROBE and reason not in (Recovery.POWER, Recovery.INPUT):
                return
            if reason in (Recovery.POWER, Recovery.INPUT):
                operation = "native_barrier_before_wake"
                if not await self.guard.recovery_barrier() or not self._may_continue(identity, generation, session_mode):
                    result = "native_barrier_unproven"
                    return
                operation = "wake"
                await self.android.wake()
                if not self._may_continue(identity, generation, session_mode):
                    return
                operation = "native_barrier_before_cec"
                if not await self.guard.recovery_barrier() or not self._may_continue(identity, generation, session_mode):
                    result = "native_barrier_unproven"
                    return
                operation = "one_touch_play"
                try:
                    await self.android.one_touch_play()
                except AndroidCommandError as exc:
                    if not (exc.failure_type == "exit_status" and exc.exit_code == 1
                            and exc.output_status == "hdmi_callback_deadline"):
                        raise
                    if not await self._confirm_power_state(identity, generation, session_mode):
                        raise
                    confirmation = Confirmation.STATE
                    diagnostics = {"failure_type":exc.failure_type, "exit_code":exc.exit_code,
                                   "output_status":exc.output_status}
                if not self._may_continue(identity, generation, session_mode):
                    return
            if session_mode == SessionMode.POWER_PROBE:
                result = "power_probe_confirmed" if confirmation == Confirmation.STATE else "power_probe_requested"
            elif self.state.snapshot.authentication != Authentication.CLEAR:
                result = "auth_required" if self.state.snapshot.authentication == Authentication.REQUIRED else "auth_unknown"
            elif self._may_continue(identity, generation, session_mode):
                # App owns target/video/resume logic; no blind am start/deeplink.
                operation = "native_barrier_before_app"
                if (not await self.guard.recovery_barrier() or not self._may_continue(identity, generation, session_mode)
                        or self.state.snapshot.authentication != Authentication.CLEAR):
                    result = "native_barrier_unproven"
                    return
                operation = "app_recover"
                await self.android.admin("recover")
                result = "requested"
        except asyncio.CancelledError:
            result = "phone_or_state_cancelled"
            raise
        except AndroidCommandError as exc:
            result = "failed"
            diagnostics = {"failure_type":exc.failure_type, "exit_code":exc.exit_code,
                           "output_status":exc.output_status}
        except (AdapterError, OSError, asyncio.TimeoutError) as exc:
            result = "failed"
            diagnostics = {"failure_type":"adapter_error" if isinstance(exc,AdapterError) else "os_error" if isinstance(exc,OSError) else "deadline"}
        finally:
            log("recovery", result=result, reason=reason.value, operation=operation, **diagnostics)
            if result != "phone_or_state_cancelled":
                try:
                    await self.client.event({"type": "recovery", "result": result, "reason": reason.value})
                except (AdapterError, ValueError, OSError):
                    self.state = failed_status(self.state)
                    self.guard_wakeup.set()
            self.status_wakeup.set()

    def status(self):
        session = self.state.snapshot.session if self.state.snapshot else None
        return {"schema": 1, "controller": "running", "state_source": self.state.availability.value,
                "session_phase": session.phase.value if session else "unknown",
                "session_id": session.id if session else None, "phone_intent": self.state.intent.value,
                "native_process": self.guard.phase.value,
                "native_health": self.native_health.value, "media_evidence_source": self.media_evidence_source,
                "input_observer": self.state.observer.value, "provenance_fence": self.state.fault_fence.value,
                "recovery_mode": self.config.recovery_mode.value}

    async def _control(self, reader, writer):
        try:
            line = await asyncio.wait_for(reader.readline(), 2)
            command = line.decode("ascii").strip()
            if command == "status":
                response = self.status()
            elif command == "shutdown":
                self.stop_requested.set()
                await self.guard.close()
                response = {"schema": 1, "controller": "stopping"}
            elif command == "refresh":
                self.status_wakeup.set()
                response = {"schema": 1, "result": "status_refresh_requested"}
            else:
                response = {"schema": 1, "error": "unknown_control_command"}
            writer.write((json.dumps(response) + "\n").encode())
            await writer.drain()
        except (OSError, ValueError, asyncio.TimeoutError):
            pass
        finally:
            writer.close()
            await writer.wait_closed()


async def control_request(directory, command):
    reader, writer = await asyncio.wait_for(asyncio.open_unix_connection(str(Path(directory) / "control.sock")), 2)
    try:
        writer.write((command + "\n").encode("ascii"))
        await writer.drain()
        return json.loads(await asyncio.wait_for(reader.readline(), 5))
    finally:
        writer.close()
        await writer.wait_closed()
