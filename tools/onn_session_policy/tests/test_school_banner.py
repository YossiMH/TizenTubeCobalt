"""School-banner policy and controller races, with fakes only at I/O boundaries."""
import asyncio
from copy import deepcopy
from dataclasses import replace
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from onn_session_policy.adapters import Android, GuardPhase, SessionClient
from onn_session_policy.controller import Config, Controller, RecoveryMode, mono_ms, wall_ms
from onn_session_policy.debugger_forward import ForwardState
from onn_session_policy.media import CobaltMediaReader, MediaSource
from onn_session_policy.native_protocol import event, requested, started
from onn_session_policy.policy import (
    Activity, Authentication, Availability, FaultFence, InputKey, Intent,
    Observation, Observer, Phase, Playback, Playing, PolicyState, Power,
    Recovery, Rollout, SessionMode, Source, apply_status, begin_phone_key,
    failed_status, guard_required, live_session, observer_proven, parse_status,
    provenance_gap, recovery_context_allowed, recovery_reason,
    school_waiting_context_allowed,
)
from test_adapters import LocalServer, TOKEN


NOW = 1_800_000_000_000
MONO = 10_000
RUN_ID = "morning-20261117"
VIDEO_ID = "abcdefghijk"


def status_payload(banner_phase="active", episode_phase="completed", now=NOW,
                   banner_lifetime=100_000, episode_lifetime=None, diagnostic=False):
    if episode_lifetime is None:
        episode_lifetime = -1 if episode_phase == "completed" else 60_000
    return {
        "schema": 1, "rollout": "enabled", "session_mode": "scheduled",
        "authentication": "clear", "native_lease_status": "monitoring",
        "foreground_package": "io.gh.yossim.tizentube.cobalt",
        "session": {
            "id": RUN_ID, "video_id": VIDEO_ID, "phase": episode_phase,
            "expires_at_ms": now + episode_lifetime,
            "duration_seconds": 3600, "position_seconds": 3600,
        },
        "school_banner": {
            "phase": banner_phase, "run_id": RUN_ID, "video_id": VIDEO_ID,
            "expires_at_ms": now + banner_lifetime,
            "diagnostic": diagnostic, "protection_ready": True,
            "overlay_visible": True,
        },
    }


def live_banner(**kwargs):
    return observer_proven(apply_status(PolicyState(), parse_status(status_payload(**kwargs)), MONO, 0), MONO)


def native_proof(now):
    monitor = event(started(now), {
        "kind": "device", "source": "phone", "input_id": 7,
        "name": "virtual-remote", "bus": 6, "vendor": 0x18d1, "product": 0x0100,
    }, now).state
    return event(requested(monitor, now), {
        "kind": "status", "lease": "disarmed", "volume": "absent", "boottime_ms": 0,
    }, now).state


class SchoolBannerStatusTests(unittest.TestCase):
    def test_separate_canonical_authorization_normalizes_only_control_session(self):
        raw = status_payload()
        original = deepcopy(raw)
        snapshot = parse_status(raw)
        self.assertEqual(original, raw)
        self.assertEqual(Phase.SCHOOL_BANNER, snapshot.session.phase)
        self.assertEqual(RUN_ID, snapshot.session.id)
        self.assertEqual(VIDEO_ID, snapshot.session.video_id)
        self.assertEqual(NOW + 100_000, snapshot.session.expires_at_ms)
        self.assertIsNone(snapshot.session.duration_seconds)
        self.assertEqual("active", snapshot.school_banner.phase)
        del raw["school_banner"]
        self.assertEqual(Phase.COMPLETED, parse_status(raw).session.phase)
        raw["session"]["phase"] = "school_banner"
        with self.assertRaisesRegex(ValueError, "separate canonical authorization"):
            parse_status(raw)

    def test_active_banner_requires_both_explicit_boolean_proofs(self):
        for field in ("protection_ready", "overlay_visible"):
            for value in (False, None, 1, "true"):
                with self.subTest(field=field, value=value):
                    raw = status_payload()
                    raw["school_banner"][field] = value
                    with self.assertRaisesRegex(ValueError, "unproven active banner"):
                        parse_status(raw)
            raw = status_payload()
            del raw["school_banner"][field]
            with self.assertRaisesRegex(ValueError, "unproven active banner"):
                parse_status(raw)

    def test_waiting_unproven_and_terminal_phases_do_not_become_banner_session(self):
        for phase in ("waiting", "awaiting_observer", "inactive", "dismissed", "expired", "skipped"):
            with self.subTest(phase=phase):
                state = live_banner(banner_phase=phase)
                self.assertEqual(Phase.COMPLETED, state.snapshot.session.phase)
                self.assertFalse(guard_required(state, MONO, NOW))
                self.assertFalse(recovery_context_allowed(state, MONO, NOW))

    def test_live_banner_identity_expiry_and_diagnostic_types_are_validated(self):
        invalid = (
            ("phase", "visible"), ("run_id", ""), ("run_id", "../other"),
            ("run_id", "x" * 129), ("video_id", "short"),
            ("video_id", "abcdefghij!"), ("expires_at_ms", True),
            ("expires_at_ms", -1), ("expires_at_ms", float(NOW)),
            ("diagnostic", 1), ("diagnostic", "false"),
        )
        for field, value in invalid:
            with self.subTest(field=field, value=value):
                raw = status_payload()
                raw["school_banner"][field] = value
                with self.assertRaises(ValueError):
                    parse_status(raw)


