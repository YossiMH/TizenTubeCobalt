#!/usr/bin/env python3
import importlib.util
from pathlib import Path

PATCHER = Path(__file__).with_name("patch_allowed_only_apk.py")
spec = importlib.util.spec_from_file_location("patch_allowed_only_apk", PATCHER)
patcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patcher)

assert len(patcher.EVERGREEN_UPDATE_URL_PROD) == len(patcher.DISABLED_EVERGREEN_URL_PROD)
assert len(patcher.EVERGREEN_UPDATE_URL_QA) == len(patcher.DISABLED_EVERGREEN_URL_QA)

sample = (
    b"before:"
    + patcher.EVERGREEN_UPDATE_URL_PROD
    + b":middle:"
    + patcher.EVERGREEN_UPDATE_URL_QA
    + b":after"
)
original_len = len(sample)
patched, count = patcher.disable_evergreen_updates(sample)

assert count == 2, count
assert len(patched) == original_len
assert patcher.EVERGREEN_UPDATE_URL_PROD not in patched
assert patcher.EVERGREEN_UPDATE_URL_QA not in patched
assert patcher.DISABLED_EVERGREEN_URL_PROD in patched
assert patcher.DISABLED_EVERGREEN_URL_QA in patched

patched_again, second_count = patcher.disable_evergreen_updates(patched)
assert second_count == 0
assert patched_again == patched

print("TizenTube patcher Evergreen safety tests passed.")
