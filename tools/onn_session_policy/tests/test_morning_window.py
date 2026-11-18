"""Production controller transitions with only Android/CDP/native I/O replaced."""
import asyncio
from copy import deepcopy
from dataclasses import replace
from pathlib import Path
import tempfile
import unittest

from onn_session_policy.controller import Controller, Config, RecoveryMode, mono_ms, wall_ms
from onn_session_policy.media import CobaltMediaReader
from onn_session_policy.policy import (Authentication, FaultFence, Intent, InputKey, Observation, Phase,
    PolicyState, Recovery, Source, apply_status, begin_phone_key, guard_required, observer_proven,
    mark_phone_posted, parse_status, provenance_gap, recovery_context_allowed, recovery_reason)
from test_school_banner import ForwardBoundary, NativeBoundary, native_proof, status_payload

RUN = "12345678-1234-1234-1234-123456789abc"
VIDEO = "abcdefghijk"
PLAYER = "io.gh.yossim.tizentube.cobalt"


def starting(now=None, **changes):
    now = wall_ms() if now is None else now
    body = status_payload(now=now)
    body["session"]["id"] = "old-completed-run"
    body["school_banner"] = {"phase":"starting", "run_id":RUN, "video_id":"",
        "started_at_ms":now-1000, "startup_deadline_ms":now+299000, "fallback_at_ms":now+120000,
        "expires_at_ms":now+180000, "scheduled_admission":True, "pipeline_diagnostic":False,
        "diagnostic":False, "protection_ready":True, "overlay_visible":False,
        "candidate_video_id":"", "completed_at_ms":-1, "reason":"preparing_episode"}
    body["school_banner"].update(changes)
    return body


def ready_progress(start):
    return {"__youtube_history":{"schema":1,"source":"youtube_playlist",
        "playlist_id":"PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA", "signed_in":True,"complete":True,
        "captured_at_ms":start+100,"covered_ids":[VIDEO],"progress":{VIDEO:49}}}


def prior_phone_intent(now, wall, posted=True):
    old = parse_status(status_payload(banner_phase="inactive", now=wall))
    state = observer_proven(apply_status(PolicyState(), old, now, 0), now)
    state = begin_phone_key(state, InputKey(Source.PHONE, 7, 158, 1, wall-20000), now-20000)
    return mark_phone_posted(state, state.phone_generation) if posted else state


