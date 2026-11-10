#!/usr/bin/env python3
"""Opt-in, bounded production-wiring check on the owner's Onn.

This sends no Android keys, PIN input, volume, power, input-selection, or media
launch commands. Two <=10-second TEST sessions may briefly grab the physical
remote. Their only policy inputs are explicitly simulated HTTP events and the
protected assistant override. It does not establish real-button behavior.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from onn_session_policy.adapters import broadcast_json, read_token  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-bounded-grab", action="store_true")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--native-path", required=True)
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if not args.allow_bounded_grab:
        parser.error("explicit --allow-bounded-grab is required")
    args.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(args.state_dir, 0o700)
    cli = [sys.executable, str(ROOT / "onn_session_controller.py"), "--serial", args.serial,
           "--state-dir", str(args.state_dir)]
    report = {"schema": 1, "test": "prepared_production_wiring", "checks": [],
              "limits": ["No real hardware or smartphone keypress tested", "No volume change tested",
                         "No power/input/playback recovery tested", "No PIN entered",
                         "No streaming-app audio adapter tested"]}
    controller = None
    log_file = None
    token = None
    initial = None
    test_stage = "untouched"

    def execute(command: list[str], timeout: float = 8) -> str:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, timeout=timeout, check=True)
        return result.stdout

    def admin(command: str, **extras) -> dict:
        argv = ["adb", "-s", args.serial, "shell", "am", "broadcast", "-a",
                "dev.yossi.onnkeyguard.POLICY_ADMIN", "-n",
                "dev.yossi.onnkeyguard/.PolicyAdminReceiver", "--es", "command", command]
        for key, value in extras.items():
            argv += ["--ei" if type(value) is int else "--es", key, str(value)]
        return broadcast_json(execute(argv))

    def http_request(method="GET", path="/status", value=None, authorized=True):
        connection = http.client.HTTPConnection("127.0.0.1", 8768, timeout=2)
        try:
            headers = {"Content-Type": "application/json", "Connection": "close"}
            if authorized:
                headers["Authorization"] = "Bearer " + token
            body = None if value is None else json.dumps(value, separators=(",", ":"))
            connection.request(method, path, body, headers)
            response = connection.getresponse()
            payload = response.read(65537)
            if len(payload) > 65536:
                raise RuntimeError("oversized_response")
            return response.status, json.loads(payload)
        finally:
            connection.close()

    def status():
        code, data = http_request()
        if code != 200:
            raise RuntimeError("status_http_" + str(code))
        return data

    def check(label, condition, **details):
        if not condition:
            raise RuntimeError(label)
        report["checks"].append({"check": label, "result": "pass", **details})

    def wait(label, predicate, seconds=8):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                check(label, True)
                return value
            time.sleep(0.1)
        raise RuntimeError(label + "_timeout")

    def armed():
        data = status()
        return data if data.get("native_lease_status") == "physical_grabbed" else None

    try:
        initial = admin("probe")
        check("initial_prepared", initial.get("rollout") == "prepared")
        check("no_existing_active_session", initial.get("session", {}).get("phase")
              not in ("active", "phone_paused"))
        result = json.loads(execute(cli + ["get-token"]))
        check("private_token_acquired", result.get("result") == "token_saved")
        token = read_token(args.state_dir / "controller.token")
        code, _ = http_request(authorized=False)
        check("unauthenticated_http_rejected", code == 401, http_status=code)
        check("authenticated_http_available", status().get("schema") == 1)
        log_file = open(args.state_dir / "integration-controller.jsonl", "w", encoding="utf-8")
        os.chmod(log_file.name, 0o600)
        controller = subprocess.Popen(cli + ["run", "--native-path", args.native_path,
                                             "--recovery-mode", "observe"],
                                      stdin=subprocess.DEVNULL, stdout=log_file, stderr=log_file)
        wait("private_control_socket_started", lambda: (args.state_dir / "control.sock").exists())
        # The passive observer must be live before enabling any physical grab.
        wait("passive_native_observer_ready", lambda: status().get("input_observer_status") == "live",
             seconds=12)
        check("prepared_has_no_live_session", status().get("session", {}).get("phase")
              not in ("active", "phone_paused"))
        check("rollout_remains_prepared", status().get("rollout") == "prepared")
        test_id = "wiring-phone-" + str(time.time_ns())
        test_stage = "test_requested"
        started = admin("test_session", run_generation=test_id, video_id="EP2tuoBV184", duration_seconds=10)
        check("bounded_test_session_started", started.get("session_mode") == "test"
              and started.get("rollout") == "prepared"
              and started.get("session", {}).get("id") == test_id
              and started["session"]["expires_at_ms"] - int(time.time() * 1000) <= 10000)
        wait("native_physical_grab_reported", armed)
        code, result = http_request("POST", "/event", {"type": "phone_key", "linux_code": 116,
                                                "value": 1, "wall_ms": int(time.time() * 1000)})
        check("simulated_phone_power_policy_accepted", code == 200)
        wait("simulated_phone_power_stops_session", lambda: status().get("session", {}).get("phase")
             == "phone_stopped")
        wait("native_release_reported_after_phone_policy_stop", lambda: status().get("native_lease_status")
             in ("monitoring", "disarmed", "stopped"))
        test_id = "wiring-assistant-" + str(time.time_ns())
        started = admin("test_session", run_generation=test_id, video_id="EP2tuoBV184", duration_seconds=10)
        check("second_bounded_test_session_started", started.get("session", {}).get("id") == test_id)
        wait("second_native_grab_reported", armed)
        override = json.loads(execute(cli + ["override"]))
        check("assistant_override_terminal", override.get("session", {}).get("reason") == "assistant_override")
        wait("native_release_reported_after_assistant_override", lambda: status().get("native_lease_status")
             in ("monitoring", "disarmed", "stopped"))
        report["result"] = "pass"
    except BaseException as exc:
        report["result"] = "fail"
        # Never copy raw subprocess output or HTTP content into evidence.
        report["failure"] = str(exc) if type(exc) is RuntimeError else type(exc).__name__
    finally:
        cleanup = {}
        if test_stage == "test_requested":
            try:
                cleanup["test_override_acknowledged"] = (
                    admin("override").get("session", {}).get("phase") not in ("active", "phone_paused"))
            except Exception:
                cleanup["test_override_acknowledged"] = False
        if controller is not None:
            try:
                execute(cli + ["stop"])
                controller.wait(timeout=5)
            except Exception:
                if controller.poll() is None:
                    controller.terminate()
                    try:
                        controller.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        controller.kill()
                        controller.wait(timeout=3)
            cleanup["owned_controller_exited"] = controller.poll() is not None
            cleanup["control_socket_removed"] = not (args.state_dir / "control.sock").exists()
        if log_file is not None:
            log_file.close()
            log_path = Path(log_file.name)
            cleanup["controller_log_sha256"] = hashlib.sha256(log_path.read_bytes()).hexdigest()
        try:
            cleanup["final_policy"] = admin("probe")
            # Administrative status never includes the bearer token.
            cleanup["final_policy"].pop("token", None)
            cleanup["prepared_restored"] = cleanup["final_policy"].get("rollout") == "prepared"
        except Exception:
            cleanup["final_policy"] = "unavailable"
        report["cleanup"] = cleanup
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
    if report["result"] != "pass" or not report["cleanup"].get("prepared_restored", False):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
