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

## Addendum (Milestone 3): delivery's generated header has two layers, and
## its Android build task only emits one

Building `nim-src/logos-delivery`'s `liblogosdelivery.so` for Android
(`libLogosDeliveryAndroid` in `logos_delivery.nimble`) produces a header via
`--header`, but that flag alone only emits the *raw* CBOR-ABI exports
(`logosdelivery_create_node(reqCbor, reqCborLen, callback, userData)` and
friends — every argument crosses as an opaque CBOR-encoded buffer). The
*typed* helper layer documented in `library/README.md` and used by every
reference shim this milestone checked (`logosdelivery_ctx_create(const char
*configJson, ...)`, `_ctx_start_node`, `_ctx_subscribe`, ...) is a second,
separate codegen pass, gated behind three extra defines
(`-d:ffiGenBindings -d:targetLang=c -d:ffiOutputDir=library/generated
-d:ffiSrcPath=...`) that delivery's *desktop* `buildLibrary` nimble task
passes but its *Android* `buildMobileAndroid` task does not. This looks like
a gap in the upstream Android task, not something specific to this fork's
build.

`delivery_jni.c` is written against the typed layer (plain C strings in/out,
no hand-rolled CBOR encoding in the shim). `scripts/build-jni-shims.sh`
therefore reruns the equivalent `nim c` invocation with those three defines
added, immediately before compiling `delivery_jni.c`, rather than patching
the pinned submodule (out of scope — delivery is not forked, see ADR 0002).
This second `nim c` run reuses the same source tree, `nimbledeps/`, and
`nimble.paths` the main per-ABI build already populated, so it is a relink
of already-resolved dependencies (confirmed by wall-clock time: comparable
to a warm rebuild, not a fresh one) rather than a second full build.

Two knock-on consequences for the shim's own build:

- The typed layer's inline `static inline` encoders/decoders `#include
  <tinycbor/cbor.h>`, so `delivery_jni.c` — despite not calling any CBOR
  function directly itself — must compile against nim-ffi's vendored
  TinyCBOR headers (`nimbledeps/pkgs2/ffi-*/ffi/codegen/templates/cpp/vendor/tinycbor/`)
  and link a small static archive built from five of its `.c` files
  (`cborencoder.c`, `cborencoder_close_container_checked.c`, `cborparser.c`,
  `cborparser_dup_string.c`, `cborerrorstrings.c` — the same set
  `TinyCbor.mk`'s host-arch `tinycbor` target builds, just cross-compiled
  for the target ABI here). The resulting `libdelivery_jni.so` has no
  runtime TinyCBOR dependency — it's a build-time-only static link.
- Delivery's generated typed API is **fully asynchronous**: every
  `logosdelivery_ctx_*` call submits a request and returns immediately, with
  the terminal result delivered later via callback from nim-ffi's own
  dispatch thread. `delivery_jni.c` blocks the calling JVM thread on a
  `pthread_cond_t` inside each lifecycle trampoline so `DeliveryNative`'s
  `external fun`s present the same synchronous-return-plus-callback shape
  `StorageNative`/`StorageNode` already use for storage's own (also fully
  async) C API — this was a real, corrected assumption from Milestone 2's
  stub, not a pre-existing design decision; see `DeliveryNode.kt`'s and
  `NodeLifecycle.kt`'s doc comments.

**For Milestone 4 (storage):** `libstorage.h` is fully hand-written and
checked in (no generated-header step, no CBOR layer), so `storage_jni.c`
skips this whole addendum's first two points — but its own C API is already
known to be async (`StorageNode.kt`'s stub already documents this), so the
pthread-condvar blocking pattern in `delivery_jni.c`'s
`nativeCreate`/`nativeStart`/`nativeStop` is directly reusable there with
`storage_new`/`storage_start`/`storage_stop`'s reply shapes substituted in.
