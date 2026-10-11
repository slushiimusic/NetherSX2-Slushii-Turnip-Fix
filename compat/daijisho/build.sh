#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-11.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
KEYSTORE="${TURNIP_KEYSTORE:-$HOME/.config/nethersx2-turnip/signing.jks}"
[[ -f "$KEYSTORE" ]] || { echo 'Existing Pink signing key required'; exit 1; }
BUILD=$(mktemp -d /tmp/slushii-daijisho.XXXXXX)
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/classes" "$BUILD/dex" out
"$BT/aapt" package -f -M compat/daijisho/AndroidManifest.xml -I "$SDK/platforms/android-35/android.jar" -F "$BUILD/connector.apk"
javac -source 11 -target 11 -cp "$SDK/platforms/android-35/android.jar" -d "$BUILD/classes" compat/daijisho/EmulationActivity.java
jar cf "$BUILD/connector.jar" -C "$BUILD/classes" .
"$BT/d8" --release --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --output "$BUILD/dex" "$BUILD/connector.jar"
(cd "$BUILD/dex" && "$BT/aapt" add "$BUILD/connector.apk" classes.dex)
"$BT/zipalign" -f 4 "$BUILD/connector.apk" "$BUILD/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:nethersx2 --key-pass pass:nethersx2 \
    --out out/Slushii-Daijisho-Connector-v1.apk "$BUILD/aligned.apk"
"$BT/apksigner" verify --verbose out/Slushii-Daijisho-Connector-v1.apk
