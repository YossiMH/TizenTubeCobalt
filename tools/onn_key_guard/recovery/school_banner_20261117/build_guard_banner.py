#!/usr/bin/env python3
"""Patch the pinned installed Guard 8 DEX; compile only clean SchoolBanner classes.

No device actions or signing. All original APK payloads other than classes.dex and
old signatures are preserved. Exact base and target hashes plus a round-trip
canonical comparison guard every existing method.
"""
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

BASE_APK_SHA256 = "2a63048d454e7b66407e42de5bdce1d46586399bba9ccee4169727d36601190f"
BASE_DEX_SHA256 = "418eb9989575390a8833159fde1af27d21812fc8423bd885ab6822f137099f3c"
LEGACY_PLAYER_SHA256 = "1fe2154764b819ad81036d04882cca104f3c28787b789b8b465cdfc23546a3a3"
LEGACY_PLAYER_PATH = "/" + LEGACY_PLAYER_SHA256[:41] + ".js"
PACKAGE = "dev/yossi/onnkeyguard/"
RUNTIME = "Ldev/yossi/onnkeyguard/SchoolBannerRuntime;"
POLICY_RUNTIME = "Ldev/yossi/onnkeyguard/PolicyRuntime;"
METHOD_HASHES = {
    'KeyGuardService:onDestroy()V': 'c353f4f1fe48bff948313ab73b5429f9cc68d455af9e478c1df965ddb6ac6098',
    'KeyGuardService:onServiceConnected()V': '6e8fa1170dfe0b8416726346e36b46d23d0920ef49c8837deae8fad9dfcf0696',
    'KeyGuardService:onUnbind(Landroid/content/Intent;)Z': '30301fc4b9f95450db7e068e5f5f293740e947e7c1b968d953e654184d1d8b42',
    'PolicyRuntime:handleEvent(Lorg/json/JSONObject;)Lorg/json/JSONObject;': '1ba2d04c6d0eee950a06c6814e38f32f538c2a5bda9e159ce97c3e8ea83e297d',
    'PolicyRuntime:persistSessionTransition(J)V': 'dae65c88719cbde50bfeea131b4153b18f48b732304e8bd3732ab6202e6403b9',
    'PolicyRuntime:statusJson()Lorg/json/JSONObject;': 'e975667ad577c889231ffb8a2cf8887bdc4ae1ef6ae6cbc01167fcbd787d7a74',
    'PolicyRuntime:admin(Landroid/content/Intent;)Lorg/json/JSONObject;': '9e0430b4d6d3f6a21f057f7ae5da755bff351e71bbef579adb15652679426531',
    'PolicyRuntime:physicalProtectionActive()Z': '1f263f792b2af28a9137749ef7cd5bb1137899750e94fd35181dcbd3e8935cf5',
    'SesameSessionPolicy:observePlayback(Ljava/lang/String;DDLdev/yossi/onnkeyguard/SesameSessionPolicy$PlaybackPhase;J)V': 'd8eed4f2fbcd4dbab3fd06b1ebf21727033470330aa0fe530147a59eb74c8da2',
    'PlayerScriptAsset:<init>([BLjava/lang/String;)V': '0a504afc954a2e2c875be7e02d80bea082ee16b0fc64c9cc3b9a762e93665bcd',
}
METHOD_PATTERN = re.compile(r"^\.method [^\n]+\n.*?^\.end method", re.M | re.S)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(*args):
    subprocess.run(list(map(str, args)), check=True, timeout=90)


def smali(jar, command, *args):
    run("java", "-cp", jar, "com.android.tools.smali." + command, *args)


def methods(text):
    result = {}
    for match in METHOD_PATTERN.finditer(text):
        value = match[0]
        signature = value.splitlines()[0].split()[-1]
        if signature in result:
            raise ValueError("duplicate_method:" + signature)
        result[signature] = value
    return result


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("unexpected_anchor:" + old[:160])
    return text.replace(old, new, 1)


