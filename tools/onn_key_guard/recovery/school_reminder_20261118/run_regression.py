#!/usr/bin/env python3
"""Run the actual reminder timeline and retained banner/calendar/asset regressions."""
import argparse
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    package = Path("dev/yossi/onnkeyguard")
    classes = args.output / "timeline-classes"
    classes.mkdir(parents=True, exist_ok=True)
    commands = [
        ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", classes,
         here / "src" / package / "SchoolReminderTimeline.java",
         here / "test" / package / "SchoolReminderTimelineTest.java"],
        ["java", "-cp", classes, "dev.yossi.onnkeyguard.SchoolReminderTimelineTest"],
        [sys.executable, here.parent / "school_banner_20261117" / "run_regression.py",
         "--output", args.output / "existing-banner"],
    ]
    for command in commands:
        subprocess.run(list(map(str, command)), check=True, timeout=90)


if __name__ == "__main__":
    main()
