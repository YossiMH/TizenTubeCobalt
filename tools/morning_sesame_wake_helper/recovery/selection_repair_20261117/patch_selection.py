#!/usr/bin/env python3
"""Narrow, pinned update to the installed v12 helper; keeps startup fencing bytecode intact."""
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

BASE_APK_SHA256 = "4bfbeebfde3d308bd9dfd4039d0d759f80bbd1618efac59d56bc062a3fdb1f53"
BASE_DEX_SHA256 = "1f62decea3e1d59732adf794647da0bad6696a678c4c3966d02e3a7b3d583c1c"
PACKAGE = Path("dev/yossi/morningsesame")
SOURCES = ["MorningEpisodePolicy", "MorningStartHistory", "MorningSelectionRepair", "MorningChannelPage", "MorningChannelSource"]
TARGETS = {
    ("AlarmReceiver", ".method private static selectAndVerify(Landroid/content/Context;Ljava/lang/String;)V"):
        "9fc299d13e04a1945a4b851d628fdea2987147178fb53cec11f5d335b609a60b",
    ("WakeAndPlayActivity", ".method private fetchCandidates()Ljava/util/Map;"):
        "7e6c285d38259d5b7637e713794cd318a7baf62855a4d580c3bacafc36a48709",
    ("RunCoordinator", ".method static declared-synchronized confirmPlayed(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V"):
        "7d9b0d6b2eba512ef37690a603d3a450f4031b03d0af4a37b9317c90b19d1803",
    ("DiagnosticArchive", ".method static constructor <clinit>()V"):
        "53faece3bae235b51704311620bb04933db365d7007b65d43a233c33ff813a1a",
    ("WakeAndPlayActivity", ".method private fetchCandidatesWithRetry()Ljava/util/Map;"):
        "3c5cb1006bfb5f22b44d64b1f5135ec2da0ff1a7ac0508188baf8965c41fbb2e",
}
SELECT = """.method private static selectAndVerify(Landroid/content/Context;Ljava/lang/String;)V
    .registers 5
    const-string v0, "selecting_account_progress"
    const/4 v1, 0x0
    const/4 v2, 0x0
    invoke-static {p0, p1, v0, v1, v2}, Ldev/yossi/morningsesame/RunCoordinator;->note(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Z
    move-result v0
    if-eqz v0, :done
    invoke-static {p0, p1}, Ldev/yossi/morningsesame/AlarmReceiver;->readProgressWithRetry(Landroid/content/Context;Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v0
    invoke-static {p0, p1, v0}, Ldev/yossi/morningsesame/MorningSelectionRepair;->selectAndVerify(Landroid/content/Context;Ljava/lang/String;Lorg/json/JSONObject;)V
    :done
    return-void
.end method"""
FETCH = """.method private fetchCandidates()Ljava/util/Map;
    .registers 3
    const-string v0, "https://www.youtube.com/@SesameStreetClassics/videos"
    invoke-direct {p0, v0}, Ldev/yossi/morningsesame/WakeAndPlayActivity;->fetch(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    iget-object v1, p0, Ldev/yossi/morningsesame/WakeAndPlayActivity;->runId:Ljava/lang/String;
    invoke-static {p0, v1, v0}, Ldev/yossi/morningsesame/MorningChannelSource;->fetch(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Ljava/util/Map;
    move-result-object v0
    return-object v0
.end method"""
STUBS = {
"RunCoordinator": """package dev.yossi.morningsesame; import android.content.Context;
final class RunCoordinator {
 static native boolean checkpoint(Context c,String r);
 static native boolean startupWorkAllowed(Context c,String r);
 static native boolean keepAwake(Context c,String r);
 static native long remaining(Context c,String r);
 static native boolean storePending(Context c,String r,String v);
 static native boolean note(Context c,String r,String p,String v,int a);
 static native void finish(Context c,String r,String o,String why);
}""",
"AlarmReceiver": """package dev.yossi.morningsesame; import android.content.Context; import java.util.Map;
final class AlarmReceiver {
 static native Map<String,Integer> parseDurations(String encoded);
 static native void routePlayback(Context c,String r,String v);
 static native void schedulePlay(Context c,String r,String v);
}""",
"RunDiagnostics": """package dev.yossi.morningsesame; import android.content.Context; import java.util.Map;
final class RunDiagnostics {
 static native void event(Context c,String r,String p,String v,Map<String,Long> m);
 static native Map<String,Long> values(Object... pairs);
}""",
}

