#!/usr/bin/env python3
"""Patch the exact installed Guard with admitted morning-window integration; no signing/device I/O."""
import argparse
import json
from pathlib import Path
import shutil
import zipfile
from dex_tools import digest, run, smali, methods, replace_once, after_registers, canonical, class_header, dex_integrity
from patch_helper_visibility import patch_manifest, HELPER

BASE = "1a56676eb5eaef7fda9a0d8a33543e6002accb2ec49bb26fd3d7133ea3b0c2a3"
DEX = "cd97484ea6911cd0395bef60060a57fde50e31ed8656f5e7a29b5e67ab2ea566"
PLAYER = "b42d6c054448ea37d691751466aee224b77d8b29d89e81f7a2a4df99ad589b11"
PKG = Path("dev/yossi/onnkeyguard")
R = "Ldev/yossi/onnkeyguard/SchoolBannerRuntime;"
P = "Ldev/yossi/onnkeyguard/PolicyRuntime;"
OWNED = ("SchoolBannerPolicy", "SchoolBannerRuntime", "MorningHistoryCache", "MorningWindowGrant")


def owned(path):
    return any(path.name == name + ".smali" or path.name.startswith(name + "$") for name in OWNED)


def hook(cls, sig, old):
    if cls == "MorningPlaybackReceiver":
        old = replace_once(old, "    :cond_3\n", "    :cond_3\n    invoke-static {p0, p2}, " + R + "->captureSender(Landroid/content/BroadcastReceiver;Landroid/content/Intent;)V\n\n")
        return replace_once(old, "    :cond_e5\n", """    :cond_e5
    invoke-static {p2}, Ldev/yossi/onnkeyguard/SchoolBannerRuntime;->bindCandidate(Landroid/content/Intent;)Z
    move-result v0
    if-nez v0, :morning_candidate_allowed
    return-void
    :morning_candidate_allowed
""")
    if cls == "PolicyRuntime" and sig.startswith("scheduledStartupAuthentication("):
        old = after_registers(old, """    move-object/from16 v0, p1
    const-string v1, "command"
    invoke-virtual {v0, v1}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    const-string v2, "history"
    invoke-virtual {v2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :morning_original_startup
    const-string v1, "run_generation"
    invoke-virtual {v0, v1}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    move-object/from16 v2, p0
    invoke-virtual {v2, v1}, Ldev/yossi/onnkeyguard/PolicyRuntime;->allowsStartupWork(Ljava/lang/String;)Z
    move-result v1
    invoke-static {v0, v1}, Ldev/yossi/onnkeyguard/SchoolBannerRuntime;->historyForRun(Landroid/content/Intent;Z)Lorg/json/JSONObject;
    move-result-object v0
    return-object v0
    :morning_original_startup""")
        return replace_once(old, "    :cond_219\n", """    :cond_219
    invoke-static/range {p1 .. p1}, Ldev/yossi/onnkeyguard/SchoolBannerRuntime;->scheduledStart(Landroid/content/Intent;)Z
    move-result v0
    if-nez v0, :morning_window_admitted
    invoke-direct/range {p0 .. p0}, Ldev/yossi/onnkeyguard/PolicyRuntime;->cancelStartup()V
    const-string v0, "morning_window_not_admitted"
    invoke-static {v0}, Ldev/yossi/onnkeyguard/PolicyRuntime;->error(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v0
    return-object v0
    :morning_window_admitted
""")
    if cls == "PolicyRuntime" and sig.startswith("handleEvent("):
        return after_registers(old, """    invoke-static/range {p1 .. p1}, Ldev/yossi/onnkeyguard/SchoolBannerRuntime;->controllerEvent(Lorg/json/JSONObject;)Lorg/json/JSONObject;
    move-result-object v0
    if-eqz v0, :morning_original_event
    return-object v0
    :morning_original_event""")
    if cls == "PolicyRuntime" and sig.startswith("allowsStartupWork("):
        return after_registers(old, """    invoke-static {p1}, Ldev/yossi/onnkeyguard/SchoolBannerRuntime;->allowsStartupWork(Ljava/lang/String;)Z
    move-result v0
    if-nez v0, :morning_original_work
    const/4 v0, 0x0
    return v0
    :morning_original_work""")
    if cls == "PolicyRuntime" and sig.startswith("startSession("):
        return after_registers(old, """    invoke-static {p1, p2}, Ldev/yossi/onnkeyguard/SchoolBannerRuntime;->allowsEpisodeConfirmation(Ljava/lang/String;Ljava/lang/String;)Z
    move-result v0
    if-nez v0, :morning_original_confirmation
    const-string v0, "morning_window_not_live"
    iput-object v0, p0, Ldev/yossi/onnkeyguard/PolicyRuntime;->lastStartResult:Ljava/lang/String;
    return-void
    :morning_original_confirmation""")
    if cls == "PolicyRuntime" and sig.startswith("admin("):
        return replace_once(old, "    :cond_13\n", """    :cond_13
    iget-object v4, p0, Ldev/yossi/onnkeyguard/PolicyRuntime;->authentication:Ldev/yossi/onnkeyguard/PolicyRuntime$Authentication;
    sget-object v5, Ldev/yossi/onnkeyguard/PolicyRuntime$Authentication;->CLEAR:Ldev/yossi/onnkeyguard/PolicyRuntime$Authentication;
    if-ne v4, v5, :morning_auth_not_clear
    const/4 v4, 0x1
    goto :morning_admin_auth_ready
    :morning_auth_not_clear
    const/4 v4, 0x0
    :morning_admin_auth_ready
    iget-object v5, p0, Ldev/yossi/onnkeyguard/PolicyRuntime;->foregroundPackage:Ljava/lang/String;
    invoke-static {p1, v4, v5}, Ldev/yossi/onnkeyguard/SchoolBannerRuntime;->admin(Landroid/content/Intent;ZLjava/lang/String;)Lorg/json/JSONObject;
    move-result-object v4
    if-eqz v4, :morning_original_admin
    return-object v4
    :morning_original_admin
""")
    if cls == "SesameSessionPolicy" and sig.startswith("begin("):
        anchor = "    iput-wide p4, p0, Ldev/yossi/onnkeyguard/SesameSessionPolicy;->expiresAtMs:J"
        return replace_once(old, anchor, "    invoke-static {p1, p4, p5}, " + R
            + "->sessionDeadline(Ljava/lang/String;J)J\n    move-result-wide p4\n\n" + anchor)
    raise ValueError("unowned_hook:" + cls + ":" + sig)


