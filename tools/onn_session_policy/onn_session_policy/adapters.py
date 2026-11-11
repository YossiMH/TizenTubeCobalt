"""OS/HTTP effects. No global input, pairing, Bluetooth, or CEC settings."""
import asyncio
from enum import Enum
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import stat

from .policy import parse_native_key, parse_status
from .native_protocol import (LeasePhase, MonitorFault, MonitorPhase, MonitorState, Proof,
    event as monitor_event, observer_live as monitor_live, requested, started,
    status_due, watchdog)


MAX_HTTP_BODY = 65_536
MAX_EVENT_BODY = 4096
ADMIN_COMPONENT = "dev.yossi.onnkeyguard/.PolicyAdminReceiver"
ADMIN_ACTION = "dev.yossi.onnkeyguard.POLICY_ADMIN"


class AdapterError(RuntimeError):
    """Messages are fixed classifications; remote bodies and secrets stay private."""


class AndroidCommandError(AdapterError):
    def __init__(self, failure_type, exit_code=None, output_status="unknown"):
        super().__init__("android_command_failed")
        self.failure_type = failure_type
        self.exit_code = exit_code
        self.output_status = output_status


def hdmi_output_status(output):
    """Fixed AOSP shell outcomes only; no command text enters diagnostics."""
    if b"One Touch Play timed out." in output:
        return "hdmi_callback_deadline"
    for ending, status in ((b"Communication Failed", "hdmi_communication_failed"),
                           (b"Source not available", "hdmi_source_unavailable"),
                           (b"Target not available", "hdmi_target_unavailable"),
                           (b"Incorrect mode", "hdmi_incorrect_mode"),
                           (b"Timeout", "hdmi_result_timeout"),
                           (b"Exception", "hdmi_exception"),
                           (b"Success", "hdmi_result_success")):
        if output.strip().endswith(b"done (" + ending + b")"):
            return status
    return "unknown"


class GuardPhase(str, Enum):
    STOPPED = "stopped"
    RUNNING = "running"
    FAILED = "failed"


class DrainCapability(str, Enum):
    UNKNOWN = "unknown"
    SUPPORTED = "supported"
    UNSUPPORTED = "unsupported"


class NativeHealth(str, Enum):
    STOPPED = "stopped"
    STARTING = "starting"
    LEASE_ARMED = "lease_armed"
    PHYSICAL_GRABBED = "physical_grabbed"
    DISARMED = "disarmed"
    UNAVAILABLE = "unavailable"
    MONITORING = "monitoring"


def private_directory(path):
    path = Path(path).expanduser()
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise AdapterError("private_directory_required")
    return path


def read_token(path):
    descriptor = os.open(Path(path).expanduser(), os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise AdapterError("private_token_file_required")
        value = os.read(descriptor, 256).decode("ascii").strip()
    finally:
        os.close(descriptor)
    if not re.fullmatch(r"[0-9a-fA-F]{64}", value):
        raise AdapterError("invalid_token_file")
    return value


def store_token(path, value):
    if not re.fullmatch(r"[0-9a-fA-F]{64}", value):
        raise AdapterError("invalid_controller_token")
    path = Path(path).expanduser()
    private_directory(path.parent)
    temporary = path.with_name(".controller-token-" + secrets.token_hex(8))
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        os.write(descriptor, (value + "\n").encode("ascii"))
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def broadcast_json(output):
    """am prints resultData as data="{...}"; never return/log its raw output."""
    decoder = json.JSONDecoder()
    for match in re.finditer(r"\{", output):
        try:
            value, _ = decoder.raw_decode(output[match.start():])
            if isinstance(value, dict) and value.get("schema") == 1:
                return value
        except ValueError:
            pass
    # Some adb wrappers escape resultData's inner quotation marks.
    if '\\"' in output:
        return broadcast_json(output.replace('\\"', '"'))
    raise AdapterError("admin_response_invalid")


class SessionClient:
    def __init__(self, token, port=8768, timeout=2.0):
        self._token = token
        self.port = port
        self.timeout = timeout

    def _request(self, method, path, value=None):
        payload = None if value is None else json.dumps(value, separators=(",", ":"), allow_nan=False).encode()
        if payload is not None and len(payload) > MAX_EVENT_BODY:
            raise AdapterError("event_too_large")
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=self.timeout)
        try:
            connection.request(method, path, payload, {
                "Authorization": "Bearer " + self._token,
                "Content-Type": "application/json", "Connection": "close"})
            response = connection.getresponse()
            body = response.read(MAX_HTTP_BODY + 1)
            if len(body) > MAX_HTTP_BODY:
                raise AdapterError("service_response_too_large")
            if response.status != 200:
                raise AdapterError("service_http_" + str(response.status))
            parsed = json.loads(body)
            if not isinstance(parsed, dict):
                raise AdapterError("service_response_invalid")
            return parsed
        except (OSError, http.client.HTTPException, ValueError) as exc:
            raise AdapterError("service_unavailable") from exc
        finally:
            connection.close()

    async def status(self):
        return parse_status(await asyncio.to_thread(self._request, "GET", "/status"))

    async def event(self, value):
        return await asyncio.to_thread(self._request, "POST", "/event", value)


