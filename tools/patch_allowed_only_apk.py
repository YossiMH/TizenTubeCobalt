#!/usr/bin/env python3
import argparse
import hashlib
import struct
import zipfile
import zlib
from pathlib import Path

OLD_PACKAGE = b"io.gh.reisxd.tizentube.cobalt"
NEW_PACKAGE = b"io.gh.yossim.tizentube.cobalt"
OLD_LABEL = b"TizenTube"
NEW_LABEL = b"TizenSub+"
POLYFILL_PATH = Path("cobalt/shell/embedded_resources/cobalt_java_script_polyfill/html_media_element_extension_on_java_bridge.js")


def replace_same_length(data: bytes, old: bytes, new: bytes):
    if len(old) != len(new):
        raise ValueError(f"Replacement length mismatch: {old!r} -> {new!r}")
    count = data.count(old)
    if count:
        data = data.replace(old, new)
    old16 = old.decode("ascii").encode("utf-16le")
    new16 = new.decode("ascii").encode("utf-16le")
    count16 = data.count(old16)
    if count16:
        data = data.replace(old16, new16)
    return data, count, count16


def re_sign_dex(data: bytes) -> bytes:
    """Recompute the DEX header checksum (adler32 of bytes 12..end) and
    signature (SHA-1 of bytes 32..end) after in-place byte replacement.
    Without this, ART rejects the dex and the app crashes at launch with
    ClassNotFoundException."""
    if len(data) < 0x70 or data[:4] != b"dex\n":
        return data
    body = bytearray(data)
    body[12:32] = hashlib.sha1(data[32:]).digest()
    body[8:12] = struct.pack("<I", zlib.adler32(bytes(body[12:])) & 0xFFFFFFFF)
    return bytes(body)


def make_document_start_loader(script_url: str, target_size: int) -> bytes:
    loader = (
        'if(/(^|\\.)youtube\\.com$/.test(location.hostname)){'
        'var x=new XMLHttpRequest;x.open("GET",' + repr(script_url).replace("'", '"') + ',false);'
        'x.send();(0,eval)(x.responseText)}'
    ).encode("utf-8")
    if len(loader) > target_size:
        raise ValueError(f"Document-start loader is {len(loader)} bytes, larger than {target_size}-byte embedded polyfill")
    # JavaScript whitespace padding preserves the generated resource's fixed byte size.
    return loader + (b" " * (target_size - len(loader)))


def should_strip_signature(name: str) -> bool:
    upper = name.upper()
    if not upper.startswith("META-INF/"):
        return False
    leaf = upper.rsplit("/", 1)[-1]
    return leaf == "MANIFEST.MF" or leaf.endswith((".SF", ".RSA", ".DSA", ".EC"))


def patch_apk(input_apk: Path, output_apk: Path, repo_root: Path, script_url: str):
    polyfill = (repo_root / POLYFILL_PATH).read_bytes()
    loader = make_document_start_loader(script_url, len(polyfill))

    counts = {
        "package_ascii": 0,
        "package_utf16": 0,
        "label_ascii": 0,
        "label_utf16": 0,
        "polyfill": 0,
        "signatures_removed": 0,
    }
    lib_seen = []

    with zipfile.ZipFile(input_apk, "r") as zin, zipfile.ZipFile(output_apk, "w") as zout:
        for info in zin.infolist():
            if should_strip_signature(info.filename):
                counts["signatures_removed"] += 1
                continue
            data = zin.read(info.filename)

            data, c8, c16 = replace_same_length(data, OLD_PACKAGE, NEW_PACKAGE)
            counts["package_ascii"] += c8
            counts["package_utf16"] += c16

            data, c8, c16 = replace_same_length(data, OLD_LABEL, NEW_LABEL)
            counts["label_ascii"] += c8
            counts["label_utf16"] += c16

            if info.filename.endswith("/libchrobalt.so"):
                lib_seen.append(info.filename)
                matches = data.count(polyfill)
                if matches:
                    data = data.replace(polyfill, loader)
                    counts["polyfill"] += matches

            # In-place byte replacement invalidates the dex header signature;
            # re-sign so ART can load the dex at runtime.
            if info.filename.endswith(".dex"):
                data = re_sign_dex(data)

            zout.writestr(info, data)

    if not lib_seen:
        raise RuntimeError("No libchrobalt.so was found in the APK")
    if counts["polyfill"] != 1:
        raise RuntimeError(
            f"Expected exactly one embedded document-start polyfill in {input_apk.name}; found {counts['polyfill']} across {lib_seen}"
        )
    if counts["package_ascii"] + counts["package_utf16"] < 1:
        raise RuntimeError("Could not find the upstream applicationId in the APK")

    return counts, lib_seen, len(polyfill), len(loader.rstrip(b" "))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input_apk", type=Path)
    parser.add_argument("output_apk", type=Path)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--script-url", required=True)
    args = parser.parse_args()

    counts, libs, resource_size, loader_size = patch_apk(
        args.input_apk, args.output_apk, args.repo_root, args.script_url
    )
    print(f"Patched {args.input_apk} -> {args.output_apk}")
    print(f"libchrobalt: {', '.join(libs)}")
    print(f"document-start resource: {resource_size} bytes; loader: {loader_size} bytes")
    for key, value in counts.items():
        print(f"{key}: {value}")


if __name__ == "__main__":
    main()
