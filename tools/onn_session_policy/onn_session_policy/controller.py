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
from .debugger_forward import CobaltForward
from .native_protocol import observer_live as native_observer_live
from .policy import (
    Authentication, Availability, FaultFence, Intent, Observer, Phase, Playback, Playing, PolicyState, Recovery, Rollout, SessionMode, Source, STATUS_MAX_AGE_MS, OBSERVATION_MAX_AGE_MS, VOLUME_CODES,
    apply_status, begin_phone_key, failed_status, guard_required, live_session,
    mark_phone_posted, parse_observation, parse_player, recovery_reason,
    observe_phone_playback, observer_fresh, observer_proven, parse_status, provenance_gap,
    record_recovery, recovery_context_allowed, restored_power_state, school_waiting_context_allowed, startup_context_allowed, StartupGrantState, terminal_stop_confirmed,
)


ACTIVE_POLL_SECONDS = 4.0
IDLE_POLL_SECONDS = 1.0
PLAYBACK_CONFIRM_SECONDS = 4.0


def advancing_playback(first, second, video_id, minimum_wall_ms, now_wall_ms):
    """Two actual, fresh samples from this recovery; cached state cannot pass."""
    if not all(isinstance(sample, Playback) and sample.video_id == video_id
               and sample.playing == Playing.PLAYING
               and sample.sample_wall_ms >= minimum_wall_ms
               and -1000 <= now_wall_ms - sample.sample_wall_ms <= OBSERVATION_MAX_AGE_MS
               for sample in (first, second)):
        return False
    return (second.sample_wall_ms - first.sample_wall_ms >= 200
            and second.position_seconds - first.position_seconds >= 0.2)



class RecoveryMode(str, Enum):
    OBSERVE = "observe"
    ENABLED = "enabled"


class PinScope(str, Enum):
    SCHEDULED = "scheduled"
    MANUAL = "manual"
    STARTUP = "startup"