class SchoolBannerPolicyTests(unittest.TestCase):
    def test_guard_needs_fresh_app_and_phone_source_proof(self):
        state = live_banner()
        self.assertTrue(guard_required(state, MONO, NOW))
        self.assertTrue(guard_required(state, MONO + 5000, NOW))
        denied = {
            "status unavailable": failed_status(state),
            "status stale": replace(state, fetched_at_ms=MONO - 5001),
            "status future": replace(state, fetched_at_ms=MONO + 1),
            "observer unknown": replace(state, observer=Observer.UNKNOWN),
            "observer stale": replace(state, observer_at_ms=MONO - 5001),
            "observer future": replace(state, observer_at_ms=MONO + 1),
            "source lost": provenance_gap(state),
            "fault fenced": replace(state, fault_fence=FaultFence.STOP_REQUIRED),
            "blocked run": replace(state, blocked_session_id=RUN_ID),
            "prepared rollout": replace(state, snapshot=replace(state.snapshot, rollout=Rollout.PREPARED)),
        }
        for reason, blocked in denied.items():
            with self.subTest(reason=reason):
                self.assertFalse(guard_required(blocked, MONO, NOW))
                self.assertFalse(recovery_context_allowed(blocked, MONO, NOW))

    def test_banner_deadline_is_exclusive(self):
        state = live_banner(banner_lifetime=10)
        self.assertTrue(guard_required(state, MONO, NOW + 9))
        self.assertFalse(guard_required(state, MONO, NOW + 10))
        self.assertFalse(recovery_context_allowed(state, MONO, NOW + 10))

    def test_phone_navigation_and_power_win_before_post_or_status_roundtrip(self):
        for code, intent in ((102, Intent.PENDING), (116, Intent.POWER_STOP)):
            with self.subTest(code=code):
                state = begin_phone_key(live_banner(), InputKey(Source.PHONE, 7, code, 1, NOW), MONO)
                self.assertEqual(intent, state.intent)
                self.assertFalse(guard_required(state, MONO, NOW))
                self.assertFalse(recovery_context_allowed(state, MONO, NOW))
                old = apply_status(state, parse_status(status_payload()), MONO + 1, 0)
                self.assertEqual(intent, old.intent)
                self.assertFalse(guard_required(old, MONO + 1, NOW))

    def test_physical_keys_and_phone_volume_do_not_dismiss_banner(self):
        original = live_banner()
        for source, code, value in ((Source.HARDWARE, 116, 1), (Source.UNKNOWN, 102, 1),
                                    (Source.PHONE, 115, 1), (Source.PHONE, 102, 0)):
            with self.subTest(source=source, code=code, value=value):
                state = begin_phone_key(original, InputKey(source, 7, code, value, NOW), MONO)
                self.assertEqual(original, state)
                self.assertTrue(guard_required(state, MONO, NOW))

    def test_banner_recovers_only_explicit_power_or_input(self):
        state = live_banner()
        state = replace(state, snapshot=replace(state.snapshot,
            authentication=Authentication.REQUIRED,
            foreground_package="com.google.android.apps.tv.launcherx"))
        paused = Playback(VIDEO_ID, 300, Playing.PAUSED, NOW, 3600)
        self.assertEqual(Recovery.POWER, recovery_reason(state, MONO, NOW,
            Observation(Activity.UNKNOWN, Power.ASLEEP), paused, "different.player"))
        self.assertEqual(Recovery.INPUT, recovery_reason(state, MONO, NOW,
            Observation(Activity.INACTIVE, Power.AWAKE), paused, "different.player"))
        for observation in (Observation(), Observation(Activity.ACTIVE, Power.AWAKE)):
            with self.subTest(observation=observation):
                self.assertIsNone(recovery_reason(state, MONO, NOW, observation, paused, "different.player"))

    def test_diagnostic_guard_is_bounded_to_sixty_seconds_and_cannot_recover(self):
        for lifetime, guarded in ((1, True), (60_000, True), (60_001, False), (0, False)):
            with self.subTest(lifetime=lifetime):
                raw = status_payload(diagnostic=True, banner_lifetime=lifetime)
                raw["school_banner"]["video_id"] = ""
                state = observer_proven(apply_status(PolicyState(), parse_status(raw), MONO, 0), MONO)
                self.assertEqual("diagnostic-banner:" + str(NOW + lifetime), state.snapshot.session.id)
                self.assertEqual(guarded, guard_required(state, MONO, NOW))
                self.assertFalse(recovery_context_allowed(state, MONO, NOW))
                self.assertIsNone(recovery_reason(state, MONO, NOW, Observation(Activity.INACTIVE, Power.ASLEEP)))

    def test_waiting_outlives_episode_expiry_only_for_end_observation(self):
        for phase in ("active", "completed"):
            with self.subTest(phase=phase):
                state = live_banner(banner_phase="waiting", episode_phase=phase, episode_lifetime=-1)
                self.assertIsNone(live_session(state, MONO, NOW))
                self.assertTrue(school_waiting_context_allowed(state, MONO, NOW))
                self.assertFalse(guard_required(state, MONO, NOW))
                self.assertFalse(recovery_context_allowed(state, MONO, NOW))
                self.assertIsNone(recovery_reason(state, MONO, NOW, Observation(Activity.INACTIVE, Power.ASLEEP)))
                self.assertFalse(school_waiting_context_allowed(state, MONO, NOW + 100_000))

    def test_waiting_observation_retains_run_phone_freshness_and_rollout_fences(self):
        state = live_banner(banner_phase="waiting", episode_lifetime=-1)
        snapshot = state.snapshot
        variants = {
            "cached": replace(state, availability=Availability.CACHE),
            "stale app": replace(state, fetched_at_ms=MONO - 5001),
            "stale observer": replace(state, observer_at_ms=MONO - 5001),
            "source lost": provenance_gap(state),
            "phone navigation": begin_phone_key(state, InputKey(Source.PHONE, 7, 102, 1, NOW), MONO),
            "phone power": begin_phone_key(state, InputKey(Source.PHONE, 7, 116, 1, NOW), MONO),
            "unposted phone": replace(state, phone_generation=1),
            "phone grace": replace(state, phone_grace_until_ms=MONO + 1),
            "blocked run": replace(state, blocked_session_id=RUN_ID),
            "prepared": replace(state, snapshot=replace(snapshot, rollout=Rollout.PREPARED)),
            "test session": replace(state, snapshot=replace(snapshot, session_mode=SessionMode.TEST)),
            "phone stopped": replace(state, snapshot=replace(snapshot,
                session=replace(snapshot.session, phase=Phase.PHONE_STOPPED))),
            "different run": replace(state, snapshot=replace(snapshot,
                school_banner=replace(snapshot.school_banner, run_id="another-run"))),
            "different video": replace(state, snapshot=replace(snapshot,
                school_banner=replace(snapshot.school_banner, video_id="12345678901"))),
            "diagnostic": replace(state, snapshot=replace(snapshot,
                school_banner=replace(snapshot.school_banner, diagnostic=True))),
        }
        for reason, blocked in variants.items():
            with self.subTest(reason=reason):
                self.assertFalse(school_waiting_context_allowed(blocked, MONO, NOW))


