"""Coordinated, bounded Android input integration at a known PIN screen.

The caller must inspect the current UI and ensure DPAD_DOWN/UP can change and
restore focus without activating anything. No PIN entry, Enter, or Power is sent.
This uses ordinary shell sendevent only and stops if access is denied.
"""
import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path

from test_live import start, wait_event, send, stop


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--allow-pin-focus-check", action="store_true")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--binary", default="/data/local/tmp/onn-policy-20261110/onn_input_guard")
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    if not args.allow_pin_focus_check:
        parser.error("--allow-pin-focus-check requires prior UI inspection and coordination")
    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report = {"started_epoch_ms": int(time.time() * 1000), "status": "running",
              "verification": "real evdev writes through ordinary authenticated ADB shell",
              "keys_permitted": [103, 108], "lease_ttl_ms": 12000, "steps": [], "screenshots": {}}
    run = None
    phone_moved = False
    phone = None

    def adb(arguments, timeout=3):
        return subprocess.run([args.adb, "-s", args.serial, *arguments],
                              capture_output=True, timeout=timeout, check=True)

    def capture(label):
        data = adb(["exec-out", "screencap", "-p"]).stdout
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise AssertionError("screencap did not return PNG")
        path = report_path.parent / (report_path.stem + "-" + label + ".png")
        path.write_bytes(data)
        report["screenshots"][label] = {"path": str(path), "sha256": hashlib.sha256(data).hexdigest()}

    def key_pair(device, code):
        node = "/dev/input/event" + str(device["input_id"])
        held = False
        try:
            adb(["shell", "sendevent", node, "1", str(code), "1"])
            held = True
            adb(["shell", "sendevent", node, "0", "0", "0"])
        finally:
            if held:
                adb(["shell", "sendevent", node, "1", str(code), "0"])
                adb(["shell", "sendevent", node, "0", "0", "0"])
        down = wait_event(run, lambda e: e.get("kind") == "key" and
                          e.get("source") == device["source"] and
                          e.get("code") == code and e.get("value") == 1, timeout=1)
        up = wait_event(run, lambda e: e.get("kind") == "key" and
                        e.get("source") == device["source"] and
                        e.get("code") == code and e.get("value") == 0, timeout=1)
        report["steps"].append({"source": device["source"], "code": code, "down": down, "up": up})
        return down, up

    try:
        report["binary_sha256"] = adb(["shell", "sha256sum", args.binary]).stdout.decode().split()[0]
        capture("before")
        run = start(args.adb, args.serial, args.binary)
        wait_event(run, lambda e: e.get("kind") == "ready")
        wait_event(run, lambda e: e.get("kind") == "device" and e.get("source") == "phone")
        devices = [e for e in run["events"] if e.get("kind") == "device"]
        physicals = [d for d in devices if d["source"] == "physical"]
        phones = [d for d in devices if d["source"] == "phone"]
        assert len(physicals) == len(phones) == 1, "One known physical and phone source required"
        physical, phone = physicals[0], phones[0]
        assert (physical["name"], physical["bus"], physical["vendor"], physical["product"]) == (
            "Onn-Remote Keyboard", 5, 0x0957, 0x0031)
        assert (phone["name"], phone["bus"], phone["vendor"], phone["product"]) == (
            "virtual-remote", 6, 0x18d1, 0x0100)
        report["identities"] = [physical, phone]
        send(run, f"ARM 12000 {int(time.time() * 1000) + 20000}")
        wait_event(run, lambda e: e.get("kind") == "grabbed", timeout=3)
        down, up = key_pair(physical, 108)
        assert down["action"] == up["action"] == "blocked", "Physical input must be blocked"
        capture("physical-down")
        down, up = key_pair(phone, 108)
        phone_moved = True
        assert down["action"] == up["action"] == "observed", "Phone input must pass through"
        capture("phone-down")
        down, up = key_pair(phone, 103)
        phone_moved = False
        assert down["action"] == up["action"] == "observed", "Phone restore must pass through"
        capture("restored")
        report["status"] = "input_events_passed_visual_review_required"
    except subprocess.CalledProcessError as error:
        report["status"] = "blocked_by_command_failure"
        report["failed_command"] = error.cmd
        report["returncode"] = error.returncode
        report["stderr"] = error.stderr.decode(errors="replace")
        report["stdout"] = error.stdout.decode(errors="replace")
    except BaseException as error:
        report["status"] = "failed"
        report["error"] = str(error)
    finally:
        if run:
            try:
                if phone_moved and phone is not None:
                    key_pair(phone, 103)
                    phone_moved = False
                send(run, "DISARM")
                send(run, "QUIT")
                wait_event(run, lambda e: e.get("kind") == "stopped", timeout=3)
                report["helper_exit_code"] = run["process"].wait(timeout=2)
            except BaseException as error:
                report["cleanup_error"] = str(error)
            finally:
                report["events"] = run["events"]
                stop(run)
        try:
            result = adb(["shell", "-T", args.binary, "--probe"], timeout=5)
            report["post_exit_probe"] = [json.loads(line) for line in result.stdout.decode().splitlines()]
            assert any(e.get("result") == "passed_and_released" for e in report["post_exit_probe"])
        except BaseException as error:
            report["post_exit_probe_error"] = str(error)
        report["phone_focus_restore_pending"] = phone_moved
        report["finished_epoch_ms"] = int(time.time() * 1000)
        report_path.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
