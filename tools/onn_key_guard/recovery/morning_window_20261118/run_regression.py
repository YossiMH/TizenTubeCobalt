#!/usr/bin/env python3
"""Run the real pure policies and production controller tests with host-only I/O boundaries."""
import argparse
from pathlib import Path
import subprocess


def run(*args, cwd=None):
    subprocess.run(list(map(str,args)),check=True,timeout=90,cwd=cwd)


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json-jar",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--controller-dir",type=Path)
    args=parser.parse_args();here=Path(__file__).resolve().parent;pkg=Path("dev/yossi/onnkeyguard")
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    json_jar=args.json_jar.resolve()
    old_test=here/"test"/pkg/"SchoolBannerPolicyTest.java"
    if not old_test.exists(): old_test=here.parent/"school_banner_20261117/test"/pkg/"SchoolBannerPolicyTest.java"
    run("java","-m","jdk.compiler/com.sun.tools.javac.Main","-cp",json_jar,"-d",out,
        *(here/"src"/pkg/name for name in ("SchoolBannerPolicy.java","MorningHistoryCache.java","MorningWindowGrant.java")),
        here/"test"/pkg/"MorningWindowTest.java",old_test)
    run("java","-cp",str(out)+":"+str(json_jar),"dev.yossi.onnkeyguard.MorningWindowTest")
    run("java","-cp",out,"dev.yossi.onnkeyguard.SchoolBannerPolicyTest")
    controller=args.controller_dir or (here/"controller" if (here/"controller").exists()
        else here.parents[2]/"onn_session_policy")
    run("python","-m","unittest","discover","-s","tests","-p","test_*.py",cwd=controller.resolve())
