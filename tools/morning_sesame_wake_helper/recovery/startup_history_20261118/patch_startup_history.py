#!/usr/bin/env python3
"""Narrowly patch the verified installed Nov18 helper, preserving playlist and startup fences."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile
import zlib

BASE_APK_SHA256 = "4b378a10bebb6e6030f271cc7eb19f53abbc708d884afcb1cdc7a7c5d4da9999"
BASE_DEX_SHA256 = "a653862bc7acae0689647bfa7b80c5526a9abf75251f574045389d793404f9e2"
PACKAGE = Path("dev/yossi/morningsesame")
SOURCES = ("MorningHistoryReply", "MorningHistoryReader", "MorningOrderedBroadcast", "MorningSelectionRepair")
REPLACED = {"MorningSelectionRepair": "b5ea4f83343107ee5ac1da0a26d3377b834a40ae6e8955966c0f29bf705fbc0a"}
PATCHES = (
    ("AlarmReceiver", ".method private static selectAndVerify(Landroid/content/Context;Ljava/lang/String;)V",
     "fccdc6c51743de57577637613e9f88069b4fbf95b1b785b382d0de41beba01b1",
     "Ldev/yossi/morningsesame/AlarmReceiver;->readProgressWithRetry(Landroid/content/Context;Ljava/lang/String;)Lorg/json/JSONObject;",
     "Ldev/yossi/morningsesame/MorningHistoryReader;->read(Landroid/content/Context;Ljava/lang/String;)Lorg/json/JSONObject;"),
    ("StartupAuthenticationCoordinator$Request", ".method private send(Ljava/lang/String;)V",
     "e783443181dcc5d91602e6ae62bd0ac90580d5226d91c13ecdfa85a661a56001",
     "invoke-virtual/range {v4 .. v11}, Landroid/content/Context;->sendOrderedBroadcast(Landroid/content/Intent;Ljava/lang/String;Landroid/content/BroadcastReceiver;Landroid/os/Handler;ILjava/lang/String;Landroid/os/Bundle;)V",
     "invoke-static/range {v4 .. v11}, Ldev/yossi/morningsesame/MorningOrderedBroadcast;->send(Landroid/content/Context;Landroid/content/Intent;Ljava/lang/String;Landroid/content/BroadcastReceiver;Landroid/os/Handler;ILjava/lang/String;Landroid/os/Bundle;)V"),
)
STUBS = {
    "RunCoordinator": """package dev.yossi.morningsesame; import android.content.Context;
final class RunCoordinator {
 static native boolean checkpoint(Context c,String r);
 static native long remaining(Context c,String r);
 static native long startupDeadlineWall(Context c,String r);
 static native boolean storePending(Context c,String r,String v);
 static native boolean note(Context c,String r,String p,String v,int a);
 static native void finish(Context c,String r,String o,String why);
}""",
    "AlarmReceiver": """package dev.yossi.morningsesame; import android.content.Context; import java.util.Map;
final class AlarmReceiver {
 static native Map<String,Integer> parseDurations(String encoded);
 static native void schedulePlay(Context c,String r,String v);
}""",
    "RunDiagnostics": """package dev.yossi.morningsesame; import android.content.Context; import java.util.Map;
final class RunDiagnostics {
 static native void event(Context c,String r,String p,String v,Map<String,Long> m);
 static native Map<String,Long> values(Object... pairs);
}""",
    "WorkerFenceContinuation": """package dev.yossi.morningsesame; import android.content.Context;
final class WorkerFenceContinuation { static native void deferPlayback(Context c,String r,String v); }""",
    "MorningYouTubeHistory": """package dev.yossi.morningsesame; import org.json.JSONObject; import java.util.Map;
final class MorningYouTubeHistory { static native Map<String,Double> parse(JSONObject data,long now) throws Exception; }""",
    "MorningEpisodePolicy": """package dev.yossi.morningsesame; import java.util.Map;
