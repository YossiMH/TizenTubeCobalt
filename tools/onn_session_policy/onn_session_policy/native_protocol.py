"""Pure native observation proof; process existence is not input provenance."""
from dataclasses import dataclass, replace
from enum import Enum


STATUS_INTERVAL_MS = 3_000
STATUS_REPLY_TIMEOUT_MS = 4_000
OBSERVER_MAX_AGE_MS = 5_000


class MonitorPhase(str, Enum):
    STOPPED = "stopped"
    STARTING = "starting"
    LIVE = "live"
    LOST = "lost"


class LeasePhase(str, Enum):
    UNKNOWN = "unknown"
    DISARMED = "disarmed"
    ARMED = "armed"


class Proof(str, Enum):
    NONE = "none"
    NATIVE_STATUS = "native_status"


class MonitorFault(str, Enum):
    NONE = "none"
    SOURCE_BLIND = "native_phone_source_blind"
    PHONE_REMOVED = "native_phone_device_removed"
    STATUS_TIMEOUT = "native_status_timeout"
    STALE = "native_status_stale"
    PROTOCOL = "native_status_invalid"
    INPUT_ERROR = "native_input_error"


@dataclass(frozen=True)
class MonitorState:
    phase: MonitorPhase = MonitorPhase.STOPPED
    phone_inputs: tuple[str, ...] = ()
    physical_grabs: tuple[str, ...] = ()
    started_ms: int = 0
    request_ms: int | None = None
    last_request_ms: int | None = None
    response_ms: int | None = None
    native_boot_ms: int | None = None
    lease: LeasePhase = LeasePhase.UNKNOWN


@dataclass(frozen=True)
class MonitorResult:
    state: MonitorState
    proof: Proof = Proof.NONE
    fault: MonitorFault = MonitorFault.NONE


def started(now_ms):
    return MonitorState(phase=MonitorPhase.STARTING, started_ms=now_ms)


def status_due(state, now_ms):
    return state.request_ms is None and (state.last_request_ms is None
                                         or now_ms - state.last_request_ms >= STATUS_INTERVAL_MS)


def requested(state, now_ms):
    return replace(state, request_ms=now_ms, last_request_ms=now_ms)


def observer_live(state, now_ms):
    return (state.phase == MonitorPhase.LIVE and bool(state.phone_inputs)
            and state.response_ms is not None and 0 <= now_ms - state.response_ms <= OBSERVER_MAX_AGE_MS)


def lost(state, fault):
    return MonitorResult(replace(state, phase=MonitorPhase.LOST, response_ms=None), fault=fault)


def watchdog(state, now_ms):
    if state.phase in (MonitorPhase.LOST, MonitorPhase.STOPPED):
        return MonitorResult(state)
    if state.phase == MonitorPhase.LIVE and not observer_live(state, now_ms):
        return lost(state, MonitorFault.STALE)
    if state.phase == MonitorPhase.STARTING and now_ms - state.started_ms > OBSERVER_MAX_AGE_MS:
        return lost(state, MonitorFault.SOURCE_BLIND if not state.phone_inputs else MonitorFault.STATUS_TIMEOUT)
    if state.request_ms is not None and now_ms - state.request_ms > STATUS_REPLY_TIMEOUT_MS:
        return lost(state, MonitorFault.STATUS_TIMEOUT)
    return MonitorResult(state)


def _input_id(value):
    if type(value) is int and 0 <= value <= 65_535:
        return str(value)
    if isinstance(value, str) and value.startswith("event") and value[5:].isdigit():
        return value[5:]
    return None


def event(state, value, now_ms):
    if not isinstance(value, dict) or state.phase in (MonitorPhase.STOPPED, MonitorPhase.LOST):
        return MonitorResult(state)
    kind = value.get("kind")
    identity = _input_id(value.get("input_id"))
    if kind == "device":
        if (identity is not None and value.get("source") in ("phone", "software")
                and value.get("name") in ("virtual-remote", "virtual-remote-2")
                and value.get("bus") == 6 and value.get("vendor") == 0x18d1 and value.get("product") == 0x0100):
            next_state = replace(state, phone_inputs=tuple(sorted(set(state.phone_inputs) | {identity})))
            if state.phase == MonitorPhase.LIVE and identity not in state.phone_inputs:
                return lost(next_state, MonitorFault.SOURCE_BLIND)
            return MonitorResult(next_state)
    elif kind == "device_removed" and identity in state.phone_inputs:
        return lost(replace(state, phone_inputs=tuple(x for x in state.phone_inputs if x != identity)), MonitorFault.PHONE_REMOVED)
    elif kind == "grabbed" and identity is not None and value.get("source") in ("physical", "hardware"):
        return MonitorResult(replace(state, physical_grabs=tuple(sorted(set(state.physical_grabs) | {identity}))))
    elif kind == "released" and identity in state.physical_grabs:
        return MonitorResult(replace(state, physical_grabs=tuple(x for x in state.physical_grabs if x != identity)))
    elif kind == "disarmed":
        return MonitorResult(replace(state, lease=LeasePhase.DISARMED, physical_grabs=()))
    elif kind == "error":
        return lost(state, MonitorFault.INPUT_ERROR)
    elif kind == "status":
        native_boot = value.get("boottime_ms")
        if (state.request_ms is None or not 0 <= now_ms - state.request_ms <= STATUS_REPLY_TIMEOUT_MS
                or type(native_boot) is not int or native_boot < 0
                or (state.native_boot_ms is not None and native_boot <= state.native_boot_ms)
                or value.get("lease") not in ("armed", "disarmed")
                or value.get("volume") not in ("ready", "absent")):
            return lost(state, MonitorFault.PROTOCOL)
        lease = LeasePhase(value["lease"])
        next_state = replace(state, request_ms=None, native_boot_ms=native_boot, lease=lease,
                             physical_grabs=state.physical_grabs if lease == LeasePhase.ARMED else ())
        if not state.phone_inputs:
            return MonitorResult(next_state)
        return MonitorResult(replace(next_state, phase=MonitorPhase.LIVE, response_ms=now_ms), Proof.NATIVE_STATUS)
    # 'ready' is emitted before discovery. It never establishes source proof.
    return MonitorResult(state)
