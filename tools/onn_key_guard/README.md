# Onn key guard policy runtime

Version 3.0-prepared keeps daily enforcement in `rollout=prepared` until an explicit DUMP administrative command enables it. Streaming audio adapters are `unverified`; the compiled Hebrew preference policy makes no app selections.

## Input provenance and session admission

The controller runs the native input observer passively before daily playback starts. Its verified phone-device identity plus a fresh actual native `STATUS` reply authorizes a heartbeat:

```json
{"type":"native_health","status":"monitoring","proof":"native_status","sample_wall_ms":123456789}
```

Only `monitoring` or `physical_grabbed` with this proof renews observer health. The sample must pass the event freshness check. Replayed timestamps and samples at or before an observed fault are rejected. Daily enrollment requires eight seconds of continuous observed history, a heartbeat no older than four seconds, and no nonvolume phone intent in the previous eight seconds. Volume never grants an override or an admission exception.

A failed observer, twelve seconds without proof, service detach, or an application process gap terminally releases an active session with `input_observer_lost`. A later heartbeat cannot revive that run ID. Same-boot persistence retains the original deadline and terminal run identity; it never restores input proof.

Generic phone actions suppress recovery while their effects are unresolved. A known pause preserves the phone pause. After the eight-second intent window, continuation requires recent expected-video PLAYING evidence and canonical target foreground evidence. An unknown media observation invalidates earlier PLAYING evidence. Missing evidence terminally releases the session, so a later sample cannot silently undo a phone choice.

## Bounded integration tests

The DUMP-protected receiver accepts an explicit short test while rollout remains prepared:

```sh
am broadcast -a dev.yossi.onnkeyguard.POLICY_ADMIN \
  -n dev.yossi.onnkeyguard/.PolicyAdminReceiver \
  --es command test_session --es run_generation test-unique-id \
  --es video_id dQw4w9WgXcQ --ei duration_seconds 10
```

The ID is a syntactic policy fixture; this command launches no video. `test_session` requires a currently proven live observer and a duration from 1 through 60 seconds. It authorizes physical filtering only for that exact finite TEST session; no persistent enable is needed. TEST always refuses playback recovery. Automatic expiry releases protection even when the test client loses transport. An explicit `override` or `disable_session` also releases it immediately. Fresh observer proof remains mandatory throughout the test. Test sessions may bypass the daily prestart-intent/warmup fence because the DUMP command is explicit assistant authorization.

The app exposes status and bounded events on authenticated loopback port 8768. Retrieve its private bearer token only through the DUMP `controller_token` command and keep it out of logs. `audio_tree` is a bounded DUMP-only diagnostic and does not click or change settings.

Host verification:

```sh
bash tools/onn_key_guard/tests/run_pure_tests.sh
```

These tests exercise the real pure policies, HTTP parser, source classifier, and balanced key gate. Simulated HTTP events verify integration wiring; they do not demonstrate actual physical-button or phone-button behavior.
