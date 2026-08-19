# Filter reliability notes (2026-08-18)

What keeps the allowed-only filter running on the TV, how it was proven, and
how to rebuild/verify a release. Read this before changing x.js or the patcher.

## Enforcement architecture (verified on the Onn 4K Pro, 2026-08-18)

- The ALLOWED-ONLY filter is injected by TWO independent document paths:
  1. **Native user-script injection** — the upstream app fetches its user script
     from a URL stored in `libchrobalt.so`; the patcher redirects that exact
     66-byte slot to a pinned cdn.jsdelivr.net GitHub URL for our `x.js`. This
     path is CSP-exempt and is the path that ACTUALLY enforces (proved on-device:
     console shows `[allowed-only] filter active` sourced from the pinned URL).
  2. **Document-start loader** — replaces the embedded
     `html_media_element_extension_on_java_bridge.js` polyfill (288-byte slot)
     with a small Trusted-Types-safe loader that appends a <script> to the page.
- A pilot that pointed the loader at a bundled asset (`file:///android_asset/x.js`,
  plus adding assets/x.js to the APK) was rejected by Cobalt:
  \`Not allowed to load local resource: file:///android_asset/x.js\`. So the
  enforcement cannot be made fully network-free via the document-start loader.
  The native path remains the enforcement backbone.
- Consequence: keep the 66-byte native slot on a pinned jsDelivr URL (it is the
  only length that fits), and keep the loader on the same pinned URL. Both point
  at the SAME committed x.js so behavior is identical regardless of which path
  wins the race.

## Self-healing guard (x.js)

`x.js` installs a long-lived `guard()` (driven by `watchStatus`'s 1.5 s timer)
that:

- Re-wraps `XMLHttpRequest.prototype` (`open`/`send` + `response`/`responseText`
  getters) if YouTube TV ever replaces the prototype mid-session (detected via
  the `__ttWrap` tag; re-install clears `__ao` and reruns installXhr).
- Re-arms the getter interception (`S.arm()`) if it was clobbered but the
  prototype wrapper survived.
- Re-installs the `window.fetch` wrapper if it was replaced (detected via the
  `__tt` tag on the wrapper / missing `S.fetch0`).

This closes the "filter was live, then a page frame replaced an interceptor and
everything passed through unfiltered" failure class that matches a signed-in
search appearing unconstrained.

## Regression tests

- `tools/tizentube_allowed_only_test.js` — search/browse/queue/player filtering,
  guest fail-closed, and the guard re-wrap contract.
- `tools/tizentube_boot_recovery_test.js` — signed-out boot, retry schedule.
- `tools/tizentube_status_ui_test.js` — visible restriction status.
- `tools/tizentube_patcher_safety_test.py` + `tools/patch_allowed_only_apk_test.py`
  — patcher invariants (polyfill slot still 288 bytes, loader Trusted-Types-safe,
  dex re-signing, Evergreen endpoint neutralization when present).

Run:
    node tools/tizentube_allowed_only_test.js
    node tools/tizentube_boot_recovery_test.js
    node tools/tizentube_status_ui_test.js
    python tools/tizentube_patcher_safety_test.py
    python tools/patch_allowed_only_apk_test.py

