#!/usr/bin/env python3
"""Replace only the rich reminder classes in the exact installed Guard APK.

No device access, deployment, or signing. Unrelated classes and payloads must
survive a full Smali round trip, and compile-only classes never enter the DEX.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

BASE_APK_SHA256 = "b86e4d42e8533291c316bc4ae98bf09d3d94e9453b00ad996ea09a9c077104e6"
BASE_DEX_SHA256 = "5dae22b5dcfad6996559480b2deb6f6067b469a4315fa6ef12c232f4fe03c32c"
PLAYER_SHA256 = "299bcd8c478d739a086d692c78f82d0f481b14d92a417ead92e43c5b2da66290"
PACKAGE = Path("dev/yossi/onnkeyguard")
FILES = {"reminder.mp4": 64 * 1024 * 1024, "music.flac": 64 * 1024 * 1024,
         "first-frame.png": 12 * 1024 * 1024, "last-frame.png": 12 * 1024 * 1024}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(*args):
    subprocess.run(list(map(str, args)), check=True, timeout=90)


def smali(jar, action, *args):
    run("java", "-cp", jar, "com.android.tools.smali." + action, *args)


def owned(path):
    name = path.name
    return name == "SchoolBannerRuntime.smali" or name.startswith("SchoolBannerRuntime$") \
        or name.startswith("SchoolReminder")


def public_api(text, helpers):
    return {signature for signature, method in helpers.methods(text).items()
            if "public" in method.splitlines()[0].split()}


def read_manifest(path):
    data = path.read_bytes()
    if len(data) > 16384:
        raise ValueError("manifest_too_large")
    value = json.loads(data)
    if value.get("schema") != 1 or set(value.get("assets", {})) != set(FILES):
        raise ValueError("invalid_manifest_inventory")
    for name, limit in FILES.items():
        pin = value["assets"][name]
        size, sha = pin.get("bytes"), pin.get("sha256")
        if type(size) is not int or not 0 < size <= limit or not isinstance(sha, str) \
                or len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
            raise ValueError("invalid_manifest_pin:" + name)
    return data


def build(args):
    here = Path(__file__).resolve().parent
    prior = here.parent / "school_banner_20261117"
    spec = importlib.util.spec_from_file_location("banner_build_helpers", prior / "build_guard_banner.py")
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)
    if digest(args.base.read_bytes()) != BASE_APK_SHA256:
        raise ValueError("wrong_installed_base")
    manifest = read_manifest(args.media_manifest)
    out = args.output.resolve()
    if out.exists() and any(out.iterdir()):
        raise ValueError("output_must_be_empty")
    out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.base) as apk:
        dex = apk.read("classes.dex")
        if digest(dex) != BASE_DEX_SHA256 or digest(apk.read("assets/player.js")) != PLAYER_SHA256:
            raise ValueError("wrong_base_dex_or_player")
        if "assets/school_reminder_manifest.json" in apk.namelist():
            raise ValueError("reminder_already_present")
    (out / "original.dex").write_bytes(dex)
    original = out / "original-smali"
    smali(args.smali_jar, "baksmali.Main", "disassemble", out / "original.dex", "-o", original)
    compiled = out / "compiled"
    compiled.mkdir()
    sources = sorted((here / "src" / PACKAGE).glob("*.java"))
    run("java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "8", "-target", "8",
        "-Xlint:-options", "-bootclasspath", args.android_jar, "-d", compiled,
        prior / "src" / PACKAGE / "SchoolBannerPolicy.java",
        prior / "compile-stubs" / PACKAGE / "SesameSessionPolicy.java", *sources)
    program = [p for p in sorted(compiled.rglob("*.class"))
               if p.name.startswith(("SchoolBannerRuntime", "SchoolReminder"))]
    if not program or any("SchoolBannerPolicy" in p.name or "SesameSessionPolicy" in p.name for p in program):
        raise ValueError("invalid_program_inventory")
    dex_out = out / "compiled-dex"
    dex_out.mkdir()
    run("java", "-cp", args.d8_jar, "com.android.tools.r8.D8", "--min-api", "26",
        "--lib", args.android_jar, "--classpath", compiled, "--output", dex_out, *program)
    fresh = out / "new-smali"
    smali(args.smali_jar, "baksmali.Main", "disassemble", dex_out / "classes.dex", "-o", fresh)
    new_files = {p.relative_to(fresh): p.read_text() for p in fresh.rglob("*.smali")}
    if not all(p.parent == PACKAGE and owned(p) for p in new_files):
        raise ValueError("unexpected_compiled_class")
    old_files = {p.relative_to(original): p.read_text() for p in original.rglob("*.smali")}
    runtime = PACKAGE / "SchoolBannerRuntime.smali"
    if public_api(old_files[runtime], helpers) != public_api(new_files[runtime], helpers):
        raise ValueError("runtime_public_api_changed")
    merged = out / "merged-smali"
    shutil.copytree(original, merged)
    removed = []
    for relative in old_files:
        if owned(relative):
            removed.append(str(relative)); (merged / relative).unlink()
    for relative, text in new_files.items():
        (merged / relative).parent.mkdir(parents=True, exist_ok=True)
        (merged / relative).write_text(text)
    patched = out / "classes.dex"
    smali(args.smali_jar, "smali.Main", "assemble", merged, "-o", patched)
    roundtrip = out / "roundtrip-smali"
    smali(args.smali_jar, "baksmali.Main", "disassemble", patched, "-o", roundtrip)
    actual_files = {p.relative_to(roundtrip): p.read_text() for p in roundtrip.rglob("*.smali")}
    retained = {p: text for p, text in old_files.items() if not owned(p)}
    expected = dict(retained)
    expected.update(new_files)
    if set(actual_files) != set(expected):
        raise ValueError("class_inventory_changed")
    methods = 0
    for relative, text in expected.items():
        actual = actual_files[relative]
        if helpers.class_header(text) != helpers.class_header(actual):
            raise ValueError("class_header_changed:" + str(relative))
        before_methods, after_methods = helpers.methods(text), helpers.methods(actual)
        if set(before_methods) != set(after_methods):
            raise ValueError("method_inventory_changed:" + str(relative))
        for name, value in before_methods.items():
            if helpers.canonical(value) != helpers.canonical(after_methods[name]):
                raise ValueError("method_changed_in_roundtrip:" + str(relative) + ":" + name)
            if relative in retained:
                methods += 1
    new_dex = patched.read_bytes()
    integrity = helpers.dex_integrity(new_dex)
    source_hashes = {str(p.relative_to(here)): digest(p.read_bytes()) for p in sources}
    metadata = {"revision": "school-reminder-20261118", "base_apk_sha256": BASE_APK_SHA256,
                "source_sha256": source_hashes, "media_manifest_sha256": digest(manifest),
                "frame_crossfade_after_video_end_ms": 8000, "frame_crossfade_ms": 3000,
                "initial_music_fade_ms": 5000, "diagnostic_hold_ms": 30000}
    unsigned = out / "guard-reminder-unsigned.apk"
    payloads = {}
    with zipfile.ZipFile(args.base) as old, zipfile.ZipFile(unsigned, "w") as new:
        for info in old.infolist():
            if info.filename.startswith("META-INF/"):
                continue
            data = new_dex if info.filename == "classes.dex" else old.read(info.filename)
            new.writestr(info, data)
            if info.filename != "classes.dex": payloads[info.filename] = digest(data)
        new.writestr("assets/school_reminder_manifest.json", manifest)
        new.writestr("assets/school_reminder_revision.json", json.dumps(metadata, sort_keys=True) + "\n")
    with zipfile.ZipFile(unsigned) as apk:
        for name, sha in payloads.items():
            if digest(apk.read(name)) != sha: raise ValueError("payload_changed:" + name)
    report = {"base_apk_sha256": BASE_APK_SHA256, "base_dex_sha256": BASE_DEX_SHA256,
              "unsigned_apk": str(unsigned), "unsigned_sha256": digest(unsigned.read_bytes()),
              "unsigned_bytes": unsigned.stat().st_size, "new_source_sha256": source_hashes,
              "media_manifest_sha256": digest(manifest), "replaced_classes": removed,
              "new_classes": list(map(str, sorted(new_files))), "retained_classes": len(retained),
              "retained_methods_verified": methods, "all_untouched_methods_verified": True,
              "runtime_public_api_unchanged": True, "retained_apk_payloads": payloads,
              "candidate_dex": integrity, "signed": False, "installed": False}
    (out / "invariants.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: report[k] for k in ("unsigned_apk", "unsigned_sha256", "unsigned_bytes",
                                            "retained_classes", "retained_methods_verified")}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "smali-jar", "d8-jar", "android-jar", "media-manifest", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    build(parser.parse_args())
