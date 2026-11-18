#!/usr/bin/env python3
"""Compile actual helper boundary sources with Android transport/storage fixtures."""
from __future__ import annotations
import argparse
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path

HERE=Path(__file__).resolve().parent
PINS={
    "WorkerFenceContinuation.java":"7b9f8b0324871eccf236ba5836c7da1f17d464ebcf99df42e5a5276140e07793",
    "StartupFencePolicy.java":"363c2137cf06b9bf255d3af45abbda40dd606b8079c9317dcab6c77e4acee65b",
    "WorkerFenceContinuation.smali":"21baeeaed1d43d3023fd01bc6f5adcf3365517e4e2e2752db17ca1addfd4e329",
}

def sha(path:Path)->str:return hashlib.sha256(path.read_bytes()).hexdigest()

def main()->None:
    parser=argparse.ArgumentParser()
    parser.add_argument("--helper-source",type=Path,default=HERE.parents[1])
    parser.add_argument("--validator-source",type=Path,default=HERE.parents[2]/"playlist_selection_20261118")
    parser.add_argument("--json-jar",type=Path,required=True)
    parser.add_argument("--result",type=Path)
    args=parser.parse_args()
    fixtures=HERE/"fixtures"
    for name,digest in PINS.items():
        if sha(fixtures/name)!=digest:raise SystemExit(f"Fixture pin failed: {name}")
    worker=(fixtures/"WorkerFenceContinuation.java").read_text()
    # JADX represented DEX boolean xor(true) and boolean constant(1) as invalid Java.
    # These two syntax repairs correspond to pinned smali lines; no behavior is changed.
    fixes={"RunCoordinator.isStartupBound(this.context, this.runId) ^ CONFIRM":"!RunCoordinator.isStartupBound(this.context, this.runId)",
           "? false : CONFIRM;":"? false : true;"}
    for old,new in fixes.items():
        if worker.count(old)!=1:raise SystemExit(f"Decompiler artifact changed: {old}")
        worker=worker.replace(old,new)
    smali=(fixtures/"WorkerFenceContinuation.smali").read_text()
    if "xor-int/2addr v1, v2" not in smali or "const/4 v2, 0x1" not in smali:
        raise SystemExit("Pinned boolean instruction evidence absent")
    source=[args.helper_source/name for name in ["MorningHistoryReply.java","MorningHistoryReader.java","MorningOrderedBroadcast.java","MorningSelectionRepair.java"]]
    source += [args.validator_source/name for name in ["MorningYouTubeHistory.java","MorningEpisodePolicy.java"]]
    sources=list(source)+sorted((HERE/"src").rglob("*.java"))
    with tempfile.TemporaryDirectory(prefix="helper-boundary-") as temp:
        temp=Path(temp);(temp/"WorkerFenceContinuation.java").write_text(worker)
        sources += [temp/"WorkerFenceContinuation.java",fixtures/"StartupFencePolicy.java"]
        compile_command=["java","-m","jdk.compiler/com.sun.tools.javac.Main","-cp",str(args.json_jar),"-d",str(temp/"classes")]+list(map(str,sources))
        subprocess.run(compile_command,check=True)
        result=subprocess.run(["java","-cp",str(temp/"classes")+":"+str(args.json_jar),"dev.yossi.morningsesame.HelperBoundaryTest"],capture_output=True,text=True)
        print(result.stdout,end="")
        if result.returncode:
            print(result.stderr,end="");raise SystemExit(result.returncode)
    receipt={"schema":1,"status":"passed","kind":"host_boundary_harness",
        "result":result.stdout.strip(),"production_sources":{path.name:sha(path) for path in source},
        "deployed_fixture_sha256":PINS,"fixture_java_repairs":fixes,
        "limits":["Android transport, storage, handler scheduling, and RunCoordinator are test fixtures.",
                  "Actual parser, reader, API-level shim, selection adapter, history validator, episode selector, and deployed worker continuation execute.",
                  "This does not prove installed Android signature delivery or the real scheduled device path."]}
    if args.result:
        args.result.parent.mkdir(parents=True, exist_ok=True)
        args.result.write_text(json.dumps(receipt,indent=2)+"\n")

if __name__=="__main__":main()
