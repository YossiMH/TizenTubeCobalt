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
        z.writestr('lib/arm64-v8a/libchrobalt.so', polyfill + OLD_PACKAGE + b' TizenTube ' + patcher.UPSTREAM_USERSCRIPT_URL)
    return in_apk


def main() -> None:
    # The document-start gate must rely solely on the Trusted-Types-exempt
    # native injection and hold network requests until enforcement is armed.
    polyfill = (REPO_ROOT / patcher.POLYFILL_PATH).read_bytes()
    loader = patcher.make_document_start_loader(
        'https://cdn.jsdelivr.net/gh/YossiMH/TizenTubeCobalt@752b76abfc5b69033e44115b31db41318c9162a3/x.js',
        len(polyfill),
    )
    assert b'eval' not in loader, 'gate must not use eval'
    assert b'script.src' not in loader, 'gate must rely on Trusted-Types-exempt native injection'
    assert b'createElement' not in loader, 'gate must not retry script injection'
    assert b'appendChild' not in loader, 'gate must not retry script injection'
    assert b'F=fetch,X=' in loader, 'gate must capture originals before wrapping them'
    assert b'.apply(this,arguments)' in loader, 'gate must preserve receiver and arguments'
    assert b'window.__ttAllowedOnly' in loader, 'gate must wait for the real marker'
    assert b'new Promise' in loader, 'gate must expose one readiness barrier'
    assert b'fetch=' in loader, 'gate must delay fetch until enforcement exists'
    assert b'.send=' in loader, 'gate must delay XHR until enforcement exists'
    assert b'setInterval' in loader, 'gate must poll for enforcement'
    assert b'clearInterval' in loader, 'gate must stop polling once enforcement exists'
    assert len(loader.rstrip(b' ')) <= len(polyfill), 'gate must fit the embedded polyfill slot'

    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        in_apk = build_test_apk(tmp)
        out_apk = tmp / 'out.apk'
        counts, libs, _, _ = patcher.patch_apk(
            in_apk, out_apk, REPO_ROOT,
            'https://cdn.jsdelivr.net/gh/YossiMH/TizenTubeCobalt@752b76abfc5b69033e44115b31db41318c9162a3/x.js',
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
        # The injected loader must be Trusted-Types-safe too.
        with zipfile.ZipFile(out_apk) as z:
            so = z.read('lib/arm64-v8a/libchrobalt.so')
        assert b'eval' not in so, 'patched .so must not contain an eval-based loader'
        # The upstream app's native user-script injection must now point at our
        # filter script, byte-for-byte the same length so string tables stay valid.
        assert b'foxreis' not in so, 'upstream user-script URL must be redirected'
        native = patcher.pinned_cdn_url(
            'https://cdn.jsdelivr.net/gh/YossiMH/TizenTubeCobalt@752b76abfc5b69033e44115b31db41318c9162a3/x.js', 9
        ).encode()
        assert len(native) == len(patcher.UPSTREAM_USERSCRIPT_URL)
        assert native in so, 'native injection must point at the filter script'
        assert counts['upstream_script_redirect'] >= 1, counts

        # When an upstream build DOES embed the Cobalt Evergreen update
        # endpoints, the patcher must neutralize them (same-length redirect to
        # a disabled host). The fixture below models that upstream shape.
        in_apk2 = tmp / 'in-evergreen.apk'
        out_apk2 = tmp / 'out-evergreen.apk'
        with zipfile.ZipFile(in_apk2, 'w') as z:
            evergreen_blob = (
                b'x=' + patcher.EVERGREEN_UPDATE_URL_PROD + b';' +
                patcher.EVERGREEN_UPDATE_URL_QA
            )
            z.writestr('classes.dex', make_minimal_dex())
            z.writestr(
                'lib/arm64-v8a/libchrobalt.so',
                polyfill + patcher.UPSTREAM_USERSCRIPT_URL + evergreen_blob,
            )
        counts2, _, _, _ = patcher.patch_apk(
            in_apk2, out_apk2, REPO_ROOT,
            'https://cdn.jsdelivr.net/gh/YossiMH/TizenTubeCobalt@752b76abfc5b69033e44115b31db41318c9162a3/x.js',
        )
        assert counts2['evergreen_update_urls_disabled'] == 2, counts2
        with zipfile.ZipFile(out_apk2) as z:
            so2 = z.read('lib/arm64-v8a/libchrobalt.so')
        assert patcher.EVERGREEN_UPDATE_URL_PROD not in so2
        assert patcher.EVERGREEN_UPDATE_URL_QA not in so2
        assert patcher.DISABLED_EVERGREEN_URL_PROD in so2
        assert patcher.DISABLED_EVERGREEN_URL_QA in so2

    print('All APK patcher dex-integrity and loader-safety tests passed.')


if __name__ == '__main__':
    main()
