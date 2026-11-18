#!/usr/bin/env python3
"""Compile the actual account/playlist selector and run boundary, adapter and bounded-network regressions."""
import argparse
from pathlib import Path
import subprocess

def run(*args): subprocess.run(list(map(str, args)), check=True, timeout=60)

if __name__ == "__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--json-jar", type=Path, required=True)
    p.add_argument("--output", type=Path, default=Path("regression-classes"))
    a=p.parse_args(); root=Path(__file__).resolve().parent
    a.output.mkdir(parents=True, exist_ok=True)
    production=list(root.glob("*.java"))
    fixture=list((root/"tests/fixtures").rglob("*.java"))
    tests=sorted((root/"tests").glob("*Test.java"))
    run("java", "--module", "jdk.compiler/com.sun.tools.javac.Main", "-source", "8", "-target", "8",
        "-cp", a.json_jar, "-d", a.output, *production, *fixture, *tests)
    for test in tests:
        run("java", "-cp", str(a.output)+":"+str(a.json_jar), "dev.yossi.morningsesame."+test.stem)
