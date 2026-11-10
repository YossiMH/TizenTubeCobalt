"""Side-effect-free session policy. Cached app state never authorizes action."""
from dataclasses import dataclass, replace
from enum import Enum
import json
import math
import re
import xml.etree.ElementTree as ET
from .native_protocol import OBSERVER_MAX_AGE_MS


STATUS_MAX_AGE_MS = 5_000
OBSERVATION_MAX_AGE_MS = 5_000
RECOVERY_DEBOUNCE_MS = 10_000
RECOVERY_WINDOW_MS = 60_000
RECOVERY_LIMIT = 3
RECOVERY_COOLDOWN_MS = 60_000
VOLUME_CODES = frozenset((113, 114, 115))
POWER_CODES = frozenset((116, 142))  # Linux KEY_POWER, KEY_SLEEP.
PHONE_GRACE_MS = 8_000
PHONE_EVIDENCE_MAX_AGE_MS = 3_000


class Phase(str, Enum):
    INACTIVE = "inactive"
    ACTIVE = "active"
    PHONE_PAUSED = "phone_paused"
    PHONE_STOPPED = "phone_stopped"
    COMPLETED = "completed"


class Availability(str, Enum):
    UNAVAILABLE = "unavailable"
    CACHE = "cache"
    LIVE = "live"


class Intent(str, Enum):
    NONE = "none"
    PENDING = "pending_phone_post_or_status"
    POWER_STOP = "phone_power_stopped"


class Source(str, Enum):
    HARDWARE = "hardware"
    PHONE = "phone"
    UNKNOWN = "unknown"


class Activity(str, Enum):
    ACTIVE = "active"
    INACTIVE = "inactive"
    UNKNOWN = "unknown"


class Power(str, Enum):
    AWAKE = "awake"
    ASLEEP = "asleep"
    UNKNOWN = "unknown"


class Playing(str, Enum):
    PLAYING = "playing"
    PAUSED = "paused"
    ENDED = "ended"
    UNKNOWN = "unknown"


class Recovery(str, Enum):
    POWER = "power"
    INPUT = "input"
    PLAYBACK = "playback"


class Authentication(str, Enum):
    CLEAR = "clear"
    REQUIRED = "required"
    UNKNOWN = "unknown"


class Rollout(str, Enum):
    PREPARED = "prepared"
    ENABLED = "enabled"


class SessionMode(str, Enum):
    SCHEDULED = "scheduled"
    TEST = "test"


class Observer(str, Enum):
    UNKNOWN = "unknown"
    LIVE = "live"
    LOST = "lost"


class FaultFence(str, Enum):
    CLEAR = "clear"
    STOP_REQUIRED = "terminal_stop_required"


@dataclass(frozen=True)
class Session:
    id: str | None
    video_id: str | None
    phase: Phase
    expires_at_ms: int
    duration_seconds: float | None
    position_seconds: float | None


@dataclass(frozen=True)
class Snapshot:
    session: Session
    foreground_package: str | None
    native_lease_status: str
    authentication: Authentication = Authentication.UNKNOWN
    rollout: Rollout = Rollout.PREPARED
    session_mode: SessionMode = SessionMode.SCHEDULED


@dataclass(frozen=True)
class InputKey:
    source: Source
    input_id: str | int
    linux_code: int
    value: int
    wall_ms: int

    def event(self):
        return {"type": "phone_key", "linux_code": self.linux_code,
                "value": self.value, "wall_ms": self.wall_ms}


@dataclass(frozen=True)
class Observation:
    activity: Activity = Activity.UNKNOWN
    power: Power = Power.UNKNOWN


@dataclass(frozen=True)
class Playback:
    video_id: str
    position_seconds: float
    playing: Playing
    sample_wall_ms: int
    duration_seconds: float | None = None

    def event(self):
        return {"type": "playback", "video_id": self.video_id,
                "position_seconds": self.position_seconds,
                "duration_seconds": self.duration_seconds,
                "playing": self.playing.value, "sample_wall_ms": self.sample_wall_ms}


@dataclass(frozen=True)
class PolicyState:
    availability: Availability = Availability.UNAVAILABLE
    snapshot: Snapshot | None = None
    fetched_at_ms: int = 0
    intent: Intent = Intent.NONE
    intent_session_id: str | None = None
    phone_generation: int = 0
    posted_generation: int = 0
    phone_status_generation: int = 0
    phone_key_wall_ms: int = 0
    phone_grace_until_ms: int = 0
    observer: Observer = Observer.UNKNOWN
    observer_at_ms: int | None = None
    observer_generation: int = 0
    fault_fence: FaultFence = FaultFence.CLEAR
    blocked_session_id: str | None = None
    recovery_times_ms: tuple[int, ...] = ()
    cooldown_until_ms: int = 0