CANCEL_METHOD = """.method public cancelMorningWindowWork(Ljava/lang/String;)V
    .registers 4
    if-eqz p1, :morning_cancel_done
    iget-object v0, p0, Ldev/yossi/onnkeyguard/PolicyRuntime;->startupRun:Ldev/yossi/onnkeyguard/StartupRunPolicy;
    invoke-virtual {v0}, Ldev/yossi/onnkeyguard/StartupRunPolicy;->runId()Ljava/lang/String;
    move-result-object v0
    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :morning_cancel_done
    invoke-direct {p0}, Ldev/yossi/onnkeyguard/PolicyRuntime;->cancelStartup()V
    :morning_cancel_done
    return-void
.end method
"""

TARGETS = {
    "MorningPlaybackReceiver": ["onReceive(Landroid/content/Context;Landroid/content/Intent;)V"],
    "PolicyRuntime": ["scheduledStartupAuthentication(Landroid/content/Intent;)Lorg/json/JSONObject;",
        "handleEvent(Lorg/json/JSONObject;)Lorg/json/JSONObject;", "allowsStartupWork(Ljava/lang/String;)Z",
        "startSession(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V",
        "admin(Landroid/content/Intent;)Lorg/json/JSONObject;"],
    "SesameSessionPolicy": ["begin(Ljava/lang/String;Ljava/lang/String;IJ)Ldev/yossi/onnkeyguard/SesameSessionPolicy$BeginResult;"],
}


