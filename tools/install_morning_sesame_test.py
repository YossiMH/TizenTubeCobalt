#!/usr/bin/env python3
"""Pure safety-contract checks for the paired installer; does not contact a TV."""
from pathlib import Path
import unittest
from install_morning_sesame import fingerprint, package_badging, package_path, install_command, APP_CERT

class InstallerTests(unittest.TestCase):
    def test_fingerprint(self):
        self.assertEqual(fingerprint("Signer #1 certificate SHA-256 digest: "+APP_CERT), APP_CERT)
        with self.assertRaises(ValueError):fingerprint("public key SHA-256 digest: "+APP_CERT)
        with self.assertRaises(ValueError):fingerprint("")
        with self.assertRaises(ValueError):
            fingerprint("certificate SHA-256 digest: "+APP_CERT+"\ncertificate SHA-256 digest: "+"0"*64)

    def test_badging(self):
        self.assertEqual(package_badging("package: name='dev.yossi.morningsesame' versionCode='6'"), ("dev.yossi.morningsesame",6))
        with self.assertRaises(ValueError):package_badging("unrelated versionCode='6'")

    def test_path(self):
        self.assertEqual(package_path("package:/data/app/~~abc==/dev.yossi.foo-xyz==/base.apk"), "/data/app/~~abc==/dev.yossi.foo-xyz==/base.apk")
        for value in ["/sdcard/base.apk", "package:/data/app/x/base.apk;bad", "package:/data/app/x/base.apk bad"]:
            with self.assertRaises(ValueError):package_path(value)

    def test_setup_revision_argument(self):
        source=Path(__file__).with_name("onn_setup.ps1").read_text()
        self.assertIn("--sha $SourceCommit",source)
        self.assertIn("[string]$SourceCommit",source)

    def test_atomic_update(self):
        args=install_command("adb","192.168.1.172:5555",Path("app.apk"),Path("helper.apk"))
        self.assertEqual(args,["adb","-s","192.168.1.172:5555","install-multi-package","-r","-t","app.apk","helper.apk"])
        self.assertNotIn("uninstall",args)
        self.assertNotIn("-d",args)

if __name__=="__main__":unittest.main()