def _integer(value, label, minimum=0):
    if type(value) is not int or value < minimum or value > 2**63 - 1:
        raise ValueError("invalid " + label)
    return value


def _number(value, label):
    if value is None:
        return None
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError("invalid " + label)
    return float(value)


def _text(value, label, limit=256):
    if value is None:
        return None
    if not isinstance(value, str) or len(value) > limit:
        raise ValueError("invalid " + label)
    return value


def parse_status(value):
    if not isinstance(value, dict) or type(value.get("schema")) is not int or value["schema"] != 1:
        raise ValueError("unsupported status schema")
    s = value.get("session")
    if not isinstance(s, dict):
        raise ValueError("missing session")
    try:
        phase = Phase(s.get("phase"))
    except (TypeError, ValueError) as exc:
        raise ValueError("invalid session phase") from exc
    session = Session(_text(s.get("id"), "id"), _text(s.get("video_id"), "video_id", 32),
                      phase, _integer(s.get("expires_at_ms"), "expires_at_ms"),
                      _number(s.get("duration_seconds"), "duration_seconds"),
                      _number(s.get("position_seconds"), "position_seconds"))
    if phase in (Phase.ACTIVE, Phase.PHONE_PAUSED) and (not session.id or not session.video_id):
        raise ValueError("live session missing identity")
    authentication = Authentication(value.get("authentication", "unknown"))
    return Snapshot(session, _text(value.get("foreground_package"), "foreground_package"),
                    _text(value.get("native_lease_status", "unknown"), "native_lease_status") or "unknown",
                    authentication, Rollout(value.get("rollout", "prepared")),
                    SessionMode(value.get("session_mode", "scheduled")))


def parse_native_key(line):
    try:
        value = json.loads(line)
        if not isinstance(value, dict) or value.get("kind") != "key":
            return None
        source = {"phone": Source.PHONE, "software": Source.PHONE,
                  "physical": Source.HARDWARE, "hardware": Source.HARDWARE}.get(value.get("source"), Source.UNKNOWN)
        code = _integer(value.get("code", value.get("linux_code")), "code")
        key_value = _integer(value.get("value"), "value")
        wall = _integer(value.get("wall_ms"), "wall_ms")
        input_id = value.get("input_id", "unknown")
        if code > 0xffff or key_value > 2 or type(input_id) not in (int, str):
            return None
        return InputKey(source, input_id, code, key_value, wall)
    except (ValueError, TypeError):
        return None


def apply_status(state, snapshot, now_ms, request_generation, observer_generation=None):
    if observer_generation is not None and observer_generation != state.observer_generation:
        return state  # An in-flight response from before a provenance barrier.
    intent, intent_id = state.intent, state.intent_session_id
    previous = state.snapshot.session.id if state.snapshot else None
    changed = previous is not None and snapshot.session.id != previous
    if (intent == Intent.POWER_STOP and now_ms >= state.phone_grace_until_ms
            and ((intent_id is not None and snapshot.session.id != intent_id)
                 or (intent_id is None and snapshot.session.phase == Phase.INACTIVE))):
        intent, intent_id = Intent.NONE, None
    if intent == Intent.POWER_STOP and intent_id is None and snapshot.session.phase in (Phase.ACTIVE, Phase.PHONE_PAUSED):
        intent_id = snapshot.session.id
    status_generation = state.phone_status_generation
    if state.posted_generation >= state.phone_generation and request_generation >= state.phone_generation:
        status_generation = max(status_generation, request_generation)
    return replace(state, availability=Availability.LIVE, snapshot=snapshot,
                   fetched_at_ms=now_ms, intent=intent, intent_session_id=intent_id,
                   phone_status_generation=status_generation,
                   recovery_times_ms=() if changed else state.recovery_times_ms,
                   cooldown_until_ms=0 if changed else state.cooldown_until_ms)


def failed_status(state):
    return replace(state, availability=Availability.CACHE if state.snapshot else Availability.UNAVAILABLE)


