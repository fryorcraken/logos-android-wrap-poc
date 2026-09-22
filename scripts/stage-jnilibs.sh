#!/usr/bin/env bash
# Copies the Nim-built native libraries and the compiled JNI shims into each
# Gradle module's src/main/jniLibs/<abi>/, where AGP picks them up as plain
# prebuilt .so's (see docs/adr/0003-jni-shim-per-module.md -- from Gradle's
# point of view a shim's .so is indistinguishable from a Nim-produced one,
# it is just another file already sitting in jniLibs/ by the time Gradle
# runs).
#
# Run after scripts/build-nim-android.sh (which builds the Nim libraries and
# calls scripts/build-jni-shims.sh in turn) and before
# `./gradlew assembleDebug`.
#
# Currently covers only logos-delivery / arm64-v8a -- see build-jni-shims.sh
# for why the other ABIs and logos-storage are deferred.
#
# Usage:
#   ./scripts/stage-jnilibs.sh [abi ...]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DELIVERY_BUILD_DIR="$REPO_ROOT/nim-src/logos-delivery/build/android"
JNI_BUILD_DIR="$REPO_ROOT/build/jni"
DELIVERY_JNILIBS_DIR="$REPO_ROOT/android/logos-delivery/src/main/jniLibs"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must be set}"
case "$(uname -s)" in
  Darwin) NDK_HOST_TAG="darwin-x86_64" ;;
  *)      NDK_HOST_TAG="linux-x86_64" ;;
esac
# NDK's own llvm-strip, not a host `strip` or `patchelf` -- either of those
# corrupts DT_GNU_HASH, which bionic's dynamic linker requires (see
# vpavlin/logos-delivery's WRITEUP.md, cross-checked empirically here: a
# host-strip-then-patchelf round trip on this exact .so drops DT_GNU_HASH,
# while llvm-strip --strip-unneeded preserves it and still shrinks a debug
# build's liblogosdelivery.so from ~170MB to ~33MB).
LLVM_STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin/llvm-strip"

ALL_ABIS=(arm64-v8a x86_64 x86 armeabi-v7a)
ABIS=("${@:-${ALL_ABIS[@]}}")

stage_delivery_abi() {
  local abi="$1"
  local nimOutDir="$DELIVERY_BUILD_DIR/$abi"
  local jniOutDir="$JNI_BUILD_DIR/$abi"
  local destDir="$DELIVERY_JNILIBS_DIR/$abi"

  local deliverySo="$nimOutDir/liblogosdelivery.so"
  local rlnSo="$nimOutDir/librln.so"
  local shimSo="$jniOutDir/libdelivery_jni.so"

  if [[ ! -f "$deliverySo" || ! -f "$shimSo" ]]; then
    echo "==> [$abi] skipping logos-delivery staging: missing $deliverySo or $shimSo (build that ABI first)"
    return 0
  fi

  mkdir -p "$destDir"
  cp "$deliverySo" "$destDir/"
  cp "$shimSo" "$destDir/"
  # librln.so: liblogosdelivery.so's own NEEDED entry (confirmed via
  # `readelf -d liblogosdelivery.so | grep NEEDED` -- it links `-lrln`
  # against the RLN dylib the RLN Android cross-build produces, not a
  # bundled/static copy), so it must ship alongside it in the same jniLibs
  # dir for the dynamic linker to resolve it on-device.
  if [[ -f "$rlnSo" ]]; then
    cp "$rlnSo" "$destDir/"
  else
    echo "WARNING: [$abi] $rlnSo not found -- liblogosdelivery.so will fail to dlopen on-device (missing NEEDED librln.so)" >&2
  fi

  if [[ -x "$LLVM_STRIP" ]]; then
    "$LLVM_STRIP" --strip-unneeded "$destDir/liblogosdelivery.so"
    "$LLVM_STRIP" --strip-unneeded "$destDir/libdelivery_jni.so"
  else
    echo "WARNING: [$abi] llvm-strip not found at $LLVM_STRIP -- staged .so's are unstripped (debug symbols, larger APK)" >&2
  fi

  echo "==> [$abi] staged $(ls "$destDir")" | tr '\n' ' '
  echo
}

echo "==> Staging jniLibs for ABIs: ${ABIS[*]}"
for abi in "${ABIS[@]}"; do
  stage_delivery_abi "$abi"
done
