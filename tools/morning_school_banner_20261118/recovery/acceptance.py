#!/usr/bin/env python3
"""Bounded Onn acceptance checks; run inside the existing Termux account.

inspect is read-only. start leaves a <=60-second diagnostic visible for a
separate screenshot; dismiss uses an authenticated synthetic phone_key event.
expiry starts a separate bounded diagnostic and checks natural release.
Use a new receipt for each start/expiry; reuse its receipt only for dismiss.
No pairing, configuration, power, input, or real-episode controls are changed.
"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import socket
import stat
import subprocess
import sys
import time


PLAYER_PATH = "/1fe2154764b819ad81036d04882cca104f3c28787.js"
PLAYER_SHA256 = "b42d6c054448ea37d691751466aee224b77d8b29d89e81f7a2a4df99ad589b11"
MESSAGE = "No more TV, Bernie -- it's time to leave for school!"
IDLE_SESSIONS = {"inactive", "completed", "phone_stopped"}
IDLE_BANNERS = {"inactive", "dismissed", "expired", "skipped"}
SESSION_FIELDS = ("id", "video_id", "phase", "expires_at_ms", "duration_seconds", "reason")
STATUS_FIELDS = ("schema", "rollout", "session_mode", "readiness", "server_status",
                 "input_observer_status", "input_observer_age_ms", "input_activation_status",
                 "native_lease_status", "foreground_package")
CONTROLLER_FIELDS = ("schema", "controller", "state_source", "session_phase", "session_id",
                     "native_process", "native_lease", "native_physical_grabs", "native_health",
                     "input_observer", "phone_intent", "provenance_fence", "recovery_in_progress")


class CheckFailed(Exception):
    """Only fixed, nonsecret classifications are allowed in the receipt."""


def now_ms():
    return int(time.time() * 1000)


def require(condition, reason):
    if not condition:
        raise CheckFailed(reason)


def read_token(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
                and not info.st_mode & 0o077, "private_token_file_required")
        token = os.read(fd, 256).decode("ascii").strip()
    finally:
        os.close(fd)
    require(bool(re.fullmatch(r"[0-9a-fA-F]{64}", token)), "invalid_token_file")
    return token


class Client:
    def __init__(self, state_dir):
        self.state_dir = state_dir
        self.token = read_token(state_dir / "controller.token")

    def http(self, method, path, event=None, limit=65536):
        connection = http.client.HTTPConnection("127.0.0.1", 8768, timeout=2)
        headers = {"Authorization": "Bearer " + self.token}
        body = None if event is None else json.dumps(event).encode("ascii")
        if body is not None:
            headers["Content-Type"] = "application/json"
        try:
            connection.request(method, path, body, headers)
            response = connection.getresponse()
            data = response.read(limit + 1)
            require(response.status == 200, "http_status_not_200")
            require(len(data) <= limit, "http_response_too_large")
            return data, response.getheader("ETag")
        finally:
            connection.close()

    def json(self, path, event=None):
        data, _ = self.http("GET" if event is None else "POST", path, event)
        value = json.loads(data)
        require(isinstance(value, dict) and value.get("schema") == 1, "invalid_json_response")
        return value

    def controller(self):
        with socket.socket(socket.AF_UNIX) as control:
            control.settimeout(2)
            control.connect(str(self.state_dir / "control.sock"))
            control.sendall(b"status\n")
            data = b""
            while b"\n" not in data and len(data) <= 65536:
                part = control.recv(min(4096, 65537 - len(data)))
                if not part:
                    break
                data += part
        require(len(data) <= 65536, "controller_response_too_large")
        value = json.loads(data)
        require(isinstance(value, dict) and value.get("schema") == 1, "invalid_controller_response")
        return {key: value.get(key) for key in CONTROLLER_FIELDS}

    def state(self):
        raw = self.json("/status")
        require(isinstance(raw.get("session"), dict), "canonical_session_missing")
        require(isinstance(raw.get("school_banner"), dict), "banner_patch_missing")
        canonical = {key: raw.get(key) for key in STATUS_FIELDS}
        canonical["session"] = {key: raw["session"].get(key) for key in SESSION_FIELDS}
        canonical["school_banner"] = raw["school_banner"]
        return {"canonical": canonical, "controller": self.controller()}

    def asset(self):
        for query in ("", "?v=20261118.1"):
            data, etag = self.http("GET", PLAYER_PATH + query, limit=1048576)
            require(hashlib.sha256(data).hexdigest() == PLAYER_SHA256, "player_digest_mismatch")
            require(etag == '"' + PLAYER_SHA256 + '"', "player_etag_mismatch")
        return {"path": PLAYER_PATH, "sha256": PLAYER_SHA256,
                "etag_verified": True, "versioned_route_verified": True, "bytes": len(data)}


def idle(state):
    canonical, controller = state["canonical"], state["controller"]
    banner = canonical["school_banner"]
    require(canonical["session"]["phase"] in IDLE_SESSIONS, "real_episode_not_idle")
    require(banner.get("phase") in IDLE_BANNERS and not banner.get("diagnostic")
            and not banner.get("overlay_visible"), "existing_banner_present")
    require(controller["session_phase"] in IDLE_SESSIONS, "controller_session_not_idle")
    require(controller["native_lease"] == "disarmed"
            and controller["native_physical_grabs"] == [], "existing_native_lease_present")
    require(not controller["recovery_in_progress"], "recovery_in_progress")


def proof_live(state):
    canonical, controller = state["canonical"], state["controller"]
    age = canonical["input_observer_age_ms"]
    return (canonical["input_observer_status"] == "live" and type(age) is int and 0 <= age <= 4000
            and controller["controller"] == "running" and controller["state_source"] == "live"
            and controller["native_process"] == "running" and controller["input_observer"] == "live"
            and controller["native_health"] in ("monitoring", "physical_grabbed"))


def identity(state):
    canonical = state["canonical"]
    return {key: canonical[key] for key in ("session", "rollout", "session_mode")}


def active(state, deadline):
    banner, controller = state["canonical"]["school_banner"], state["controller"]
    return (banner.get("diagnostic") is True and banner.get("run_id") == "diagnostic"
            and banner.get("expires_at_ms") == deadline and banner.get("phase") == "active"
            and banner.get("protection_ready") is True and banner.get("overlay_visible") is True
            and banner.get("message") == MESSAGE and proof_live(state)
            and controller["session_phase"] == "school_banner"
            and controller["native_lease"] == "armed" and bool(controller["native_physical_grabs"])
            and controller["native_health"] == "physical_grabbed")


def released(state, expected_result):
    banner, controller = state["canonical"]["school_banner"], state["controller"]
    return (banner.get("diagnostic") is False and not banner.get("overlay_visible")
            and not banner.get("protection_ready") and banner.get("phase") in IDLE_BANNERS
            and banner.get("last_diagnostic_result") == expected_result
            and controller["session_phase"] in IDLE_SESSIONS
            and controller["native_lease"] == "disarmed" and controller["native_physical_grabs"] == []
            and controller["native_health"] == "monitoring" and proof_live(state))


def poll(client, predicate, seconds, failure):
    deadline = time.monotonic() + seconds
    while True:
        state = client.state()
        if predicate(state):
            return state
        if time.monotonic() >= deadline:
            raise CheckFailed(failure)
        time.sleep(0.25)


class Receipt:
    def __init__(self, path, existing=False):
        self.path = path
        if existing:
            self.value = json.loads(path.read_text())
            require(self.value.get("schema") == 1 and self.value.get("kind") == "onn_banner_acceptance",
                    "invalid_receipt")
        else:
            self.value = {"schema": 1, "kind": "onn_banner_acceptance", "steps": []}
            path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, "w") as output:
                json.dump(self.value, output)
                output.flush()
                os.fsync(output.fileno())

    def record(self, step, **details):
        self.value["steps"].append({"step": step, "wall_ms": now_ms(), **details})
        temporary = self.path.with_name(self.path.name + ".tmp-" + str(os.getpid()))
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        try:
            with os.fdopen(fd, "w") as output:
                json.dump(self.value, output, indent=2, sort_keys=True)
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.path)
        finally:
            temporary.unlink(missing_ok=True)


def broadcast(args):
    command = [args.adb, "-s", args.serial, "shell", "am", "broadcast", "-a",
               "dev.yossi.onnkeyguard.POLICY_ADMIN", "-n", "dev.yossi.onnkeyguard/.PolicyAdminReceiver",
               "--es", "command", "test_banner", "--ei", "duration_seconds", str(args.seconds)]
    result = subprocess.run(command, capture_output=True, timeout=6, check=False)
    require(result.returncode == 0, "diagnostic_command_failed")
    output = result.stdout.decode("utf-8", errors="replace")
    for candidate in (output, output.replace('\\"', '"')):
        for match in re.finditer(r"\{", candidate):
            try:
                value, _ = json.JSONDecoder().raw_decode(candidate[match.start():])
                if isinstance(value, dict) and value.get("schema") == 1:
                    require(value.get("ok") is True, "diagnostic_declined")
                    return value
            except ValueError:
                continue
    raise CheckFailed("diagnostic_response_invalid")


def start(client, args, receipt):
    asset = client.asset()
    before = client.state()
    idle(before)
    require(proof_live(before), "fresh_native_proof_required")
    receipt.value["before"] = before
    receipt.value["asset"] = asset
    receipt.value["duration_seconds"] = args.seconds
    receipt.record("diagnostic_submission_recorded")
    response = broadcast(args)  # Once only; uncertainty is resolved by reads or bounded expiry.
    banner = response.get("school_banner", {})
    deadline = banner.get("expires_at_ms")
    require(type(deadline) is int and now_ms() < deadline <= now_ms() + args.seconds * 1000 + 500,
            "diagnostic_deadline_invalid")
    receipt.value["diagnostic_deadline_ms"] = deadline
    receipt.record("diagnostic_accepted", deadline_ms=deadline)
    state = poll(client, lambda value: active(value, deadline), min(6, args.seconds - 1),
                 "diagnostic_protection_not_confirmed")
    require(identity(state) == identity(before), "canonical_episode_or_configuration_changed")
    receipt.record("diagnostic_active", state=state)
    return state


def confirm_release(client, receipt, reason, seconds=6):
    state = poll(client, lambda value: released(value, reason), seconds, "native_release_not_confirmed")
    restored = identity(state) == identity(receipt.value["before"])
    receipt.record("diagnostic_released", state=state, canonical_state_restored=restored,
                   native_lease_released=True, release_reason=reason)
    require(restored, "canonical_episode_or_configuration_changed")
    return state


def dismiss(client, receipt):
    steps = {step["step"] for step in receipt.value["steps"]}
    require("diagnostic_active" in steps, "active_diagnostic_receipt_required")
    require("phone_down_submission_recorded" not in steps, "phone_submission_already_recorded")
    state = client.state()
    require(identity(state) == identity(receipt.value["before"]), "canonical_episode_or_configuration_changed")
    require(active(state, receipt.value["diagnostic_deadline_ms"]), "recorded_diagnostic_no_longer_active")
    require(receipt.value["diagnostic_deadline_ms"] - now_ms() >= 2000, "diagnostic_too_close_to_expiry")
    receipt.record("phone_down_submission_recorded", source="synthetic_authenticated_phone_key",
                   actual_handset_or_source_classification_test=False, linux_code=158)
    accepted = False
    try:
        response = client.json("/event", {"type": "phone_key", "wall_ms": now_ms(), "linux_code": 158, "value": 1})
        accepted = response.get("ok") is True
        receipt.record("phone_down_response", accepted=accepted)
    finally:
        # A distinct balanced key-up is safe even when the down response is uncertain.
        response = client.json("/event", {"type": "phone_key", "wall_ms": now_ms(), "linux_code": 158, "value": 0})
        receipt.record("phone_up_response", accepted=response.get("ok") is True)
    require(accepted, "synthetic_phone_event_not_accepted")
    return confirm_release(client, receipt, "phone_dismissed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("inspect", "start", "dismiss", "expiry"))
    parser.add_argument("--state-dir", type=Path, default=Path.home() / ".local/state/onn-session-policy")
    parser.add_argument("--receipt", type=Path, help="New path for start/expiry; same path for dismiss")
    parser.add_argument("--serial", default=os.environ.get("ONN_ADB_SERIAL"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--seconds", type=int, default=20, help="Diagnostic duration, 8..60 seconds")
    args = parser.parse_args()
    require(8 <= args.seconds <= 60, "diagnostic_duration_must_be_8_to_60_seconds")
    if args.action != "inspect":
        require(args.receipt is not None, "explicit_receipt_path_required")
    if args.action in ("start", "expiry"):
        require(bool(args.serial), "explicit_adb_serial_required")
    client = Client(args.state_dir.expanduser())
    if args.action == "inspect":
        state, asset = client.state(), client.asset()
        idle(state)
        require(proof_live(state), "fresh_native_proof_required")
        print(json.dumps({"ok": True, "action": "inspect", "state": state, "asset": asset}, sort_keys=True))
        return
    receipt = Receipt(args.receipt.expanduser(), existing=args.action == "dismiss")
    try:
        if args.action in ("start", "expiry"):
            state = start(client, args, receipt)
            if args.action == "expiry":
                remaining = max(0, (receipt.value["diagnostic_deadline_ms"] - now_ms()) / 1000)
                state = confirm_release(client, receipt, "expired", remaining + 6)
        else:
            state = dismiss(client, receipt)
        print(json.dumps({"ok": True, "action": args.action, "receipt": str(receipt.path),
                          "state": state, "screenshot_verified": False,
                          "phone_test": "synthetic_authenticated_event" if args.action == "dismiss" else None},
                         sort_keys=True), flush=True)
    except Exception as error:
        reason = str(error) if isinstance(error, CheckFailed) else type(error).__name__
        receipt.record("action_failed", action=args.action, reason=reason,
                       automatic_mutation_retry=False, diagnostic_maximum_seconds=60)
        raise


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        reason = str(error) if isinstance(error, CheckFailed) else type(error).__name__
        print(json.dumps({"ok": False, "error": reason, "automatic_mutation_retry": False}), file=sys.stderr)
        sys.exit(2)
