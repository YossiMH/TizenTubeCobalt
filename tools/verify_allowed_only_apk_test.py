#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import importlib.util,tempfile,zipfile
from pathlib import Path
P=Path(__file__).with_name('verify_allowed_only_apk.py')
spec=importlib.util.spec_from_file_location('va',P)
V=importlib.util.module_from_spec(spec)
spec.loader.exec_module(V)
SHA='29aea15dedf'.encode('ascii')
def make_apk(lib):
    d=Path(tempfile.mkdtemp())/'fake.apk'
    with zipfile.ZipFile(d,'w') as z:
        z.writestr('classes.dex',V.NEW+b' '+V.LB,compress_type=zipfile.ZIP_STORED)
        z.writestr('lib/arm64-v8a/libchrobalt.so',lib,compress_type=zipfile.ZIP_STORED)
    return d
good_lib=V.GH+SHA[:9]+V.XJS+b' '+V.GH+SHA[:7]+V.XJS+b' '+V.LM+b' '
upstream_lib=V.UP
oldpin_lib=V.GH+b'263904d06'+V.XJS+b' '+V.GH+b'263904d0'+V.XJS+b' '+V.LM+b' '
noloader_lib=V.GH+SHA[:9]+V.XJS+b' '+V.GH+SHA[:7]+V.XJS+b' '
def problems_of(lib):
    apk=make_apk(lib)
    _,out=V.verify_apk(apk,SHA)
    return out
assert problems_of(good_lib)==[],problems_of(good_lib)
up=problems_of(upstream_lib)
assert any('upstream user script' in x for x in up),up
old=problems_of(oldpin_lib)
assert any('native slot pin does not match' in x for x in old),old
nl=problems_of(noloader_lib)
assert any('loader missing' in x for x in nl),nl
print('all allowed-only APK release-verifier tests passed.')
