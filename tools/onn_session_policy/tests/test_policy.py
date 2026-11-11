import json
import unittest
from dataclasses import replace
from pathlib import Path

from onn_session_policy.policy import (
    Activity, Availability, InputKey, Intent, Playback, Playing, PolicyState,
    Recovery, Source, apply_status, begin_phone_key, failed_status,
    guard_required, mark_phone_posted, parse_native_key, parse_observation,
    parse_player, parse_status, recovery_reason, record_recovery,
    FaultFence, Observer, observer_proven, observe_phone_playback,
    provenance_gap, terminal_stop_confirmed,
)


NOW = 1_800_000_000_000


def status(phase="active", identity="morning-1", expires=None):
    return parse_status({"schema": 1, "session": {
        "id": identity, "video_id": "abcdefghijk", "phase": phase,
        "expires_at_ms": NOW + 100_000 if expires is None else expires,
        "duration_seconds": None, "position_seconds": None,
    }, "foreground_package": "com.google.android.youtube.tv",
        "native_lease_status": "unknown", "rollout": "enabled", "authentication": "clear"})


def live(phase="active"):
    return observer_proven(apply_status(PolicyState(), status(phase), 1000, 0), 1000)


class PolicyTests(unittest.TestCase):
    def test_guard_follows_only_live_unexpired_allowed_phases(self):
        for phase in ("active", "phone_paused"):
            self.assertTrue(guard_required(live(phase), 1001, NOW))
        for phase in ("inactive", "phone_stopped", "completed"):
            self.assertFalse(guard_required(live(phase), 1001, NOW))
        self.assertFalse(guard_required(live(), 6001, NOW))
        self.assertFalse(guard_required(live(), 1001, NOW + 100_000))
        unavailable = failed_status(live())
        self.assertEqual(Availability.CACHE, unavailable.availability)
        self.assertFalse(guard_required(unavailable, 1001, NOW))

    def test_phone_power_stops_immediately_and_old_status_cannot_undo(self):
        state = begin_phone_key(live(), InputKey(Source.PHONE, "event7", 116, 1, NOW))
        self.assertEqual(Intent.POWER_STOP, state.intent)
        self.assertFalse(guard_required(state, 1001, NOW))
        self.assertIsNone(recovery_reason(state, 1001, NOW, observation(Activity.INACTIVE)))
        state = mark_phone_posted(state, state.phone_generation)
        state = apply_status(state, status(), 1002, state.phone_generation)
        self.assertEqual(Intent.POWER_STOP, state.intent)
        state = apply_status(state, status(identity="morning-2"), 1003, state.phone_generation)
        self.assertEqual(Intent.POWER_STOP, state.intent)
        state = apply_status(state, status(identity="morning-2"), 10000, state.phone_generation)
        self.assertEqual(Intent.NONE, state.intent)

    def test_navigation_waits_for_post_and_new_status(self):
        state = begin_phone_key(live(), InputKey(Source.PHONE, 7, 102, 1, NOW))
        generation = state.phone_generation
        self.assertEqual(Intent.PENDING, state.intent)
        state = apply_status(state, status(), 1002, generation)
        self.assertEqual(Intent.PENDING, state.intent)
        state = mark_phone_posted(state, generation)
        state = apply_status(state, status(), 1003, generation - 1)
        self.assertEqual(Intent.PENDING, state.intent)
        state = apply_status(state, status("phone_paused"), 1004, generation)
        self.assertEqual(Intent.PENDING, state.intent)
        self.assertTrue(guard_required(state, 1004, NOW))
        self.assertIsNone(recovery_reason(state, 1004, NOW, observation(Activity.INACTIVE)))

    def test_volume_release_unknown_and_physical_do_not_create_phone_intent(self):
        for source, code, value in [(Source.PHONE, 115, 1), (Source.PHONE, 116, 0),
                                    (Source.HARDWARE, 116, 1), (Source.UNKNOWN, 116, 1)]:
            self.assertEqual(live(), begin_phone_key(live(), InputKey(source, 1, code, value, NOW)))

    def test_recovery_requires_explicit_fresh_observation(self):
        self.assertEqual(Recovery.INPUT, recovery_reason(live(), 1001, NOW, observation(Activity.INACTIVE)))
        self.assertEqual(Recovery.POWER, recovery_reason(live(), 1001, NOW, observation(Activity.ACTIVE, asleep=True)))
        self.assertIsNone(recovery_reason(live(), 1001, NOW, observation(Activity.UNKNOWN)))
        self.assertIsNone(recovery_reason(live(), 8000, NOW, observation(Activity.INACTIVE)))
        self.assertIsNone(recovery_reason(failed_status(live()), 1001, NOW, observation(Activity.INACTIVE)))

    def test_recovery_budget_debounce_and_cooldown(self):
        state = live()
        for now in (1000, 11000, 21000):
            state = observer_proven(apply_status(state, status(), now, 0), now)
            self.assertEqual(Recovery.INPUT, recovery_reason(state, now, NOW, observation(Activity.INACTIVE)))
            state = record_recovery(state, now)
            self.assertIsNone(recovery_reason(state, now + 1, NOW, observation(Activity.INACTIVE)))
        state = observer_proven(apply_status(state, status(), 61001, 0), 61001)
        self.assertIsNone(recovery_reason(state, 61001, NOW, observation(Activity.INACTIVE)))
        state = observer_proven(apply_status(state, status(), 81001, 0), 81001)
        self.assertEqual(Recovery.INPUT, recovery_reason(state, 81001, NOW, observation(Activity.INACTIVE)))

    def test_prepared_rollout_never_grabs_or_recovers(self):
        from onn_session_policy.policy import Rollout
        state = live()
        state = replace(state, snapshot=replace(state.snapshot, rollout=Rollout.PREPARED))
        self.assertFalse(guard_required(state, 1001, NOW))
        self.assertIsNone(recovery_reason(state, 1001, NOW, observation(Activity.INACTIVE)))

    def test_test_session_has_bounded_guard_and_no_recovery(self):
        from onn_session_policy.policy import Rollout, SessionMode
        state = live()
        snapshot = replace(state.snapshot, session_mode=SessionMode.TEST, rollout=Rollout.PREPARED,
                           session=replace(state.snapshot.session, expires_at_ms=NOW+60_000))
        state = replace(state, snapshot=snapshot)
        self.assertTrue(guard_required(state, 1001, NOW))
        self.assertIsNone(recovery_reason(state, 1001, NOW, observation(Activity.INACTIVE)))
        state = replace(state, snapshot=replace(snapshot, session=replace(snapshot.session, expires_at_ms=NOW+60_001)))
        self.assertFalse(guard_required(state, 1001, NOW))

    def test_prepared_power_probe_only_explicit_power_or_input_can_recover(self):
        from onn_session_policy.policy import Rollout, SessionMode
        state = live()
        snapshot = replace(state.snapshot, rollout=Rollout.PREPARED, session_mode=SessionMode.POWER_PROBE,
                           session=replace(state.snapshot.session, expires_at_ms=NOW+60_000))
        state = replace(state, snapshot=snapshot)
        self.assertTrue(guard_required(state,1001,NOW))
        self.assertEqual(Recovery.POWER,recovery_reason(state,1001,NOW,observation(Activity.UNKNOWN,asleep=True)))
        self.assertEqual(Recovery.INPUT,recovery_reason(state,1001,NOW,observation(Activity.INACTIVE)))
        paused=Playback("abcdefghijk",10,Playing.PAUSED,NOW)
        self.assertIsNone(recovery_reason(state,1001,NOW,observation(Activity.ACTIVE),paused))
        self.assertIsNone(recovery_reason(state,1001,NOW,observation(Activity.ACTIVE),None,"other.foreground"))
        self.assertIsNone(recovery_reason(state,1001,NOW,observation(Activity.UNKNOWN)))

    def test_power_probe_expiry_phone_intent_and_source_loss_win(self):
        from onn_session_policy.policy import Rollout, SessionMode
        state=live()
        snapshot=replace(state.snapshot,rollout=Rollout.PREPARED,session_mode=SessionMode.POWER_PROBE,
                         session=replace(state.snapshot.session,expires_at_ms=NOW+60_000))
        state=replace(state,snapshot=snapshot)
        for blocked in (provenance_gap(state),begin_phone_key(state,InputKey(Source.PHONE,7,116,1,NOW),1000)):
            self.assertFalse(guard_required(blocked,1001,NOW))
            self.assertIsNone(recovery_reason(blocked,1001,NOW,observation(Activity.INACTIVE)))
        self.assertFalse(guard_required(state,1001,NOW+60_000))
        self.assertIsNone(recovery_reason(state,1001,NOW+60_000,observation(Activity.INACTIVE)))
        over=replace(state,snapshot=replace(snapshot,session=replace(snapshot.session,expires_at_ms=NOW+60_001)))
        self.assertFalse(guard_required(over,1001,NOW))
        self.assertIsNone(recovery_reason(over,1001,NOW,observation(Activity.INACTIVE)))

    def test_authentication_blocks_playback_launch_but_allows_power_observation(self):
        from onn_session_policy.policy import Authentication
        state = live()
        state = replace(state, snapshot=replace(state.snapshot, authentication=Authentication.REQUIRED))
        playback = Playback("abcdefghijk", 10, Playing.PAUSED, NOW)
        self.assertIsNone(recovery_reason(state, 1001, NOW, observation(Activity.ACTIVE), playback))
        self.assertEqual(Recovery.POWER, recovery_reason(state, 1001, NOW, observation(Activity.INACTIVE, asleep=True), playback))

    def test_unknown_authentication_cannot_authorize_playback_launch(self):
        from onn_session_policy.policy import Authentication
        state = live()
        state = replace(state, snapshot=replace(state.snapshot, authentication=Authentication.UNKNOWN))
        playback = Playback("abcdefghijk", 10, Playing.PAUSED, NOW)
        self.assertIsNone(recovery_reason(state, 1001, NOW, observation(Activity.ACTIVE), playback))
        self.assertIsNone(recovery_reason(state, 1001, NOW, observation(Activity.ACTIVE), None, "different.player"))
        self.assertEqual(Recovery.INPUT, recovery_reason(state, 1001, NOW, observation(Activity.INACTIVE), playback))

    def test_actual_ended_sample_prevents_racing_canonical_completion(self):
        playback = Playback("abcdefghijk", 20, Playing.ENDED, NOW, 20)
        self.assertIsNone(recovery_reason(live(), 1001, NOW, observation(Activity.INACTIVE), playback))

    def test_observer_unknown_lost_and_stale_block_grab_and_recovery(self):
        for state in (replace(live(), observer=Observer.UNKNOWN), provenance_gap(live()),
                      replace(live(), observer_at_ms=0)):
            self.assertFalse(guard_required(state, 6001, NOW))
            self.assertIsNone(recovery_reason(state, 6001, NOW, observation(Activity.INACTIVE)))

    def test_fault_cannot_rearm_same_session_after_fresh_proof(self):
        state = provenance_gap(live())
        generation = state.observer_generation
        state = observer_proven(state, 1002)
        self.assertFalse(guard_required(state, 1002, NOW))
        state = terminal_stop_confirmed(state, generation)
        self.assertEqual(FaultFence.CLEAR, state.fault_fence)
        self.assertEqual(Observer.UNKNOWN, state.observer)
        state = observer_proven(apply_status(state, status(), 1003, 0), 1003)
        self.assertFalse(guard_required(state, 1003, NOW))
        self.assertIsNone(recovery_reason(state, 1003, NOW, observation(Activity.INACTIVE)))
        state = apply_status(state, status(identity="next-daily-run"), 1004, 0)
        self.assertTrue(guard_required(state, 1004, NOW))

    def test_inflight_active_status_from_before_terminal_ack_is_ignored(self):
        state = provenance_gap(live())
        generation = state.observer_generation
        state = terminal_stop_confirmed(state, generation)
        result = apply_status(state, status(identity="unknown-racing-run"), 1002, 0, generation)
        self.assertEqual(state, result)

    def test_navigation_does_not_timeout_into_late_blind_resume(self):
        state = begin_phone_key(live(), InputKey(Source.PHONE, 7, 102, 1, NOW), 1000)
        state = mark_phone_posted(state, state.phone_generation)
        state = observer_proven(apply_status(state, status(), 20000, state.phone_generation), 20000)
        paused = Playback("abcdefghijk", 20, Playing.PAUSED, NOW+100)
        state = observe_phone_playback(state, paused, 20000, NOW+100, "com.google.android.youtube.tv")
        self.assertEqual(Intent.PENDING, state.intent)
        self.assertIsNone(recovery_reason(state, 20000, NOW+100, observation(Activity.INACTIVE), paused))

    def test_positive_post_key_playing_requires_post_status_and_foreground_then_grace(self):
        state = begin_phone_key(live(), InputKey(Source.PHONE, 7, 102, 1, NOW), 1000)
        playing = Playback("abcdefghijk", 20, Playing.PLAYING, NOW+100)
        self.assertEqual(state, observe_phone_playback(state, playing, 1001, NOW+100, "com.google.android.youtube.tv"))
        state = mark_phone_posted(state, state.phone_generation)
        state = apply_status(state, status(), 1002, state.phone_generation)
        self.assertEqual(state, observe_phone_playback(state, playing, 1002, NOW+100, "wrong.player"))
        state = observe_phone_playback(state, playing, 1002, NOW+100, "com.google.android.youtube.tv")
        self.assertEqual(Intent.PENDING, state.intent)
        self.assertIsNone(recovery_reason(state, 1003, NOW+100, observation(Activity.INACTIVE)))
        state = observer_proven(apply_status(state, status(), 9001, state.phone_generation), 9001)
        state = observe_phone_playback(state, playing, 9001, NOW+8100, "com.google.android.youtube.tv")
        self.assertEqual(Intent.PENDING, state.intent)
        playing = Playback("abcdefghijk", 28, Playing.PLAYING, NOW+8100)
        state = observe_phone_playback(state, playing, 9001, NOW+8100, "com.google.android.youtube.tv")
        self.assertEqual(Intent.NONE, state.intent)
        self.assertEqual(Recovery.INPUT, recovery_reason(state, 9001, NOW+8100, observation(Activity.INACTIVE)))

    def test_old_late_playing_sample_cannot_clear_phone_intent(self):
        state = begin_phone_key(live(), InputKey(Source.PHONE, 7, 102, 1, NOW), 1000)
        state = mark_phone_posted(state, state.phone_generation)
        state = observer_proven(apply_status(state, status(), 15000, state.phone_generation), 15000)
        old_playing = Playback("abcdefghijk", 28, Playing.PLAYING, NOW+8100)
        state = observe_phone_playback(state, old_playing, 15000, NOW+12000, "com.google.android.youtube.tv")
        self.assertEqual(Intent.PENDING, state.intent)

    def test_prestart_phone_intent_survives_first_active_snapshot(self):
        state = begin_phone_key(PolicyState(), InputKey(Source.PHONE, 7, 102, 1, NOW), 1000)
        state = mark_phone_posted(state, state.phone_generation)
        state = observer_proven(apply_status(state, status(), 1002, state.phone_generation), 1002)
        self.assertEqual(Intent.PENDING, state.intent)
        self.assertIsNone(recovery_reason(state, 1003, NOW, observation(Activity.INACTIVE)))


