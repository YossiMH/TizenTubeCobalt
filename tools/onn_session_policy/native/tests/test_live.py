"""Real Android integration checks; run in Termux with authenticated local ADB.

No keypresses are generated. The explicit flag permits three brief hardware
grabs; every command has a deadline and every child is reaped in finally.
"""
import argparse
import json
import os
import selectors
import subprocess
import time
from pathlib import Path


def start(adb, serial, binary):
    process = subprocess.Popen(
        [adb, "-s", serial, "shell", "-T", binary, "--run"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=0,
    )
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    return {"process": process, "selector": selector, "pending": b"", "events": []}


def wait_event(run, predicate, timeout=4):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        while b"\n" in run["pending"]:
            line, run["pending"] = run["pending"].split(b"\n", 1)
            event = json.loads(line)
            run["events"].append(event)
            if predicate(event):
                return event
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        if run["selector"].select(remaining):
            chunk = os.read(run["process"].stdout.fileno(), 65536)
            if not chunk:
                break
            run["pending"] += chunk
    raise AssertionError(f"Expected helper event was not received: {run['events']}")


def send(run, command):
    run["process"].stdin.write((command + "\n").encode())
    run["process"].stdin.flush()


def arm(run, ttl):
    send(run, f"ARM {ttl} {int(time.time() * 1000) + 10000}")
    wait_event(run, lambda event: event.get("kind") == "grabbed")


def stop(run):
    process = run["process"]
    try:
        if process.stdin and not process.stdin.closed:
            process.stdin.close()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
    finally:
        run["selector"].close()
        process.stdout.close()


def check_exit(run):
    wait_event(run, lambda event: event.get("kind") == "stopped")
    assert run["process"].wait(timeout=3) == 0
    assert any(event.get("kind") == "released" for event in run["events"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--allow-bounded-grab", action="store_true")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--binary", default="/data/local/tmp/onn-policy-20261110/onn_input_guard")
    parser.add_argument("--report", required=True)
    parser.add_argument("--scenario", nargs="+", choices=("lease_expiry", "stdin_eof", "sigterm"),
                        default=("lease_expiry", "stdin_eof", "sigterm"))
    args = parser.parse_args()
    if not args.allow_bounded_grab:
        parser.error("--allow-bounded-grab is required")
    checksum = subprocess.run([args.adb, "-s", args.serial, "shell", "sha256sum", args.binary],
                              check=True, timeout=3, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE).stdout.split()[0]
    report = {"started_epoch_ms": int(time.time() * 1000), "binary_sha256": checksum, "cases": []}
    for scenario in args.scenario:
        run = start(args.adb, args.serial, args.binary)
        case = {"scenario": scenario, "events": run["events"], "status": "running"}
        report["cases"].append(case)
        try:
            ready = wait_event(run, lambda event: event.get("kind") == "ready")
            arm(run, 1200 if scenario == "lease_expiry" else 2500)
            if scenario == "lease_expiry":
                wait_event(run, lambda event: event.get("kind") == "disarmed"
                           and event.get("reason") == "lease_expired")
                send(run, "STATUS")
                status = wait_event(run, lambda event: event.get("kind") == "status")
                assert status["lease"] == "disarmed"
                run["process"].stdin.close()
            elif scenario == "stdin_eof":
                run["process"].stdin.close()
            else:
                pid = ready["pid"]
                assert isinstance(pid, int) and pid > 1
                subprocess.run([args.adb, "-s", args.serial, "shell", "kill", "-TERM", str(pid)],
                               check=True, timeout=3, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            check_exit(run)
            case["status"] = "passed"
        except BaseException as error:
            case["status"] = "failed"
            case["error"] = str(error)
            raise
        finally:
            stop(run)
            Path(args.report).write_text(json.dumps(report, indent=2) + "\n")
    # A fresh real grab after all exits proves no earlier process retained one.
    result = subprocess.run([args.adb, "-s", args.serial, "shell", "-T", args.binary, "--probe"],
                            check=True, timeout=5, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE)
    report["post_exit_probe"] = [json.loads(line) for line in result.stdout.splitlines()]
    assert any(event.get("result") == "passed_and_released" for event in report["post_exit_probe"])
    assert any(event.get("result") == "created_ready_destroyed" for event in report["post_exit_probe"])
    report["finished_epoch_ms"] = int(time.time() * 1000)
    Path(args.report).write_text(json.dumps(report, indent=2) + "\n")
    print("PASS: real kernel " + ", ".join(args.scenario) + ", fresh post-exit grab")


if __name__ == "__main__":
    main()