async def read_player(port=8012, timeout=2.0):
    def request():
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
        try:
            connection.request("GET", "/apps/YouTube", headers={"Connection": "close"})
            response = connection.getresponse()
            body = response.read(MAX_HTTP_BODY + 1)
            if response.status != 200 or len(body) > MAX_HTTP_BODY:
                raise AdapterError("player_unavailable")
            return body.decode("utf-8")
        except (OSError, http.client.HTTPException, UnicodeError) as exc:
            raise AdapterError("player_unavailable") from exc
        finally:
            connection.close()
    return await asyncio.to_thread(request)


class Android:
    def __init__(self, executable="adb", serial=None):
        self.prefix = [executable] + (["-s", serial] if serial else [])

    async def shell(self, *arguments, timeout=4.0):
        process = await asyncio.create_subprocess_exec(*self.prefix, "shell", *arguments,
            stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE)
        try:
            output, error_output = await asyncio.wait_for(process.communicate(), timeout)
            if process.returncode != 0:
                status = hdmi_output_status(output + error_output) if arguments == ("cmd", "hdmi_control", "onetouchplay") else "unknown"
                raise AndroidCommandError("exit_status", process.returncode, status)
            if len(output) > 1_048_576 or len(error_output) > 1_048_576:
                raise AdapterError("android_response_too_large")
            return output.decode("utf-8", "replace")
        except asyncio.TimeoutError as exc:
            if process.returncode is None:
                process.kill()  # This exact subprocess belongs to this adapter.
            await process.wait()
            raise AndroidCommandError("process_deadline") from exc
        except asyncio.CancelledError:
            if process.returncode is None:
                process.kill()
            await process.wait()
            raise

    async def admin(self, command):
        if command not in ("controller_token", "status", "override", "recover", "probe"):
            raise AdapterError("unsupported_admin_command")
        result = await self.shell("am", "broadcast", "-a", ADMIN_ACTION, "-n",
                                  ADMIN_COMPONENT, "--es", "command", command)
        return broadcast_json(result)

    async def observation(self):
        # A single bounded shell command captures both fresh observations.
        return await self.shell("sh", "-c",
            "'dumpsys hdmi_control; echo \"<ONN_POLICY_POWER>\"; dumpsys power'", timeout=5.0)

    async def wake(self):
        await self.shell("input", "keyevent", "KEYCODE_WAKEUP")

    async def one_touch_play(self):
        await self.shell("cmd", "hdmi_control", "onetouchplay")


