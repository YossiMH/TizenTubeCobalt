#!/usr/bin/env python3
"""Permanent regression test: patching an APK must keep every .dex valid.

Runs the real patch_allowed_only_apk.patch_apk on a constructed APK whose
classes.dex contains the upstream applicationId bytes, then verifies the
patched dex still has a valid header checksum and signature (adler32 of
bytes 12..end, SHA-1 of bytes 32..end). Without re-signing, ART rejects the
dex at runtime and the app crashes on launch with ClassNotFoundException.
"""
import hashlib
import struct
import sys
import tempfile
import zipfile
import zlib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / 'tools'))
import patch_allowed_only_apk as patcher

OLD_PACKAGE = b'io.gh.reisxd.tizentube.cobalt'
NEW_PACKAGE = b'io.gh.yossim.tizentube.cobalt'


def make_minimal_dex() -> bytes:
    # Minimal but structurally valid dex header + a string-data section that
    # contains the upstream applicationId in ASCII (as real dex string data does).
    header_size = 0x70
    string_data = OLD_PACKAGE + b'\x00' + b'A' * 64
    file_size = header_size + len(string_data)
    h = bytearray(header_size)
    h[0:8] = b'dex\n035\x00'
    h[0x24:0x28] = struct.pack('<I', header_size)
    h[0x28:0x2C] = struct.pack('<I', 0x12345678)  # endian tag
    h[0x2C:0x30] = struct.pack('<I', 0x70)  # link size
    body = bytes(h) + string_data
    # Sign the header: signature = SHA-1 of bytes 32..end, checksum = adler32 of bytes 12..end.
    body = body[:12] + hashlib.sha1(body[32:]).digest() + body[32:]
    body = body[:8] + struct.pack('<I', zlib.adler32(body[12:]) & 0xFFFFFFFF) + body[12:]
    return body


def dex_header_valid(data: bytes) -> bool:
    if data[:8] != b'dex\n035\x00':
        return False
    checksum = struct.unpack('<I', data[8:12])[0]
    signature = data[12:32]
    return (
        checksum == (zlib.adler32(data[12:]) & 0xFFFFFFFF)
        and signature == hashlib.sha1(data[32:]).digest()
    )


def build_test_apk(tmp: Path) -> Path:
    polyfill = (REPO_ROOT / patcher.POLYFILL_PATH).read_bytes()
    dex = make_minimal_dex()
    assert OLD_PACKAGE in dex
    in_apk = tmp / 'in.apk'
    with zipfile.ZipFile(in_apk, 'w') as z:
        # Real binary manifests carry strings in UTF-16, so exercise both encodings.
        z.writestr('AndroidManifest.xml', (OLD_PACKAGE.decode() + '\nTizenTube\n').encode('utf-16le'))
        z.writestr('classes.dex', dex)
        z.writestr('lib/arm64-v8a/libchrobalt.so', polyfill + OLD_PACKAGE + b' TizenTube')
    return in_apk


def main() -> None:
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        in_apk = build_test_apk(tmp)
        out_apk = tmp / 'out.apk'
        counts, libs, _, _ = patcher.patch_apk(
            in_apk, out_apk, REPO_ROOT, 'https://example.test/x.js'
        )
        assert counts['package_ascii'] >= 1, counts
        assert counts['package_utf16'] >= 1, counts
        assert counts['polyfill'] == 1, counts
        with zipfile.ZipFile(out_apk) as z:
            patched_dex = z.read('classes.dex')
        assert NEW_PACKAGE in patched_dex, 'package id must be replaced in dex'
        assert OLD_PACKAGE not in patched_dex, 'upstream id must be gone from dex'
        # The regression: without re-signing, this assertion fails.
        assert dex_header_valid(patched_dex), 'patched dex header checksum/signature must be valid'
    print('All APK patcher dex-integrity tests passed.')


if __name__ == '__main__':
    main()
