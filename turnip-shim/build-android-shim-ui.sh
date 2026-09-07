#!/usr/bin/env bash
# Compile shim settings Java helpers and merge into classes.dex.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
API="${ANDROID_API:-35}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-$API/android.jar"
[[ -f "$PLATFORM" ]] || { echo "need $PLATFORM" >&2; exit 1; }

OUT=/tmp/nether-shim-ui
rm -rf "$OUT"
mkdir -p "$OUT/classes"

javac -source 11 -target 11 -classpath "$PLATFORM" \
  -d "$OUT/classes" android/*.java

jar cf "$OUT/shim.jar" -C "$OUT/classes" .

mkdir -p "$OUT/dex"
"$BT/d8" --release --lib "$PLATFORM" --min-api 29 \
  --output "$OUT/dex/shim-ui.zip" \
  "$OUT/shim.jar"

cp "$OUT/dex/shim-ui.zip" ./shim-ui.dex.zip
unzip -qo ./shim-ui.dex.zip classes.dex -d .
mv classes.dex shim-ui-classes.dex
rm -f shim-ui.dex.zip

# Merge shim helpers into base classes.dex for repack
BASE_DEX="${BASE_APK:-$HOME/Downloads/NetherSX2-Fused-r31.apk}"
if [[ -f "$BASE_DEX" ]]; then
  unzip -qo "$BASE_DEX" classes.dex -d /tmp/nether-shim-merge
  "$BT/d8" --release --lib "$PLATFORM" --min-api 29 \
    --output /tmp/nether-shim-merged.zip \
    /tmp/nether-shim-merge/classes.dex "$OUT/shim.jar"
  unzip -qo /tmp/nether-shim-merged.zip classes.dex -d .
  mv classes.dex merged-classes.dex
  echo "Built merged-classes.dex (base + shim UI)"
fi
echo "Built shim-ui-classes.dex"