class PinAuthentication(str, Enum):
    SUCCEEDED = "succeeded"
    STOPPED = "stopped"
    DENIED = "denied"
    DEADLINE = "deadline"
    UNAVAILABLE = "unavailable"


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
        self._school_lease_armed = False
        self.media_evidence_source = "unobserved"
        self.debugger_forward = CobaltForward(android.prefix, config.debugger_port)
        self._last_forward_state = None
        self._media_lock = asyncio.Lock()
        self._latest_playback = None
        self._latest_playback_session = None
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
        log(reason, **(self.guard.last_error if reason == "native_input_error" else {}))
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
                await self._update_school_lease(clear=True)
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
            startup = self.state.snapshot.startup_authentication if self.state.snapshot else None
            if (self.config.recovery_mode == RecoveryMode.ENABLED and startup is not None
                    and startup.state == StartupGrantState.PENDING
                    and (self.recovery_task is None or self.recovery_task.done())
                    and startup_context_allowed(self.state, mono_ms(), wall_ms(), startup.run_id)):
                self.recovery_task = asyncio.create_task(self._recover_startup(startup.run_id))
            notice = (self.state.availability, self.state.snapshot.session.phase if self.state.snapshot else None)
            if notice != self._last_notice:
                log("session_status", state_source=self.state.availability.value,
                    phase=notice[1].value if notice[1] else "unknown")
                self._last_notice = notice
            current = self.state.snapshot.session if self.state.snapshot else None
            # Idle polling is loopback HTTP only. One-second reads notice a
            # signature-owned five-second startup grant while it is still fresh.
            interval = ACTIVE_POLL_SECONDS if current and current.phase in (Phase.ACTIVE, Phase.PHONE_PAUSED, Phase.SCHOOL_BANNER) else IDLE_POLL_SECONDS
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
                        if event.get("value") in (1, 2) and event.get("linux_code") not in VOLUME_CODES:
                            await self._update_school_lease(clear=True)
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

    def _playback_identity(self):
        session = self.state.snapshot.session if self.state.snapshot else None
        if session is None:
            return None
        if session.phase in (Phase.ACTIVE, Phase.PHONE_PAUSED):
            return session.id
        banner = self.state.snapshot.school_banner
        if banner is not None and banner.phase == "waiting" and banner.expires_at_ms > wall_ms():
            return banner.run_id
        return None

    def _playback_status(self):
        identity = self._playback_identity()
        sample = self._latest_playback
        if (identity is None or identity != self._latest_playback_session
                or sample is None
                or not -1000 <= wall_ms() - sample.sample_wall_ms <= OBSERVATION_MAX_AGE_MS):
            self._latest_playback = None
            self._latest_playback_session = None
            return None
        return sample.event()

    async def _read_cdp(self):
        return await self._run_cdp(self.media.sample)

    async def _run_cdp(self, function, *args):
        # Drain a cancelled caller's bounded operation before reusing the socket.
        # Any accepted media lease remains finite and is cleared on shutdown.
        task = asyncio.create_task(asyncio.to_thread(function, *args))
        try:
            return await asyncio.shield(task)
        except asyncio.CancelledError:
            while not task.done():
                try:
                    await asyncio.shield(task)
                except asyncio.CancelledError:
                    continue
                except Exception:
                    break
            if task.done() and not task.cancelled():
                task.exception()
            raise

    async def _update_school_lease(self, clear=False):
        """Renew only after fresh app/native proof; parent intent clears in order."""
        def requested():
            now, wall = mono_ms(), wall_ms()
            snapshot = self.state.snapshot
            banner = snapshot.school_banner if snapshot else None
            if (clear or banner is None or banner.diagnostic
                    or banner.phase not in ("waiting", "active")
                    or not (recovery_context_allowed(self.state, now, wall)
                            or school_waiting_context_allowed(self.state, now, wall))
                    or not native_observer_live(self.guard.monitor, now)
                    or self.guard.phase != GuardPhase.RUNNING):
                return None
            return min(wall + 12_000, banner.expires_at_ms), banner.video_id, banner.phase == "waiting"

        desired = requested()
        if desired is None and not self._school_lease_armed:
            return
        try:
            async with self._media_lock:
                desired = requested()
                if desired is not None:
                    if not await self.guard.recovery_barrier():
                        desired = None
                    else:
                        desired = requested()
                if desired is None and not self._school_lease_armed:
                    return
                if not await self.debugger_forward.ensure(mono_ms()):
                    return
                desired = requested()
                if desired is None and not self._school_lease_armed:
                    return
                args = desired if desired is not None else (0, "", False)
                accepted = await self._run_cdp(self.media.set_school_banner, *args)
                if accepted:
                    self._school_lease_armed = desired is not None
        except (AdapterError, OSError, ValueError, asyncio.TimeoutError):
            # The existing page lease expires after at most twelve seconds.
            log("school_media_lease_unavailable")

    async def _sample_cdp(self):
        if not await self.debugger_forward.ensure(mono_ms()):
            self.media.close()
            raise AdapterError("cdp_forward_unavailable")
        try:
            return await self._read_cdp()
        except (AdapterError, OSError, ValueError):
            self.debugger_forward.transport_failed(mono_ms())
            raise

    async def _sample_player(self):
        # The observer and bounded recovery proof share exactly one CDP reader.
        async with self._media_lock:
            identity = self._playback_identity()
            if self.config.media_source == MediaSource.DIAL:
                result = await read_player(self.config.player_port)
                evidence = parse_player(result, wall_ms())
                source = "dial" if evidence is not None else "unavailable"
            elif self.config.media_source == MediaSource.CDP:
                evidence = await self._sample_cdp()
                source = "cdp" if evidence is not None else "unavailable"
            else:
                results = await asyncio.gather(self._sample_cdp(),
                    read_player(self.config.player_port), return_exceptions=True)
                if isinstance(results[0], Playback):
                    evidence, source = results[0], "cdp"
                else:
                    evidence = parse_player(results[1], wall_ms()) if isinstance(results[1], str) else None
                    source = "dial" if evidence is not None else "unavailable"
            # Discard the returned observation as well as its cache if the run
            # ended or changed while the bounded I/O was in flight.
            if identity is None or identity != self._playback_identity():
                evidence = None
            if (identity is not None and isinstance(evidence, Playback)):
                self._latest_playback = evidence
                self._latest_playback_session = identity
            self._playback_status()  # Expire old evidence without changing its timestamp.
            forward_state = self.debugger_forward.state
            if (self.config.media_source != MediaSource.DIAL
                    and forward_state != self._last_forward_state):
                self._last_forward_state = forward_state
                log("debugger_forward", state=forward_state.value)
            if source != self.media_evidence_source:
                self.media_evidence_source = source
                log("media_source", source=source)
            return evidence

    async def _observe_loop(self):
        while not self.stop_requested.is_set():
            cycle_started = mono_ms()
            await self._update_school_lease()
            session = live_session(self.state, mono_ms(), wall_ms())
            waiting = school_waiting_context_allowed(self.state, mono_ms(), wall_ms())
            if session is None and waiting:
                session = self.state.snapshot.session
            if (session is not None and (session.phase in (Phase.ACTIVE, Phase.SCHOOL_BANNER) or waiting)
                    and observer_fresh(self.state, mono_ms())
                    and self.state.fault_fence == FaultFence.CLEAR
                    and native_observer_live(self.guard.monitor, mono_ms())):
                observed_at = mono_ms()
                if waiting and session.phase == Phase.COMPLETED:
                    try:
                        results = [None, await self._sample_player()]
                    except (AdapterError, OSError, asyncio.TimeoutError) as exc:
                        results = [None, exc]
                elif self.state.snapshot.session_mode == SessionMode.POWER_PROBE or session.phase == Phase.SCHOOL_BANNER:
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
                        playback_event = playback.event()
                        playback_event["run_id"] = session.id
                        await self.client.event(playback_event)
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

    def _may_continue(self, identity, generation, session_mode=None, expected_phase=None):
        now = mono_ms()
        session = live_session(self.state, now, wall_ms())
        return (recovery_context_allowed(self.state, now, wall_ms())
                and not self.stop_requested.is_set()
                and session.id == identity and self.state.phone_generation == generation
                and (session_mode is None or self.state.snapshot.session_mode == session_mode)
                and (expected_phase is None or session.phase == expected_phase)
                and native_observer_live(self.guard.monitor, now) and self.guard.phase == GuardPhase.RUNNING)

    async def _fenced_admin(self, command, allowed, deadline):
        remaining = deadline - asyncio.get_running_loop().time()
        if remaining <= 0 or not allowed():
            raise asyncio.TimeoutError
        if not await self.guard.recovery_barrier(timeout=min(2.0, remaining)) or not allowed():
            raise AdapterError("native_barrier_unproven")
        remaining = deadline - asyncio.get_running_loop().time()
        if remaining <= 0:
            raise asyncio.TimeoutError
        return await asyncio.wait_for(self.android.admin(command), remaining)

    async def _authenticate(self, allowed, scope=PinScope.SCHEDULED, expires_wall_ms=None):
        """One attempt, five seconds total, with a real phone drain before each digit."""
        remaining = 5.0 if expires_wall_ms is None else min(5.0, max(0.0, (expires_wall_ms - wall_ms()) / 1000))
        deadline = asyncio.get_running_loop().time() + remaining
        command = {PinScope.MANUAL:"authenticate_once", PinScope.SCHEDULED:"authenticate_begin",
                   PinScope.STARTUP:"authenticate_startup_begin"}[scope]
        try:
            while allowed():
                response = await self._fenced_admin(command, allowed, deadline)
                if response.get("ok") is False:
                    return PinAuthentication.DENIED
                state = response.get("state")
                if state == "succeeded":
                    return PinAuthentication.SUCCEEDED
                if state == "stopped":
                    return PinAuthentication.STOPPED
                if state not in ("entering", "awaiting_result"):
                    return PinAuthentication.DENIED
                # Let window events and phone deliveries run between individual actions.
                remaining = deadline - asyncio.get_running_loop().time()
                if remaining <= 0:
                    return PinAuthentication.DEADLINE
                await self._pause(min(0.12, remaining))
                command = "authenticate_step"
            return PinAuthentication.STOPPED
        except asyncio.TimeoutError:
            return PinAuthentication.DEADLINE

    async def _probe_recovery_status(self, allowed):
        generation, observer_generation = self.state.phone_generation, self.state.observer_generation
        result = await self._fenced_admin("probe", allowed, asyncio.get_running_loop().time() + 4.0)
        self.state = apply_status(self.state, parse_status(result), mono_ms(), generation, observer_generation)
        return allowed()

    async def _recover_startup(self, run_id):
        """Only authenticate the genuine alarm grant; the helper owns subsequent normal startup."""
        generation = self.state.phone_generation
        def allowed():
            now = mono_ms()
            return (not self.stop_requested.is_set() and self.state.phone_generation == generation
                    and startup_context_allowed(self.state, now, wall_ms(), run_id)
                    and native_observer_live(self.guard.monitor, now) and self.guard.phase == GuardPhase.RUNNING)
        result = PinAuthentication.STOPPED
        try:
            if allowed():
                grant = self.state.snapshot.startup_authentication
                result = await self._authenticate(allowed, PinScope.STARTUP, grant.expires_at_ms)
        except asyncio.CancelledError:
            raise
        except (AdapterError, OSError, ValueError):
            result = PinAuthentication.UNAVAILABLE
        finally:
            log("startup_authentication", result=result.value)
            self.status_wakeup.set()

    def _manual_authentication_allowed(self, generation):
        now = mono_ms()
        snapshot = self.state.snapshot
        return (not self.stop_requested.is_set() and self.state.phone_generation == generation
                and self.state.posted_generation >= generation and now >= self.state.phone_grace_until_ms
                and self.state.availability == Availability.LIVE and 0 <= now - self.state.fetched_at_ms <= STATUS_MAX_AGE_MS
                and snapshot is not None and snapshot.session.phase not in (Phase.ACTIVE, Phase.PHONE_PAUSED, Phase.SCHOOL_BANNER)
                and observer_fresh(self.state, now) and self.state.fault_fence == FaultFence.CLEAR
                and native_observer_live(self.guard.monitor, now) and self.guard.phase == GuardPhase.RUNNING)

    async def _manual_authenticate_once(self):
        """Explicit private-socket test: authentication only, no session, rollout or playback changes."""
        generation = self.state.phone_generation
        def allowed():
            return self._manual_authentication_allowed(generation)
        try:
            observer_generation = self.state.observer_generation
            snapshot = await self.client.status()
            self.state = apply_status(self.state, snapshot, mono_ms(), generation, observer_generation)
            if not allowed():
                return {"schema":1, "result":PinAuthentication.DENIED.value}
            if not await self._probe_recovery_status(allowed):
                return {"schema":1, "result":PinAuthentication.STOPPED.value}
            result = await self._authenticate(allowed, PinScope.MANUAL)
            return {"schema":1, "result":result.value}
        except asyncio.TimeoutError:
            return {"schema":1, "result":PinAuthentication.DEADLINE.value}
        except (AdapterError, OSError, ValueError):
            return {"schema":1, "result":PinAuthentication.UNAVAILABLE.value}
        finally:
            self.status_wakeup.set()

    async def _confirm_power_state(self, identity, generation, session_mode, expected_phase=None):
        """Six seconds of passive proof only; no additional power/CEC command."""
        deadline = asyncio.get_running_loop().time() + 6.0
        while self._may_continue(identity, generation, session_mode, expected_phase):
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                return False
            started_at = mono_ms()
            try:
                sample = await asyncio.wait_for(self.android.observation(), min(5.0, remaining))
            except (AdapterError, OSError, asyncio.TimeoutError):
                return False
            if (not self._may_continue(identity, generation, session_mode, expected_phase)
                    or mono_ms() - started_at > 5000 or asyncio.get_running_loop().time() > deadline):
                return False
            if restored_power_state(parse_observation(sample)):
                return True
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining > 0:
                await self._pause(min(0.5, remaining))
        return False

    async def _confirm_resumed_playback(self, identity, generation, session_mode):
        """Four seconds of read-only proof after wake/authentication."""
        minimum_wall_ms = wall_ms()
        deadline = asyncio.get_running_loop().time() + PLAYBACK_CONFIRM_SECONDS
        first = None
        while self._may_continue(identity, generation, session_mode, Phase.ACTIVE):
            snapshot = self.state.snapshot
            if (snapshot.authentication != Authentication.CLEAR
                    or snapshot.foreground_package != self.config.player_package):
                return False
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                return False
            try:
                sample = await asyncio.wait_for(self._sample_player(), remaining)
            except (AdapterError, OSError, asyncio.TimeoutError):
                return False
            if (not self._may_continue(identity, generation, session_mode, Phase.ACTIVE)
                    or asyncio.get_running_loop().time() > deadline):
                return False
            if (self.state.snapshot.authentication != Authentication.CLEAR
                    or self.state.snapshot.foreground_package != self.config.player_package):
                return False
            if advancing_playback(first, sample, snapshot.session.video_id,
                                  minimum_wall_ms, wall_ms()):
                return True
            if (isinstance(sample, Playback) and sample.playing == Playing.PLAYING
                    and sample.video_id == snapshot.session.video_id
                    and sample.sample_wall_ms >= minimum_wall_ms):
                if first is None or sample.position_seconds < first.position_seconds:
                    first = sample
            else:
                first = None
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining > 0:
                await self._pause(min(0.35, remaining))
        return False

    async def _recover(self, reason):
        identity = self.state.snapshot.session.id
        generation = self.state.phone_generation
        session_mode = self.state.snapshot.session_mode
        expected_phase = self.state.snapshot.session.phase
        def allowed():
            return self._may_continue(identity, generation, session_mode, expected_phase)
        result = "cancelled"
        operation = "context"
        diagnostics = {}
        confirmation = Confirmation.NONE
        try:
            if not allowed():
                return
            if session_mode == SessionMode.POWER_PROBE and reason not in (Recovery.POWER, Recovery.INPUT):
                return
            banner_recovery = self.state.snapshot.session.phase == Phase.SCHOOL_BANNER
            if banner_recovery and reason not in (Recovery.POWER, Recovery.INPUT):
                return
            if reason in (Recovery.POWER, Recovery.INPUT):
                operation = "native_barrier_before_wake"
                if not await self.guard.recovery_barrier() or not allowed():
                    result = "native_barrier_unproven"
                    return
                operation = "wake"
                await self.android.wake()
                if not allowed():
                    return
                operation = "native_barrier_before_cec"
                if not await self.guard.recovery_barrier() or not allowed():
                    result = "native_barrier_unproven"
                    return
                operation = "one_touch_play"
                try:
                    await self.android.one_touch_play()
                except AndroidCommandError as exc:
                    if not (exc.failure_type == "exit_status" and exc.exit_code == 1
                            and exc.output_status == "hdmi_callback_deadline"):
                        raise
                    if not await self._confirm_power_state(identity, generation, session_mode, expected_phase):
                        raise
                    confirmation = Confirmation.STATE
                    diagnostics = {"failure_type":exc.failure_type, "exit_code":exc.exit_code,
                                   "output_status":exc.output_status}
                if not allowed():
                    return
            if banner_recovery:
                result = "school_banner_power_restored"
            elif session_mode == SessionMode.POWER_PROBE:
                result = "power_probe_confirmed" if confirmation == Confirmation.STATE else "power_probe_requested"
            elif allowed():
                # Wake can reveal a PIN screen after the original status. Read the
                # current bounded tree before deciding whether authentication is needed.
                operation = "probe_authentication"
                if not await self._probe_recovery_status(allowed):
                    return
                if self.state.snapshot.authentication != Authentication.CLEAR:
                    operation = "normal_pin_entry"
                    authenticated = await self._authenticate(allowed)
                    if authenticated != PinAuthentication.SUCCEEDED:
                        result = "authentication_" + authenticated.value
                        return
                    operation = "confirm_authentication"
                    if not await self._probe_recovery_status(allowed):
                        return
                if self.state.snapshot.authentication != Authentication.CLEAR:
                    result = "authentication_unconfirmed"
                    return
                if reason in (Recovery.POWER, Recovery.INPUT):
                    operation = "confirm_resumed_playback"
                    if await self._confirm_resumed_playback(identity, generation, session_mode):
                        result = "playback_resumed"
                        return
                    if not allowed():
                        return
                operation = "app_recover"
                reply = await self._fenced_admin("recover", allowed, asyncio.get_running_loop().time() + 4.0)
                result = "requested" if reply.get("ok") is True else "app_rejected"
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
                "native_lease": self.guard.monitor.lease.value,
                "native_physical_grabs": list(self.guard.monitor.physical_grabs),
                "native_health": self.native_health.value, "media_evidence_source": self.media_evidence_source,
                "input_observer": self.state.observer.value, "provenance_fence": self.state.fault_fence.value,
                "recovery_mode": self.config.recovery_mode.value,
                "debugger_forward": self.debugger_forward.state.value,
                "playback": self._playback_status(),
                "recovery_in_progress": bool(self.recovery_task is not None and not self.recovery_task.done())}

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
            elif command == "authenticate-once":
                if self.recovery_task is not None and not self.recovery_task.done():
                    response = {"schema":1, "result":"recovery_in_progress"}
                else:
                    self.recovery_task = asyncio.create_task(self._manual_authenticate_once())
                    try:
                        response = await self.recovery_task
                    except asyncio.CancelledError:
                        response = {"schema":1, "result":"phone_or_state_cancelled"}
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
        return json.loads(await asyncio.wait_for(reader.readline(), 10 if command == "authenticate-once" else 5))
    finally:
        writer.close()
        await writer.wait_closed()
