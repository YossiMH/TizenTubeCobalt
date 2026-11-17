#!/usr/bin/env python3
"""Host-test the original readable asset class with the identical constructor delta.

This is not Android DEX execution. The builder independently verifies that all
other asset methods remain bytecode-equivalent in the final APK.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from build_guard_banner import LEGACY_PLAYER_SHA256, LEGACY_PLAYER_PATH, replace_once


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path,
                        default=Path(__file__).resolve().parent / "test/fixtures/original-source")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    package = Path("dev/yossi/onnkeyguard")
    hashes = json.loads((Path(__file__).resolve().parent / "test/fixtures/source-hashes.json").read_text())
    for name, expected in hashes["source_sha256"].items():
        actual = hashlib.sha256((args.source_root / package / name).read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError("unexpected_original_source:" + name)
    out_source = args.output / "source" / package
    out_source.mkdir(parents=True, exist_ok=True)
    original = (args.source_root / package / "PlayerScriptAsset.java").read_text()
    patched = replace_once(original, 'this.path = "/" + str.substring(0, 41) + ".js";',
                           'this.path = "' + LEGACY_PLAYER_PATH + '";')
    asset = out_source / "PlayerScriptAsset.java"
    asset.write_text(patched)
    protocol = out_source / "ControllerProtocol.java"
    protocol.write_text((args.source_root / package / "ControllerProtocol.java").read_text())
    test = Path(__file__).resolve().parent / "test" / package / "PlayerAssetRouteTest.java"
    classes = args.output / "classes"
    classes.mkdir(exist_ok=True)
    subprocess.run(["java", "--module", "jdk.compiler/com.sun.tools.javac.Main", "-d", str(classes),
                    str(asset), str(protocol), str(test)], check=True, timeout=30)
    subprocess.run(["java", "-cp", str(classes), "dev.yossi.onnkeyguard.PlayerAssetRouteTest",
                    LEGACY_PLAYER_SHA256], check=True, timeout=30)


if __name__ == "__main__":
    main()
