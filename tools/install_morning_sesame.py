#!/usr/bin/env python3
"""Verify and atomically update the existing Morning Sesame app pair."""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

APP = "io.gh.yossim.tizentube.cobalt"
HELPER = "dev.yossi.morningsesame"
DEVICE = "PUSA2415005278"
APP_CERT = "f8821e414cd2e093f64f6cfb6e3254592e839b1c1d0afcd3b2dec0ee1065b9ab"
HELPER_CERT = "2603b0cf10c08a140658cc7c0289d3e413e94e4297da4403dce4b85fd6b16fb8"


def run(args: list[str], timeout: int = 20) -> str:
    if os.name == "nt" and args[0].lower().endswith(".bat"):
        args = ["cmd", "/d", "/c", *args]
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError((result.stderr or result.stdout).strip()[:2000])
    return result.stdout.strip()


def fingerprint(output: str) -> str:
    found = set(re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", output))
    if len(found) != 1:
        raise ValueError("Expected exactly one APK signing certificate.")
    return next(iter(found)).lower()


def package_badging(text: str) -> tuple[str, int]:
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", text, re.M)
    if not match:
        raise ValueError("Cannot read APK package and version.")
    return match[1], int(match[2])


def package_path(text: str) -> str:
    for line in text.splitlines():
        value = line.removeprefix("package:").strip()
        if re.fullmatch(r"/data/app/[A-Za-z0-9._~=/+-]+/base\.apk", value):
            return value
    raise ValueError("Expected an existing installed base APK under /data/app.")


def digest(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def install_command(adb: str, serial: str, app: Path, helper: Path) -> list[str]:
    # Android commits all child packages together, or neither. Never uninstall.
    return [adb, "-s", serial, "install-multi-package", "-r", "-t", str(app), str(helper)]


def build_tools(sdk: Path) -> Path:
    versions = [p for p in (sdk / "build-tools").iterdir()
                if p.is_dir() and re.fullmatch(r"\d+(?:\.\d+)+", p.name)]
    if not versions:
        raise ValueError("Android SDK build-tools were not found.")
    return max(versions, key=lambda p: tuple(map(int, p.name.split("."))))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--serial", default="192.168.1.172:5555")
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get(
        "ANDROID_HOME", str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk"))))
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    receipt = {"time_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
               "runtime_commit": args.source_commit, "status": "verification_started",
               "playback_verified": False}
    try:
        if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
            raise ValueError("A full lowercase 40-character source commit is required.")
        app, helper = args.app.resolve(strict=True), args.helper.resolve(strict=True)
        bt = build_tools(args.sdk)
        signer = str(bt / ("apksigner.bat" if os.name == "nt" else "apksigner"))
        aapt = str(bt / ("aapt.exe" if os.name == "nt" else "aapt"))
        for path, package, expected_cert in [(app, APP, APP_CERT), (helper, HELPER, HELPER_CERT)]:
            if fingerprint(run([signer, "verify", "--print-certs", str(path)])) != expected_cert:
                raise ValueError("Refusing a signing-identity change for " + package)
            actual, version = package_badging(run([aapt, "dump", "badging", str(path)]))
            if actual != package or (package == HELPER and version != 6):
                raise ValueError("Unexpected package/version: " + actual + "/" + str(version))
            receipt[package] = {"path": str(path), "sha256": digest(path),
                                "certificate_sha256": expected_cert, "version_code": version}
        run([sys.executable, str(Path(__file__).with_name("verify_allowed_only_apk.py")),
             str(app), "--sha", args.source_commit], timeout=45)
        receipt["status"] = "artifacts_verified_not_installed"
        if args.check_only:
            print(receipt["status"])
            return 0
        adb = shutil.which("adb") or str(args.sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
        if ":" in args.serial:
            run([adb, "connect", args.serial], timeout=12)
        try:
            state = run([adb, "-s", args.serial, "get-state"], timeout=8)
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            receipt["status"] = "waiting_for_authorized_adb"
            raise RuntimeError("Onn ADB is unavailable. Enable USB debugging on the box; no app update was attempted.") from error
        if state != "device":
            raise RuntimeError("An authorized ADB device is required; no update attempted.")
        if run([adb, "-s", args.serial, "shell", "getprop", "ro.serialno"]) != DEVICE:
            raise RuntimeError("Refusing to update a different device.")
        if "armeabi-v7a" not in run([adb, "-s", args.serial, "shell", "getprop", "ro.product.cpu.abilist"]):
            raise RuntimeError("This release requires ARM32 compatibility.")
        for package in [APP, HELPER]:
            package_path(run([adb, "-s", args.serial, "shell", "pm", "path", "--user", "0", package]))
        receipt["status"] = "atomic_install_started"
        try:
            output = run(install_command(adb, args.serial, app, helper), timeout=180)
        except subprocess.TimeoutExpired:
            receipt["status"] = "installation_outcome_unknown_recheck_required"
            raise
        if "Success" not in output:
            raise RuntimeError("Package manager did not report success: " + output)
        for package in [APP, HELPER]:
            installed = package_path(run([adb, "-s", args.serial, "shell", "pm", "path", "--user", "0", package]))
            actual = run([adb, "-s", args.serial, "shell", "sha256sum", installed]).split()[0]
            if actual != receipt[package]["sha256"]:
                raise RuntimeError("Installed APK hash mismatch for " + package)
        receipt["status"] = "pair_installed_playback_unverified"
        run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n",
             HELPER + "/.WakeAndPlayActivity", "-a", HELPER + ".SETUP"])
        receipt["schedule_setup_requested"] = True
        print("Matching APK pair installed and hashes verified. Schedule setup requested.")
        print("Real sleeping-TV cold/warm playback acceptance is still required.")
        return 0
    except Exception as error:
        receipt["error"] = str(error)
        print(str(error), file=sys.stderr)
        return 1
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
