#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-11.jdk/Contents/Home}"
CHECK_OUT=$(mktemp -d /tmp/nether-handheld-tests.XXXXXX)
trap 'rm -rf "$CHECK_OUT"' EXIT
"$JAVA_HOME/bin/javac" -source 11 -target 11 -classpath "$SDK/platforms/android-35/android.jar" \
  -d "$CHECK_OUT" turnip-shim/android/*.java tests/CapturePausesTest.java tests/IrRebuildGateTest.java 2>"$CHECK_OUT/javac.log" \
  || { cat "$CHECK_OUT/javac.log"; exit 1; }
"$JAVA_HOME/bin/java" -cp "$CHECK_OUT:$SDK/platforms/android-35/android.jar" xyz.aethersx2.android.shim.CapturePausesTest "$CHECK_OUT"
"$JAVA_HOME/bin/java" -cp "$CHECK_OUT" xyz.aethersx2.android.shim.IrRebuildGateTest
# Host doubles replace Android/VM boundaries only; all shim logic above is real.
mkdir -p "$CHECK_OUT/host"
"$JAVA_HOME/bin/javac" -source 11 -target 11 -cp "$CHECK_OUT:$SDK/platforms/android-35/android.jar" \
  -d "$CHECK_OUT/host" $(rg --files tests/host-stubs -g '*.java') tests/SettingsRegressionTest.java
"$JAVA_HOME/bin/java" -cp "$CHECK_OUT/host:$CHECK_OUT:$SDK/platforms/android-35/android.jar" \
  xyz.aethersx2.android.shim.SettingsRegressionTest "$CHECK_OUT/settings-fixture"

"${CXX:-c++}" -std=c++17 -O2 tests/ArtifactGuardTest.cpp -o "$CHECK_OUT/artifact-guard"
"$CHECK_OUT/artifact-guard"

"${CXX:-c++}" -std=c++17 -O2 -pthread tests/CaptureHandoffTest.cpp -o "$CHECK_OUT/capture-handoff"
"$CHECK_OUT/capture-handoff"

"${CXX:-c++}" -std=c++17 -O2 tests/FramePacingTest.cpp -o "$CHECK_OUT/frame-pacing"
"$CHECK_OUT/frame-pacing"

python3 tests/run-frame-history-check.py