def after_registers(method, code):
    anchors = list(re.finditer(r"^    \.registers \d+\n", method, re.M))
    if len(anchors) != 1:
        raise ValueError("unexpected_register_directive")
    i = anchors[0].end()
    return method[:i] + "\n" + code + "\n" + method[i:]


def canonical(text):
    # Disassembly may add separate goto/conditional/try labels for one address.
    # Normalize references to their instruction ordinal, preserving every
    # opcode, register count, catch region and annotation. Debug .line placement
    # can move across an adjacent label without changing execution.
    lines = text.splitlines()
    mapping = {}
    definitions = set()
    ordinal = 0
    payload = False
    for index, raw in enumerate(lines):
        line = raw.strip()
        if line.startswith((".packed-switch ", ".sparse-switch", ".array-data ")):
            payload = True
            ordinal += 1
        elif line.startswith((".end packed-switch", ".end sparse-switch", ".end array-data")):
            payload = False
        elif re.fullmatch(r":[A-Za-z_][A-Za-z_0-9]*", line) and not payload:
            mapping[line] = f":instruction_{ordinal}"
            definitions.add(index)
        elif line and not line.startswith((".", ":", "#")):
            ordinal += 1
    result = []
    for index, raw in enumerate(lines):
        line = raw.strip()
        if index in definitions or not line or line.startswith(("#", ".line ")):
            continue
        result.append(re.sub(r":[A-Za-z_][A-Za-z_0-9]*", lambda m: mapping.get(m[0], m[0]), line))
    return "\n".join(result)


def class_header(text):
    return canonical(METHOD_PATTERN.sub("", text))


def instructions(text):
    return [line.strip() for line in text.splitlines()
            if line.strip() and not line.strip().startswith((".", ":", "#"))]


def dex_integrity(data):
    if not data.startswith(b"dex\n") or struct.unpack_from("<I", data, 32)[0] != len(data):
        raise ValueError("invalid_dex_header")
    if hashlib.sha1(data[32:]).digest() != data[12:32]:
        raise ValueError("invalid_dex_signature")
    if zlib.adler32(data[12:]) & 0xffffffff != struct.unpack_from("<I", data, 8)[0]:
        raise ValueError("invalid_dex_checksum")
    return {"sha256": digest(data), "bytes": len(data), "sha1_signature_valid": True,
            "adler32_checksum_valid": True}


SYNC_METHOD = """.method private syncSchoolBanner(J)V
    .registers 8

    iget-object v0, p0, Ldev/yossi/onnkeyguard/PolicyRuntime;->session:Ldev/yossi/onnkeyguard/SesameSessionPolicy;
    invoke-virtual {v0, p1, p2}, Ldev/yossi/onnkeyguard/SesameSessionPolicy;->status(J)Ldev/yossi/onnkeyguard/SesameSessionPolicy$Snapshot;
    move-result-object v0

    const/4 v1, 0x0
    iget-object v2, p0, Ldev/yossi/onnkeyguard/PolicyRuntime;->rollout:Ldev/yossi/onnkeyguard/PolicyRuntime$Rollout;
    sget-object v3, Ldev/yossi/onnkeyguard/PolicyRuntime$Rollout;->ENABLED:Ldev/yossi/onnkeyguard/PolicyRuntime$Rollout;
    if-ne v2, v3, :banner_disabled
    iget-object v2, p0, Ldev/yossi/onnkeyguard/PolicyRuntime;->sessionMode:Ldev/yossi/onnkeyguard/SessionMode;
    sget-object v3, Ldev/yossi/onnkeyguard/SessionMode;->SCHEDULED:Ldev/yossi/onnkeyguard/SessionMode;
    if-ne v2, v3, :banner_disabled
    const/4 v1, 0x1

    :banner_disabled
    iget-object v2, p0, Ldev/yossi/onnkeyguard/PolicyRuntime;->inputObserver:Ldev/yossi/onnkeyguard/InputObserverPolicy;
    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J
    move-result-wide v3
    invoke-virtual {v2, v3, v4}, Ldev/yossi/onnkeyguard/InputObserverPolicy;->isLive(J)Z
    move-result v2
    invoke-static {v0, v1, v2, p1, p2}, Ldev/yossi/onnkeyguard/SchoolBannerRuntime;->sync(Ldev/yossi/onnkeyguard/SesameSessionPolicy$Snapshot;ZZJ)V
    return-void
.end method"""


