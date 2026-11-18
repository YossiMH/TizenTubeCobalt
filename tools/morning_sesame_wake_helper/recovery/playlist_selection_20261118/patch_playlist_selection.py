#!/usr/bin/env python3
"""Pinned update of the installed Nov17 v12 helper; retain every unrelated startup/fencing method."""
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

BASE_APK_SHA256="e9353160b28c323581ae82504507c73df2030123a2a2e90303683af276e40a8f"
BASE_DEX_SHA256="b09b50cce6562f0ff0e2f7b6619f3350ffa08e7dd87451439bbad60fd51ece95"
PACKAGE=Path("dev/yossi/morningsesame")
SOURCES=("MorningEpisodePolicy","MorningSelectionRepair","MorningChannelPage",
         "MorningYouTubeHistory","MorningPlaylistPage","MorningPlaylistSource")
REPLACED={
    "MorningEpisodePolicy":"3200e780dde6b355862897034a51512d9b2382a57447ba40de508508c39d282a",
    "MorningSelectionRepair":"8b6b5f492c15f2ab68dab0d4608dc196b24696a6bac717bf18a607bac18b57b6",
    "MorningChannelPage":"228a2d985f967de53fec1e629f3412d84a797c6cb481cbdcd8d43ca0cf305b8e",
}
FETCH_SIGNATURE=".method private fetchCandidates()Ljava/util/Map;"
FETCH_SHA256="2c3b4f9302b070cb91007b871fe542d87cec4440f167c5bda97cb5cd9a15eff7"
FETCH=""".method private fetchCandidates()Ljava/util/Map;
    .registers 3
    const-string v0, "https://www.youtube.com/playlist?list=PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA"
    invoke-direct {p0, v0}, Ldev/yossi/morningsesame/WakeAndPlayActivity;->fetch(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    iget-object v1, p0, Ldev/yossi/morningsesame/WakeAndPlayActivity;->runId:Ljava/lang/String;
    invoke-static {p0, v1, v0}, Ldev/yossi/morningsesame/MorningPlaylistSource;->fetch(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Ljava/util/Map;
    move-result-object v0
    return-object v0
.end method"""
STUBS={
"RunCoordinator":"""package dev.yossi.morningsesame; import android.content.Context;
final class RunCoordinator {
 static native boolean checkpoint(Context c,String r);
 static native boolean startupWorkAllowed(Context c,String r);
 static native boolean keepAwake(Context c,String r);
 static native long remaining(Context c,String r);
 static native boolean storePending(Context c,String r,String v);
 static native boolean note(Context c,String r,String p,String v,int a);
 static native void finish(Context c,String r,String o,String why);
}""",
"AlarmReceiver":"""package dev.yossi.morningsesame; import android.content.Context; import java.util.Map;
final class AlarmReceiver {
 static native Map<String,Integer> parseDurations(String encoded);
 static native void routePlayback(Context c,String r,String v);
 static native void schedulePlay(Context c,String r,String v);
}""",
"RunDiagnostics":"""package dev.yossi.morningsesame; import android.content.Context; import java.util.Map;
final class RunDiagnostics {
 static native void event(Context c,String r,String p,String v,Map<String,Long> m);
 static native Map<String,Long> values(Object... pairs);
}""",
}

def digest(data):return hashlib.sha256(data).hexdigest()
def run(*args):subprocess.run(list(map(str,args)),check=True,timeout=120)
def smali(jar,command,*args):run("java","-cp",jar,"com.android.tools.smali."+command,*args)
def method(source,signature):
    if source.count(signature)!=1:raise ValueError("target_method_count:"+signature)
    start=source.index(signature)
    return source[start:source.index(".end method",start)+len(".end method")]
def canonical(source):return "\n".join(line.strip() for line in source.splitlines() if line.strip())
def validate_dex(data):
    if not data.startswith(b"dex\n") or struct.unpack_from("<I",data,32)[0]!=len(data):raise ValueError("dex_size")
    if hashlib.sha1(data[32:]).digest()!=data[12:32]:raise ValueError("dex_signature")
    if zlib.adler32(data[12:])&0xffffffff!=struct.unpack_from("<I",data,8)[0]:raise ValueError("dex_checksum")

