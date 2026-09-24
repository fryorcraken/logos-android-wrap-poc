# Logos Android SDK (proof of concept)

Kotlin bindings for [Logos Messaging](https://github.com/logos-messaging/logos-delivery)
(delivery) and [Logos Storage](https://github.com/logos-storage/logos-storage-nim)
(storage), built from source on every GitHub Release.

## What this is — and isn't

This is a proof of concept for wrapping Logos's Nim-based peer-to-peer
libraries as Android libraries. It currently covers two of an eventual **six**
native libraries: **delivery** and **storage** now, with **lez-node,
lez-wallet, l1-node, and l1-wallet** expected to follow the same pattern
later. It is not a production SDK yet.

The point of this repo is to prove, end to end, that:

1. Logos's plain-C-ABI Nim libraries can be cross-compiled for Android
   (`arm64-v8a`, `x86_64`, `x86`, `armeabi-v7a`) and wrapped in idiomatic
   Kotlin via a small hand-written JNI shim.
2. An app that only needs one of these libraries can depend on it alone —
   and provably ship without the other library's native code.
3. Multi-library functionality (reading combined status across delivery and
   storage) can be layered on top without breaking guarantee #2 for apps
   that don't need it.

## Current status

- **`logos-delivery` is fully wired and verified end to end**: real JNI
  shim, real cross-compiled `.so`'s, and `demo-app-delivery` has been run on
  a real Android emulator showing live peer connections against the real
  `logos.dev` network. `release.yml` builds and publishes its APK from
  scratch on every GitHub Release — proven by the
  [`v0.1.0-poc`](https://github.com/fryorcraken/logos-android-wrap-poc/releases/tag/v0.1.0-poc)
  release, cut entirely by this pipeline with no manually-staged artifacts.
- **`logos-storage`'s JNI shim does not exist yet** — Milestone 4 (writing
  `storage_jni.c`) has not started. `logos-glue` and `demo-app-full` are
  still Milestone-2-era stubs and do not build a working, runnable app.
  Neither is built or published by `release.yml` currently — see "Release
  process" below.
- **Only `arm64-v8a` and `x86_64` are supported right now**, for both
  libraries — not the full four-ABI matrix this doc otherwise describes as
  the eventual target. `x86`/`armeabi-v7a` have never been attempted for
  delivery. For storage, 32-bit ABIs are blocked by a real upstream bug in
  storage's own source (see the module map below and
  `docs/adr/0002-fork-storage-nim-for-android.md`) — modern Android devices
  are effectively all 64-bit regardless, so this is not considered
  blocking for the POC.

## This is a collection of independent Kotlin libraries, not one SDK dependency

This repo publishes **four separate Kotlin/AAR artifacts** under the group
`com.fryorcraken.logos`:

| Artifact | Depends on | Bundles |
|---|---|---|
| `com.fryorcraken.logos:common` | *(nothing in this repo)* | no native code |
| `com.fryorcraken.logos:delivery` | `common` | `liblogosdelivery.so`, `librln.so` |
| `com.fryorcraken.logos:storage` | `common` | `libstorage.so` |
| `com.fryorcraken.logos:glue` | `delivery`, `storage` | no native code of its own |

There is **no single fat "SDK" dependency** that pulls in everything. A
developer explicitly chooses what they need:

```kotlin
// Messaging-only app:
implementation("com.fryorcraken.logos:delivery:x.y.z")

// App needing both, plus cross-library status:
implementation("com.fryorcraken.logos:delivery:x.y.z")
implementation("com.fryorcraken.logos:storage:x.y.z")
implementation("com.fryorcraken.logos:glue:x.y.z")
```

"Logos Android SDK" is a **documentation-level umbrella name only** — a way
to talk about this repo and its artifacts together. It is never a Gradle
coordinate you depend on directly, and it never will be. As lez-node,
lez-wallet, l1-node, and l1-wallet are added, they follow this exact
one-module-per-native-library shape. **This pattern must not be collapsed
into fewer, fatter modules** — see below for why.

## Why not one library with build-time exclusion of unused native code?

It's tempting to ship a single `logos-sdk` module containing every native
library, and let consumers exclude what they don't need via Android Gradle
Plugin's packaging options:

```kotlin
android {
    packaging {
        jniLibs {
            excludes += ["lib/*/libstorage.so"]
        }
    }
}
```

**This repo deliberately does not use that pattern, and it must not be
reintroduced.** Reasons:

1. **R8/AGP do not tree-shake native `.so` files based on Kotlin/Java code
   reachability.** R8's shrinking operates on JVM bytecode only. Whether or
   not any Kotlin code path actually calls into `libstorage.so`, if a module
   containing it is a resolved Gradle dependency, its `.so` is merged into
   `jniLibs/` and packaged into the APK. Native library inclusion is driven
   by **dependency resolution**, not reachability analysis — a completely
   different axis from, and independent of, R8 code shrinking.

2. **`packaging { jniLibs { excludes } }` is a trap, not a reference
   pattern.** It's a fragile, string-pattern file exclusion bolted onto a
   dependency graph that still resolved and compiled against the excluded
   library's Kotlin API underneath. Nothing keeps the exclude list in sync
   with reality — add a new native lib upstream, forget to update the
   pattern, and it silently ships. Worse, it changes the failure mode from a
   **compile-time** error ("you don't depend on `:storage`, this symbol
   doesn't exist") to a **runtime** `UnsatisfiedLinkError` the instant a code
   path that assumed the excluded `.so` was present actually executes — R8
   has no idea the exclusion happened, because from its point of view the
   code calling into that library is perfectly reachable. You only told the
   *packager* to drop a file after the fact.

3. **The only structurally sound way to guarantee `libstorage.so` never
   reaches a delivery-only APK is to never resolve `logos-storage` (or
   `logos-glue`) as a dependency of that app in the first place.**
   "Tree-shaking" here is not a build flag — it is the **Gradle dependency
   graph itself**. This is exactly why this repo is a *collection of
   independent libraries* — `logos-common`, `logos-delivery`,
   `logos-storage`, each its own module and artifact — instead of one module
   with flavors or excludes, and exactly why `logos-common` stays
   dependency-free while `logos-glue` (which depends on both native
   libraries) is only pulled in by consumers who explicitly want
   multi-library functionality.

CI enforces this directly: it asserts `libstorage.so` is **structurally
absent** from the delivery-only demo app's APK — not merely excluded after
the fact. See `scripts/verify-no-storage-in-delivery-apk.sh` and the
`release.yml` workflow.

## Module map

| Module | Gradle path | Depends on | Bundles | Purpose |
|---|---|---|---|---|
| `logos-common` | `:logos-common` | — | — | Shared opaque-ctx + async-callback plumbing both native libraries use |
| `logos-delivery` | `:logos-delivery` | `logos-common` | `liblogosdelivery.so`, `librln.so` | Logos Messaging node: lifecycle, events, channels |
| `logos-storage` | `:logos-storage` | `logos-common` | `libstorage.so` | Logos Storage node: lifecycle, peers, upload/download |
| `logos-glue` | `:logos-glue` | `logos-delivery`, `logos-storage` | — | **Template** for cross-library glue code (e.g. `getCombinedStatus()`); the pattern future lez/l1 glue modules should follow |
| `demo-app-delivery` | `:demo-app-delivery` | `logos-delivery` only | — | Starts a delivery node, shows live peer count |
| `demo-app-full` | `:demo-app-full` | `logos-delivery`, `logos-storage`, `logos-glue` | — | Starts both nodes, shows combined status |

## Demo apps

Both demo apps do exactly one thing: start the relevant node(s) and show a
live peer-connection count. They intentionally do **not** demo messaging
send/receive or upload/download — that's out of scope for this proof of
concept. `demo-app-full` additionally calls `logos-glue`'s
`getCombinedStatus()` to visibly exercise the cross-library glue pattern.

## Building locally

Prerequisites:

- Nim + nimble (each submodule pins its own version — see each submodule's
  `Makefile`/`.nimble` file)
- Android NDK + SDK, with `ANDROID_NDK_HOME` set

```bash
git submodule update --init --recursive
./scripts/build-nim-android.sh arm64-v8a x86_64   # cross-compiles delivery + JNI shim for the verified ABIs
./scripts/stage-jnilibs.sh arm64-v8a x86_64       # copies the resulting .so's into logos-delivery's jniLibs/
cd android && ./gradlew :demo-app-delivery:assembleDebug
```

(`build-nim-android.sh`/`stage-jnilibs.sh` accept `x86`/`armeabi-v7a` too,
and will attempt `logos-storage-nim`'s matching Makefile targets for any
ABI passed — but see "Current status" above for why only `arm64-v8a`/
`x86_64` are actually verified working today, and why `demo-app-full` does
not build regardless of which ABIs are staged.)

## Release process

Publishing a GitHub Release triggers `.github/workflows/release.yml`, which
today is **delivery-only** (storage's JNI shim doesn't exist yet — see
"Current status" above). It:

1. Calls the reusable `.github/workflows/ci-nim-android.yml` workflow to
   cross-compile `liblogosdelivery.so`, `librln.so`, and the compiled
   `delivery_jni.c` shim from source, for `arm64-v8a` and `x86_64` (a
   matrix job, one per ABI). This includes bootstrapping the pinned Nim
   2.2.6 toolchain and Nimble dependencies (via `nim-src/logos-delivery`'s
   own `make deps`/`make build-deps`), downloading Android NDK r27c
   directly, and cross-compiling `librln.so` via `cross`/Docker.
2. Stages the resulting `.so`'s into
   `android/logos-delivery/src/main/jniLibs/<abi>/` via
   `scripts/stage-jnilibs.sh`, then builds `demo-app-delivery`'s release
   APK with `./gradlew :demo-app-delivery:assembleRelease`. The release
   build type is signed with a debug keystore (POC-appropriate only — see
   the signing config comment in `demo-app-delivery/build.gradle.kts`), not
   a dedicated production keystore.
3. Verifies the built APK structurally excludes any storage-related native
   library via `scripts/verify-no-storage-in-delivery-apk.sh`, failing the
   release if one is found. This is the single most important CI check in
   this repo — see "Why not one library with build-time exclusion" above.
4. Reports per-ABI native library sizes via `scripts/report-apk-sizes.sh`
   to the workflow run's job summary.
5. Attaches `demo-app-delivery-release.apk` to the GitHub Release that
   triggered the run.

`demo-app-full` (which would need both `logos-delivery` and
`logos-storage`) is not built or published by this pipeline — it can't be:
`logos-glue`/`demo-app-full` are still Milestone-2-era stubs, and
`logos-storage` has no JNI shim yet. Wiring storage into this pipeline is
future Milestone 4/5 work.

`release.yml` also accepts `workflow_dispatch`, so the pipeline can be
iterated on and re-run without creating/deleting real GitHub Releases.

## Nim source

The two native libraries are tracked as git submodules under `nim-src/`:

- `nim-src/logos-delivery` → upstream
  [`logos-messaging/logos-delivery`](https://github.com/logos-messaging/logos-delivery),
  which already has full Android cross-compilation support.
- `nim-src/logos-storage-nim` → [`fryorcraken/logos-storage-nim`](https://github.com/fryorcraken/logos-storage-nim),
  a fork of [`logos-storage/logos-storage-nim`](https://github.com/logos-storage/logos-storage-nim)
  that adds Android cross-compilation support (`make libstorage-android`)
  not yet available upstream. Everything else in the fork is unmodified
  upstream code.

Two more forks exist as **reference/documentation only** — neither is a
submodule of this repo, and CI does not clone them directly:

- [`fryorcraken/leopard`](https://github.com/fryorcraken/leopard) and
  [`fryorcraken/nim-leopard`](https://github.com/fryorcraken/nim-leopard)
  document, in a real reviewable diff, the fix for an Android
  `x86`/`x86_64` NDK cross-compile failure in the vendored Leopard-RS
  Reed-Solomon library that `logos-delivery` pulls in transitively (via
  `status-im/nim-leopard` → `status-im/leopard`, resolved into
  `nim-src/logos-delivery/nimbledeps/`). Since `logos-delivery` itself is
  deliberately not forked (see `docs/adr/0002`), this repo's actual build
  does not consume those forks via a git dependency swap — instead,
  `scripts/patch-leopard-android-x86.sh` mechanically re-applies the
  identical, small `CMakeLists.txt` edit directly to whatever
  `nimbledeps/` already resolved, immediately before the x86_64 build step
  (`scripts/build-nim-android.sh` calls it automatically). See that
  script's own top-of-file comment for the full root-cause writeup and
  rationale for a scripted patch over a dependency-level fork.

## License

Dual-licensed under [MIT](LICENSE-MIT) or [Apache 2.0](LICENSE-APACHE), at
your option — matching both upstream Nim libraries. The
`fryorcraken/logos-storage-nim` fork used as a submodule is itself
dual-licensed the same way.