def patch_method(class_name, signature, old):
    if class_name == "KeyGuardService":
        if signature == "onServiceConnected()V":
            anchor = "    invoke-virtual {v0, p0}, Ldev/yossi/onnkeyguard/PolicyRuntime;->attach(Ldev/yossi/onnkeyguard/KeyGuardService;)V"
            return replace_once(old, anchor, anchor + "\n\n    invoke-static {p0}, " + RUNTIME + "->attach(Landroid/accessibilityservice/AccessibilityService;)V")
        if signature in ("onDestroy()V", "onUnbind(Landroid/content/Intent;)Z"):
            return after_registers(old, "    invoke-static {p0}, " + RUNTIME + "->detach(Landroid/accessibilityservice/AccessibilityService;)V")
    if class_name == "PolicyRuntime":
        if signature == "persistSessionTransition(J)V":
            return after_registers(old, "    invoke-direct {p0, p1, p2}, " + POLICY_RUNTIME + "->syncSchoolBanner(J)V")
        if signature == "handleEvent(Lorg/json/JSONObject;)Lorg/json/JSONObject;":
            anchor = "    :goto_240\n    invoke-direct {v1, v13, v14}, " + POLICY_RUNTIME + "->persistSessionTransition(J)V"
            # p1 remains the input JSONObject; v0 has been reused for a schema integer.
            return replace_once(old, anchor, "    :goto_240\n    invoke-static/range {p1 .. p1}, " + RUNTIME
                    + "->onAcceptedEvent(Lorg/json/JSONObject;)V\n\n    invoke-direct {v1, v13, v14}, "
                    + POLICY_RUNTIME + "->persistSessionTransition(J)V")
        if signature == "statusJson()Lorg/json/JSONObject;":
            return replace_once(old, "    return-object v1", "    invoke-static {v1}, " + RUNTIME
                    + "->decorateStatus(Lorg/json/JSONObject;)Lorg/json/JSONObject;\n\n    move-result-object v1\n\n    return-object v1")
        if signature == "physicalProtectionActive()Z":
            anchor = "    invoke-direct {p0, v0, v1}, " + POLICY_RUNTIME + "->refreshObserverSafety(J)V"
            addition = """

    invoke-direct {p0, v0, v1}, Ldev/yossi/onnkeyguard/PolicyRuntime;->syncSchoolBanner(J)V
    invoke-static {}, Ldev/yossi/onnkeyguard/SchoolBannerRuntime;->physicalProtectionActive()Z
    move-result v2
    if-eqz v2, :banner_check_original_protection
    const/4 v2, 0x1
    return v2

    :banner_check_original_protection"""
            return replace_once(old, anchor, anchor + addition)
        if signature == "admin(Landroid/content/Intent;)Lorg/json/JSONObject;":
            anchor = "    :cond_13\n    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J"
            replacement = """    :cond_13
    invoke-static {v0}, Ldev/yossi/onnkeyguard/SchoolBannerRuntime;->onAdminCommand(Ljava/lang/String;)V
    const-string v4, "test_banner"
    invoke-virtual {v4, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v4
    if-eqz v4, :banner_original_admin
    const-string v4, "duration_seconds"
    const/16 v5, 0x1e
    invoke-virtual {p1, v4, v5}, Landroid/content/Intent;->getIntExtra(Ljava/lang/String;I)I
    move-result v4
    iget-object v5, p0, Ldev/yossi/onnkeyguard/PolicyRuntime;->inputObserver:Ldev/yossi/onnkeyguard/InputObserverPolicy;
    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J
    move-result-wide v6
    invoke-virtual {v5, v6, v7}, Ldev/yossi/onnkeyguard/InputObserverPolicy;->isLive(J)Z
    move-result v5
    invoke-static {v4, v5}, Ldev/yossi/onnkeyguard/SchoolBannerRuntime;->testBanner(IZ)Lorg/json/JSONObject;
    move-result-object v4
    return-object v4

    :banner_original_admin
    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J"""
            return replace_once(old, anchor, replacement)
    if class_name == "SesameSessionPolicy" and signature.startswith("observePlayback("):
        anchor = """    sget-object p1, Ldev/yossi/onnkeyguard/SesameSessionPolicy$Reason;->PLAYBACK_COMPLETE:Ldev/yossi/onnkeyguard/SesameSessionPolicy$Reason;

    invoke-direct {p0, p1}, Ldev/yossi/onnkeyguard/SesameSessionPolicy;->complete(Ldev/yossi/onnkeyguard/SesameSessionPolicy$Reason;)V"""
        # Keep real ENDED and expiry semantics. Near-end position must not release
        # the show guard one sample early or discard the subsequent true end.
        return replace_once(old, anchor, "    goto :cond_a9")
    if class_name == "PlayerScriptAsset" and signature == "<init>([BLjava/lang/String;)V":
        # The deployed Cobalt APK embeds this exact route. Route identity stays
        # stable while sha256, ETag and load-time digest verification use the
        # actual replacement asset. Existing strict ?v= parsing is untouched.
        start = old.index("    .line 22\n")
        end = old.index("    .line 23\n", start)
        original_path = old[start:end]
        if "const/16 v2, 0x29" not in original_path or "->substring(II)Ljava/lang/String;" not in original_path:
            raise ValueError("unexpected_player_path_derivation")
        fixed = ('    .line 22\n    const-string v0, "' + LEGACY_PLAYER_PATH + '"\n\n'
                 '    iput-object v0, p0, Ldev/yossi/onnkeyguard/PlayerScriptAsset;->path:Ljava/lang/String;\n\n')
        return old[:start] + fixed + old[end:]
    raise ValueError("unhandled_patch:" + class_name + ":" + signature)