class MorningPhoneHandoffTests(unittest.TestCase):
    NOW = 50000
    WALL = 1800000000000

    def setUp(self):
        self.state = prior_phone_intent(self.NOW, self.WALL)
        self.snapshot = parse_status(starting(now=self.WALL))

    def apply(self, state=None, snapshot=None, **changes):
        state = self.state if state is None else state
        arguments = {"now_ms":self.NOW, "request_generation":state.phone_generation,
            "observer_generation":state.observer_generation,
            "request_posted_generation":state.posted_generation}
        arguments.update(changes)
        return apply_status(state, self.snapshot if snapshot is None else snapshot, **arguments)

    def test_acknowledged_previous_run_releases_new_startup_gate(self):
        blocked = self.apply(request_posted_generation=None)
        self.assertEqual(Intent.PENDING, blocked.intent)
        self.assertFalse(guard_required(blocked, self.NOW, self.WALL))
        result = self.apply()
        self.assertEqual(Intent.NONE, result.intent)
        self.assertIsNone(result.intent_session_id)
        self.assertTrue(guard_required(result, self.NOW, self.WALL))
        self.assertTrue(recovery_context_allowed(result, self.NOW, self.WALL))

    def test_post_and_response_order_must_both_be_proven(self):
        cases = (
            (replace(self.state, posted_generation=0), {}),
            (self.state, {"request_posted_generation":0}),
            (self.state, {"request_posted_generation":None}),
            (self.state, {"request_generation":0}),
            (self.state, {"observer_generation":None}),
        )
        for state, arguments in cases:
            with self.subTest(arguments=arguments, posted=state.posted_generation):
                result = self.apply(state, **arguments)
                self.assertEqual(Intent.PENDING, result.intent)
                self.assertFalse(guard_required(result, self.NOW, self.WALL))

    def test_same_run_and_phone_at_or_after_new_start_remain_terminal(self):
        states = (
            replace(self.state, intent_session_id=RUN),
            replace(self.state, phone_key_wall_ms=self.snapshot.school_banner.started_at_ms),
            replace(self.state, phone_key_wall_ms=self.snapshot.school_banner.started_at_ms+1),
            replace(self.state, phone_grace_until_ms=self.NOW+1),
        )
        for state in states:
            with self.subTest(state=state):
                self.assertEqual(Intent.PENDING, self.apply(state).intent)

    def test_only_explicit_matching_scheduled_start_can_handoff(self):
        b = self.snapshot.school_banner
        snapshots = (
            replace(self.snapshot, school_banner=None),
            replace(self.snapshot, school_banner=replace(b, scheduled_admission=False)),
            replace(self.snapshot, school_banner=replace(b, run_id="different-run")),
            replace(self.snapshot, school_banner=replace(b, phase="waiting")),
            parse_status(status_payload(banner_phase="inactive", now=self.WALL)),
            parse_status(starting(now=self.WALL, phase="active", reason="scheduled_fallback", overlay_visible=True)),
        )
        for snapshot in snapshots:
            with self.subTest(snapshot=snapshot):
                self.assertEqual(Intent.PENDING, self.apply(snapshot=snapshot).intent)

    def test_provenance_barrier_discards_inflight_startup_response(self):
        changed = provenance_gap(self.state)
        result = self.apply(changed, observer_generation=self.state.observer_generation)
        self.assertIs(changed, result)
        self.assertEqual(Intent.PENDING, self.apply(changed).intent)
        self.assertEqual(FaultFence.STOP_REQUIRED, self.apply(changed).fault_fence)

    def test_new_phone_after_request_prevents_handoff_even_after_its_post(self):
        changed = begin_phone_key(self.state, InputKey(Source.PHONE,7,158,1,self.WALL),self.NOW)
        changed = mark_phone_posted(changed, changed.phone_generation)
        result = self.apply(changed, now_ms=self.NOW+8000,
            request_generation=self.state.phone_generation,
            request_posted_generation=self.state.posted_generation)
        self.assertEqual(Intent.PENDING, result.intent)
        self.assertEqual(changed.phone_generation, result.phone_generation)


class MorningStatusTests(unittest.TestCase):
    def state(self, body):
        now=mono_ms()
        return observer_proven(apply_status(PolicyState(),parse_status(body),now,0),now)

    def test_starting_protects_without_fabricating_episode(self):
        raw=starting(); state=self.state(raw)
        self.assertEqual(Phase.MORNING_STARTING,state.snapshot.session.phase)
        self.assertEqual("",state.snapshot.session.video_id)
        self.assertEqual(RUN,state.snapshot.session.id)
        self.assertTrue(guard_required(state,mono_ms(),wall_ms()))
        self.assertTrue(recovery_context_allowed(state,mono_ms(),wall_ms()))
        self.assertEqual("completed",raw["session"]["phase"])

    def test_absence_and_admission_are_explicit(self):
        for changes in ({"scheduled_admission":False},{"video_id":VIDEO},
                {"startup_deadline_ms":wall_ms()+301000},{"overlay_visible":True}):
            with self.subTest(changes=changes), self.assertRaises(ValueError): parse_status(starting(**changes))
        raw=starting(protection_ready=False)
        raw["session"]["phase"]="active"; raw["session"]["id"]="other-authorized"
        self.assertEqual(Phase.ACTIVE,parse_status(raw).session.phase)
        raw=starting(); raw["session"]["phase"]="morning_starting"
        with self.assertRaises(ValueError): parse_status(raw)

    def test_unbound_fallback_is_separate_from_actual_end(self):
        raw=starting(phase="active",reason="scheduled_fallback",overlay_visible=True)
        state=self.state(raw)
        self.assertEqual(Phase.SCHOOL_BANNER,state.snapshot.session.phase)
        self.assertEqual("",state.snapshot.session.video_id)
        for field,value in (("reason","episode_ended"),("completed_at_ms",wall_ms()),("scheduled_admission",False)):
            bad=deepcopy(raw);bad["school_banner"][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):parse_status(bad)

    def test_phone_press_or_repeat_blocks_starting_immediately(self):
        for value in (1,2):
            state=self.state(starting());now=mono_ms()
            changed=begin_phone_key(state,InputKey(Source.PHONE,7,158,value,wall_ms()),now)
            self.assertEqual(Intent.PENDING,changed.intent)
            self.assertFalse(guard_required(changed,now,wall_ms()))
            self.assertFalse(recovery_context_allowed(changed,now,wall_ms()))

    def test_starting_foreground_recovery_preserves_helper_and_pin(self):
        state=self.state(starting())
        for foreground in (PLAYER,"dev.yossi.morningsesame","dev.yossi.onnkeyguard"):
            current=replace(state,snapshot=replace(state.snapshot,foreground_package=foreground))
            self.assertIsNone(recovery_reason(current,mono_ms(),wall_ms(),Observation(),player_package=PLAYER))
        netflix=replace(state,snapshot=replace(state.snapshot,foreground_package="com.netflix.ninja"))
        self.assertEqual(Recovery.PLAYBACK,recovery_reason(netflix,mono_ms(),wall_ms(),Observation(),player_package=PLAYER))
        pin=replace(netflix,snapshot=replace(netflix.snapshot,authentication=Authentication.REQUIRED))
        self.assertIsNone(recovery_reason(pin,mono_ms(),wall_ms(),Observation(),player_package=PLAYER))


