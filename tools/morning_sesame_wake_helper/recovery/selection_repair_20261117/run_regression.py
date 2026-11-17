#!/usr/bin/env python3
"""Run production policy, parsing, adapter, and bounded-network code against deterministic fixtures."""
import argparse
from pathlib import Path
import subprocess

def run(*args):subprocess.run(list(map(str,args)),check=True,timeout=60)

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--json-jar",type=Path,required=True)
    p.add_argument("--output",type=Path,default=Path("regression-classes"))
    a=p.parse_args();root=Path(__file__).resolve().parent
    a.output.mkdir(parents=True,exist_ok=True)
    production=[root/(name+".java") for name in ("MorningEpisodePolicy","MorningStartHistory","MorningChannelPage","MorningChannelSource","MorningSelectionRepair")]
    fixture=list((root/"tests/fixtures").rglob("*.java"));tests=list((root/"tests").glob("*Test.java"))
    run("java","--module","jdk.compiler/com.sun.tools.javac.Main","-source","8","-target","8",
        "-cp",a.json_jar,"-d",a.output,*production,*fixture,*tests)
    cp=str(a.output)+":"+str(a.json_jar)
    for name in ("MorningEpisodePolicyTest","MorningStartHistoryTest","MorningSelectionRepairTest"):
        run("java","-cp",cp,"dev.yossi.morningsesame."+name)
    for name in ("MorningChannelPageTest","MorningChannelSourceTest"):
        run("java","-cp",cp,"dev.yossi.morningsesame."+name,
            root/"tests/fixtures/channel-latest.html",root/"tests/fixtures/channel-next.json")