def compile_new(args, out):
    here = Path(__file__).resolve().parent
    classes = out / "compiled-new"
    stub_classes = out / "compiled-stubs"
    classes.mkdir()
    stub_classes.mkdir()
    stub = here / "compile-stubs/dev/yossi/onnkeyguard/SesameSessionPolicy.java"
    sources = sorted((here / "src").rglob("*.java"))
    if {p.name for p in sources} != {"SchoolBannerPolicy.java", "SchoolBannerRuntime.java"}:
        raise ValueError("unexpected_source_inventory")
    javac = ["java", "--module", "jdk.compiler/com.sun.tools.javac.Main", "-source", "8", "-target", "8"]
    run(*javac, "-cp", args.android_jar, "-d", stub_classes, stub)
    classpath = str(args.android_jar) + ":" + str(stub_classes)
    run(*javac, "-sourcepath", here / "src", "-cp", classpath, "-d", classes, *sources)
    new_classes = sorted(classes.rglob("*.class"))
    if any(not p.name.startswith("SchoolBanner") for p in new_classes):
        raise ValueError("unexpected_new_class_file")
    stub_jar = out / "compile-classpath.jar"
    with zipfile.ZipFile(stub_jar, "w") as jar:
        for cls in stub_classes.rglob("*.class"):
            jar.write(cls, str(cls.relative_to(stub_classes)))
    dex_dir = out / "new-class-dex"
    dex_dir.mkdir()
    run("java", "-cp", args.d8_jar, "com.android.tools.r8.D8", "--min-api", "26", "--lib", args.android_jar,
        "--classpath", stub_jar, "--output", dex_dir, *new_classes)
    new_smali = out / "new-class-smali"
    smali(args.smali_jar, "baksmali.Main", "disassemble", dex_dir / "classes.dex", "-o", new_smali)
    relative_classes = {p.relative_to(new_smali) for p in new_smali.rglob("*.smali")}
    if not relative_classes or any(str(p.parent) != PACKAGE.rstrip("/") or not p.name.startswith("SchoolBanner")
                                   for p in relative_classes):
        raise ValueError("unexpected_compiled_dex_class_inventory")
    return new_smali, relative_classes, {p.name: digest(p.read_bytes()) for p in sources}


