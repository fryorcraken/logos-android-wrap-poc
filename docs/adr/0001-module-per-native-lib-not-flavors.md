# 0001 — One Gradle module per native library, not product flavors

## Status

Accepted.

## Context

This repo wraps two Nim libraries (delivery, storage) as Android/Kotlin
libraries today, with four more (lez-node, lez-wallet, l1-node, l1-wallet)
expected to follow the same pattern later — six native libraries in total at
the target architecture.

Android Gradle Plugin offers product flavors as a built-in mechanism for
producing build variants that include/exclude code and resources. A
flavor-per-library-combination scheme was considered and rejected.

## Decision

Each native library gets its own Gradle module (`logos-delivery`,
`logos-storage`, and so on), each producing its own AAR artifact. Consuming
apps declare `implementation(project(":logos-delivery"))` for exactly the
libraries they need. There are no product flavors dividing "which native
libraries are included" — that decision is made entirely by which modules a
consumer depends on.

## Why not flavors

Flavors model *which combination of libraries an app includes* as a build
dimension. With one boolean flavor dimension per library, six libraries
means 2⁶ = 64 possible combinations. Even if only a handful of those
combinations are ever built as real variants, the *flavor definitions*
themselves — flavor dimensions, per-flavor `jniLibs` source sets, per-flavor
dependency declarations — grow combinatorially as libraries are added. Every
new library requires touching every consuming module's flavor
configuration, not just adding one new dependency.

Per-module AARs instead let dependency resolution do the combining: adding a
seventh library later means publishing a new module, not redesigning a
flavor matrix. See the root README's "Why not one library with build-time
exclusion" section for the closely related reasoning about why native `.so`
inclusion must be driven by the dependency graph, not by any build-time
flag (flavor-based `jniLibs` selection or `packaging.jniLibs.excludes`
alike).

## Consequences

- Adding a new native library is: fork/patch it for Android if needed, add
  one new Gradle module, done. No changes to existing modules' build
  scripts.
- A consumer's dependency list *is* its bill of native libraries — auditable
  by reading `build.gradle.kts`, not by decoding which flavor was selected.
- Cross-library functionality needs its own answer, since no single library
  module may depend on a sibling — see the `logos-glue` module and the
  README's module-map table.
