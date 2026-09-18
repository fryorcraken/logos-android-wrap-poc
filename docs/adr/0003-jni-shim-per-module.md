# 0003 — Hand-written JNI shim per module, compiled outside Gradle

## Status

Accepted.

## Context

`liblogosdelivery.so` and `libstorage.so` export plain C symbols
(`logosdelivery_add_event_listener`, `storage_new`, and so on) — not
`Java_<package>_<Class>_<method>`-mangled JNI exports. Kotlin's `external
fun` mechanism resolves symbols via `System.loadLibrary` + JNI's naming
convention, so it cannot call these Nim-exported functions directly. A
small, hand-written C shim per library (`delivery_jni.c`, `storage_jni.c`)
is required to bridge the two conventions and to bridge the libraries'
background-thread event callbacks into JVM calls (see each library module's
own documentation for the `AttachCurrentThread`/`GlobalRef` mechanics).

Two ways to build and package these shims were considered:

1. Gradle's `externalNativeBuild { cmake { ... } }`, letting AGP invoke
   CMake as part of the normal Gradle build.
2. Compile each shim directly with the NDK's clang in the same shell/CI
   pipeline stage that cross-compiles the Nim libraries, then stage the
   resulting `.so` into `jniLibs/` alongside them.

## Decision

Option 2. Each shim is compiled with
`$ANDROID_TOOLCHAIN_DIR/bin/<target>-clang` directly, immediately after the
corresponding Nim library finishes building for that ABI (so the shim can
`#include` any build-generated header, such as delivery's
`generated/logosdelivery.h`). The resulting `.so` lands in the same per-ABI
output directory that the staging script copies into each module's
`src/main/jniLibs/<abi>/`. From Gradle's point of view, the shim's `.so` is
indistinguishable from the Nim-produced ones — just another file already
sitting in `jniLibs/` by the time Gradle runs.

## Why not Gradle/CMake

The shim links against `.so`s produced by a completely different build
system (`nim`/`nimble`/`make`) at an earlier pipeline stage, not against
anything Gradle itself builds. Wiring `externalNativeBuild`/CMake to depend
on artifacts from a prior, non-Gradle CI job means teaching CMake to locate
prebuilt libraries and headers from an external build — machinery that
duplicates, rather than replaces, the staging script (`scripts/stage-jnilibs.sh`)
this pipeline needs regardless. Keeping Gradle's job to "package what's
already in `jniLibs/`, compile Kotlin, run R8" keeps the two build systems
(Nim/NDK and Gradle) at a clean boundary: one produces `.so` files, the
other packages them.

## Consequences

- Local development requires running `scripts/build-nim-android.sh` (or
  equivalent) before `./gradlew assembleDebug` will produce a working APK —
  Gradle alone cannot build the native side from a fresh checkout.
- The NDK toolchain path/compiler-triple logic already computed by the
  Makefile patches (ADR 0002) is reused directly for the shim's own compiler
  invocation, rather than re-derived in a second place.
- If local-dev ergonomics without the full Nim toolchain becomes a priority
  later (e.g. contributors who only touch Kotlin/JNI code), a CMake-based
  build remains a reasonable follow-up — this decision defers it as
  unnecessary complexity for the current scope, not as permanently rejected.