def build(args):
    if digest(args.base.read_bytes())!=BASE_APK_SHA256:raise ValueError("unexpected_base_apk")
    out=args.output
    if out.exists() and any(out.iterdir()):raise ValueError("output_must_be_empty")
    out.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.base) as z:original_dex=z.read("classes.dex")
    if digest(original_dex)!=BASE_DEX_SHA256:raise ValueError("unexpected_base_dex")
    validate_dex(original_dex);(out/"original.dex").write_bytes(original_dex)
    original=out/"original-smali";candidate=out/"candidate-smali";readback=out/"readback-smali"
    smali(args.smali_jar,"baksmali.Main","disassemble",out/"original.dex","-o",original)
    shutil.copytree(original,candidate)
    for name,expected_hash in REPLACED.items():
        if digest((original/PACKAGE/(name+".smali")).read_bytes())!=expected_hash:raise ValueError("unexpected_replaced_class:"+name)
    target=PACKAGE/"WakeAndPlayActivity.smali";text=(candidate/target).read_text();old=method(text,FETCH_SIGNATURE)
    if digest(old.encode())!=FETCH_SHA256:raise ValueError("unexpected_fetch_method")
    (candidate/target).write_text(text.replace(old,FETCH))
    stubs=out/"compile-stubs";stubs.mkdir()
    for name,text in STUBS.items():(stubs/(name+".java")).write_text(text)
    classes=out/"compiled";classes.mkdir();source=Path(__file__).resolve().parent
    run("java","--module","jdk.compiler/com.sun.tools.javac.Main","-source","8","-target","8",
        "-cp",args.android_jar,"-d",classes,*stubs.glob("*.java"),*[source/(name+".java") for name in SOURCES])
    classpath=out/"compile-classpath.jar"
    with zipfile.ZipFile(classpath,"w") as z:
        for name in STUBS:
            path=PACKAGE/(name+".class");z.write(classes/path,str(path))
    new_dex=out/"compiled-dex";new_dex.mkdir()
    run("java","-cp",args.d8_jar,"com.android.tools.r8.D8","--min-api","26","--lib",args.android_jar,
        "--classpath",classpath,"--output",new_dex,*[classes/PACKAGE/(name+".class") for name in SOURCES])
    source_smali=out/"source-smali";smali(args.smali_jar,"baksmali.Main","disassemble",new_dex/"classes.dex","-o",source_smali)
    source_files={PACKAGE/(name+".smali") for name in SOURCES}
    if {p.relative_to(source_smali) for p in source_smali.rglob("*.smali")}!=source_files:raise ValueError("unexpected_compiled_class_inventory")
    for path in source_files:shutil.copy2(source_smali/path,candidate/path)
    smali(args.smali_jar,"smali.Main","assemble",candidate,"-o",out/"classes.dex")
    patched=(out/"classes.dex").read_bytes();validate_dex(patched)
    smali(args.smali_jar,"baksmali.Main","disassemble",out/"classes.dex","-o",readback)
    original_files={p.relative_to(original) for p in original.rglob("*.smali")}
    added=source_files-original_files
    if {p.relative_to(readback) for p in readback.rglob("*.smali")}!=original_files|added:raise ValueError("final_class_inventory")
    total=0;replaced_methods=0
    for path in sorted(original_files):
        old=(original/path).read_text();new=(readback/path).read_text();count=len(re.findall(r"^\.method ",old,re.M));total+=count
        if path in source_files:replaced_methods+=count;continue
        if path==target:
            old_method=method(old,FETCH_SIGNATURE);new_method=method(new,FETCH_SIGNATURE)
            if canonical(new_method)!=canonical(FETCH):raise ValueError("unexpected_fetch_replacement")
            old=old.replace(old_method,"VERIFIED_FETCH_METHOD");new=new.replace(new_method,"VERIFIED_FETCH_METHOD")
        if old!=new:raise ValueError("unrelated_existing_bytecode_changed:"+str(path))
    for path in source_files:
        if (readback/path).read_bytes()!=(source_smali/path).read_bytes():raise ValueError("source_merge_difference:"+str(path))
    unsigned=out/"MorningSesame-playlist-selection-unsigned.apk";retained={}
    with zipfile.ZipFile(args.base) as old,zipfile.ZipFile(unsigned,"w") as new:
        for entry in old.infolist():
            if entry.filename.startswith("META-INF/"):continue
            data=patched if entry.filename=="classes.dex" else old.read(entry.filename);new.writestr(entry,data)
            if entry.filename!="classes.dex":retained[entry.filename]=digest(data)
    with zipfile.ZipFile(unsigned) as z:
        for name,checksum in retained.items():
            if digest(z.read(name))!=checksum:raise ValueError("retained_resource_changed:"+name)
    report={"base_apk_sha256":BASE_APK_SHA256,"base_dex_sha256":BASE_DEX_SHA256,
        "candidate_dex_sha256":digest(patched),"existing_classes":len(original_files),"existing_methods":total,
        "replaced_classes":REPLACED,"methods_in_replaced_classes":replaced_methods,
        "changed_adapter_methods":[{"class":"WakeAndPlayActivity","signature":FETCH_SIGNATURE,"old_sha256":FETCH_SHA256}],
        "unchanged_existing_methods":total-replaced_methods-1,"added_classes":sorted(map(str,added)),
        "retained_payloads":retained,"new_source_sha256":{name:digest((source/(name+".java")).read_bytes()) for name in SOURCES},
        "unsigned_apk":str(unsigned.resolve()),"unsigned_apk_sha256":digest(unsigned.read_bytes()),"signed":False,"installed":False}
    (out/"invariants.json").write_text(json.dumps(report,indent=2)+"\n");print(json.dumps(report,indent=2))

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for flag in ("base","smali-jar","d8-jar","android-jar","output"):p.add_argument("--"+flag,type=Path,required=True)
    build(p.parse_args())