def observation(activity, asleep=False):
    from onn_session_policy.policy import Power, Observation
    return Observation(activity, Power.ASLEEP if asleep else Power.AWAKE)


class ParserTests(unittest.TestCase):
    def test_native_actual_sources_and_aliases(self):
        for name in ("phone", "software"):
            event = parse_native_key(json.dumps({"kind":"key", "source":name,
                "input_id":7, "code":116, "value":1, "wall_ms":NOW}))
            self.assertEqual(Source.PHONE, event.source)
            self.assertEqual(116, event.linux_code)
        for name in ("cec", "other", "other_power", "unknown"):
            event = parse_native_key(json.dumps({"kind":"key", "source":name,
                "input_id":7, "code":116, "value":1, "wall_ms":NOW}))
            self.assertEqual(Source.UNKNOWN, event.source)
        self.assertIsNone(parse_native_key('{"kind":"status","lease":"armed"}'))

    def test_bad_status_fails_closed(self):
        for value in ({}, {"schema":2}, {"schema": True},
                      {"schema":1, "session":{"phase":"not_a_phase"}}):
            with self.assertRaises(ValueError):
                parse_status(value)

    def test_cec_uses_local_playback4_and_ignores_unrelated_sources(self):
        text = '''mLocalDevices:
  HdmiCecLocalDeviceTv:
    mDeviceType: 0
    isActiveSource(): true
  HdmiCecLocalDevicePlayback:
    mDeviceType: 4
    isActiveSource(): false
  HdmiCecLocalDeviceAudioSystem:
    mDeviceType: 5
    isActiveSource(): true
<ONN_POLICY_POWER>
mWakefulness=Awake
'''
        parsed = parse_observation(text)
        self.assertEqual(Activity.INACTIVE, parsed.activity)
        text = text.replace("isActiveSource(): false", "isActiveSource(): true")
        self.assertEqual(Activity.ACTIVE, parse_observation(text).activity)
        self.assertEqual(Activity.UNKNOWN, parse_observation("isActiveSource(): false\nmWakefulness=Awake").activity)

    def test_cec_ambiguous_or_remote_device_never_recovers(self):
        text = '''HdmiCecLocalDevicePlayback:
  mDeviceType: 4
  isActiveSource(): true
  isActiveSource(): false
<ONN_POLICY_POWER>
mWakefulness=Awake'''
        self.assertEqual(Activity.UNKNOWN, parse_observation(text).activity)
        self.assertEqual(Activity.UNKNOWN, parse_observation("mRemoteDevices:\n  playback4:\n    isActiveSource(): false").activity)

    def test_sleep_and_tv_power_status(self):
        from onn_session_policy.policy import Power
        self.assertEqual(Power.ASLEEP, parse_observation("<ONN_POLICY_POWER>\nmWakefulness=Asleep").power)
        self.assertEqual(Power.ASLEEP, parse_observation("mTvPowerStatus: 1\n<ONN_POLICY_POWER>\nmWakefulness=Awake").power)
        self.assertEqual(Power.UNKNOWN, parse_observation("mWakefulness=Awake").power)

    def test_power_confirmation_requires_three_explicit_current_states(self):
        from onn_session_policy.policy import restored_power_state
        sample='HdmiCecLocalDevice #4:\n  mDeviceType: 4\n  isActiveSource(): true\nmTvPowerStatus: 0\n<ONN_POLICY_POWER>\nmWakefulness=Awake\n'
        self.assertTrue(restored_power_state(parse_observation(sample)))
        for invalid in (sample.replace('mTvPowerStatus: 0',''),sample.replace('mTvPowerStatus: 0','mTvPowerStatus: 1'),
                        sample.replace('mTvPowerStatus: 0','mTvPowerStatus: 2'),sample.replace('isActiveSource(): true','isActiveSource(): false'),
                        sample.replace('mWakefulness=Awake','mWakefulness=Dreaming'),sample.replace('mWakefulness=Awake','mWakefulness=Asleep'),
                        sample.replace('mTvPowerStatus: 0','mTvPowerStatus: 0\nmTvPowerStatus: 0'),sample.replace('<ONN_POLICY_POWER>','')):
            self.assertFalse(restored_power_state(parse_observation(invalid)))

    def test_real_hdmi_fixture_ignores_historical_active_source_lines(self):
        fixture = Path(__file__).parent / "fixtures" / "hdmi_playback4.txt"
        parsed = parse_observation(fixture.read_text() + "\n<ONN_POLICY_POWER>\nmWakefulness=Awake\n")
        self.assertEqual(Activity.INACTIVE, parsed.activity)

    def test_player_freshness_and_idle_never_means_ended(self):
        sample = '<service><ms_state>paused</ms_state><ms_playing>abcdefghijk</ms_playing><ms_playing_at>{}</ms_playing_at><ms_position>2.5</ms_position></service>'
        parsed = parse_player(sample.format(NOW - 5000), NOW)
        self.assertEqual(Playing.PAUSED, parsed.playing)
        self.assertEqual(2.5, parsed.position_seconds)
        self.assertIsNone(parsed.duration_seconds)
        self.assertIsNone(parse_player(sample.format(NOW - 5001), NOW))
        self.assertIsNone(parse_player(sample.format(NOW + 1001), NOW))
        self.assertIsNone(parse_player("<service><state>idle</state></service>", NOW))
        self.assertEqual(Playing.UNKNOWN, parse_player(sample.replace("paused", "idle").format(NOW), NOW).playing)


if __name__ == "__main__":
    unittest.main()