def begin_phone_key(state, key, now_ms=0):
    if key.source != Source.PHONE or key.value != 1 or key.linux_code in VOLUME_CODES:
        return state
    intent = Intent.POWER_STOP if key.linux_code in POWER_CODES else Intent.PENDING
    if state.intent == Intent.POWER_STOP:
        intent = Intent.POWER_STOP
    identity = state.snapshot.session.id if state.snapshot else None
    return replace(state, intent=intent, intent_session_id=identity,
                   phone_generation=state.phone_generation + 1, phone_key_wall_ms=key.wall_ms,
                   phone_grace_until_ms=max(state.phone_grace_until_ms, now_ms + PHONE_GRACE_MS))


def mark_phone_posted(state, generation):
    return replace(state, posted_generation=max(state.posted_generation, generation))


def observer_proven(state, now_ms):
    return replace(state, observer=Observer.LIVE, observer_at_ms=now_ms)


def observer_fresh(state, now_ms):
    return (state.observer == Observer.LIVE and state.observer_at_ms is not None
            and 0 <= now_ms - state.observer_at_ms <= OBSERVER_MAX_AGE_MS)


def provenance_gap(state):
    identity = state.snapshot.session.id if state.snapshot else None
    return replace(state, observer=Observer.LOST, observer_at_ms=None,
                   observer_generation=state.observer_generation + 1,
                   fault_fence=FaultFence.STOP_REQUIRED,
                   blocked_session_id=identity or state.blocked_session_id)


def terminal_stop_confirmed(state, generation):
    if state.observer_generation != generation or state.fault_fence != FaultFence.STOP_REQUIRED:
        return state
    # Increment again so every status request begun before stop confirmation is
    # unable to revive an active snapshot, including startup with unknown id.
    return replace(state, fault_fence=FaultFence.CLEAR,
                   observer_generation=state.observer_generation + 1,
                   observer=Observer.UNKNOWN, observer_at_ms=None,
                   availability=Availability.CACHE if state.snapshot else Availability.UNAVAILABLE)


def observe_phone_playback(state, playback, now_ms, wall_ms, player_package):
    if state.intent != Intent.PENDING or state.snapshot is None:
        return state
    session = live_session(state, now_ms, wall_ms)
    if (session is not None and session.phase == Phase.ACTIVE
            and state.snapshot.foreground_package == player_package
            and state.posted_generation >= state.phone_generation
            and state.phone_status_generation >= state.phone_generation
            and playback.video_id == session.video_id and playback.playing == Playing.PLAYING
            and now_ms >= state.phone_grace_until_ms
            and playback.sample_wall_ms >= state.phone_key_wall_ms + PHONE_GRACE_MS
            and -1000 <= wall_ms - playback.sample_wall_ms <= PHONE_EVIDENCE_MAX_AGE_MS):
        return replace(state, intent=Intent.NONE, intent_session_id=None)
    return state


def live_session(state, now_ms, wall_ms):
    if state.availability != Availability.LIVE or state.snapshot is None:
        return None
    age = now_ms - state.fetched_at_ms
    if age < 0 or age > STATUS_MAX_AGE_MS:
        return None
    session = state.snapshot.session
    return session if session.expires_at_ms > wall_ms else None


def guard_required(state, now_ms, wall_ms):
    session = live_session(state, now_ms, wall_ms)
    return (session is not None and session.phase in (Phase.ACTIVE, Phase.PHONE_PAUSED)
            and observer_fresh(state, now_ms) and state.fault_fence == FaultFence.CLEAR
            and session.id != state.blocked_session_id
            and (state.snapshot.rollout == Rollout.ENABLED or state.snapshot.session_mode == SessionMode.TEST)
            and (state.snapshot.session_mode != SessionMode.TEST or session.expires_at_ms - wall_ms <= 60_000)
            and state.intent != Intent.POWER_STOP)


