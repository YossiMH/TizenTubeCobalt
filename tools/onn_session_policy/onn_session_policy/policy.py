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
    SCHOOL_BANNER = "school_banner"
    MORNING_STARTING = "morning_starting"


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
    AUTHENTICATION = "authentication"
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
    POWER_PROBE = "power_probe"


class StartupGrantState(str, Enum):
    NONE = "none"
    PENDING = "pending"
    CONSUMED = "consumed"
    SUCCEEDED = "succeeded"
    STOPPED = "stopped"
    EXPIRED = "expired"


@dataclass(frozen=True)
class StartupGrant:
    state: StartupGrantState
    run_id: str | None
    issued_wall_ms: int
    expires_at_ms: int


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
class SchoolBanner:
    phase: str
    run_id: str
    video_id: str
    expires_at_ms: int
    diagnostic: bool = False
    started_at_ms: int = -1
    startup_deadline_ms: int = -1
    fallback_at_ms: int = -1
    candidate_video_id: str = ""
    scheduled_admission: bool = False
    pipeline_diagnostic: bool = False


@dataclass(frozen=True)
class Snapshot:
    session: Session
    foreground_package: str | None
    native_lease_status: str
    authentication: Authentication = Authentication.UNKNOWN
    rollout: Rollout = Rollout.PREPARED
    session_mode: SessionMode = SessionMode.SCHEDULED
    startup_authentication: StartupGrant | None = None
    school_banner: SchoolBanner | None = None


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
    onn_power: Power = Power.UNKNOWN
    tv_power: Power = Power.UNKNOWN


def restored_power_state(observation):
    return (observation.activity == Activity.ACTIVE and observation.onn_power == Power.AWAKE
            and observation.tv_power == Power.AWAKE)


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
    if phase in (Phase.SCHOOL_BANNER, Phase.MORNING_STARTING):
        raise ValueError("school banner requires separate canonical authorization")
    session = Session(_text(s.get("id"), "id"), _text(s.get("video_id"), "video_id", 32),
                      phase, _integer(s.get("expires_at_ms"), "expires_at_ms"),
                      _number(s.get("duration_seconds"), "duration_seconds"),
                      _number(s.get("position_seconds"), "position_seconds"))
    if phase in (Phase.ACTIVE, Phase.PHONE_PAUSED) and (not session.id or not session.video_id):
        raise ValueError("live session missing identity")
    banner = None
    raw_banner = value.get("school_banner")
    if raw_banner is not None:
        if not isinstance(raw_banner, dict):
            raise ValueError("invalid school banner")
        banner_phase = raw_banner.get("phase", "inactive")
        if banner_phase not in ("inactive", "starting", "waiting", "active", "awaiting_observer", "dismissed", "expired", "skipped"):
            raise ValueError("invalid school banner phase")
        diagnostic = raw_banner.get("diagnostic", False)
        if type(diagnostic) is not bool:
            raise ValueError("invalid school banner diagnostic")
        if banner_phase in ("starting", "waiting", "active", "awaiting_observer"):
            run_id = _text(raw_banner.get("run_id"), "banner run")
            video_id = _text(raw_banner.get("video_id", ""), "banner video", 32) or ""
            expiry = _integer(raw_banner.get("expires_at_ms"), "banner expiry")
            if not run_id or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", run_id):
                raise ValueError("invalid banner identity")
            admitted = raw_banner.get("scheduled_admission", False)
            pipeline = raw_banner.get("pipeline_diagnostic", False)
            if type(admitted) is not bool or type(pipeline) is not bool or (pipeline and not admitted):
                raise ValueError("invalid morning admission")
            started = _integer(raw_banner.get("started_at_ms", -1), "morning start", -1)
            work_end = _integer(raw_banner.get("startup_deadline_ms", -1), "morning work deadline", -1)
            fallback = _integer(raw_banner.get("fallback_at_ms", -1), "morning fallback", -1)
            candidate = _text(raw_banner.get("candidate_video_id", ""), "morning candidate", 32) or ""
            if candidate and not re.fullmatch(r"[A-Za-z0-9_-]{11}", candidate):
                raise ValueError("invalid morning candidate")
            if admitted and (started < 0 or work_end - started != 300_000 or not 0 < fallback < expiry
                    or not 0 < expiry - started <= (180_000 if pipeline else 9_000_000)):
                raise ValueError("invalid finite morning window")
            fallback_banner = (admitted and not video_id and banner_phase in ("active", "awaiting_observer")
                and raw_banner.get("reason") == "scheduled_fallback" and raw_banner.get("completed_at_ms") == -1)
            if banner_phase == "starting" and (not admitted or video_id):
                raise ValueError("invalid starting identity")
            if not diagnostic and banner_phase != "starting" and not fallback_banner and not re.fullmatch(r"[A-Za-z0-9_-]{11}", video_id):
                raise ValueError("invalid banner video")
            banner = SchoolBanner(banner_phase, run_id, video_id, expiry, diagnostic,
                                  started, work_end, fallback, candidate, admitted, pipeline)
            if banner_phase == "starting":
                if type(raw_banner.get("protection_ready")) is not bool or raw_banner.get("overlay_visible") is not False:
                    raise ValueError("unproven starting window")
                if raw_banner["protection_ready"]:
                    session = Session(run_id, "", Phase.MORNING_STARTING, expiry, None, None)
            if banner_phase == "active":
                if raw_banner.get("protection_ready") is not True or raw_banner.get("overlay_visible") is not True:
                    raise ValueError("unproven active banner")
                # The authenticated app keeps episode state intact. Normalize its
                # independently authorized overlay into the control session only.
                identity = "diagnostic-banner:" + str(expiry) if diagnostic else run_id
                session = Session(identity, video_id, Phase.SCHOOL_BANNER, expiry, None, None)
    authentication = Authentication(value.get("authentication", "unknown"))
    startup = value.get("startup_authentication")
    grant = None
    if startup is not None:
        if not isinstance(startup, dict):
            raise ValueError("invalid startup grant")
        grant = StartupGrant(StartupGrantState(startup.get("state", "none")),
            _text(startup.get("run_id"), "startup run"),
            _integer(startup.get("issued_wall_ms", -1), "startup issue", -1),
            _integer(startup.get("expires_at_ms", 0), "startup expiry"))
        if grant.state in (StartupGrantState.PENDING, StartupGrantState.CONSUMED):
            if not grant.run_id or grant.issued_wall_ms < 0 or not 0 < grant.expires_at_ms - grant.issued_wall_ms <= 5000:
                raise ValueError("invalid finite startup grant")
    return Snapshot(session, _text(value.get("foreground_package"), "foreground_package"),
                    _text(value.get("native_lease_status", "unknown"), "native_lease_status") or "unknown",
                    authentication, Rollout(value.get("rollout", "prepared")),
                    SessionMode(value.get("session_mode", "scheduled")), grant, banner)


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


