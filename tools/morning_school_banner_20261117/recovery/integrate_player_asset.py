#!/usr/bin/env python3
"""Insert the root player source into an explicitly pinned unsigned Guard APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


ASSETS = frozenset(("assets/player.js", "assets/player.sha256"))
MAX_SCRIPT_BYTES = 1_048_576


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def integrate(base, expected_sha256, player, output):
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise ValueError("invalid_guard_sha256_pin")
    if sha256(base.read_bytes()) != expected_sha256:
        raise ValueError("unexpected_guard_apk")
    source = player.read_bytes()
    if not 0 < len(source) <= MAX_SCRIPT_BYTES:
        raise ValueError("invalid_player_size")
    player_hash = sha256(source)
    changed = []
    retained = {}
    with zipfile.ZipFile(base) as original:
        names = original.namelist()
        if len(names) != len(set(names)) or not ASSETS.issubset(names):
            raise ValueError("invalid_guard_asset_inventory")
        if any(name.startswith("META-INF/") for name in names):
            raise ValueError("unsigned_guard_required")
        with zipfile.ZipFile(output, "x") as candidate:
            for entry in original.infolist():
                old = original.read(entry.filename)
                data = (source if entry.filename == "assets/player.js" else
                        (player_hash + "\n").encode("ascii") if entry.filename == "assets/player.sha256" else old)
                candidate.writestr(entry, data)
                if old != data:
                    changed.append(entry.filename)
                if entry.filename not in ASSETS:
                    retained[entry.filename] = sha256(old)
    with zipfile.ZipFile(output) as candidate:
        if candidate.namelist() != names:
            raise ValueError("changed_payload_inventory")
        for name, digest in retained.items():
            if sha256(candidate.read(name)) != digest:
                raise ValueError("changed_nonplayer_payload:" + name)
        if candidate.read("assets/player.js") != source:
            raise ValueError("player_readback_mismatch")
        if candidate.read("assets/player.sha256").decode("ascii").strip() != player_hash:
            raise ValueError("player_digest_readback_mismatch")
    return {
        "guard_patch_sha256": expected_sha256,
        "guard_integrated_unsigned_sha256": sha256(output.read_bytes()),
        "player_sha256": player_hash,
        "changed_payloads": changed,
        "unchanged_nonplayer_payloads": retained,
        "bytes": output.stat().st_size,
        "signed": False,
        "installed": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--guard", type=Path, required=True)
    parser.add_argument("--guard-sha256", required=True)
    parser.add_argument("--player", type=Path,
                        default=Path(__file__).resolve().parents[3] / "x.js")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = integrate(args.guard, args.guard_sha256, args.player, args.output)
    encoded = json.dumps(report, indent=2) + "\n"
    if args.report:
        with args.report.open("x") as stream:
            stream.write(encoded)
    print(encoded, end="")


if __name__ == "__main__":
    main()
