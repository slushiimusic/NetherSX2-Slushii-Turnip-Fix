#!/usr/bin/env bash
# Build lsfg-vk for the GameNative Android emulator (Wine-on-Android).
#
# Produces:
#   build-android/liblsfg-vk.so        (aarch64 shared library)
#   build-android/VkLayer_LS_frame_generation.json (copied from repo root)
#
# Requirements:
#   ANDROID_NDK env var pointing at an Android NDK r25+ (we use the CMake toolchain it ships).
#
# Usage:
#   ANDROID_NDK=/path/to/android-ndk-r26d ./scripts/build/android.sh [Release|Debug]

set -euo pipefail

BUILD_TYPE="${1:-Release}"
ABI="${ANDROID_ABI:-arm64-v8a}"
API="${ANDROID_PLATFORM:-android-28}"

if [[ -z "${ANDROID_NDK:-}" ]]; then
    echo "error: ANDROID_NDK must be set to your NDK root (e.g. /opt/android-ndk-r26d)" >&2
    exit 1
fi

TOOLCHAIN="${ANDROID_NDK}/build/cmake/android.toolchain.cmake"
if [[ ! -f "${TOOLCHAIN}" ]]; then
    echo "error: toolchain not found at ${TOOLCHAIN}" >&2
    exit 1
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." &>/dev/null && pwd)"
BUILD_DIR="${REPO_ROOT}/build-android"

mkdir -p "${BUILD_DIR}"

cmake -S "${REPO_ROOT}" -B "${BUILD_DIR}" \
    -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN}" \
    -DANDROID_ABI="${ABI}" \
    -DANDROID_PLATFORM="${API}" \
    -DCMAKE_BUILD_TYPE="${BUILD_TYPE}" \
    -DLSFGVK_ANDROID_WINE=ON

cmake --build "${BUILD_DIR}" --parallel

cp "${REPO_ROOT}/VkLayer_LS_frame_generation.json" "${BUILD_DIR}/"

echo ""
echo "Build complete. Artifacts:"
echo "  ${BUILD_DIR}/liblsfg-vk.so"
echo "  ${BUILD_DIR}/VkLayer_LS_frame_generation.json"
echo ""
echo "Copy both files into GameNative's assets/lsfg-vk/ directory."