def apply_status(state, snapshot, now_ms, request_generation, observer_generation=None,
                 request_posted_generation=None):
    if observer_generation is not None and observer_generation != state.observer_generation:
        return state  # An in-flight response from before a provenance barrier.
    intent, intent_id = state.intent, state.intent_session_id
    previous = state.snapshot.session.id if state.snapshot else None
    changed = previous is not None and snapshot.session.id != previous
    banner = snapshot.school_banner
    if (intent == Intent.PENDING and snapshot.session.phase == Phase.MORNING_STARTING
            and banner is not None and banner.phase == "starting" and banner.scheduled_admission
            and snapshot.session.id == banner.run_id and snapshot.session.id != intent_id
            and banner.started_at_ms > state.phone_key_wall_ms
            and now_ms >= state.phone_grace_until_ms
            and request_generation >= state.phone_generation
            and state.posted_generation >= state.phone_generation
            and request_posted_generation is not None
            and request_posted_generation >= state.phone_generation
            and observer_generation is not None and state.fault_fence == FaultFence.CLEAR):
        # A prior run's acknowledged phone action must not wait for playback
        # that the new run's STARTING gate itself prevents. The status request
        # must begin after that acknowledgement, so an older response cannot
        # erase an action whose POST completes while the response is in flight.
        intent, intent_id = Intent.NONE, None
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
    reminder_repeat = (key.value == 2 and state.snapshot is not None
                      and state.snapshot.session.phase in (Phase.MORNING_STARTING, Phase.SCHOOL_BANNER))
    if key.source != Source.PHONE or (key.value != 1 and not reminder_repeat) or key.linux_code in VOLUME_CODES:
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
    return (session is not None and session.phase in (Phase.ACTIVE, Phase.PHONE_PAUSED, Phase.SCHOOL_BANNER, Phase.MORNING_STARTING)
            and observer_fresh(state, now_ms) and state.fault_fence == FaultFence.CLEAR
            and session.id != state.blocked_session_id
            and (state.snapshot.rollout == Rollout.ENABLED or state.snapshot.session_mode in (SessionMode.TEST, SessionMode.POWER_PROBE))
            and (state.snapshot.session_mode == SessionMode.SCHEDULED or session.expires_at_ms - wall_ms <= 60_000)
            and state.intent != Intent.POWER_STOP
            and (session.phase not in (Phase.SCHOOL_BANNER, Phase.MORNING_STARTING) or state.intent == Intent.NONE)
            and (not state.snapshot.school_banner or not state.snapshot.school_banner.diagnostic
                 or 0 < session.expires_at_ms - wall_ms <= 60_000))


