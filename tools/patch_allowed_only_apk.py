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
# The upstream release injects its user script from this exact URL through a
# native (Cobalt/V8) path that is exempt from the page's Trusted Types policy.
# Replacing the bytes with our own same-length CDN URL redirects that native
# injection to the allowed-only filter script.
UPSTREAM_USERSCRIPT_URL = b"https://cdn.jsdelivr.net/npm/@foxreis/tizentube/dist/userScript.js"
ALLOWED_ONLY_CDN_PREFIX = "https://cdn.jsdelivr.net/gh/YossiMH/TizenTubeCobalt@"
XJS_SUFFIX = "/x.js"

# Cobalt's Evergreen updater can replace the native runtime that contains the
# injection above. A restricted build must not silently replace its enforcement
# runtime independently of the signed APK. Keep both replacements byte-for-byte
# the same size so patching does not alter native string-table offsets.
EVERGREEN_UPDATE_URL_PROD = b"https://tools.google.com/service/update2/json"
EVERGREEN_UPDATE_URL_QA = b"https://omaha-qa.sandbox.google.com/service/update2/json"
DISABLED_EVERGREEN_URL_PROD = b"https://disabled.invalid/service/update2/json"
DISABLED_EVERGREEN_URL_QA = b"https://disabled.invalid/service/update2/json?q=disabled"


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


def disable_evergreen_updates(data: bytes):
    """Redirect Cobalt Evergreen update endpoints to reserved .invalid hosts.

    The filter's native injection lives inside libchrobalt.so. Allowing Cobalt's
    independently downloaded Evergreen runtime to replace that library would
    let the enforcement mechanism disappear without a restricted APK upgrade.
    """
    total = 0
    for old, new in (
        (EVERGREEN_UPDATE_URL_PROD, DISABLED_EVERGREEN_URL_PROD),
        (EVERGREEN_UPDATE_URL_QA, DISABLED_EVERGREEN_URL_QA),
    ):
        if len(old) != len(new):
            raise ValueError(f"Evergreen replacement length mismatch: {old!r} -> {new!r}")
        count = data.count(old)
        if count:
            data = data.replace(old, new)
            total += count
    return data, total


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


def pinned_cdn_url(script_url: str, sha_chars: int) -> str:
    """Short-SHA CDN pin that fits the fixed-size byte slots in the APK.

    The native injection slot is exactly 66 bytes (the upstream user-script
    URL), so its pin uses 9 SHA characters; the document-start loader slot is
    288 bytes, so it uses a 7-character pin.
    """
    if not script_url.startswith(ALLOWED_ONLY_CDN_PREFIX) or not script_url.endswith(XJS_SUFFIX):
        raise ValueError(f"Unsupported filter URL: {script_url!r}")
    sha = script_url[len(ALLOWED_ONLY_CDN_PREFIX):-len(XJS_SUFFIX)]
    if len(sha) < 10:
        raise ValueError(f"Filter URL SHA too short: {sha!r}")
    return ALLOWED_ONLY_CDN_PREFIX + sha[:sha_chars] + XJS_SUFFIX


def make_document_start_loader(script_url: str, target_size: int) -> bytes:
    # Trusted-Types-safe and startup-resilient: the native injection has one
    # chance, while this document-start resource retries until the filter's own
    # installation marker proves that enforcement is active.
    dom_url = pinned_cdn_url(script_url, 7).replace("https://", "//", 1)
    loader = (
        "u='" + dom_url + "',"
        "c=window.trustedTypes?trustedTypes.createPolicy('t',{createScriptURL:x=>x}).createScriptURL:x=>x,"
        'D=document.head,'
        't=setInterval(()=>window.__ttAllowedOnly?clearInterval(t):'
        "D.appendChild(D.createElement('script')).src=c(u),1e3)"
    ).encode("utf-8")
    if len(loader) > target_size:
        raise ValueError(f"Document-start loader is {len(loader)} bytes, larger than {target_size}-byte embedded polyfill")
    if b"eval(" in loader or b"eval " in loader:
        raise ValueError("Loader must never use eval (YouTube TV Trusted Types refuses it)")
    if b"createScriptURL" not in loader:
        raise ValueError("Loader must use a Trusted Types policy for script.src")
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
    native_url = pinned_cdn_url(script_url, 9).encode("utf-8")
    if len(native_url) != len(UPSTREAM_USERSCRIPT_URL):
        raise ValueError(
            f"Native redirect URL length {len(native_url)} != upstream {len(UPSTREAM_USERSCRIPT_URL)}"
        )

    counts = {
        "package_ascii": 0,
        "package_utf16": 0,
        "label_ascii": 0,
        "label_utf16": 0,
        "polyfill": 0,
        "signatures_removed": 0,
        "upstream_script_redirect": 0,
        "evergreen_update_urls_disabled": 0,
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

            # Redirect the upstream app's native user-script injection to our
            # filter script. Same length, so every string table/offset stays valid.
            upstream_count = data.count(UPSTREAM_USERSCRIPT_URL)
            if upstream_count:
                data = data.replace(UPSTREAM_USERSCRIPT_URL, native_url)
                counts["upstream_script_redirect"] += upstream_count

            # Prevent Cobalt Evergreen from independently replacing the native
            # runtime that contains the enforcement injection.
            data, evergreen_count = disable_evergreen_updates(data)
            counts["evergreen_update_urls_disabled"] += evergreen_count

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
    if counts["upstream_script_redirect"] < 1:
        raise RuntimeError(
            f"Could not find the upstream user-script URL to redirect; upstream release may have changed: {counts}"
        )
    if counts["evergreen_update_urls_disabled"] < 1:
        # Not every upstream release embeds the classic Omaha/Evergreen update
        # endpoints (verified: upstream v2.0.2 contains none). When the string
        # is absent there is nothing to neutralize, and refusing to build would
        # only stop the restricted app from shipping at all. Warn loudly so a
        # future upstream that DOES embed the endpoint cannot go unnoticed, but
        # do not block the release on a string that was never there.
        print(
            "WARNING: no Cobalt Evergreen update endpoint found to disable; "
            f"build continues (counts={counts})"
        )

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