def digest(data): return hashlib.sha256(data).hexdigest()

def run(*args): subprocess.run(list(map(str,args)),check=True,timeout=120)

def smali(jar,command,*args): run("java","-cp",jar,"com.android.tools.smali."+command,*args)

def method(source,signature):
    if source.count(signature) != 1: raise ValueError("target_method_count:"+signature)
    start=source.index(signature)
    return source[start:source.index(".end method",start)+len(".end method")]

def canonical(source):
    labels=re.findall(r"^\s*(:[A-Za-z_][A-Za-z_0-9]*)\s*$",source,re.M)
    mapping={label:f":label_{i}" for i,label in enumerate(labels)}
    source=re.sub(r":[A-Za-z_][A-Za-z_0-9]*",lambda m:mapping.get(m[0],m[0]),source)
    return "\n".join(line.strip() for line in source.splitlines() if line.strip())

def validate_dex(data):
    if not data.startswith(b"dex\n") or struct.unpack_from("<I",data,32)[0]!=len(data): raise ValueError("dex_size")
    if hashlib.sha1(data[32:]).digest()!=data[12:32]: raise ValueError("dex_signature")
    if zlib.adler32(data[12:])&0xffffffff!=struct.unpack_from("<I",data,8)[0]: raise ValueError("dex_checksum")

def changed_method(name, signature, original):
    if name=="AlarmReceiver": return SELECT
    if name=="WakeAndPlayActivity" and "fetchCandidates()" in signature: return FETCH
    if name=="WakeAndPlayActivity":
        if original.count("No qualifying >30 minute Sesame Street Classics video found")!=1: raise ValueError("expected_source_error")
        return original.replace("No qualifying >30 minute Sesame Street Classics video found","No qualifying >40 minute Sesame Street Classics video found")
    if name=="RunCoordinator":
        marker="    invoke-static {p0}, Ldev/yossi/morningsesame/RunCoordinator;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;"
        if original.count(marker)!=1: raise ValueError("expected_confirmed_checkpoint")
        # This location is AFTER the original startupWorkAllowed branch, and BEFORE
        # the legacy 250-entry reset. It runs inside the original synchronized scope.
        hook="    invoke-static {p0, p2}, Ldev/yossi/morningsesame/MorningSelectionRepair;->recordConfirmedStart(Landroid/content/Context;Ljava/lang/String;)V\n\n"
        return original.replace(marker,hook+marker)
    if name=="DiagnosticArchive":
        marker="    filled-new-array/range {v1 .. v18}, [Ljava/lang/String;\n\n    move-result-object v0\n\n    sput-object v0, Ldev/yossi/morningsesame/DiagnosticArchive;->REASONS:[Ljava/lang/String;"
        if original.count(marker)!=1: raise ValueError("expected_reason_array")
        return original.replace(marker,"    const-string v19, \"no_eligible_candidate\"\n\n"+marker.replace("v1 .. v18","v1 .. v19"))
    raise ValueError("unknown_method")

