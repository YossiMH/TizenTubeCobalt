#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import argparse,hashlib,sys,zipfile
from pathlib import Path
GH=b'cdn.jsdelivr.net/gh/YossiMH/TizenTubeCobalt@'
UP=b'cdn.jsdelivr.net/npm/@foxreis/tizentube/dist/userScript.js'
LM=b'createScriptURL'
LB=b'TizenSub+'
OLD=b'io.gh.reisxd.tizentube.cobalt'
NEW=b'io.gh.yossim.tizentube.cobalt'
XJS=b'/x.js'
def u16(b): return b.decode('utf-8').encode('utf-16le')
def verify_apk(path,sha):
    out=[]
    data=Path(path).read_bytes()
    dg=hashlib.sha256(data).hexdigest()
    if NEW not in data and u16(NEW) not in data: out.append('fork package id missing')
    if OLD in data or u16(OLD) in data: out.append('upstream package id still present')
    if LB not in data and u16(LB) not in data: out.append('fork label missing')
    with zipfile.ZipFile(path) as z:
        libs=[n for n in z.namelist() if n.endswith('libchrobalt.so')]
        lib=z.read(libs[0]) if libs else b''
        if not libs: out.append('no libchrobalt.so in APK')
        if UP in lib: out.append('native slot points at upstream user script (unpatched build)')
        if GH not in lib: out.append('fork CDN pin missing from native library')
        if GH+sha[:9]+XJS not in lib: out.append('native slot pin does not match current release')
        if GH+sha[:7]+XJS not in lib: out.append('loader slot pin does not match current release')
        if LM not in lib: out.append('document-start Trusted-Types loader missing')
    return dg,out
def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('apk',type=Path)
    ap.add_argument('--sha',default='29aea15dedf')
    a=ap.parse_args()
    dg,out=verify_apk(a.apk,a.sha.encode('ascii'))
    print('sha256 '+dg)
    if out:
        print('REJECTED:')
        for x in out: print('  - '+x)
        return 1
    print('OK: verified allowed-only release build')
    return 0
if __name__=='__main__': sys.exit(main())
