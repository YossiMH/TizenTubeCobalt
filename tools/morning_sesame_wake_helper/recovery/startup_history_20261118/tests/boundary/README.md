# Independent helper boundary checks

Run `python run.py --json-jar /path/to/json.jar`. The default source paths point to this recovery bundle and the sibling `playlist_selection_20261118` bundle. Optional `--helper-source` and `--validator-source` arguments select isolated candidates; `--result` writes a source-hashed receipt.

This harness compiles the actual new history reply parser, ordered reader, Android-version transport shim and selector adapter, alongside the unchanged actual history/eligibility validators. A ready nested reply passes through these real boundaries and reaches the deployed `WorkerFenceContinuation` after receiver release. It covers invalid proof, run identity, deadlines, API 34 identity-sharing argument forwarding, API 33 fallback, pending replies, cancellation before acceptance, interruption and finite reply timeout. It then checks post-worker proof, release ordering, exact selected identity, cancellation, stale-run isolation and fence deadline behavior.

Android framework transport/storage/handler classes and RunCoordinator are fixtures. The host cannot establish actual permission enforcement or installed alarm delivery; those remain device acceptance gates, including confirmed canonical episode enrollment.

The WorkerFenceContinuation and StartupFencePolicy fixtures were recovered from the installed helper. Their raw hashes and the matching WorkerFenceContinuation smali are pinned by the runner. Two invalid JADX boolean expressions are normalized only in a temporary compilation directory: DEX xor(true) becomes Java logical negation, and DEX boolean 1 becomes Java true. The original fixture bytes stay unchanged. Product source is not altered by this harness.

The optional result receipt records the tested production-source hashes and the exact fixture limits. No device, network, account or media operation occurs.