def build(args):
    here = Path(__file__).resolve().parent
    if digest(args.base.read_bytes()) != BASE:
        raise ValueError("wrong_installed_base")
    out = args.output.resolve()
    if out.exists() and any(out.iterdir()):
        raise ValueError("output_must_be_empty")
    out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.base) as z:
        original_dex = z.read("classes.dex")
        if digest(original_dex) != DEX or digest(z.read("assets/player.js")) != PLAYER:
            raise ValueError("wrong_installed_payload")
        if z.read("assets/player.sha256") != (PLAYER + "\n").encode():
            raise ValueError("wrong_installed_player_digest")
    (out / "original.dex").write_bytes(original_dex)
    original = out / "original-smali"
    smali(args.smali_jar, "baksmali.Main", "disassemble", out / "original.dex", "-o", original)
    compiled = out / "compiled"
    compiled.mkdir()
    sources = sorted((here / "src" / PKG).glob("*.java"))
    stubs = sorted((here / "compile-stubs" / PKG).glob("*.java"))
    run("java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "8", "-target", "8",
        "-Xlint:-options", "-bootclasspath", args.android_jar, "-d", compiled, *stubs, *sources)
    program = [p for p in sorted(compiled.rglob("*.class")) if any(p.name == name + ".class"
                or p.name.startswith(name + "$") for name in OWNED)]
    dexout = out / "compiled-dex"; dexout.mkdir()
    run("java", "-cp", args.d8_jar, "com.android.tools.r8.D8", "--min-api", "26", "--lib", args.android_jar,
        "--classpath", compiled, "--output", dexout, *program)
    fresh = out / "new-smali"
    smali(args.smali_jar, "baksmali.Main", "disassemble", dexout / "classes.dex", "-o", fresh)
    old = {p.relative_to(original):p.read_text() for p in original.rglob("*.smali")}
    new = {p.relative_to(fresh):p.read_text() for p in fresh.rglob("*.smali")}
    if not all(p.parent == PKG and owned(p) for p in new): raise ValueError("unexpected_compiled_class")
    expected = {p:s for p,s in old.items() if not owned(p)}
    changes = []
    for cls, signatures in TARGETS.items():
        path = PKG / (cls + ".smali")
        text = expected[path]
        for signature in signatures:
            before = methods(text)[signature]
            after = hook(cls, signature, before)
            text = replace_once(text, before, after)
            changes.append({"class":cls, "method":signature, "before_sha256":digest(before.encode()),
                            "after_sha256":digest(after.encode())})
        if cls == "PolicyRuntime":
            if "cancelMorningWindowWork(" in text: raise ValueError("duplicate_cancel_hook")
            text += "\n" + CANCEL_METHOD
        expected[path] = text
    expected.update(new)
    merged = out / "merged-smali"
    for path, text in expected.items():
        target = merged / path; target.parent.mkdir(parents=True, exist_ok=True); target.write_text(text)
    patched = out / "classes.dex"
    smali(args.smali_jar, "smali.Main", "assemble", merged, "-o", patched)
    roundtrip = out / "roundtrip-smali"
    smali(args.smali_jar, "baksmali.Main", "disassemble", patched, "-o", roundtrip)
    actual = {p.relative_to(roundtrip):p.read_text() for p in roundtrip.rglob("*.smali")}
    if set(actual) != set(expected): raise ValueError("class_inventory_changed")
    retained_methods = 0
    for path, text in expected.items():
        if class_header(text) != class_header(actual[path]): raise ValueError("class_header_changed:" + str(path))
        left, right = methods(text), methods(actual[path])
        if set(left) != set(right): raise ValueError("method_inventory_changed:" + str(path))
        for signature, method in left.items():
            if canonical(method) != canonical(right[signature]): raise ValueError("roundtrip_changed:" + str(path) + ":" + signature)
            if not owned(path) and signature not in TARGETS.get(path.stem, ()) and signature != "cancelMorningWindowWork(Ljava/lang/String;)V":
                if canonical(methods(old[path])[signature]) != canonical(method): raise ValueError("unowned_method_changed")
                retained_methods += 1
    for path, text in old.items():
        if not owned(path): continue
        old_api = {name for name,m in methods(text).items() if "public" in m.splitlines()[0].split()}
        if path in new and not old_api <= set(methods(new[path])):
            raise ValueError("public_api_removed:" + str(path))
    dex = patched.read_bytes(); integrity = dex_integrity(dex)
    unsigned = out / "guard-morning-unsigned.apk"
    pins = {}
    replacement_player = args.player_script.read_bytes() if args.player_script else None
    with zipfile.ZipFile(args.base) as source, zipfile.ZipFile(unsigned,"w") as dest:
        for info in source.infolist():
            if info.filename.startswith("META-INF/"): continue
            payload = dex if info.filename == "classes.dex" else source.read(info.filename)
            if info.filename == "AndroidManifest.xml":
                payload = patch_manifest(payload)
            if info.filename == "assets/player.js" and replacement_player is not None:
                payload = replacement_player
            if info.filename == "assets/player.sha256" and replacement_player is not None:
                payload = (digest(replacement_player) + "\n").encode()
            dest.writestr(info, payload)
            pins[info.filename] = digest(payload)
    with zipfile.ZipFile(unsigned) as verify:
        if verify.read("assets/player.sha256") != (digest(verify.read("assets/player.js")) + "\n").encode():
            raise ValueError("replacement_player_digest_mismatch")
    report = {"base_apk_sha256":BASE, "base_dex_sha256":DEX, "unsigned_apk":str(unsigned),
              "unsigned_sha256":digest(unsigned.read_bytes()), "unsigned_bytes":unsigned.stat().st_size,
              "patched_methods":changes, "added_method":"PolicyRuntime.cancelMorningWindowWork",
              "retained_methods_verified":retained_methods, "all_unowned_methods_verified":True,
              "new_classes":list(map(str,sorted(new))), "payloads":pins, "candidate_dex":integrity,
              "source_sha256":{str(p.relative_to(here)):digest(p.read_bytes()) for p in sources},
              "manifest_visibility":{"exact_package":HELPER,"unowned_binary_xml_nodes_unchanged":True},
              "signed":False, "installed":False}
    (out / "invariants.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps({k:report[k] for k in ("unsigned_apk","unsigned_sha256","unsigned_bytes","retained_methods_verified")},indent=2))


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("base","smali-jar","d8-jar","android-jar","output"):
        p.add_argument("--"+name,type=Path,required=True)
    p.add_argument("--player-script",type=Path)
    build(p.parse_args())