class AndroidBoundary(Android):
    """Keep production Android command construction; replace subprocess I/O."""
    def __init__(self):
        super().__init__("/nonexistent/no-device-adb")
        self.commands = []
        self.on_wake = None

    async def shell(self, *arguments, timeout=4.0):
        self.commands.append(arguments)
        if arguments == ("input", "keyevent", "KEYCODE_WAKEUP"):
            if self.on_wake is not None:
                await self.on_wake()
            return ""
        if arguments == ("cmd", "hdmi_control", "onetouchplay"):
            return "Sending One Touch Play... done (Success)\n"
        if arguments[:2] == ("sh", "-c"):
            return "HdmiCecLocalDevicePlayback:\n  mDeviceType: 4\n  isActiveSource(): false\n<ONN_POLICY_POWER>\nmWakefulness=Asleep\n"
        raise AssertionError("unexpected Android command: " + repr(arguments))


class NativeBoundary:
    """A known empty native drain; real monitor protocol establishes provenance."""
    def __init__(self, now):
        self.phase = GuardPhase.RUNNING
        self.monitor = native_proof(now)
        self.barriers = 0

    async def recovery_barrier(self, timeout=4.0):
        self.barriers += 1
        return True


class ForwardBoundary:
    def __init__(self):
        self.state = ForwardState.READY
        self.on_ensure = None
        self.calls = 0

    async def ensure(self, now):
        self.calls += 1
        if self.on_ensure is not None:
            await self.on_ensure()
        return True


