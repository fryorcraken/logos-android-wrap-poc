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

## This is a collection of independent Kotlin libraries, not one SDK dependency

This repo publishes **four separate Kotlin/AAR artifacts** under the group
`com.fryorcraken.logos`:

| Artifact | Depends on | Bundles |
|---|---|---|
| `com.fryorcraken.logos:common` | *(nothing in this repo)* | no native code |
| `com.fryorcraken.logos:delivery` | `common` | `liblogosdelivery.so`, `librln.so`, `libpq.so` |
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
| `logos-delivery` | `:logos-delivery` | `logos-common` | `liblogosdelivery.so`, `librln.so`, `libpq.so` | Logos Messaging node: lifecycle, events, channels |
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
./scripts/build-nim-android.sh   # cross-compiles both Nim libs + JNI shims, all 4 ABIs
./scripts/stage-jnilibs.sh       # copies the resulting .so's into each module's jniLibs/
cd android && ./gradlew assembleDebug
```

## Release process

Publishing a GitHub Release triggers `.github/workflows/release.yml`, which:
builds both Nim libraries from source for all 4 ABIs, compiles the JNI
shims, assembles both demo APKs, verifies `demo-app-delivery`'s APK does not
contain `libstorage.so` (failing the release if it does), reports APK sizes,
and attaches both APKs to the release.

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

## License

Dual-licensed under [MIT](LICENSE-MIT) or [Apache 2.0](LICENSE-APACHE), at
your option — matching both upstream Nim libraries. The
`fryorcraken/logos-storage-nim` fork used as a submodule is itself
dual-licensed the same way.