class ClientBoundary:
    def __init__(self):self.events=[]
    async def event(self,event):self.events.append(deepcopy(event));return {"ok":True}


class MediaBoundary:
    def __init__(self):self.calls=[];self.startup=[];self.school=[]
    def morning_history(self,run,start,deadline):
        self.calls.append((run,start,deadline));return {"state":"ready","progress":ready_progress(start)}
    def set_morning_startup(self,deadline,video):self.startup.append((deadline,video));return True
    def set_school_banner(self,deadline,video,awaiting):self.school.append((deadline,video,awaiting));return True


class AndroidBoundary:
    def __init__(self):self.calls=[];self.status=None;self.prefix=["/not-used"]
    async def admin(self,command):
        self.calls.append(command)
        if command=="probe":return self.status
        if command=="morning_recover":return {"ok":True}
        raise AssertionError("unexpected credential/media operation: "+command)


class MorningControllerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory();self.client=ClientBoundary();self.android=AndroidBoundary()
        self.c=Controller(Config(Path(self.temp.name),"/not-used",recovery_mode=RecoveryMode.ENABLED),
            self.android,self.client)
        self.c.guard=NativeBoundary(mono_ms());self.c.debugger_forward=ForwardBoundary();self.c.media=MediaBoundary()
        self.install(starting())
    async def asyncTearDown(self):self.c.stop_requested.set();self.temp.cleanup()
    def install(self,raw):
        now=mono_ms();self.android.status=raw
        self.c.state=observer_proven(apply_status(self.c.state,parse_status(raw),now,self.c.state.phone_generation),now)
        self.c.guard.monitor=native_proof(now)
    async def test_status_loop_handoffs_acknowledged_old_run_to_starting(self):
        self.c.state=prior_phone_intent(mono_ms(),wall_ms())
        async def status():
            self.c.stop_requested.set()
            return parse_status(starting())
        self.client.status=status
        await self.c._status_loop()
        self.assertEqual(Intent.NONE,self.c.state.intent)
        self.assertTrue(guard_required(self.c.state,mono_ms(),wall_ms()))
        self.assertIsNotNone(self.c._morning_window(require_work=True))
    async def test_status_loop_does_not_retroactively_use_post_acknowledgement(self):
        self.c.state=prior_phone_intent(mono_ms(),wall_ms(),posted=False)
        async def status():
            self.c.state=mark_phone_posted(self.c.state,self.c.state.phone_generation)
            self.c.stop_requested.set()
            return parse_status(starting())
        self.client.status=status
        await self.c._status_loop()
        self.assertEqual(self.c.state.phone_generation,self.c.state.posted_generation)
        self.assertEqual(Intent.PENDING,self.c.state.intent)
        self.assertFalse(guard_required(self.c.state,mono_ms(),wall_ms()))
    async def test_recovery_probe_keeps_post_order_fence(self):
        self.c.state=prior_phone_intent(mono_ms(),wall_ms(),posted=False)
        async def probe(*args):
            self.c.state=mark_phone_posted(self.c.state,self.c.state.phone_generation)
            return starting()
        self.c._fenced_admin=probe
        await self.c._probe_recovery_status(lambda:True)
        self.assertEqual(Intent.PENDING,self.c.state.intent)
    async def test_visible_player_snapshot_is_published_with_exact_admitted_bounds(self):
        await self.c._publish_morning_history()
        event=self.client.events[-1]; b=self.c.state.snapshot.school_banner
        self.assertEqual("morning_history",event["type"]);self.assertEqual("ready",event["state"])
        self.assertEqual((RUN,b.started_at_ms,b.startup_deadline_ms),self.c.media.calls[0])
        self.assertEqual(RUN,event["run_id"]);self.assertEqual(b.started_at_ms,event["started_at_ms"])
        self.assertEqual(49,event["progress"]["__youtube_history"]["progress"][VIDEO])
    async def test_helper_foreground_does_not_consume_player_retry_window(self):
        raw=starting();raw["foreground_package"]="dev.yossi.morningsesame";self.install(raw)
        await self.c._publish_morning_history()
        self.assertEqual([],self.c.media.calls);self.assertEqual("unavailable",self.client.events[-1]["state"])
        self.assertNotIn("progress",self.client.events[-1])
    async def test_phone_during_cdp_cannot_publish_late_snapshot(self):
        async def cdp(function,*args):
            await self.c.on_key(InputKey(Source.PHONE,7,158,1,wall_ms()))
            return {"state":"ready","progress":ready_progress(args[1])}
        self.c._run_cdp=cdp
        await self.c._publish_morning_history()
        self.assertEqual([],self.client.events);self.assertEqual(Intent.PENDING,self.c.state.intent)
    async def test_startup_media_handoff_uses_only_signature_selected_candidate(self):
        await self.c._update_startup_lease()
        self.assertEqual("",self.c.media.startup[-1][1])
        self.assertLessEqual(self.c.media.startup[-1][0]-wall_ms(),12000)
        self.install(starting(candidate_video_id=VIDEO));await self.c._update_startup_lease()
        self.assertEqual(VIDEO,self.c.media.startup[-1][1])
        await self.c.on_key(InputKey(Source.PHONE,7,158,1,wall_ms()))
        await self.c._update_startup_lease()
        self.assertEqual((0,""),self.c.media.startup[-1])
    async def test_expired_work_does_not_end_pre_episode_protection(self):
        raw=starting(started_at_ms=wall_ms()-301000,startup_deadline_ms=wall_ms()-1000)
        self.install(raw);await self.c._publish_morning_history()
        self.assertEqual([],self.c.media.calls)
        self.assertTrue(guard_required(self.c.state,mono_ms(),wall_ms()))
    async def test_active_fallback_installs_empty_video_school_gate(self):
        self.install(starting(phase="active",reason="scheduled_fallback",overlay_visible=True))
        await self.c._update_school_lease()
        self.assertEqual(("",False),self.c.media.school[-1][1:])
    async def test_foreground_recovery_uses_no_pin_or_playback_override(self):
        raw=starting();raw["foreground_package"]="com.netflix.ninja";self.install(raw)
        await self.c._recover(Recovery.PLAYBACK)
        self.assertEqual(["probe","morning_recover"],self.android.calls)
        self.assertEqual("morning_player_restored",self.client.events[-1]["result"])


class MediaLeaseTests(unittest.TestCase):
    def test_unbound_school_lease_requires_active_mode(self):
        reader=CobaltMediaReader();expressions=[]
        reader._evaluate=lambda expression:(expressions.append(expression) or {"result":{"result":{"type":"boolean","value":True}}},1)
        self.assertTrue(reader.set_school_banner(wall_ms()+12000,"",False))
        with self.assertRaises(ValueError):reader.set_school_banner(wall_ms()+12000,"",True)
        self.assertTrue(reader.set_morning_startup(wall_ms()+12000,""))
        self.assertTrue(reader.set_morning_startup(0,""))
        with self.assertRaises(ValueError):reader.set_morning_startup(wall_ms()+16000,VIDEO)


if __name__=="__main__":unittest.main()
