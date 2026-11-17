#!/usr/bin/env python3
"""Run the actual pure banner policy and the pinned original asset-route fixture."""
import argparse
from pathlib import Path
import subprocess
import sys


def run(*args):
    subprocess.run(list(map(str, args)), check=True, timeout=60)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    package = Path("dev/yossi/onnkeyguard")
    classes = args.output / "policy-classes"
    classes.mkdir(parents=True, exist_ok=True)
    run("java", "--module", "jdk.compiler/com.sun.tools.javac.Main", "-d", classes,
        here / "src" / package / "SchoolBannerPolicy.java",
        here / "test" / package / "SchoolBannerPolicyTest.java")
    run("java", "-cp", classes, "dev.yossi.onnkeyguard.SchoolBannerPolicyTest")
    run(sys.executable, here / "test_player_route.py", "--output", args.output / "player-route")


if __name__ == "__main__":
    main()
