#!/usr/bin/env bash
# Compiles each module's hand-written JNI shim directly with the NDK's
# clang, per docs/adr/0003-jni-shim-per-module.md -- not through Gradle's
# externalNativeBuild/CMake. Invoked by scripts/build-nim-android.sh right
# after the corresponding ABI's Nim library finishes building, so a shim can
# #include a build-generated header (delivery_jni.c needs
# library/generated/logosdelivery.h, which nim-ffi's genBindings() emits at
# build time and which is NOT checked into nim-src/logos-delivery).
#
# Currently covers only logos-delivery / arm64-v8a. logos-storage's
# storage_jni.c and the other three ABIs are deferred to their own
# milestones (storage: Milestone 4, libstorage.h is fully hand-written and
# checked in, so its shim doesn't need this file's typed-header-generation
# step at all; the other three delivery ABIs: not yet verified to build
# cleanly for delivery itself, see build-nim-android.sh's ABI loop).
#
# Requires: ANDROID_NDK_HOME set, nim-src/logos-delivery already built for
# the requested ABI (i.e. `make liblogosdelivery-android-<abi>` has run --
# scripts/build-nim-android.sh does this before calling this script), Nim on
# PATH (needed for the extra `nim c` invocation below, which regenerates the
# typed CBOR header -- see the "Why a second nim c invocation" comment).
#
# Usage:
#   ./scripts/build-jni-shims.sh [abi ...]
#
# With no arguments, attempts all four ABIs (each not-yet-supported ABI is
# skipped with a warning, not a failure). Pass one or more of arm64-v8a,
# x86_64, x86, armeabi-v7a to build a subset.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DELIVERY_DIR="$REPO_ROOT/nim-src/logos-delivery"
JNI_SRC_DIR="$REPO_ROOT/android/logos-delivery/src/jni"
OUT_ROOT="$REPO_ROOT/build/jni"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must be set}"
: "${ANDROID_TARGET:=30}"

case "$(uname -s)" in
  Darwin) NDK_HOST_TAG="darwin-x86_64" ;;
  *)      NDK_HOST_TAG="linux-x86_64" ;;
esac
TOOLCHAIN_DIR="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG"

ALL_ABIS=(arm64-v8a x86_64 x86 armeabi-v7a)
ABIS=("${@:-${ALL_ABIS[@]}}")

# Per-ABI NDK clang target triples, matching nim-src/logos-delivery's own
# Makefile (liblogosdelivery-android-* targets) exactly -- ANDROID_ARCH here
# must equal that Makefile's ANDROID_ARCH for the same ABI, since it is also
# nim-ffi's -d:ffiGenBindings run's --os:android/--cpu selector context.
declare -A DELIVERY_ANDROID_ARCH=(
  [arm64-v8a]=aarch64-linux-android
  [x86_64]=x86_64-linux-android
  [x86]=i686-linux-android
  [armeabi-v7a]=armv7a-linux-androideabi
)
declare -A DELIVERY_CPU=(
  [arm64-v8a]=arm64
  [x86_64]=amd64
  [x86]=i386
  [armeabi-v7a]=arm
)

