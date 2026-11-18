"""Canonical DEX patch verification helpers retained from the prior Guard recovery builder."""

import hashlib
import re
import struct
import subprocess
import zlib

METHOD_PATTERN = re.compile(r"^\.method [^\n]+\n.*?^\.end method", re.M | re.S)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def run(*args):
    subprocess.run(list(map(str, args)), check=True, timeout=90)

def smali(jar, command, *args):
    run("java", "-cp", jar, "com.android.tools.smali." + command, *args)

def methods(text):
    result = {}
    for match in METHOD_PATTERN.finditer(text):
        value = match[0]
        signature = value.splitlines()[0].split()[-1]
        if signature in result:
            raise ValueError("duplicate_method:" + signature)
        result[signature] = value
    return result

def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("unexpected_anchor:" + old[:160])
    return text.replace(old, new, 1)

def after_registers(method, code):
    anchors = list(re.finditer(r"^    \.registers \d+\n", method, re.M))
    if len(anchors) != 1:
        raise ValueError("unexpected_register_directive")
    i = anchors[0].end()
    return method[:i] + "\n" + code + "\n" + method[i:]

def canonical(text):
    # Disassembly may add separate goto/conditional/try labels for one address.
    # Normalize references to their instruction ordinal, preserving every
    # opcode, register count, catch region and annotation. Debug .line placement
    # can move across an adjacent label without changing execution.
    lines = text.splitlines()
    mapping = {}
    definitions = set()
    ordinal = 0
    payload = False
    for index, raw in enumerate(lines):
        line = raw.strip()
        if line.startswith((".packed-switch ", ".sparse-switch", ".array-data ")):
            payload = True
            ordinal += 1
        elif line.startswith((".end packed-switch", ".end sparse-switch", ".end array-data")):
            payload = False
        elif re.fullmatch(r":[A-Za-z_][A-Za-z_0-9]*", line) and not payload:
            mapping[line] = f":instruction_{ordinal}"
            definitions.add(index)
        elif line and not line.startswith((".", ":", "#")):
            ordinal += 1
    result = []
    for index, raw in enumerate(lines):
        line = raw.strip()
        if index in definitions or not line or line.startswith(("#", ".line ")):
            continue
        result.append(re.sub(r":[A-Za-z_][A-Za-z_0-9]*", lambda m: mapping.get(m[0], m[0]), line))
    return "\n".join(result)

def class_header(text):
    return canonical(METHOD_PATTERN.sub("", text))

def dex_integrity(data):
    if not data.startswith(b"dex\n") or struct.unpack_from("<I", data, 32)[0] != len(data):
        raise ValueError("invalid_dex_header")
    if hashlib.sha1(data[32:]).digest() != data[12:32]:
        raise ValueError("invalid_dex_signature")
    if zlib.adler32(data[12:]) & 0xffffffff != struct.unpack_from("<I", data, 8)[0]:
        raise ValueError("invalid_dex_checksum")
    return {"sha256": digest(data), "bytes": len(data), "sha1_signature_valid": True,
            "adler32_checksum_valid": True}
