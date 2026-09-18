#!/usr/bin/env bash
# Cross-compiles liblogosdelivery.so and libstorage.so for all four Android
# ABIs, then compiles each library's JNI shim against the resulting headers.
#
# Requires: ANDROID_NDK_HOME set, Nim/nimble on PATH, git submodules
# initialized (`git submodule update --init --recursive`).
#
# Usage:
#   ./scripts/build-nim-android.sh [abi ...]
#
# With no arguments, builds all four ABIs. Pass one or more of
# arm64-v8a, x86_64, x86, armeabi-v7a to build a subset (useful for local
# iteration).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DELIVERY_DIR="$REPO_ROOT/nim-src/logos-delivery"
STORAGE_DIR="$REPO_ROOT/nim-src/logos-storage-nim"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must be set}"
: "${ANDROID_TARGET:=30}"

ALL_ABIS=(arm64-v8a x86_64 x86 armeabi-v7a)
ABIS=("${@:-${ALL_ABIS[@]}}")

declare -A DELIVERY_MAKE_TARGET=(
  [arm64-v8a]=liblogosdelivery-android-arm64
  [x86_64]=liblogosdelivery-android-amd64
  [x86]=liblogosdelivery-android-x86
  [armeabi-v7a]=liblogosdelivery-android-arm
)
declare -A STORAGE_MAKE_TARGET=(
  [arm64-v8a]=libstorage-android-arm64
  [x86_64]=libstorage-android-amd64
  [x86]=libstorage-android-x86
  [armeabi-v7a]=libstorage-android-arm
)

echo "==> Building for ABIs: ${ABIS[*]}"

for abi in "${ABIS[@]}"; do
  echo "==> [$abi] logos-delivery"
  make -C "$DELIVERY_DIR" "${DELIVERY_MAKE_TARGET[$abi]}" ANDROID_TARGET="$ANDROID_TARGET"

  echo "==> [$abi] logos-storage-nim"
  make -C "$STORAGE_DIR" "${STORAGE_MAKE_TARGET[$abi]}" ANDROID_TARGET="$ANDROID_TARGET"
done

if [[ -x "$REPO_ROOT/scripts/build-jni-shims.sh" ]]; then
  echo "==> Building JNI shims"
  "$REPO_ROOT/scripts/build-jni-shims.sh" "${ABIS[@]}"
  echo "==> Done. Nim libraries in nim-src/*/build/android/<abi>/, shims in build/jni/<abi>/"
else
  echo "==> Skipping JNI shims: scripts/build-jni-shims.sh not written yet (Milestone 3)."
  echo "==> Done. Nim libraries in nim-src/*/build/android/<abi>/"
fi