def build(args):
    if digest(args.base.read_bytes())!=BASE_APK_SHA256: raise ValueError("unexpected_base_apk")
    out=args.output
    if out.exists() and any(out.iterdir()): raise ValueError("output_must_be_empty")
    out.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.base) as z: original_dex=z.read("classes.dex")
    if digest(original_dex)!=BASE_DEX_SHA256: raise ValueError("unexpected_base_dex")
    validate_dex(original_dex)
    (out/"original.dex").write_bytes(original_dex)
    original=out/"original-smali";candidate=out/"candidate-smali";readback=out/"readback-smali"
    smali(args.smali_jar,"baksmali.Main","disassemble",out/"original.dex","-o",original)
    shutil.copytree(original,candidate)
    expected={}
    for (name,signature),expected_hash in TARGETS.items():
        path=PACKAGE/(name+".smali")
        source=(candidate/path).read_text()
        old=method(source,signature)
        if digest(old.encode())!=expected_hash: raise ValueError("unexpected_target:"+signature)
        new=changed_method(name,signature,old)
        (candidate/path).write_text(source.replace(old,new))
        expected[(path,signature)]=new
    stubs=out/"compile-stubs";stubs.mkdir()
    for name,source in STUBS.items(): (stubs/(name+".java")).write_text(source)
    classes=out/"compiled";classes.mkdir()
    source_root=Path(__file__).parent
    run("java","--module","jdk.compiler/com.sun.tools.javac.Main","-source","8","-target","8",
        "-cp",args.android_jar,"-d",classes,*stubs.glob("*.java"),*[source_root/(name+".java") for name in SOURCES])
    classpath=out/"compile-classpath.jar"
    with zipfile.ZipFile(classpath,"w") as z:
        for name in STUBS:
            path=PACKAGE/(name+".class");z.write(classes/path,str(path))
    new_dex=out/"new-class-dex";new_dex.mkdir()
    run("java","-cp",args.d8_jar,"com.android.tools.r8.D8","--min-api","26","--lib",args.android_jar,
        "--classpath",classpath,"--output",new_dex,*[classes/PACKAGE/(name+".class") for name in SOURCES])
    new_smali=out/"new-class-smali"
    smali(args.smali_jar,"baksmali.Main","disassemble",new_dex/"classes.dex","-o",new_smali)
    new_files={PACKAGE/(name+".smali") for name in SOURCES}
    if {p.relative_to(new_smali) for p in new_smali.rglob("*.smali")}!=new_files: raise ValueError("unexpected_new_class_inventory")
    for path in new_files: shutil.copy2(new_smali/path,candidate/path)
    smali(args.smali_jar,"smali.Main","assemble",candidate,"-o",out/"classes.dex")
    patched=(out/"classes.dex").read_bytes();validate_dex(patched)
    smali(args.smali_jar,"baksmali.Main","disassemble",out/"classes.dex","-o",readback)
    original_files={p.relative_to(original) for p in original.rglob("*.smali")}
    if {p.relative_to(readback) for p in readback.rglob("*.smali")}!=original_files|new_files: raise ValueError("final_class_inventory")
    total=0
    for path in sorted(original_files):
        old=(original/path).read_text();new=(readback/path).read_text()
        total+=len(re.findall(r"^\.method ",old,re.M))
        for (target,signature),replacement in expected.items():
            if path!=target: continue
            old_method=method(old,signature);new_method=method(new,signature)
            if canonical(new_method)!=canonical(replacement): raise ValueError("unexpected_patched_method:"+signature)
            old=old.replace(old_method,"VERIFIED_TARGET:"+signature)
            new=new.replace(new_method,"VERIFIED_TARGET:"+signature)
        if old!=new: raise ValueError("unrelated_existing_bytecode_changed:"+str(path))
    for path in new_files:
        if (readback/path).read_bytes()!=(new_smali/path).read_bytes(): raise ValueError("new_class_merge_difference:"+str(path))
    unsigned=out/"MorningSesame-selection-repair-unsigned.apk";retained={}
    with zipfile.ZipFile(args.base) as old,zipfile.ZipFile(unsigned,"w") as new:
        for entry in old.infolist():
            if entry.filename.startswith("META-INF/"):continue
            data=patched if entry.filename=="classes.dex" else old.read(entry.filename)
            new.writestr(entry,data)
            if entry.filename!="classes.dex":retained[entry.filename]=digest(data)
    with zipfile.ZipFile(unsigned) as z:
        for name,checksum in retained.items():
            if digest(z.read(name))!=checksum:raise ValueError("retained_resource_changed:"+name)
    report={"base_apk_sha256":BASE_APK_SHA256,"base_dex_sha256":BASE_DEX_SHA256,
            "candidate_dex_sha256":digest(patched),"existing_classes":len(original_files),"added_classes":sorted(map(str,new_files)),
            "existing_methods":total,"changed_existing_methods":len(TARGETS),"unchanged_existing_methods":total-len(TARGETS),
            "changed_methods":[{"class":name,"signature":sig,"old_sha256":value} for (name,sig),value in TARGETS.items()],
            "retained_payloads":retained,"new_source_sha256":{name:digest((source_root/(name+".java")).read_bytes()) for name in SOURCES},
            "unsigned_apk":str(unsigned.resolve()),"unsigned_apk_sha256":digest(unsigned.read_bytes()),"signed":False,"installed":False}
    (out/"invariants.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2))

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for flag in ("base","smali-jar","d8-jar","android-jar","output"):p.add_argument("--"+flag,type=Path,required=True)
    build(p.parse_args())