final class MorningEpisodePolicy { static native String select(Map<String,Integer> durations,Map<String,Double> account); }""",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(*args):
    subprocess.run(list(map(str, args)), check=True, timeout=120)


def smali(jar, command, *args):
    run("java", "-cp", jar, "com.android.tools.smali." + command, *args)


def method(source, signature):
    if source.count(signature) != 1:
        raise ValueError("target_method_count:" + signature)
    start = source.index(signature)
    return source[start:source.index(".end method", start) + len(".end method")]


def canonical(source):
    return "\n".join(line.strip() for line in source.splitlines() if line.strip())


def validate_dex(data):
    if not data.startswith(b"dex\n") or struct.unpack_from("<I", data, 32)[0] != len(data):
        raise ValueError("dex_size")
    if hashlib.sha1(data[32:]).digest() != data[12:32]:
        raise ValueError("dex_signature")
    if zlib.adler32(data[12:]) & 0xffffffff != struct.unpack_from("<I", data, 8)[0]:
        raise ValueError("dex_checksum")


def build(args):
    if digest(args.base.read_bytes()) != BASE_APK_SHA256:
        raise ValueError("unexpected_base_apk")
    out = args.output
    if out.exists() and any(out.iterdir()):
        raise ValueError("output_must_be_empty")
    out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.base) as archive:
        original_dex = archive.read("classes.dex")
    if digest(original_dex) != BASE_DEX_SHA256:
        raise ValueError("unexpected_base_dex")
    validate_dex(original_dex)
    (out / "original.dex").write_bytes(original_dex)
    original, candidate, readback = [out / name for name in ("original-smali", "candidate-smali", "readback-smali")]
    smali(args.smali_jar, "baksmali.Main", "disassemble", out / "original.dex", "-o", original)
    shutil.copytree(original, candidate)
    for name, expected in REPLACED.items():
        if digest((original / PACKAGE / (name + ".smali")).read_bytes()) != expected:
            raise ValueError("unexpected_replaced_class:" + name)
    patched_methods = {}
    for name, signature, expected, old_call, new_call in PATCHES:
        path = PACKAGE / (name + ".smali")
        source = (candidate / path).read_text()
        old = method(source, signature)
        if digest(old.encode()) != expected or old.count(old_call) != 1:
            raise ValueError("unexpected_adapter_method:" + name)
        new = old.replace(old_call, new_call)
        (candidate / path).write_text(source.replace(old, new))
        patched_methods[path] = (signature, new)
    stubs, classes = out / "compile-stubs", out / "compiled"
    stubs.mkdir()
    classes.mkdir()
    for name, source in STUBS.items():
        (stubs / (name + ".java")).write_text(source)
    source = Path(__file__).resolve().parent
    run("java", "--module", "jdk.compiler/com.sun.tools.javac.Main", "-source", "8", "-target", "8",
        "-cp", args.android_jar, "-d", classes, *stubs.glob("*.java"),
        *[source / (name + ".java") for name in SOURCES])
    classpath = out / "compile-classpath.jar"
    with zipfile.ZipFile(classpath, "w") as archive:
        for name in STUBS:
            path = PACKAGE / (name + ".class")
            archive.write(classes / path, str(path))
    source_classes = [p for p in (classes / PACKAGE).glob("*.class") if p.stem.split("$")[0] in SOURCES]
    new_dex = out / "compiled-dex"
    new_dex.mkdir()
    run("java", "-cp", args.d8_jar, "com.android.tools.r8.D8", "--min-api", "26", "--lib", args.android_jar,
        "--classpath", classpath, "--output", new_dex, *source_classes)
    source_smali = out / "source-smali"
    smali(args.smali_jar, "baksmali.Main", "disassemble", new_dex / "classes.dex", "-o", source_smali)
    source_files = {p.relative_to(source_smali) for p in source_smali.rglob("*.smali")}
    expected_files = {PACKAGE / (name + ".smali") for name in SOURCES} | {PACKAGE / "MorningHistoryReader$1.smali"}
    if source_files != expected_files:
        raise ValueError("unexpected_compiled_class_inventory:" + str(sorted(map(str, source_files))))
    for path in source_files:
        shutil.copy2(source_smali / path, candidate / path)
    smali(args.smali_jar, "smali.Main", "assemble", candidate, "-o", out / "classes.dex")
    patched = (out / "classes.dex").read_bytes()
    validate_dex(patched)
    smali(args.smali_jar, "baksmali.Main", "disassemble", out / "classes.dex", "-o", readback)
    original_files = {p.relative_to(original) for p in original.rglob("*.smali")}
    added = source_files - original_files
    if {p.relative_to(readback) for p in readback.rglob("*.smali")} != original_files | added:
        raise ValueError("final_class_inventory")
    total = replaced_methods = 0
    for path in sorted(original_files):
        old, new = (original / path).read_text(), (readback / path).read_text()
        count = len(re.findall(r"^\.method ", old, re.M))
        total += count
        if path in source_files:
            replaced_methods += count
            continue
        if path in patched_methods:
            signature, expected = patched_methods[path]
            old_method, new_method = method(old, signature), method(new, signature)
            if canonical(new_method) != canonical(expected):
                raise ValueError("adapter_readback_difference:" + str(path))
            old, new = old.replace(old_method, "VERIFIED_ADAPTER"), new.replace(new_method, "VERIFIED_ADAPTER")
        if old != new:
            raise ValueError("unrelated_existing_bytecode_changed:" + str(path))
    for path in source_files:
        if (readback / path).read_bytes() != (source_smali / path).read_bytes():
            raise ValueError("source_merge_difference:" + str(path))
    unsigned = out / "MorningSesame-startup-history-unsigned.apk"
    retained = {}
    with zipfile.ZipFile(args.base) as old, zipfile.ZipFile(unsigned, "w") as new:
        for entry in old.infolist():
            if entry.filename.startswith("META-INF/"):
                continue
            data = patched if entry.filename == "classes.dex" else old.read(entry.filename)
            new.writestr(entry, data)
            if entry.filename != "classes.dex":
                retained[entry.filename] = digest(data)
    with zipfile.ZipFile(unsigned) as archive:
        for name, checksum in retained.items():
            if digest(archive.read(name)) != checksum:
                raise ValueError("retained_payload_changed:" + name)
    report = {"base_apk_sha256": BASE_APK_SHA256, "base_dex_sha256": BASE_DEX_SHA256,
              "candidate_dex_sha256": digest(patched), "existing_classes": len(original_files),
              "existing_methods": total, "replaced_classes": REPLACED,
              "methods_in_replaced_classes": replaced_methods,
              "changed_adapter_methods": [{"class": p[0], "signature": p[1], "old_sha256": p[2]} for p in PATCHES],
              "unchanged_existing_methods": total - replaced_methods - len(PATCHES),
              "added_classes": sorted(map(str, added)), "retained_payloads": retained,
              "new_source_sha256": {name: digest((source / (name + ".java")).read_bytes()) for name in SOURCES},
              "unsigned_apk": str(unsigned.resolve()), "unsigned_apk_sha256": digest(unsigned.read_bytes()),
              "signed": False, "installed": False}
    (out / "invariants.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("base", "smali-jar", "d8-jar", "android-jar", "output"):
        parser.add_argument("--" + flag, type=Path, required=True)
    build(parser.parse_args())
