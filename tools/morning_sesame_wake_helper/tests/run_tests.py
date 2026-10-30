#!/usr/bin/env python3
"""Run the helper's pure Java acceptance rules without an Android device."""
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    helper = Path(__file__).resolve().parent.parent
    java = shutil.which("java")
    if not java:
        raise SystemExit("Java is required")
    javac = shutil.which("javac")
    compiler = [javac] if javac else [java, "com.sun.tools.javac.Main"]
    with tempfile.TemporaryDirectory(prefix="morning-sesame-tests-") as classes:
        sources = [helper / "PlaybackEvidence.java", helper / "RunPolicy.java",
                   helper / "tests/PlaybackEvidenceTest.java", helper / "tests/RunPolicyTest.java",
                   helper / "DiagnosticArchive.java", helper / "tests/DiagnosticArchiveTest.java",
                   helper / "PlaybackDiagnostics.java", helper / "tests/PlaybackDiagnosticsTest.java"]
        subprocess.run(compiler + ["-d", classes] + [str(path) for path in sources], check=True)
        for name in ("PlaybackEvidenceTest", "RunPolicyTest", "DiagnosticArchiveTest", "PlaybackDiagnosticsTest"):
            subprocess.run([java, "-cp", classes, "dev.yossi.morningsesame." + name], check=True)


if __name__ == "__main__":
    main()