class MediaBoundary:
    def __init__(self):
        self.leases = []
        self.playback = None
        self.on_sample = None
        self.samples = 0

    def set_school_banner(self, deadline, video, awaiting_end):
        self.leases.append((deadline, video, awaiting_end))
        return True

    def sample(self):
        self.samples += 1
        if self.on_sample is not None:
            self.on_sample()
        return self.playback


class SchoolBannerControllerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.server = LocalServer().__enter__()
        self.android = AndroidBoundary()
        self.controller = Controller(Config(Path(self.directory.name), "/data/local/tmp/not-used",
            recovery_mode=RecoveryMode.ENABLED, media_source=MediaSource.CDP),
            self.android, SessionClient(TOKEN, self.server.port))
        self.controller.guard = NativeBoundary(mono_ms())
        self.controller.debugger_forward = ForwardBoundary()
        self.controller.media = MediaBoundary()

    async def asyncTearDown(self):
        self.controller.stop_requested.set()
        task = self.controller.recovery_task
        if task is not None:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        await asyncio.to_thread(self.server.__exit__, None, None, None)
        self.directory.cleanup()

    def install_status(self, **kwargs):
        now = mono_ms()
        snapshot = parse_status(status_payload(now=wall_ms(), **kwargs))
        self.controller.state = observer_proven(apply_status(self.controller.state, snapshot,
            now, self.controller.state.phone_generation), now)
        self.controller.guard.monitor = native_proof(now)

    async def test_episode_to_banner_during_wake_cancels_remaining_episode_actions(self):
        for reason in (Recovery.POWER, Recovery.INPUT):
            with self.subTest(reason=reason):
                self.controller.state = PolicyState()
                self.android.commands.clear()
                self.controller.guard.barriers = 0
                self.install_status(banner_phase="waiting", episode_phase="active")

                async def actual_end_during_wake():
                    self.install_status(banner_phase="active", episode_phase="completed")

                self.android.on_wake = actual_end_during_wake
                await self.controller._recover(reason)
                self.assertEqual(Phase.SCHOOL_BANNER, self.controller.state.snapshot.session.phase)
                self.assertEqual(RUN_ID, self.controller.state.snapshot.session.id)
                self.assertEqual([("input", "keyevent", "KEYCODE_WAKEUP")], self.android.commands)
                self.assertEqual(1, self.controller.guard.barriers)
                self.assertEqual("cancelled", self.server.events[-1]["result"])

    async def test_active_banner_power_recovery_never_launches_or_authenticates(self):
        self.install_status()
        await self.controller._recover(Recovery.POWER)
        self.assertEqual([
            ("input", "keyevent", "KEYCODE_WAKEUP"),
            ("cmd", "hdmi_control", "onetouchplay"),
        ], self.android.commands)
        self.assertEqual("school_banner_power_restored", self.server.events[-1]["result"])
        for reason in (Recovery.PLAYBACK, Recovery.AUTHENTICATION):
            self.android.commands.clear()
            await self.controller._recover(reason)
            self.assertEqual([], self.android.commands)
            self.assertEqual("cancelled", self.server.events[-1]["result"])

    async def _phone_during_forward(self, previously_armed):
        self.install_status()
        self.controller._school_lease_armed = previously_armed

        async def phone_in_forward_await():
            await self.controller.on_key(InputKey(Source.PHONE, 7, 102, 1, wall_ms()))

        self.controller.debugger_forward.on_ensure = phone_in_forward_await
        await self.controller._update_school_lease()
        self.assertEqual(Intent.PENDING, self.controller.state.intent)
        self.assertEqual(1, self.controller.debugger_forward.calls)
        self.assertEqual(1, self.controller.guard.barriers)
        self.assertEqual([(0, "", False)] if previously_armed else [], self.controller.media.leases)
        self.assertFalse(self.controller._school_lease_armed)

    async def test_phone_arriving_during_forward_prevents_first_lease(self):
        await self._phone_during_forward(False)

    async def test_phone_arriving_during_forward_clears_existing_lease(self):
        await self._phone_during_forward(True)

    async def test_waiting_lease_remains_finite_after_episode_expiry(self):
        self.install_status(banner_phase="waiting", episode_phase="completed",
                            episode_lifetime=-1, banner_lifetime=9000)
        before = wall_ms()
        await self.controller._update_school_lease()
        self.assertEqual(1, len(self.controller.media.leases))
        deadline, video, awaiting_end = self.controller.media.leases[0]
        self.assertEqual(self.controller.state.snapshot.school_banner.expires_at_ms, deadline)
        self.assertTrue(before < deadline <= before + 12_000)
        self.assertEqual(VIDEO_ID, video)
        self.assertIs(awaiting_end, True)
        self.assertFalse(guard_required(self.controller.state, mono_ms(), wall_ms()))
        self.assertFalse(recovery_context_allowed(self.controller.state, mono_ms(), wall_ms()))

    async def test_waiting_after_episode_expiry_posts_actual_end_without_recovery(self):
        self.install_status(banner_phase="waiting", episode_phase="completed", episode_lifetime=-1)
        self.controller.media.playback = Playback(VIDEO_ID, 3600, Playing.ENDED, wall_ms(), 3600)
        loop = asyncio.get_running_loop()
        self.controller.media.on_sample = lambda: loop.call_soon_threadsafe(self.controller.stop_requested.set)
        await asyncio.wait_for(self.controller._observe_loop(), 2)
        self.assertEqual(1, self.controller.media.samples)
        self.assertEqual([], self.android.commands)
        self.assertIsNone(self.controller.recovery_task)
        playback_events = [value for value in self.server.events if value.get("type") == "playback"]
        self.assertEqual(1, len(playback_events))
        self.assertEqual(RUN_ID, playback_events[0]["run_id"])
        self.assertEqual("ended", playback_events[0]["playing"])
        self.assertEqual(VIDEO_ID, playback_events[0]["video_id"])