def build(args):
    if digest(args.base.read_bytes()) != BASE_APK_SHA256:
        raise ValueError("unexpected_base_apk")
    out = args.output
    if out.exists() and any(out.iterdir()):
        raise ValueError("output_directory_must_be_empty")
    out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.base) as apk:
        original_dex = apk.read("classes.dex")
        if digest(apk.read("assets/player.js")) != LEGACY_PLAYER_SHA256:
            raise ValueError("unexpected_original_player_asset")
    if digest(original_dex) != BASE_DEX_SHA256:
        raise ValueError("unexpected_base_dex")
    dex_integrity(original_dex)
    (out / "original.dex").write_bytes(original_dex)
    original = out / "original-smali"
    candidate = out / "candidate-smali"
    readback = out / "readback-smali"
    smali(args.smali_jar, "baksmali.Main", "disassemble", out / "original.dex", "-o", original)
    shutil.copytree(original, candidate)

    expected_methods = {}
    changed = []
    for class_name in ("KeyGuardService", "PolicyRuntime", "SesameSessionPolicy", "PlayerScriptAsset"):
        relative = Path(PACKAGE + class_name + ".smali")
        source = (original / relative).read_text()
        for signature, method in methods(source).items():
            key = class_name + ":" + signature
            if key not in METHOD_HASHES:
                continue
            if digest(method.encode()) != METHOD_HASHES[key]:
                raise ValueError("unexpected_method:" + key)
            patched = patch_method(class_name, signature, method)
            if patched == method:
                raise ValueError("empty_patch:" + key)
            if re.search(r"\.registers (\d+)", patched)[1] != re.search(r"\.registers (\d+)", method)[1]:
                raise ValueError("existing_register_count_changed:" + key)
            source = replace_once(source, method, patched)
            expected_methods[(relative, signature)] = patched
            changed.append({"class": str(relative), "method": signature,
                            "before_sha256": digest(method.encode()), "after_source_sha256": digest(patched.encode()),
                            "before_instructions": len(instructions(method)), "after_instructions": len(instructions(patched))})
        if class_name == "PolicyRuntime":
            if "syncSchoolBanner(J)V" in methods(source):
                raise ValueError("bridge_already_exists")
            source += "\n\n" + SYNC_METHOD + "\n"
            expected_methods[(relative, "syncSchoolBanner(J)V")] = SYNC_METHOD
        (candidate / relative).write_text(source)
    if len(changed) != len(METHOD_HASHES):
        raise ValueError("missing_target_patch")
    snapshot = (original / (PACKAGE + "SesameSessionPolicy$Snapshot.smali")).read_text()
    for field in ("id:Ljava/lang/String;", "videoId:Ljava/lang/String;",
                  "phase:Ldev/yossi/onnkeyguard/SesameSessionPolicy$Phase;", "reason:Ljava/lang/String;"):
        if not re.search(r"^\.field public final " + re.escape(field) + r"$", snapshot, re.M):
            raise ValueError("snapshot_abi_mismatch:" + field)

    new_smali, added_classes, source_hashes = compile_new(args, out)
    for relative in sorted(added_classes):
        if (candidate / relative).exists():
            raise ValueError("new_class_overwrites_existing:" + str(relative))
        shutil.copy2(new_smali / relative, candidate / relative)
    smali(args.smali_jar, "smali.Main", "assemble", candidate, "-o", out / "classes.dex")
    patched_dex = (out / "classes.dex").read_bytes()
    integrity = dex_integrity(patched_dex)
    smali(args.smali_jar, "baksmali.Main", "disassemble", out / "classes.dex", "-o", readback)

    original_files = {p.relative_to(original) for p in original.rglob("*.smali")}
    if {p.relative_to(readback) for p in readback.rglob("*.smali")} != original_files | added_classes:
        raise ValueError("final_class_inventory_changed")
    total_methods = 0
    unchanged_methods = 0
    for relative in sorted(original_files):
        old_text = (original / relative).read_text()
        new_text = (readback / relative).read_text()
        old_methods = methods(old_text)
        new_methods = methods(new_text)
        additions = {"syncSchoolBanner(J)V"} if relative == Path(PACKAGE + "PolicyRuntime.smali") else set()
        if new_methods.keys() != old_methods.keys() | additions:
            raise ValueError("unexpected_existing_class_method_inventory:" + str(relative))
        if class_header(old_text) != class_header(new_text):
            raise ValueError("existing_fields_or_annotations_changed:" + str(relative))
        total_methods += len(old_methods)
        for signature, actual in new_methods.items():
            expected = expected_methods.get((relative, signature), old_methods.get(signature))
            if canonical(actual) != canonical(expected):
                (out / "comparison-expected.txt").write_text(canonical(expected))
                (out / "comparison-actual.txt").write_text(canonical(actual))
                raise ValueError("assembled_method_differs:" + str(relative) + ":" + signature)
            if (relative, signature) not in expected_methods:
                unchanged_methods += 1
    for relative in added_classes:
        if canonical((readback / relative).read_text()) != canonical((new_smali / relative).read_text()):
            raise ValueError("compiled_new_class_changed_during_merge:" + str(relative))

    metadata = {"revision": "school-banner-v2-20261117", "source_sha256": source_hashes,
                "base_apk_sha256": BASE_APK_SHA256, "base_dex_sha256": BASE_DEX_SHA256,
                "timezone": "America/Los_Angeles", "deadline": "09:30", "completion": "actual_ended",
                "stable_player_request_path": LEGACY_PLAYER_PATH,
                "holidays": "US federal actual and observed dates", "phone_override": "persistent for local morning"}
    unsigned = out / "OnnKeyGuard-v8-school-banner-unsigned.apk"
    retained = {}
    with zipfile.ZipFile(args.base) as old, zipfile.ZipFile(unsigned, "w") as new:
        for entry in old.infolist():
            if entry.filename.startswith("META-INF/") and (
                    entry.filename.endswith((".SF", ".RSA", ".DSA", ".EC")) or entry.filename == "META-INF/MANIFEST.MF"):
                continue
            value = patched_dex if entry.filename == "classes.dex" else old.read(entry.filename)
            if entry.filename != "classes.dex":
                retained[entry.filename] = digest(value)
            new.writestr(entry, value)
        new.writestr("assets/school_banner_revision.json", json.dumps(metadata, sort_keys=True, indent=2) + "\n")
    with zipfile.ZipFile(unsigned) as apk:
        for name, expected_hash in retained.items():
            if digest(apk.read(name)) != expected_hash:
                raise ValueError("retained_payload_changed:" + name)
    report = {
        "base_apk_sha256": BASE_APK_SHA256, "base_dex_sha256": BASE_DEX_SHA256,
        "changed_methods": changed, "existing_classes": len(original_files), "existing_methods": total_methods,
        "unchanged_existing_methods": unchanged_methods, "changed_existing_methods": len(changed),
        "added_existing_class_methods": ["PolicyRuntime.syncSchoolBanner(J)V"],
        "added_classes": [str(p) for p in sorted(added_classes)], "new_source_sha256": source_hashes,
        "existing_register_counts_unchanged": True, "all_untouched_methods_canonically_verified": True,
        "stable_player_request_path": LEGACY_PLAYER_PATH,
        "candidate_dex": integrity, "retained_apk_payloads": retained,
        "unsigned_apk": str(unsigned.resolve()), "unsigned_apk_sha256": digest(unsigned.read_bytes()),
        "signed": False, "installed": False,
    }
    (out / "invariants.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--smali-jar", type=Path, required=True)
    parser.add_argument("--d8-jar", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    build(parser.parse_args())
