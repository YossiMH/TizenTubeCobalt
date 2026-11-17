"""Owned, loopback-only ADB forwarding for the Android Cobalt debugger."""
import asyncio
from enum import Enum
import re

from .adapters import AdapterError


DEBUGGER_SOCKET = "localabstract:content_shell_devtools_remote"
RETRY_MS = 10_000
HEALTHY_CHECK_MS = 60_000
COMMAND_TIMEOUT_SECONDS = 1.5
RECONCILE_TIMEOUT_SECONDS = 3.0


class ForwardState(str, Enum):
    UNKNOWN = "unknown"
    READY = "ready"
    CONFLICT = "conflict"
    UNAVAILABLE = "unavailable"


def mapping_state(output, serial, port):
    """Adopt only the exact selected-device mapping; never overwrite a port."""
    wanted = "tcp:" + str(port)
    matches = []
    for line in output.splitlines():
        fields = line.split()
        if not fields:
            continue
        if len(fields) != 3:
            raise AdapterError("debugger_forward_list_invalid")
        if fields[1] == wanted:
            matches.append(tuple(fields))
    if not matches:
        return ForwardState.UNKNOWN
    if matches == [(serial, wanted, DEBUGGER_SOCKET)]:
        return ForwardState.READY
    return ForwardState.CONFLICT


class CobaltForward:
    """Finite host commands; periodic reconciliation never touches TV input."""
    def __init__(self, adb_prefix, port=9222):
        if type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("invalid_debugger_port")
        self.prefix = tuple(adb_prefix)
        self.port = port
        self.state = ForwardState.UNKNOWN
        self._serial = None
        self._last_attempt_ms = -RETRY_MS
        self._next_check_ms = 0

    async def _adb(self, *arguments):
        process = await asyncio.create_subprocess_exec(
            *self.prefix, *arguments, stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        try:
            output, error = await asyncio.wait_for(process.communicate(), COMMAND_TIMEOUT_SECONDS)
            if process.returncode != 0:
                raise AdapterError("debugger_forward_command_failed")
            if len(output) > 65_536 or len(error) > 65_536:
                raise AdapterError("debugger_forward_response_too_large")
            return output.decode("ascii")
        except (asyncio.CancelledError, asyncio.TimeoutError):
            if process.returncode is None:
                process.kill()
            await process.wait()
            raise

    async def _reconcile(self):
        serial = (await self._adb("get-serialno")).strip()
        if (not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", serial)
                or serial in ("unknown", "offline")):
            raise AdapterError("debugger_serial_invalid")
        # A serial change is accepted only after inspecting its actual mapping.
        self._serial = serial
        state = mapping_state(await self._adb("forward", "--list"), serial, self.port)
        if state != ForwardState.UNKNOWN:
            return state
        try:
            await self._adb("forward", "--no-rebind", "tcp:" + str(self.port), DEBUGGER_SOCKET)
        except AdapterError:
            # Another owner may have claimed the port after our list. Read it
            # back; an error never authorizes replacing or removing a mapping.
            pass
        verified = mapping_state(await self._adb("forward", "--list"), serial, self.port)
        return ForwardState.UNAVAILABLE if verified == ForwardState.UNKNOWN else verified

    async def ensure(self, now_ms):
        if now_ms < self._next_check_ms:
            return self.state == ForwardState.READY
        self._last_attempt_ms = now_ms
        # One reconciliation, including a failed creation and readback, has an
        # overall budget. Healthy checks are sparse; failures have backoff.
        self._next_check_ms = now_ms + RETRY_MS
        self.state = ForwardState.UNKNOWN
        try:
            self.state = await asyncio.wait_for(self._reconcile(), RECONCILE_TIMEOUT_SECONDS)
        except (AdapterError, OSError, UnicodeError, asyncio.TimeoutError):
            self.state = ForwardState.UNAVAILABLE
        if self.state == ForwardState.READY:
            self._next_check_ms = now_ms + HEALTHY_CHECK_MS
        return self.state == ForwardState.READY

    def transport_failed(self, now_ms):
        self.state = ForwardState.UNAVAILABLE
        self._next_check_ms = min(
            self._next_check_ms, max(now_ms, self._last_attempt_ms + RETRY_MS))
