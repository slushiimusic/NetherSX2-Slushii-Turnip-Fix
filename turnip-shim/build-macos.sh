#!/usr/bin/env bash
# Port of compile2.bat to macOS. Builds the NetherSX2-Turnip Vulkan shim for arm64 Android.
set -euo pipefail
cd "$(dirname "$0")"

# --- locate the NDK -----------------------------------------------------
if [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then
    NDK="$ANDROID_NDK_HOME"
else
    NDK="$(ls -d /opt/homebrew/Caskroom/android-ndk/*/AndroidNDK*.app/Contents/NDK \
                 /opt/homebrew/share/android-ndk \
                 "$HOME"/Library/Android/sdk/ndk/* 2>/dev/null | head -1 || true)"
fi
[[ -d "${NDK:-}" ]] || { echo "error: Android NDK not found. Set ANDROID_NDK_HOME."; exit 1; }

# The NDK ships an x86_64-named prebuilt dir even on Apple Silicon.
TOOLCHAIN=""
for host in darwin-arm64 darwin-x86_64; do
    if [[ -d "$NDK/toolchains/llvm/prebuilt/$host/bin" ]]; then
        TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$host/bin"
        SYSROOT="$NDK/toolchains/llvm/prebuilt/$host/sysroot"
        break
    fi
done
[[ -n "$TOOLCHAIN" ]] || { echo "error: no llvm prebuilt toolchain under $NDK"; exit 1; }

API=26
CXX="$TOOLCHAIN/aarch64-linux-android$API-clang++"
CC="$TOOLCHAIN/aarch64-linux-android$API-clang"
AR="$TOOLCHAIN/llvm-ar"

echo "NDK       $NDK"
echo "toolchain $TOOLCHAIN"
"$CXX" --version | head -1
echo

rm -f ./*.o ./*.a ./*.so

# --- 1. static helper lib ----------------------------------------------
echo "[1/4] android_linker_ns.cpp + elf_soname_patcher.cpp"
"$CXX" --target=aarch64-linux-android$API --sysroot="$SYSROOT" \
    -fPIC -O2 -c android_linker_ns.cpp elf_soname_patcher.cpp \
    -I. -fvisibility=default
"$AR" rcs liblinkernsbypass.a android_linker_ns.o elf_soname_patcher.o

# --- 2. libhook_impl.so -------------------------------------------------
echo "[2/4] libhook_impl.so"
"$CXX" -shared -fPIC -O2 -static-libstdc++ -fvisibility=default \
    -o libhook_impl.so hook_impl.cpp \
    -I. -L. -llinkernsbypass -ldl -llog

# --- 3. libmain_hook.so -------------------------------------------------
echo "[3/4] libmain_hook.so"
"$CC" -shared -fPIC -O2 -z global -fvisibility=default \
    -o libmain_hook.so main_hook.c \
    -I. -ldl -llog -L. -lhook_impl

# --- 4. FSR1 shaders ----------------------------------------------------
echo "[4/5] FSR1 shaders + cpu constants"
(cd fsr && ./compile-shaders.sh)

# --- 5. libvulkad.so — the one that picks the driver --------------------
echo "[5/5] libvulkad.so"
FSR_FG_FLAGS=()
if [[ "${NETHER_NO_FSR_FRAMEGEN:-0}" == "1" ]]; then
    FSR_FG_FLAGS+=(-DNETHER_NO_FSR_FRAMEGEN=1)
    echo "  NETHER_NO_FSR_FRAMEGEN=1 (no FSR upscale / framegen)"
fi
FG_SOURCE=fg_source.cpp
if [[ "${NETHER_NO_INPROCESS_FRAMEGEN:-0}" == "1" ]]; then
    FSR_FG_FLAGS+=(-DNETHER_NO_INPROCESS_FRAMEGEN=1)
    FG_SOURCE=fg_source_disabled.cpp
fi
# fg_source.cpp is the in-process frame generator (turnip.conf lsfg_overlay).
# It is a DIFFERENT feature from the FSR-era framegen that NETHER_NO_FSR_FRAMEGEN
# strips, so it is always compiled in; the runtime gate is lsfg_overlay.
# -landroid is mandatory: without it libvulkad.so fails to load ENTIRELY on
# AHardwareBuffer_allocate and the whole shim goes inert, silently.
"$CXX" -shared -fPIC -O2 -static-libstdc++ -fvisibility=default \
    ${FSR_FG_FLAGS[@]+"${FSR_FG_FLAGS[@]}"} \
    -Wl,--no-undefined -o libvulkad.so vulkan_shim.cpp fsr_upscaler.cpp fsr/fsr_cpu_constants.cpp \
    "$FG_SOURCE" \
    -I. -Ifsr -L. -llinkernsbypass -ldl -llog -landroid

echo
echo "built:"
for f in libvulkad.so libhook_impl.so libmain_hook.so; do
    printf '  %-20s %10s bytes\n' "$f" "$(stat -f%z "$f")"
done
file libvulkad.so

# Strip. Without this the libraries carry full debug_info and come out ~6x
# larger, which matters when three of them ride inside a 125 MB apk.
echo
echo "stripping"
for f in libvulkad.so libhook_impl.so libmain_hook.so; do
    "$TOOLCHAIN/llvm-strip" --strip-unneeded "$f"
done
