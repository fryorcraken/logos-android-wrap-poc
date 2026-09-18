# 0002 — Fork logos-storage-nim to add Android support

## Status

Accepted.

## Context

This repo needs Android-cross-compiled `libstorage.so` for four ABIs
(`arm64-v8a`, `x86_64`, `x86`, `armeabi-v7a`). The upstream
`logos-storage/logos-storage-nim` repo has no Android build support: its
`build.nims` only defines host-target `libstorageDynamic`/`libstorageStatic`
tasks, and its `Makefile` has no `android` targets at all.

By contrast, the sibling repo `logos-messaging/logos-delivery` already ships
full Android cross-compilation support (`make liblogosdelivery-android`,
covering all four ABIs), which this repo's `logos-delivery` module depends
on directly with no fork needed.

Two options were considered for closing the gap on the storage side:

1. Apply the missing build-task patch to a submodule checkout at CI time
   (no separate fork; the patch lives only in this repo as a `.patch` file).
2. Fork `logos-storage/logos-storage-nim` to `fryorcraken/logos-storage-nim`
   and commit the Android support there directly.

## Decision

Fork the repo. `fryorcraken/logos-storage-nim` is a public, dual
MIT/Apache-2.0-licensed fork that adds:

- A `libstorageAndroid` nimble task in `build.nims`
  (`buildLibraryAndroid` proc), cross-compiling `libstorage.so` for a single
  ABI selected via `CPU`/`ABIDIR` environment variables.
- `libstorage-android{,-precheck,-arm64,-amd64,-x86,-arm}` targets in the
  `Makefile`, structurally mirroring `logos-delivery`'s
  `liblogosdelivery-android-*` targets (same ABI → CPU/compiler-triple
  mapping), minus the `librln`/`libpq` linkage storage doesn't need.
- Its own `android-build.yml` GitHub Actions workflow, proving the new
  `make libstorage-android` target produces a valid `.so` for every ABI on
  a clean checkout — independent of, and before, this repo's own CI depends
  on the fork as a submodule.

Everything else in the fork is unmodified upstream code.

## Why a fork, not a CI-time patch

A real, versioned fork:

- Is buildable and testable on its own, with its own CI, independent of this
  repo — matching how `logos-delivery`'s existing Android support works
  (build it directly, no patch step).
- Gives the patch a stable, referenceable commit to pin the git submodule
  to, rather than "this repo's current patch file plus whatever upstream
  commit it happens to still apply cleanly to."
- Is the natural staging ground for eventually upstreaming the change: the
  patch is small and purely additive (new tasks/targets, nothing removed or
  changed), so a future PR to `logos-storage/logos-storage-nim` is just
  "here's a fork that already proves this works," not a from-scratch
  proposal.

A CI-time patch avoids maintaining a second public repo, but couples every
downstream build to "does this patch still apply," which is a worse failure
mode than "did this pinned commit build," and gives up the fork's
independent CI as a standalone correctness signal.

## Consequences

- This repo's `nim-src/logos-storage-nim` submodule points at
  `fryorcraken/logos-storage-nim`, not `logos-storage/logos-storage-nim`
  directly — noted explicitly in the root README so this isn't mistaken for
  an oversight.
- Bumping the storage library's version means updating the fork (rebase or
  merge from upstream, keeping the Android patch) and then updating the
  submodule pin here — one extra step versus a direct upstream submodule,
  accepted as the cost of this approach.
- Upstreaming the patch is a natural follow-up (tracked as an issue on the
  fork), but is explicitly not required for this repo to function.
