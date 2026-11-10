#!/usr/bin/env python3
"""Build the two small Onn helpers with the owner's existing signing identity."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def run(args: list[str], timeout: int = 90) -> None:
    subprocess.run(args, check=True, timeout=timeout)


def build(root: Path, sdk: Path, platform: Path, key: Path, output: Path,
          name: str, source: Path) -> dict[str, str | int]:
    java_sources = sorted(source.rglob("*.java"))
    java_sources = [p for p in java_sources if "tests" not in p.relative_to(source).parts]
    if not java_sources:
        raise ValueError(f"No Java sources in {source}")
    with tempfile.TemporaryDirectory(prefix="onn-apk-") as scratch:
        scratch_path = Path(scratch)
        classes = scratch_path / "classes"
        dex = scratch_path / "dex"
        classes.mkdir()
        dex.mkdir()
        run(["java", "--module", "jdk.compiler/com.sun.tools.javac.Main", "-source", "8",
             "-target", "8", "-Xlint:-options", "-classpath", str(platform),
             "-bootclasspath", str(sdk / "core-lambda-stubs.jar") + os.pathsep + str(platform),
             "-d", str(classes), *map(str, java_sources)])
        run(["java", "-Xmx512m", "-cp", str(sdk / "lib/d8.jar"),
             "com.android.tools.r8.D8", "--lib", str(platform), "--min-api", "26",
             "--output", str(dex), *map(str, sorted(classes.rglob("*.class")))])
        unsigned = scratch_path / "unsigned.apk"
        aapt = sdk / "aapt2"
        links = [str(aapt), "link", "-I", str(platform), "--manifest", str(source / "AndroidManifest.xml"),
                 "--min-sdk-version", "26", "--target-sdk-version", "35", "-o", str(unsigned)]
        resources = source / "res"
        if resources.is_dir():
            compiled = scratch_path / "resources.zip"
            run([str(aapt), "compile", "--dir", str(resources), "-o", str(compiled)])
            links.append(str(compiled))
        run(links)
        with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as apk:
            for file in sorted(dex.glob("*.dex")):
                apk.write(file, file.name)
        aligned = scratch_path / "aligned.apk"
        run([str(sdk / "zipalign"), "-f", "4", str(unsigned), str(aligned)])
        target = output / f"{name}.apk"
        run(["java", "-jar", str(sdk / "lib/apksigner.jar"), "sign", "--ks", str(key),
             "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android",
             "--key-pass", "pass:android", "--out", str(target), str(aligned)])
        run(["java", "-jar", str(sdk / "lib/apksigner.jar"), "verify", "--print-certs", str(target)])
        return {"file": target.name, "bytes": target.stat().st_size,
                "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--target", choices=("morning", "guard", "all"), default="all")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    args.output.mkdir(parents=True, exist_ok=True)
    targets = {
        "morning": ("MorningSesame-v10", root / "tools/morning_sesame_wake_helper"),
        "guard": ("OnnKeyGuard-v6", root / "tools/onn_key_guard"),
    }
    selected = targets if args.target == "all" else {args.target: targets[args.target]}
    results = [build(root, args.build_tools.resolve(), args.android_jar.resolve(),
                     args.keystore.resolve(), args.output.resolve(), name, source)
               for name, source in selected.values()]
    report = args.output / f"build-{args.target}.json"
    report.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
