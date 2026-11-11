#!/usr/bin/env python3
"""Explicit, finite live TV power/volume check. Never enters a PIN or launches media.

Requires the known Samsung TV already on and app rollout prepared. A protected
POWER_PROBE session expires after 60 seconds. Only the test's owned controller
is stopped. Persistent daily enforcement is never enabled.
"""
from __future__ import annotations

import argparse
import http.client
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from onn_session_policy.adapters import broadcast_json, read_token

PROBE_SUCCESS_RESULTS = frozenset(("power_probe_requested", "power_probe_confirmed"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-power-cycle", action="store_true")
    parser.add_argument("--power-only", action="store_true",
                        help="skip mute, volume and Assistant; exercise only normal sleep/recovery")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--tv-host", required=True)
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--native-path", required=True)
    args = parser.parse_args()
    if not args.allow_power_cycle:
        parser.error("--allow-power-cycle is required")
    if not re.fullmatch(r"192\.168\.\d{1,3}\.\d{1,3}", args.tv_host):
        parser.error("a previously verified local TV address is required")
    report = {"schema": 1, "test": "finite_prepared_power_probe", "checks": [],
              "scope": "power_only" if args.power_only else "power_volume_assistant",
              "limits": ["Assistant text query is not a spoken-command test",
                         "ADB volume input is not a physical or smartphone button",
                         "No protected profile authentication or streaming playback is attempted"]}
    controller = None
    controller_log = None
    token = None
    stage = "untouched"
    original = {}
    probe_id = "power-probe-" + str(time.time_ns())
    cli = [sys.executable, str(ROOT / "onn_session_controller.py"), "--serial", args.serial,
           "--state-dir", str(args.state_dir)]

    def execute(argv, timeout=6):
        result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, check=True)
        return result.stdout

    def shell(*argv, timeout=6):
        return execute(["adb", "-s", args.serial, "shell", *argv], timeout)

    def admin(command, **extras):
        argv = ["am", "broadcast", "-a", "dev.yossi.onnkeyguard.POLICY_ADMIN", "-n",
                "dev.yossi.onnkeyguard/.PolicyAdminReceiver", "--es", "command", command]
        for key, value in extras.items():
            argv += ["--ei" if type(value) is int else "--es", key, str(value)]
        return broadcast_json(shell(*argv))

    def status():
        conn = http.client.HTTPConnection("127.0.0.1", 8768, timeout=1)
        try:
            conn.request("GET", "/status", headers={"Authorization": "Bearer " + token,
                                                    "Connection": "close"})
            response = conn.getresponse()
            value = json.loads(response.read(65536))
            if response.status != 200:
                raise RuntimeError("status_unavailable")
            return value
        finally:
            conn.close()

    def tv_state():
        try:
            with urllib.request.urlopen("http://" + args.tv_host + ":8001/api/v2/", timeout=1) as response:
                device = json.load(response)["device"]
            if device.get("modelName") != "UN65RU7100FXZA":
                raise RuntimeError("tv_identity_changed")
            return device.get("PowerState", "unknown")
        except (OSError, ValueError, KeyError):
            return "unavailable"

    def rendering(action, value=None):
        argument = "" if value is None else "<Desired" + action[3:] + ">" + str(value) + "</Desired" + action[3:] + ">"
        body = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:' + action +
                ' xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID>'
                '<Channel>Master</Channel>' + argument + '</u:' + action + '></s:Body></s:Envelope>')
        request = urllib.request.Request("http://" + args.tv_host + ":9197/upnp/control/RenderingControl1",
            data=body.encode(), method="POST", headers={"Content-Type": 'text/xml; charset="utf-8"',
            "SOAPACTION": '"urn:schemas-upnp-org:service:RenderingControl:1#' + action + '"'})
        with urllib.request.urlopen(request, timeout=2) as response:
            tree = ET.fromstring(response.read(8192))
        if action.startswith("Get"):
            found = [node.text for node in tree.iter() if node.tag.rsplit("}", 1)[-1] == "Current" + action[3:]]
            if len(found) != 1:
                raise RuntimeError("rendering_response_invalid")
            return int(found[0])

    def music_volume():
        output = shell("cmd", "media_session", "volume", "--stream", "3", "--get")
        match = re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]", output)
        if match is None:
            raise RuntimeError("android_volume_response_invalid")
        return int(match.group(1))

    def check(name, condition, **details):
        report["checks"].append({"check": name, "result": "pass" if condition else "fail", **details})
        if not condition:
            raise RuntimeError(name)

    def wait(name, predicate, seconds=10):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if controller is not None and controller.poll() is not None:
                raise RuntimeError("owned_controller_exited_early")
            value = predicate()
            if value:
                check(name, True)
                return value
            time.sleep(0.15)
        raise RuntimeError(name + "_timeout")

    def observe_power(seconds):
        samples = []
        deadline = time.monotonic() + seconds
        phase = "waiting_off"
        while time.monotonic() < deadline:
            television = tv_state()
            power = shell("dumpsys", "power", timeout=3)
            match = re.search(r"mWakefulness=(\w+)", power)
            onn = match.group(1) if match else "unknown"
            samples.append({"wall_ms": time.time_ns() // 1_000_000, "tv": television, "onn": onn})
            if phase == "waiting_off" and (television in ("off", "standby") or onn == "Asleep"):
                phase = "waiting_on"
            elif phase == "waiting_on" and television == "on" and onn == "Awake":
                return "standby_then_on", samples
            time.sleep(0.2)
        return phase, samples

    def own_recovery_events():
        if controller_log is None:
            return []
        events = []
        for line in Path(controller_log.name).read_text(encoding="utf-8").splitlines():
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            if entry.get("event") == "recovery":
                events.append({key: entry.get(key) for key in ("event", "wall_ms", "result", "reason",
                    "operation", "failure_type", "exit_code", "output_status")})
        return events

    try:
        check("known_tv_initially_on", tv_state() == "on")
        initial = admin("probe")
        check("initial_prepared", initial.get("rollout") == "prepared")
        check("no_existing_active_session", initial.get("session", {}).get("phase")
              not in ("active", "phone_paused"))
        check("no_existing_live_observer", initial.get("input_observer_status") != "live")
        check("no_existing_control_socket", not (args.state_dir / "control.sock").exists())
        execute(cli + ["get-token"])
        token = read_token(args.state_dir / "controller.token")
        controller_log = open(args.state_dir / "power-probe-controller.jsonl", "w", encoding="utf-8")
        os.chmod(controller_log.name, 0o600)
        controller = subprocess.Popen(cli + ["run", "--native-path", args.native_path,
                                             "--recovery-mode", "enabled"],
                                      stdin=subprocess.DEVNULL, stdout=controller_log, stderr=controller_log)
        wait("observer_live", lambda: status().get("input_observer_status") == "live", 12)
        started = admin("power_probe", run_generation=probe_id,
                        video_id="EP2tuoBV184", duration_seconds=60)
        check("finite_power_probe_available", started.get("session_mode") == "power_probe"
              and started.get("rollout") == "prepared" and started.get("session", {}).get("phase") == "active"
              and started.get("session", {}).get("id") == probe_id
              and 0 < started["session"]["expires_at_ms"] - int(time.time() * 1000) <= 60000)
        stage = "power_probe_started"
        wait("native_grab_reported", lambda: status().get("native_lease_status") == "physical_grabbed", 8)
        original = {"tv_volume": rendering("GetVolume"), "tv_mute": rendering("GetMute"),
                    "android_music_volume": music_volume()}
        report["original"] = original
        if args.power_only:
            result = "not_requested"
            report["limits"].append("Power-only run sends no volume/mute or Assistant command")
            report["assistant_text_power"] = {"result": "not_requested"}
        else:
            stage = "tv_controls_changed"
            rendering("SetMute", 1)
            first = "KEYCODE_VOLUME_DOWN" if original["tv_volume"] > 0 and original["android_music_volume"] > 0 else "KEYCODE_VOLUME_UP"
            inverse = "KEYCODE_VOLUME_UP" if first == "KEYCODE_VOLUME_DOWN" else "KEYCODE_VOLUME_DOWN"
            shell("input", "keyevent", first)
            time.sleep(0.4)
            changed = {"tv_volume": rendering("GetVolume"), "android_music_volume": music_volume()}
            report["volume_after_key"] = changed
            shell("input", "keyevent", inverse)
            time.sleep(0.3)
            shell("cmd", "media_session", "volume", "--stream", "3", "--set", str(original["android_music_volume"]))
            time.sleep(0.3)
            rendering("SetVolume", original["tv_volume"])
            check("volume_control_responded", changed["tv_volume"] != original["tv_volume"]
                  or changed["android_music_volume"] != original["android_music_volume"])
            rendering("SetMute", 1)
            output = shell("am", "start", "-W", "-a", "android.intent.action.ASSIST", "-p",
                           "com.google.android.katniss", "--es", "query", "'turn off the TV'", timeout=8)
            report["assistant_launch_reported"] = "Status: ok" in output
            result, samples = observe_power(14)
            report["assistant_text_power"] = {"result": result, "samples": samples}
        if result != "standby_then_on":
            current = status()
            check("probe_still_active_for_sleep_test", current["session"]["phase"] == "active"
                  and current["session"]["expires_at_ms"] - int(time.time() * 1000) > 15000)
            if args.power_only:
                stage = "power_only_started"
            shell("input", "keyevent", "KEYCODE_SLEEP")
            result, samples = observe_power(14)
            report["normal_sleep_power"] = {"result": result, "samples": samples}
        check("observed_standby_recovered_automatically", result == "standby_then_on")
        report["explicit_tv_off_observed"] = any(sample["tv"] in ("off", "standby")
            for attempt in (report.get("assistant_text_power", {}), report.get("normal_sleep_power", {}))
            for sample in attempt.get("samples", []))
        check("explicit_tv_standby_observed", report["explicit_tv_off_observed"])
        wait("owned_controller_recorded_probe_recovery", lambda: any(
            entry["result"] in PROBE_SUCCESS_RESULTS for entry in own_recovery_events()), 8)
        report["controller_recoveries"] = own_recovery_events()
        wait("app_recorded_probe_recovery", lambda: status().get("last_recovery", {}).get("result")
             in PROBE_SUCCESS_RESULTS, 5)
        final = status()
        report["probe_final"] = {k: final.get(k) for k in ("rollout", "session_mode", "authentication", "last_recovery")}
        check("only_power_probe_recovery_reported", final.get("last_recovery", {}).get("result") in PROBE_SUCCESS_RESULTS)
        check("rollout_stayed_prepared", final.get("rollout") == "prepared")
        report["result"] = "pass"
    except BaseException as error:
        report["result"] = "fail"
        report["failure"] = str(error) if type(error) is RuntimeError else type(error).__name__
    finally:
        cleanup = {}
        if controller is not None:
            try:
                current = admin("status")
                if current.get("session", {}).get("id") == probe_id:
                    stopped = admin("override")
                    cleanup["own_probe_stopped"] = stopped.get("session", {}).get("phase") not in ("active", "phone_paused")
            except Exception as error:
                cleanup["probe_stop_error"] = type(error).__name__
            finally:
                if controller.poll() is None:
                    controller.terminate()
                    try:
                        controller.wait(timeout=8)
                    except subprocess.TimeoutExpired:
                        controller.kill()
                        controller.wait(timeout=3)
            cleanup["owned_controller_exited"] = controller.poll() is not None
        if stage in ("tv_controls_changed", "power_only_started"):
            try:
                shell("input", "keyevent", "KEYCODE_WAKEUP")
                shell("cmd", "hdmi_control", "onetouchplay")
                deadline = time.monotonic() + 8
                while tv_state() != "on" and time.monotonic() < deadline:
                    time.sleep(0.2)
                if tv_state() != "on":
                    raise RuntimeError("tv_on_for_restore_timeout")
                if stage == "tv_controls_changed":
                    if music_volume() != original["android_music_volume"]:
                        shell("cmd", "media_session", "volume", "--stream", "3", "--set", str(original["android_music_volume"]))
                        time.sleep(0.3)
                    if rendering("GetVolume") != original["tv_volume"]:
                        rendering("SetVolume", original["tv_volume"])
                    if rendering("GetMute") != original["tv_mute"]:
                        rendering("SetMute", original["tv_mute"])
                cleanup["volume_restored"] = rendering("GetVolume") == original["tv_volume"] and music_volume() == original["android_music_volume"]
                cleanup["mute_restored"] = rendering("GetMute") == original["tv_mute"]
            except Exception as error:
                cleanup["restore_error"] = type(error).__name__
        if controller_log is not None:
            controller_log.close()
        try:
            current = admin("status")
            cleanup["rollout"] = current.get("rollout")
            cleanup["session_phase"] = current.get("session", {}).get("phase")
        except Exception:
            cleanup["app_status"] = "unavailable"
        report["cleanup"] = cleanup
        if (cleanup.get("restore_error") or cleanup.get("probe_stop_error")
                or cleanup.get("rollout") != "prepared"
                or cleanup.get("session_phase") in ("active", "phone_paused")
                or (controller is not None and not cleanup.get("owned_controller_exited"))
                or (stage in ("tv_controls_changed", "power_only_started") and
                    (not cleanup.get("volume_restored") or not cleanup.get("mute_restored")))):
            report["result"] = "fail"
            report.setdefault("failure", "cleanup_incomplete")
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
    if report["result"] != "pass":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