def recovery_reason(state, now_ms, wall_ms, observation, playback=None, player_package=None):
    session = live_session(state, now_ms, wall_ms)
    if (session is None or session.phase != Phase.ACTIVE or state.intent != Intent.NONE
            or state.snapshot.rollout != Rollout.ENABLED or state.snapshot.session_mode == SessionMode.TEST
            or not observer_fresh(state, now_ms) or state.fault_fence != FaultFence.CLEAR
            or session.id == state.blocked_session_id or now_ms < state.phone_grace_until_ms):
        return None
    if playback is not None and playback.video_id == session.video_id and playback.playing == Playing.ENDED:
        # Completion evidence is posted to the app; do not race its next status.
        return None
    if now_ms < state.cooldown_until_ms:
        return None
    if state.recovery_times_ms and now_ms - state.recovery_times_ms[-1] < RECOVERY_DEBOUNCE_MS:
        return None
    if sum(now_ms - t < RECOVERY_WINDOW_MS for t in state.recovery_times_ms) >= RECOVERY_LIMIT:
        return None
    if observation.power == Power.ASLEEP:
        return Recovery.POWER
    if observation.activity == Activity.INACTIVE:
        return Recovery.INPUT
    if state.snapshot.authentication != Authentication.CLEAR:
        return None
    if playback is not None and playback.video_id == session.video_id:
        age = wall_ms - playback.sample_wall_ms
        if -1000 <= age <= OBSERVATION_MAX_AGE_MS and playback.playing == Playing.PAUSED:
            return Recovery.PLAYBACK
    if player_package is not None and state.snapshot.foreground_package not in (None, "", player_package):
        return Recovery.PLAYBACK
    return None


def record_recovery(state, now_ms):
    attempts = tuple(t for t in state.recovery_times_ms if now_ms - t < RECOVERY_WINDOW_MS) + (now_ms,)
    cooldown = now_ms + RECOVERY_COOLDOWN_MS if len(attempts) >= RECOVERY_LIMIT else state.cooldown_until_ms
    return replace(state, recovery_times_ms=attempts, cooldown_until_ms=cooldown)


def parse_observation(text):
    """Read only the local playback4 block; unrelated CEC devices cannot win."""
    hdmi, marker, power_text = text.partition("<ONN_POLICY_POWER>")
    headers = list(re.finditer(r"(?m)^(\s*)(?:HdmiCecLocalDevice(?:Playback|\s+#4)|Playback(?:Device)?\s*#?4)\s*:", hdmi))
    activities = []
    for header in headers:
        indent = len(header.group(1))
        lines = hdmi[header.end():].splitlines()
        block = []
        for line in lines:
            if line.strip() and len(line) - len(line.lstrip()) <= indent:
                break
            block.append(line)
        segment = "\n".join(block)
        types = re.findall(r"mDeviceType\s*[:=]\s*(\d+)", segment)
        if types and set(types) != {"4"}:
            continue
        values = re.findall(r"(?m)^\s*(?:mIsActiveSource|isActiveSource(?:\(\))?)\s*[:=]\s*(true|false)\s*$", segment, re.I)
        if len(values) == 1:
            activities.append(Activity.ACTIVE if values[0].lower() == "true" else Activity.INACTIVE)
    activity = activities[0] if len(activities) == 1 else Activity.UNKNOWN
    power = Power.UNKNOWN
    if marker:
        wake = re.findall(r"mWakefulness\s*[:=]\s*(Awake|Asleep|Dozing|Dreaming)\b", power_text)
        if len(wake) == 1:
            power = Power.ASLEEP if wake[0] in ("Asleep", "Dozing") else Power.AWAKE
        tv = re.findall(r"(?m)^\s*mTvPowerStatus\s*[:=]\s*(\d+)\s*$", hdmi)
        tv += re.findall(r"(?m)^\s*CEC:\s*logical_address:\s*0x00\s+device_type:\s*0\b[^\n]*?power_status:\s*(\d+)\b", hdmi)
        if len(tv) == 1 and tv[0] in ("1", "3"):
            power = Power.ASLEEP
    return Observation(activity, power)


def parse_player(xml, wall_ms):
    """Only ms_* samples carry freshness. Idle and missing fields are unknown."""
    if len(xml) > 65_536:
        return None
    try:
        root = ET.fromstring(xml)
        fields = {}
        for element in root.iter():
            name = element.tag.rsplit("}", 1)[-1]
            if name in ("ms_state", "ms_playing", "ms_playing_at", "ms_position"):
                if name in fields:
                    return None
                fields[name] = (element.text or "").strip()
        video = fields["ms_playing"]
        sampled = int(fields["ms_playing_at"])
        position = float(fields["ms_position"])
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", video) or not math.isfinite(position) or position < 0:
            return None
        if sampled > wall_ms + 1000 or wall_ms - sampled > OBSERVATION_MAX_AGE_MS:
            return None
        # This endpoint has no reliable ended/duration signal. Never infer them.
        playing = {"playing": Playing.PLAYING, "paused": Playing.PAUSED}.get(fields.get("ms_state"), Playing.UNKNOWN)
        return Playback(video, position, playing, sampled)
    except (ET.ParseError, ValueError, KeyError):
        return None
