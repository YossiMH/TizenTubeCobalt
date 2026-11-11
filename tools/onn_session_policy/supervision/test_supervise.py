import importlib.util
import json
import os
from pathlib import Path
import subprocess
import socket
import shutil
import sys
import tempfile
import time
import unittest


MODULE = Path(__file__).with_name("supervise.py")
spec = importlib.util.spec_from_file_location("onn_owned_supervision", MODULE)
service = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = service
spec.loader.exec_module(service)


def configuration(directory):
    root = Path(directory)
    return {"schema":1, "activation":"disabled", "python":sys.executable,
            "entry":"/not-installed/controller.py", "adb":"/not-installed/adb",
            "serial":"emulator-5554", "native":"/data/local/tmp/not-installed",
            "controller_state":str(root/"controller-state"), "token_file":str(root/"token"),
            "supervisor_state":str(root/"supervisor-state"), "recovery_mode":"observe", "media_source":"auto"}


class SupervisionTests(unittest.TestCase):
    def test_sigterm_during_real_popen_survives_running_and_failure_transitions(self):
        try:
            probe=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);probe.close()
        except PermissionError:
            self.skipTest("host policy forbids AF_UNIX; run signal integration on Termux")
        adb=shutil.which('adb')
        if adb is None:self.skipTest('authorized real ADB transport required')
        ready=subprocess.run([adb,'-s','emulator-5554','get-state'],capture_output=True,timeout=5)
        if ready.returncode or ready.stdout.strip()!=b'device':self.skipTest('authorized real ADB transport required')
        # The audit callback is a real interpreter hook at the Popen boundary.
        # It sends a real SIGTERM after the production pre-spawn check, before
        # Popen returns or raises. No supervisor method is replaced.
        runner=r'''
import importlib.util,os,pathlib,signal,sys
spec=importlib.util.spec_from_file_location('signal_supervision',sys.argv[1])
m=importlib.util.module_from_spec(spec);sys.modules[spec.name]=m;spec.loader.exec_module(m)
config_path=pathlib.Path(sys.argv[2]);config=m.load_config(config_path)
def audit(name,args):
 if name=='subprocess.Popen' and config.entry in args[1]:
  os.kill(os.getpid(),signal.SIGTERM)
sys.addaudithook(audit)
m.Supervisor(config,config_path).run()
'''
        for spawn in ('success','failure'):
            with self.subTest(spawn=spawn), tempfile.TemporaryDirectory() as directory:
                root=Path(directory);value=configuration(directory);value['activation']='enabled'
                child=root/'owned-child.py';child.write_text('import time;time.sleep(30)\n')
                value['adb']=adb;value['entry']=str(child)
                if spawn=='failure':value['python']='/nonexistent/no-python'
                token=root/'token';token.write_text('test-only');token.chmod(0o600)
                config=root/'config.json';config.write_text(json.dumps(value));config.chmod(0o600)
                result=subprocess.run([sys.executable,'-c',runner,str(MODULE),str(config)],
                                      capture_output=True,text=True,timeout=4)
                self.assertEqual(0,result.returncode,result.stderr)
                state=json.loads((root/'supervisor-state/status.json').read_text())
                self.assertEqual('stopping',state['phase'])
                self.assertEqual(0,state['starts'])
                self.assertFalse((root/'supervisor-state/supervisor.sock').exists())

    def test_isolated_supervisor_single_instance_control_and_adb_backoff(self):
        try:
            probe=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM); probe.close()
        except PermissionError:
            self.skipTest("host policy forbids AF_UNIX; run this integration test on Termux")
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); value=configuration(directory); value["activation"]="enabled"
            token=root/"token"; token.write_text("test-only-not-a-controller-token"); token.chmod(0o600)
            path=root/"config.json"; path.write_text(json.dumps(value)); path.chmod(0o600)
            command=[sys.executable,str(MODULE),"--config",str(path)]
            process=subprocess.Popen([*command,"run"],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            try:
                control=Path(value["supervisor_state"])/"supervisor.sock"
                for _ in range(100):
                    if control.exists():break
                    self.assertIsNone(process.poll())
                    time.sleep(0.01)
                status=subprocess.run([*command,"status"],capture_output=True,text=True,timeout=3)
                self.assertEqual(0,status.returncode,status.stderr)
                self.assertEqual(0,json.loads(status.stdout)["starts"])
                duplicate=subprocess.run([*command,"run"],capture_output=True,text=True,timeout=3)
                self.assertEqual(1,duplicate.returncode)
                self.assertEqual("supervisor_already_running",json.loads(duplicate.stdout)["error"])
                stopped=subprocess.run([*command,"stop"],capture_output=True,text=True,timeout=3)
                self.assertEqual(0,stopped.returncode)
                process.communicate(timeout=3)
                self.assertEqual(0,process.returncode)
                self.assertFalse(control.exists())
                audit=(Path(value["supervisor_state"])/"service.jsonl").read_text()
                self.assertIn('"event":"adb_unavailable"',audit)
                self.assertNotIn('"event":"controller_started"',audit)
            finally:
                if process.poll() is None:process.terminate();process.communicate(timeout=3)

    def test_disabled_run_cannot_start_controller_or_touch_runtime_state(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"config.json"
            path.write_text(json.dumps(configuration(directory))); path.chmod(0o600)
            result = subprocess.run([sys.executable,str(MODULE),"--config",str(path),"run"],
                                    capture_output=True,text=True,timeout=3)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertEqual("disabled",json.loads(result.stdout)["result"])
            self.assertFalse((Path(directory)/"supervisor-state").exists())
            self.assertFalse((Path(directory)/"controller-state").exists())

    def test_private_config_and_strict_modes_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"config.json"
            value = configuration(directory)
            path.write_text(json.dumps(value)); path.chmod(0o644)
            with self.assertRaises(service.ServiceError):service.load_config(path)
            path.chmod(0o600)
            for key, invalid in (("activation","yes"),("entry","relative.py"),("recovery_mode","maybe")):
                path.write_text(json.dumps({**value,key:invalid}))
                with self.assertRaises((service.ServiceError,ValueError)):service.load_config(path)

    def test_crash_backoff_is_bounded_and_nonzero(self):
        self.assertEqual([5,10,20,40,60,60],[service.backoff(i) for i in range(1,7)])
        self.assertEqual(60,service.backoff(1_000_000))

    def test_log_rotation_total_size_and_untrusted_output_are_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            log = service.BoundedLog(Path(directory))
            for _ in range(1600):log.write({"event":"sample","reason":"x"*1000,"token":"never-save-this"})
            log.child_line(b'Authorization: never-save-this')
            log.child_line(b'{"event":"sample","token":"never-save-this","unexpected":{"body":"secret"}}')
            paths = list(Path(directory).iterdir())
            self.assertLessEqual(len(paths),3)
            self.assertLessEqual(sum(p.stat().st_size for p in paths),3*log.LIMIT)
            for path in paths:
                self.assertNotIn("never-save-this",path.read_text())
                self.assertEqual(0o600,path.stat().st_mode&0o777)

    def test_owned_child_stops_normally_and_reaps_handle(self):
        child = service.OwnedChild([sys.executable,"-c","import time; time.sleep(30)"])
        child.stop(grace=1)
        self.assertIsNotNone(child.process.poll())
        self.assertTrue(child.process.stdout.closed)

    def test_noncooperative_owned_child_is_bounded_and_killed(self):
        child = service.OwnedChild([sys.executable,"-u","-c",
            "import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); print('ready',flush=True); time.sleep(30)"])
        self.assertEqual(b"ready\n",child.process.stdout.readline())
        child.stop(grace=0.1)
        self.assertEqual(-9,child.process.returncode)


if __name__ == "__main__":unittest.main()