def startup_context_allowed(state, now_ms, wall_ms, run_id):
    snapshot = state.snapshot
    if state.availability != Availability.LIVE or snapshot is None or not 0 <= now_ms - state.fetched_at_ms <= STATUS_MAX_AGE_MS:
        return False
    grant = snapshot.startup_authentication
    return (grant is not None and grant.run_id == run_id
            and grant.state in (StartupGrantState.PENDING, StartupGrantState.CONSUMED)
            and grant.issued_wall_ms <= wall_ms + 1000 and wall_ms < grant.expires_at_ms
            and snapshot.rollout == Rollout.ENABLED
            and snapshot.session.phase not in (Phase.ACTIVE, Phase.PHONE_PAUSED, Phase.SCHOOL_BANNER)
            and observer_fresh(state, now_ms) and state.fault_fence == FaultFence.CLEAR
            and state.posted_generation >= state.phone_generation and now_ms >= state.phone_grace_until_ms)


def recovery_context_allowed(state, now_ms, wall_ms):
    session = live_session(state, now_ms, wall_ms)
    return (session is not None and session.phase in (Phase.ACTIVE, Phase.SCHOOL_BANNER, Phase.MORNING_STARTING) and state.intent == Intent.NONE
            and state.snapshot.session_mode != SessionMode.TEST
            and (state.snapshot.rollout == Rollout.ENABLED or state.snapshot.session_mode == SessionMode.POWER_PROBE)
            and (state.snapshot.session_mode != SessionMode.POWER_PROBE or session.expires_at_ms - wall_ms <= 60_000)
            and observer_fresh(state, now_ms) and state.fault_fence == FaultFence.CLEAR
            and session.id != state.blocked_session_id and now_ms >= state.phone_grace_until_ms
            and (not state.snapshot.school_banner or not state.snapshot.school_banner.diagnostic))


def school_waiting_context_allowed(state, now_ms, wall_ms):
    """Retain end observation after the episode timeout, without recovery authority."""
    snapshot = state.snapshot
    if (state.availability != Availability.LIVE or snapshot is None
            or not 0 <= now_ms - state.fetched_at_ms <= STATUS_MAX_AGE_MS):
        return False
    banner = snapshot.school_banner
    return (banner is not None and banner.phase == "waiting" and not banner.diagnostic
            and wall_ms < banner.expires_at_ms
            and banner.run_id == snapshot.session.id and banner.video_id == snapshot.session.video_id
            and snapshot.session.phase in (Phase.ACTIVE, Phase.COMPLETED)
            and snapshot.rollout == Rollout.ENABLED and snapshot.session_mode == SessionMode.SCHEDULED
            and state.intent == Intent.NONE and state.fault_fence == FaultFence.CLEAR
            and observer_fresh(state, now_ms) and snapshot.session.id != state.blocked_session_id
            and state.posted_generation >= state.phone_generation
            and now_ms >= state.phone_grace_until_ms)


def recovery_reason(state, now_ms, wall_ms, observation, playback=None, player_package=None):
    if not recovery_context_allowed(state, now_ms, wall_ms):
        return None
    session = state.snapshot.session
    if session.phase != Phase.SCHOOL_BANNER and playback is not None and playback.video_id == session.video_id and playback.playing == Playing.ENDED:
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
    if session.phase == Phase.SCHOOL_BANNER:
        return None  # The banner restores only power/input; it never resumes TV.
    if session.phase == Phase.MORNING_STARTING:
        # PIN entry remains solely under the original five-second startup grant.
        if (state.snapshot.authentication == Authentication.CLEAR and player_package is not None
                and state.snapshot.foreground_package not in (None, "", player_package,
                    "dev.yossi.morningsesame", "dev.yossi.onnkeyguard")):
            return Recovery.PLAYBACK
        return None
    if state.snapshot.session_mode == SessionMode.POWER_PROBE:
        return None  # Explicit power/input observations are the only probe authority.
    if state.snapshot.authentication != Authentication.CLEAR:
        if state.snapshot.foreground_package == "com.google.android.apps.tv.launcherx":
            # This only authorizes a fresh bounded app probe. The app must positively
            # recognize its exact Google PIN keypad before any credential action.
            return Recovery.AUTHENTICATION
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
    onn_power = Power.UNKNOWN
    tv_power = Power.UNKNOWN
    if marker:
        wake = re.findall(r"mWakefulness\s*[:=]\s*(Awake|Asleep|Dozing|Dreaming)\b", power_text)
        if len(wake) == 1:
            power = Power.ASLEEP if wake[0] in ("Asleep", "Dozing") else Power.AWAKE
            onn_power = Power.AWAKE if wake[0] == "Awake" else Power.ASLEEP if wake[0] in ("Asleep", "Dozing") else Power.UNKNOWN
        tv = re.findall(r"(?m)^\s*mTvPowerStatus\s*[:=]\s*(\d+)\s*$", hdmi)
        tv += re.findall(r"(?m)^\s*CEC:\s*logical_address:\s*0x00\s+device_type:\s*0\b[^\n]*?power_status:\s*(\d+)\b", hdmi)
        if len(tv) == 1 and tv[0] in ("1", "3"):
            power = Power.ASLEEP
        if len(tv) == 1:
            tv_power = Power.AWAKE if tv[0] == "0" else Power.ASLEEP if tv[0] in ("1", "3") else Power.UNKNOWN
    return Observation(activity, power, onn_power, tv_power)


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
