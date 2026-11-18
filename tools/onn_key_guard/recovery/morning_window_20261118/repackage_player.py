#!/usr/bin/env python3
"""Replace the paired player assets in the verified unsigned Guard v3 only."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

BASE_SHA = "3996d6a1b3c683cfd02b6ba9c799bb4538f75bb18a8ba64c2d28cfa5af0afa8e"
OLD_PLAYER_SHA = "b68753418534e0f8b0b5576c63784327d45f717fafef616f95fe51aa0aa14c7a"
SCRIPT = "assets/player.js"
CHECKSUM = "assets/player.sha256"


def sha(value):
    return hashlib.sha256(value).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--player-script", type=Path, required=True)
    parser.add_argument("--player-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if sha(args.base.read_bytes()) != BASE_SHA:
        raise SystemExit("unexpected unsigned Guard v3 base")
    player = args.player_script.read_bytes()
    if not player or sha(player) != args.player_sha256:
        raise SystemExit("player source does not match the reviewed SHA-256")
    if args.output.exists():
        raise SystemExit("output directory already exists")
    with zipfile.ZipFile(args.base) as source:
        entries = source.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise SystemExit("duplicate base ZIP entry")
        contents = {name:source.read(name) for name in names}
        if sha(contents[SCRIPT]) != OLD_PLAYER_SHA:
            raise SystemExit("unexpected base player")
        if contents[CHECKSUM] != (OLD_PLAYER_SHA + "\n").encode("ascii"):
            raise SystemExit("invalid base player checksum sidecar")
        args.output.mkdir(parents=True)
        output = args.output / "guard-morning-unsigned.apk"
        with zipfile.ZipFile(output, "w") as target:
            target.comment = source.comment
            for entry in entries:
                data = (player if entry.filename == SCRIPT else
                    (args.player_sha256 + "\n").encode("ascii") if entry.filename == CHECKSUM else
                    contents[entry.filename])
                target.writestr(entry, data)
    with zipfile.ZipFile(output) as result:
        if result.namelist() != names or result.testzip() is not None:
            raise SystemExit("invalid output ZIP")
        changed = [name for name in names if result.read(name) != contents[name]]
        if set(changed) != {SCRIPT, CHECKSUM}:
            raise SystemExit("unexpected changed entries: " + repr(changed))
        if result.read(SCRIPT) != player or result.read(CHECKSUM) != (sha(player) + "\n").encode("ascii"):
            raise SystemExit("player and checksum readback mismatch")
        preserved = {name:sha(result.read(name)) for name in names if name not in changed}
    report = {"schema":1, "kind":"player_assets_only", "base_sha256":BASE_SHA,
        "unsigned_sha256":sha(output.read_bytes()), "unsigned_bytes":output.stat().st_size,
        "player_sha256":sha(player), "previous_player_sha256":OLD_PLAYER_SHA,
        "changed_entries":changed, "preserved_entries_sha256":preserved,
        "unchanged_manifest_and_dex":True,
        "legacy_player_url":"/1fe2154764b819ad81036d04882cca104f3c28787.js"}
    (args.output / "invariants.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
