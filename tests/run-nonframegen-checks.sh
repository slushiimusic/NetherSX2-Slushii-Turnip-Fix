#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-11.jdk/Contents/Home}"
CHECK_OUT=$(mktemp -d /tmp/nether-nonframegen-tests.XXXXXX)
trap 'rm -rf "$CHECK_OUT"' EXIT
cat > "$CHECK_OUT/FramegenBuild.java" <<'JAVA'
package xyz.aethersx2.android.shim;
final class FramegenBuild { static final boolean AVAILABLE = false; }
JAVA
JAVA_SOURCES=()
for source in turnip-shim/android/*.java; do
 [[ "$source" == turnip-shim/android/FramegenBuild.java ]] || JAVA_SOURCES+=("$source")
done
"$JAVA_HOME/bin/javac" -source 11 -target 11 -classpath "$SDK/platforms/android-35/android.jar" -d "$CHECK_OUT" "${JAVA_SOURCES[@]}" "$CHECK_OUT/FramegenBuild.java"
mkdir "$CHECK_OUT/host"
"$JAVA_HOME/bin/javac" -source 11 -target 11 -cp "$CHECK_OUT:$SDK/platforms/android-35/android.jar" -d "$CHECK_OUT/host" $(rg --files tests/host-stubs -g '*.java') tests/SettingsRegressionTest.java tests/NonFramegenSettingsTest.java
"$JAVA_HOME/bin/java" -cp "$CHECK_OUT/host:$CHECK_OUT:$SDK/platforms/android-35/android.jar" xyz.aethersx2.android.shim.NonFramegenSettingsTest "$CHECK_OUT/settings"
"${CXX:-c++}" -std=c++17 -Iturnip-shim tests/DisabledFramegenTest.cpp turnip-shim/fg_source_disabled.cpp -o "$CHECK_OUT/disabled"
"$CHECK_OUT/disabled"
echo 'PASS: NONFRAMEGEN native boundary never enables, captures or arms a present route'
