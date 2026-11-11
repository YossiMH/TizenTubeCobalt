"""Real local protocol pipes + HTTP. No Android device, key injection or mocks."""
import asyncio
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

from onn_session_policy.adapters import Android, GuardPhase, SessionClient
from onn_session_policy.controller import Config, Controller, mono_ms, wall_ms
from onn_session_policy.native_protocol import event, requested, started
from onn_session_policy.policy import Intent, Recovery, apply_status, observer_proven, parse_status
from test_adapters import LocalServer, TOKEN


def production_harness(directory):
    configured=os.environ.get('ONN_DRAIN_HARNESS')
    if configured:
        return configured
    root=Path(__file__).resolve().parents[1]
    source=root/'native/tests/test_drain.c'
    compiler=shutil.which('cc') or shutil.which('clang')
    if not compiler or not source.is_file():
        raise unittest.SkipTest('provide ONN_DRAIN_HARNESS or a compiler and native test sources')
    binary=Path(directory)/'test_drain'
    subprocess.run([compiler,'-std=c11','-Wall','-Wextra','-Werror',str(source),
                    str(root/'native/policy.c'),'-o',str(binary)],check=True,timeout=15)
    return str(binary)


class RecoveryBarrierTests(unittest.IsolatedAsyncioTestCase):
    async def test_queued_phone_power_after_status_cancels_before_first_wake(self):
        await self._queued_phone_power_cancels(Recovery.POWER)

    async def test_queued_phone_power_cancels_before_media_recover(self):
        await self._queued_phone_power_cancels(Recovery.PLAYBACK)

    async def _queued_phone_power_cancels(self, reason):
        with tempfile.TemporaryDirectory() as directory, LocalServer() as server:
            controller=Controller(Config(Path(directory),'/data/local/tmp/not-used'),
                                  Android('/nonexistent/no-device-adb'),SessionClient(TOKEN,server.port))
            process=await asyncio.create_subprocess_exec(production_harness(directory),'--protocol-stream',
                stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE)
            guard=controller.guard;guard._process=process;guard.phase=GuardPhase.RUNNING
            now=mono_ms()
            guard.monitor=event(started(now),{'kind':'device','source':'phone','input_id':7,
                'name':'virtual-remote','bus':6,'vendor':0x18d1,'product':0x0100},now).state
            guard.monitor=event(requested(guard.monitor,now),{'kind':'status','lease':'disarmed',
                'volume':'absent','boottime_ms':0},now).state
            snapshot=parse_status({'schema':1,'rollout':'enabled','session_mode':'scheduled',
                'authentication':'clear','native_lease_status':'monitoring','foreground_package':None,
                'session':{'id':'local-race','video_id':'abcdefghijk','phase':'active',
                           'expires_at_ms':wall_ms()+60000,'duration_seconds':None,'position_seconds':None}})
            controller.state=observer_proven(apply_status(controller.state,snapshot,now,0),now)
            guard._reader=asyncio.create_task(guard._read(process))
            phone_loop=asyncio.create_task(controller._phone_loop())
            try:
                # Let the real reader consume the capability line; no input is
                # available until the recovery issues its ordered drain command.
                for _ in range(1000):
                    if guard._drain_capability.value=='supported':break
                    await asyncio.sleep(0.001)
                self.assertEqual('supported',guard._drain_capability.value)
                task=asyncio.create_task(controller._recover(reason))
                controller.recovery_task=task
                await asyncio.wait_for(asyncio.gather(task,return_exceptions=True),2)
                self.assertEqual(Intent.POWER_STOP,controller.state.intent)
                await asyncio.wait_for(controller.phone_outbox.join(),2)
                self.assertTrue(any(e.get('type')=='phone_key' and e.get('linux_code')==116 for e in server.events))
                # The unavailable adb executable would produce result=failed if
                # wake was attempted. Cancellation must precede every OS effect.
                self.assertFalse(any(e.get('type')=='recovery' and e.get('result')=='failed' for e in server.events))
            finally:
                controller.stop_requested.set();phone_loop.cancel()
                await asyncio.gather(phone_loop,return_exceptions=True)
                await guard.close()