class NativeGuard:
    """Persistent passive observer; only finite ARM commands grab hardware."""
    def __init__(self, android, path, on_key, on_fault, on_health=None, on_proof=None):
        if not re.fullmatch(r"/[A-Za-z0-9_./-]+", path):
            raise AdapterError("invalid_native_path")
        self.android, self.path = android, path
        self.on_key, self.on_fault = on_key, on_fault
        self.on_health, self.on_proof = on_health, on_proof
        self._health = NativeHealth.STOPPED
        self.phase = GuardPhase.STOPPED
        self.monitor = MonitorState()
        self._process = None
        self._reader = None
        self._lock = asyncio.Lock()
        self._session_id = None
        self._session_end_ms = None
        self._command_lease = LeasePhase.UNKNOWN
        self._drain_capability = DrainCapability.UNKNOWN
        self._barrier_lock = asyncio.Lock()
        self._status_processed = asyncio.Event()
        self._drain_nonce = 0
        self._drain_waiter = None

    @staticmethod
    def _now():
        import time
        return time.monotonic_ns() // 1_000_000

    async def start(self):
        async with self._lock:
            if self.phase == GuardPhase.RUNNING and self._process is not None and self._process.returncode is None:
                return
            await self._close_locked()
            self._process = await asyncio.create_subprocess_exec(*self.android.prefix,
                "shell", "-T", self.path, "--run", stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            self.phase = GuardPhase.RUNNING
            self.monitor = started(self._now())
            self._command_lease = LeasePhase.UNKNOWN
            await self._set_health(NativeHealth.STARTING)
            self._reader = asyncio.create_task(self._read(self._process))
            await self._send_locked("DISARM")
            self._command_lease = LeasePhase.DISARMED
            self.monitor = requested(self.monitor, self._now())
            await self._send_locked("STATUS")

    async def arm(self, session_id, expires_at_ms, ttl_ms=12_000):
        async with self._lock:
            if self.phase != GuardPhase.RUNNING or not monitor_live(self.monitor, self._now()):
                raise AdapterError("native_monitor_unproven")
            if self._session_id != session_id or self._session_end_ms != expires_at_ms:
                await self._send_locked("DISARM")
            await self._send_locked(f"ARM {ttl_ms} {expires_at_ms}")
            self._command_lease = LeasePhase.ARMED
            self._session_id, self._session_end_ms = session_id, expires_at_ms

    async def disarm(self):
        async with self._lock:
            if self.phase == GuardPhase.RUNNING and self._command_lease != LeasePhase.DISARMED:
                await self._send_locked("DISARM")
            self._command_lease = LeasePhase.DISARMED
            self._session_id, self._session_end_ms = None, None

    async def request_status(self):
        async with self._lock:
            now = self._now()
            if self.phase == GuardPhase.RUNNING and status_due(self.monitor, now):
                self.monitor = requested(self.monitor, now)
                await self._send_locked("STATUS")

    async def check_liveness(self):
        if self.phase == GuardPhase.RUNNING:
            await self._apply_monitor_result(watchdog(self.monitor, self._now()))

    async def recovery_barrier(self, timeout=4.0):
        """Fresh STATUS then bounded phone DRAIN; the reader consumes keys first.

        STATUS alone is not an input barrier: native commands precede its input
        batch. DRAIN's matching nonce is emitted after phone fd reads and an
        empty zero-time poll. No command is authorized by elapsed time alone.
        """
        async with self._barrier_lock:
            deadline = asyncio.get_running_loop().time() + timeout
            waiter = None
            try:
                while True:
                    async with self._lock:
                        if (self.phase != GuardPhase.RUNNING or not monitor_live(self.monitor, self._now())
                                or self._drain_capability != DrainCapability.SUPPORTED):
                            return False
                        if self.monitor.request_ms is None:
                            self._drain_nonce = self._drain_nonce % 2_147_483_647 + 1
                            waiter = asyncio.get_running_loop().create_future()
                            self._drain_waiter = (self._drain_nonce, waiter)
                            self.monitor = requested(self.monitor, self._now())
                            await self._send_locked(f"STATUS\nDRAIN {self._drain_nonce}")
                            break
                        self._status_processed.clear()
                    remaining = deadline - asyncio.get_running_loop().time()
                    await asyncio.wait_for(self._status_processed.wait(), max(0, remaining))
                remaining = deadline - asyncio.get_running_loop().time()
                result = await asyncio.wait_for(asyncio.shield(waiter), max(0, remaining))
                return (result == "clear" and self.monitor.request_ms is None
                        and self.phase == GuardPhase.RUNNING and monitor_live(self.monitor, self._now()))
            except asyncio.TimeoutError:
                self.phase = GuardPhase.FAILED
                await self.on_fault("native_recovery_barrier_timeout")
                return False
            finally:
                if self._drain_waiter is not None and self._drain_waiter[1] is waiter:
                    self._drain_waiter = None
                if waiter is not None and not waiter.done():
                    waiter.cancel()

    async def _send_locked(self, command):
        if self._process is None or self._process.returncode is not None:
            raise AdapterError("native_process_missing")
        try:
            self._process.stdin.write((command + "\n").encode("ascii"))
            await asyncio.wait_for(self._process.stdin.drain(), 0.5)
        except (OSError, asyncio.TimeoutError) as exc:
            self.phase = GuardPhase.FAILED
            await self._set_health(NativeHealth.UNAVAILABLE)
            await self.on_fault("native_pipe_failed")
            raise AdapterError("native_pipe_failed") from exc

    async def _set_health(self, health):
        if health != self._health:
            self._health = health
            if self.on_health is not None:
                await self.on_health(health)

    async def _apply_monitor_result(self, result):
        self.monitor = result.state
        if result.fault != MonitorFault.NONE:
            self.phase = GuardPhase.FAILED
            await self._set_health(NativeHealth.UNAVAILABLE)
            await self.on_fault(result.fault.value)
        elif result.proof == Proof.NATIVE_STATUS and self.on_proof is not None:
            health = NativeHealth.PHYSICAL_GRABBED if self.monitor.physical_grabs and self.monitor.lease == LeasePhase.ARMED else NativeHealth.MONITORING
            await self._set_health(health)
            await self.on_proof(health, self.monitor.response_ms)

    async def _read(self, process):
        try:
            while line := await process.stdout.readline():
                if len(line) > 16_384:
                    raise AdapterError("native_line_too_large")
                key = parse_native_key(line)
                if key is not None:
                    await self.on_key(key)
                await self._apply_monitor_result(watchdog(self.monitor, self._now()))
                if self.phase != GuardPhase.RUNNING:
                    return
                try:
                    value = json.loads(line)
                except (ValueError, TypeError):
                    continue
                if isinstance(value, dict) and value.get("kind") == "ready":
                    version = value.get("version")
                    self._drain_capability = DrainCapability.SUPPORTED if type(version) is int and version >= 2 else DrainCapability.UNSUPPORTED
                await self._apply_monitor_result(monitor_event(self.monitor, value, self._now()))
                if isinstance(value, dict) and value.get("kind") == "status":
                    self._status_processed.set()
                if isinstance(value, dict) and value.get("kind") == "drain" and self._drain_waiter is not None:
                    nonce, future = self._drain_waiter
                    if type(value.get("nonce")) is int and value["nonce"] == nonce and not future.done():
                        future.set_result(value.get("result", "unavailable"))
                if self.phase != GuardPhase.RUNNING:
                    return
            if self._process is process and self.phase == GuardPhase.RUNNING:
                self.phase = GuardPhase.FAILED
                await self._set_health(NativeHealth.UNAVAILABLE)
                await self.on_fault("native_eof")
        except asyncio.CancelledError:
            raise
        except (OSError, ValueError, AdapterError):
            self.phase = GuardPhase.FAILED
            await self._set_health(NativeHealth.UNAVAILABLE)
            await self.on_fault("native_read_failed")

    async def close(self):
        async with self._lock:
            await self._close_locked()

    async def _close_locked(self):
        process, reader = self._process, self._reader
        self._process, self._reader = None, None
        self._session_id, self._session_end_ms = None, None
        self._command_lease = LeasePhase.UNKNOWN
        self.monitor = MonitorState()
        self._drain_capability = DrainCapability.UNKNOWN
        self._status_processed.set()
        if self._drain_waiter is not None:
            _, future = self._drain_waiter
            if not future.done():
                future.set_result("unavailable")
        self.phase = GuardPhase.STOPPED
        if process is not None:
            if process.returncode is None:
                try:
                    process.stdin.write(b"DISARM\nQUIT\n")
                    await asyncio.wait_for(process.stdin.drain(), 0.3)
                except (OSError, asyncio.TimeoutError):
                    pass
                process.stdin.close()
                try:
                    await asyncio.wait_for(process.wait(), 1.0)
                except asyncio.TimeoutError:
                    if process.returncode is None:
                        process.terminate()
                    try:
                        await asyncio.wait_for(process.wait(), 1.0)
                    except asyncio.TimeoutError:
                        if process.returncode is None:
                            process.kill()
                        await process.wait()
            else:
                await process.wait()
        if reader is not None and reader is not asyncio.current_task():
            reader.cancel()
            await asyncio.gather(reader, return_exceptions=True)
        await self._set_health(NativeHealth.STOPPED)
