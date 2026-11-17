# Original player-route host fixtures

`original-source/` contains the exact readable `PlayerScriptAsset.java` and
`ControllerProtocol.java` recovered from the pinned installed Guard v8 APK.
`source-hashes.json` records their hashes; `test_player_route.py` verifies those
hashes before applying the single intended constructor-path substitution.

These fixtures support a host Java regression. They are never inputs to D8 or
packaged in the Android APK. `build_guard_banner.py` separately verifies the
actual original and patched DEX methods. The compile-only SesameSessionPolicy
ABI under `compile-stubs/` is likewise excluded from DEX.