class LeaseTransportBoundary(CobaltMediaReader):
    def __init__(self):
        super().__init__()
        self.expressions = []
        self.response = {"result": {"result": {"type": "boolean", "value": True}}}

    def _evaluate(self, expression):
        self.expressions.append(expression)
        return self.response, 7


class SchoolBannerMediaTests(unittest.TestCase):
    def test_finite_lease_validates_clock_duration_video_and_types_before_io(self):
        reader = LeaseTransportBoundary()
        with patch("onn_session_policy.media.time.time_ns", return_value=NOW * 1_000_000):
            for deadline, video, awaiting in (
                (NOW, VIDEO_ID, False), (NOW - 1, VIDEO_ID, False),
                (NOW + 15_001, VIDEO_ID, False), (-1, VIDEO_ID, False),
                (True, VIDEO_ID, False), (float(NOW + 1), VIDEO_ID, False),
                (NOW + 1, "short", False), (NOW + 1, "abcdefghij!", False),
                (NOW + 1, VIDEO_ID, 1),
            ):
                with self.subTest(deadline=deadline, video=video, awaiting=awaiting):
                    with self.assertRaises(ValueError):
                        reader.set_school_banner(deadline, video, awaiting)
            self.assertEqual([], reader.expressions)
            self.assertTrue(reader.set_school_banner(NOW + 1, VIDEO_ID, True))
            self.assertTrue(reader.set_school_banner(NOW + 15_000, VIDEO_ID, False))
            self.assertTrue(reader.set_school_banner(0, "", False))
            self.assertIn('[0,"",false]', reader.expressions[-1])

    def test_lease_success_requires_structured_boolean_true(self):
        reader = LeaseTransportBoundary()
        responses = (
            {}, {"result": {}},
            {"result": {"result": {"type": "boolean", "value": False}}},
            {"result": {"result": {"type": "boolean", "value": 1}}},
            {"result": {"result": {"type": "string", "value": "true"}}},
            {"error": {"message": "unavailable"}, "result": {"result": {"type": "boolean", "value": True}}},
            {"result": {"exceptionDetails": {"text": "failed"}, "result": {"type": "boolean", "value": True}}},
        )
        for response in responses:
            with self.subTest(response=response):
                reader.response = response
                self.assertFalse(reader.set_school_banner(0, "", False))
        reader.response = {"result": {"result": {"type": "boolean", "value": True}}}
        self.assertTrue(reader.set_school_banner(0, "", False))


if __name__ == "__main__":
    unittest.main()
