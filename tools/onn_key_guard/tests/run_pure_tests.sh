#!/usr/bin/env bash
set -euo pipefail
keyguard_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
keyguard_java="${ONN_POLICY_JAVA:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}"
mkdir -p "$keyguard_root/build/pure-tests"
"$keyguard_java" --module jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror \
  -d "$keyguard_root/build/pure-tests" \
  "$keyguard_root/src/dev/yossi/onnkeyguard/AudioLanguage.java" \
  "$keyguard_root/src/dev/yossi/onnkeyguard/AudioPreferencePolicy.java" \
  "$keyguard_root/src/dev/yossi/onnkeyguard/SesameSessionPolicy.java" \
  "$keyguard_root/src/dev/yossi/onnkeyguard/SessionMode.java" \
  "$keyguard_root/src/dev/yossi/onnkeyguard/RemoteIdentity.java" \
  "$keyguard_root/src/dev/yossi/onnkeyguard/PhysicalKeyGate.java" \
  "$keyguard_root/src/dev/yossi/onnkeyguard/ControllerProtocol.java" \
  "$keyguard_root/src/dev/yossi/onnkeyguard/InputObserverPolicy.java" \
  "$keyguard_root/tests/AudioPreferencePolicyTest.java" \
  "$keyguard_root/tests/SesameSessionPolicyTest.java" \
  "$keyguard_root/tests/SessionModeTest.java" \
  "$keyguard_root/tests/RuntimeProtocolTest.java" \
  "$keyguard_root/tests/InputObserverPolicyTest.java"
"$keyguard_java" -cp "$keyguard_root/build/pure-tests" dev.yossi.onnkeyguard.AudioPreferencePolicyTest
"$keyguard_java" -cp "$keyguard_root/build/pure-tests" dev.yossi.onnkeyguard.SesameSessionPolicyTest
"$keyguard_java" -cp "$keyguard_root/build/pure-tests" dev.yossi.onnkeyguard.SessionModeTest
"$keyguard_java" -cp "$keyguard_root/build/pure-tests" dev.yossi.onnkeyguard.RuntimeProtocolTest
"$keyguard_java" -cp "$keyguard_root/build/pure-tests" dev.yossi.onnkeyguard.InputObserverPolicyTest