build_delivery_shim() {
  local abi="$1"
  local androidArch="${DELIVERY_ANDROID_ARCH[$abi]}"
  local cpu="${DELIVERY_CPU[$abi]}"
  local androidCompiler="${androidArch}${ANDROID_TARGET}-clang"
  local ndkClang="$TOOLCHAIN_DIR/bin/$androidCompiler"
  local abiOutDir="$OUT_ROOT/$abi"
  local deliveryOutDir="$DELIVERY_DIR/build/android/$abi"
  local deliverySo="$deliveryOutDir/liblogosdelivery.so"

  if [[ ! -x "$ndkClang" ]]; then
    echo "==> [$abi] skipping logos-delivery shim: no NDK clang at $ndkClang (ABI not supported by this NDK/API level combination)"
    return 0
  fi
  if [[ ! -f "$deliverySo" ]]; then
    echo "==> [$abi] skipping logos-delivery shim: $deliverySo not built yet -- run the matching liblogosdelivery-android-* make target first"
    return 0
  fi

  mkdir -p "$abiOutDir"

  # --- Step 1: (re)generate library/generated/logosdelivery.h -------------
  # Why a second `nim c` invocation: nim-src/logos-delivery's own
  # libLogosDeliveryAndroid nimble task (buildMobileAndroid in
  # logos_delivery.nimble) writes --header (the plain-C exports we don't
  # need to guess symbol names for) but omits the
  # -d:ffiGenBindings/-d:ffiOutputDir/-d:ffiSrcPath flags its *desktop*
  # counterpart (buildLibrary) passes -- so the typed CBOR-encoding helper
  # layer (logosdelivery_ctx_create/_ctx_start_node/...) that
  # delivery_jni.c is written against is never emitted for Android upstream.
  # This appears to be a real gap in delivery's own Android build task
  # versus its desktop one, not something specific to this fork. Rather
  # than patch the pinned submodule (out of scope for this wrapper repo --
  # delivery is not forked, unlike storage, see docs/adr/0002), this script
  # reruns the equivalent `nim c` command with those three extra defines
  # added, from the same source tree and nimcache the main build already
  # populated (nimble.paths, nimbledeps/, the Android section of
  # config.nims), so this is a relink of already-resolved dependencies, not
  # a fresh build. Verified empirically to produce a working
  # library/generated/*.h and successfully re-link liblogosdelivery.so
  # (checked with readelf; see this repo's Milestone 3 commit history).
  echo "==> [$abi] generating library/generated/logosdelivery.h (typed CBOR helper header)"
  mkdir -p "$DELIVERY_DIR/library/generated"
  (
    cd "$DELIVERY_DIR"
    ANDROID_TOOLCHAIN_DIR="$TOOLCHAIN_DIR" \
    ANDROID_ARCH="$androidArch" \
    ANDROID_COMPILER="$androidCompiler" \
    CPU="$cpu" \
    ABIDIR="$abi" \
    nim c \
      --out:"build/android/$abi/liblogosdelivery.so" \
      --threads:on --app:lib --opt:speed --noMain --mm:refc \
      -d:chronicles_sinks=textlines[dynamic] --header -d:chronosEventEngine=epoll \
      -d:discv5_protocol_id=d5waku \
      --passL:-L"build/android/$abi" --passL:-lrln --passL:-llog \
      --cpu:"$cpu" --nimMainPrefix:liblogosdelivery --os:android -d:androidNDK \
      -d:ffiGenBindings -d:targetLang=c -d:ffiOutputDir=library/generated -d:ffiSrcPath=../liblogosdelivery.nim \
      -d:chronicles_log_level=ERROR \
      library/liblogosdelivery.nim
  )

  local generatedHeader="$DELIVERY_DIR/library/generated/logosdelivery.h"
  if [[ ! -f "$generatedHeader" ]]; then
    echo "ERROR: [$abi] $generatedHeader was not produced" >&2
    return 1
  fi

  # --- Step 2: build a static TinyCBOR archive for this ABI ---------------
  # library/README.md: "The helpers call TinyCBOR, so compile against its
  # headers and link it." nim-ffi vendors the exact copy the generated
  # header's inline encoders/decoders are written against, under its own
  # nimbledeps package -- see TinyCbor.mk's `tinycbor` target (host-arch
  # only; this is the cross-compiled equivalent for $abi).
  local ffiPkgDir
  ffiPkgDir="$(ls -dt "$DELIVERY_DIR"/nimbledeps/pkgs2/ffi-* 2>/dev/null | head -1)"
  if [[ -z "$ffiPkgDir" ]]; then
    echo "ERROR: [$abi] no ffi-* package under nimbledeps/pkgs2 -- run 'make build-deps' in nim-src/logos-delivery first" >&2
    return 1
  fi
  local tinycborDir="$ffiPkgDir/ffi/codegen/templates/cpp/vendor/tinycbor"
  if [[ ! -f "$tinycborDir/cbor.h" ]]; then
    echo "ERROR: [$abi] no vendored TinyCBOR at $tinycborDir" >&2
    return 1
  fi

  local tinycborObjDir="$abiOutDir/tinycbor"
  mkdir -p "$tinycborObjDir"
  local tinycborSrcs=(cborencoder.c cborencoder_close_container_checked.c cborparser.c cborparser_dup_string.c cborerrorstrings.c)
  local tinycborObjs=()
  for src in "${tinycborSrcs[@]}"; do
    local obj="$tinycborObjDir/${src%.c}.o"
    "$ndkClang" -c -O2 -std=gnu99 --sysroot="$TOOLCHAIN_DIR/sysroot" \
      -I"$tinycborDir" "$tinycborDir/$src" -o "$obj"
    tinycborObjs+=("$obj")
  done
  local tinycborLib="$abiOutDir/libtinycbor.a"
  rm -f "$tinycborLib"
  "$TOOLCHAIN_DIR/bin/llvm-ar" rcs "$tinycborLib" "${tinycborObjs[@]}"

  # --- Step 3: compile + link delivery_jni.c -------------------------------
  # nim_ffi_prelude.h includes <tinycbor/cbor.h> (path-qualified), so the
  # include dir must be tinycbor's PARENT (vendor/), not vendor/tinycbor/
  # itself -- unlike the tinycbor .c files just compiled above, which live
  # inside that dir and use bare/relative includes.
  local tinycborParentDir
  tinycborParentDir="$(dirname "$tinycborDir")"
  echo "==> [$abi] compiling delivery_jni.c"
  "$ndkClang" -shared -fPIC -O2 \
    --sysroot="$TOOLCHAIN_DIR/sysroot" \
    -I"$DELIVERY_DIR/library" \
    -I"$DELIVERY_DIR/library/generated" \
    -I"$tinycborParentDir" \
    -o "$abiOutDir/libdelivery_jni.so" \
    "$JNI_SRC_DIR/delivery_jni.c" \
    -L"$deliveryOutDir" -llogosdelivery \
    "$tinycborLib" \
    -llog

  echo "==> [$abi] libdelivery_jni.so built at $abiOutDir/libdelivery_jni.so"
}

echo "==> Building JNI shims for ABIs: ${ABIS[*]}"
for abi in "${ABIS[@]}"; do
  build_delivery_shim "$abi"
done
