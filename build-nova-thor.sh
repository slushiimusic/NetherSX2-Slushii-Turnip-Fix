#!/usr/bin/env bash
# Rebuild Pink only, retaining its branding/game patches and adding the Nova/Thor shim.
# Build only: does not install, clear app data, or modify runtime preferences.
set -euo pipefail
cd "$(dirname "$0")"
BASE="${1:-$PWD/out/NetherSX2-Slushii-cpink02-framegen.apk}"
VERSION_CODE="${VERSION_CODE:-26500719}"
VARIANT="${VARIANT:-FRAMEGEN}"
case "$VARIANT" in FRAMEGEN|NONFRAMEGEN) ;; *) echo "VARIANT must be FRAMEGEN or NONFRAMEGEN" >&2; exit 1;; esac
EXPECTED_PACKAGE=xyz.aethersx2.cpink02
DEST="${OUT_APK:-$PWD/out/NetherSX2-Pink-v$VERSION_CODE-$VARIANT.apk}"
[[ -f "$BASE" ]] || { echo "Base APK missing: $BASE" >&2; exit 1; }
python3 - "$BASE" "$DEST" <<'PY'
import pathlib, sys
base, dest = (pathlib.Path(x).resolve() for x in sys.argv[1:])
protected = {'WORKING-VERSION.apk', 'IR-WORKING-1285640.apk',
             'NetherSX2-Fused-r97-orange-1285640.apk',
             'NetherSX2-Slushii-cpink02.apk', 'NetherSX2-Slushii-cturnip.apk',
             'NetherSX2-Slushii-cturnip-framegen.apk',
             'NetherSX2-Slushii-cpink02-framegen.apk'}
if base == dest or dest.name in protected:
    raise SystemExit('Refusing to overwrite the input or a release checkpoint')
PY
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-11.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
AAPT="${AAPT:-$HOME/Library/Android/sdk/build-tools/35.0.0/aapt}"
base_badging=$("$AAPT" dump badging "$BASE")
base_package=$(printf '%s\n' "$base_badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ "$base_package" == "$EXPECTED_PACKAGE" ]] || {
    echo "Pink build requires $EXPECTED_PACKAGE; input is $base_package" >&2
    exit 1
}
base_version=$(printf '%s\n' "$base_badging" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
[[ "$VERSION_CODE" =~ ^[0-9]+$ && "$base_version" =~ ^[0-9]+$ ]] || exit 1
(( VERSION_CODE > base_version )) || { echo "VERSION_CODE must exceed $base_version" >&2; exit 1; }
BUILD_TMP=$(mktemp -d /tmp/nether-nova-thor.XXXXXX)
trap 'rm -rf "$BUILD_TMP"' EXIT
export NETHER_NO_FSR_FRAMEGEN=1 NETHER_NO_FSR_SWITCH=1
export BASE_APK="$BASE" RELEASE_VERSION_NAME="v$VERSION_CODE"
if [[ "$VARIANT" == NONFRAMEGEN ]]; then
    export NETHER_FRAMEGEN_ROW=0 NETHER_NO_INPROCESS_FRAMEGEN=1
    export ADD_LIBS="" DROP_LIBS=liblsfg-android.so
else
    export NETHER_FRAMEGEN_ROW=1 NETHER_NO_INPROCESS_FRAMEGEN=0 DROP_LIBS=""
    export ADD_LIBS="$PWD/turnip-shim/lsfg-lib"
    if [[ ! -f "$ADD_LIBS/liblsfg-android.so" ]]; then
        export ADD_LIBS="$BUILD_TMP/lsfg-lib"
        mkdir -p "$ADD_LIBS"
        unzip -p "$BASE" lib/arm64-v8a/liblsfg-android.so > "$ADD_LIBS/liblsfg-android.so"
    fi
fi
export PROVIDER_AUTHORITY="$EXPECTED_PACKAGE.shiminit"
[[ "$VARIANT" == NONFRAMEGEN || -s "$ADD_LIBS/liblsfg-android.so" ]] || { echo "Missing LSFG library" >&2; exit 1; }
bash turnip-shim/build-macos.sh
python3 turnip-shim/ensure-vulkan-shim-prefs.py "$BASE" "$BUILD_TMP/handheld.apk"
export BUMP_VERSION=$((VERSION_CODE - base_version))
python3 turnip-shim/repack-apk.py "$BUILD_TMP/handheld.apk" "$PWD/turnip-shim/libvulkad.so"
candidate="$BUILD_TMP/handheld-patched-signed.apk"
result_badging=$("$AAPT" dump badging "$candidate")
result_package=$(printf '%s\n' "$result_badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
result_version=$(printf '%s\n' "$result_badging" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
[[ "$result_package" == "$EXPECTED_PACKAGE" && "$result_version" == "$VERSION_CODE" ]] || {
    echo "Output package/version verification failed; refusing to deliver APK" >&2
    exit 1
}
mkdir -p "$(dirname "$DEST")"
cp "$candidate" "$DEST"
printf '%s\n' "$result_badging" | sed -n '1p'
echo "Built: $DEST"
