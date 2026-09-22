#!/usr/bin/env bash
# Asserts that demo-app-delivery's release APK contains NO storage-related
# native library. This is the core structural guarantee this whole POC
# exists to prove: an app that depends only on `logos-delivery` (never
# `logos-storage` or `logos-glue`) must not ship storage's native code, full
# stop -- not "excluded via packaging{} rules", but genuinely absent because
# `:logos-storage` was never a resolved Gradle dependency of demo-app-delivery
# in the first place (see the root README's "Why not one library with
# build-time exclusion of unused native code?" section, and
# android/demo-app-delivery/build.gradle.kts's dependencies block).
#
# TODO(Milestone 4/5): once logos-storage's JNI shim exists and
# demo-app-full actually builds, add a positive-case companion check here
# (or a sibling script) that unzips demo-app-full's APK and asserts
# *storage*.so IS present under lib/**, so this script's negative assertion
# has a positive control to cross-check against. Right now there is no
# working demo-app-full APK to build that check against, so this limitation
# is only noted, not silently worked around.
#
# Usage:
#   ./scripts/verify-no-storage-in-delivery-apk.sh <path-to-apk>
#
# Exits non-zero (failing the calling CI job) if any lib/**/*storage*.so
# entry is found in the APK.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <path-to-apk>" >&2
  exit 2
fi

apk_path="$1"

if [[ ! -f "$apk_path" ]]; then
  echo "::error::APK not found at $apk_path" >&2
  exit 1
fi

echo "==> Inspecting native libraries in $apk_path"
listing="$(unzip -l "$apk_path" | awk '{print $4}')"

echo "==> lib/** entries:"
echo "$listing" | grep '^lib/' || echo "(none -- unexpected, no native libs at all)"

matches="$(echo "$listing" | grep -Ei '^lib/[^/]+/.*storage.*\.so$' || true)"

if [[ -n "$matches" ]]; then
  echo "::error::Found storage-related native librar(y/ies) in a delivery-only APK:" >&2
  echo "$matches" >&2
  echo "" >&2
  echo "This APK depends only on :logos-delivery and must never bundle storage's" >&2
  echo "native code. See the root README's 'Why not one library with build-time" >&2
  echo "exclusion of unused native code?' section." >&2
  exit 1
fi

echo "==> OK: no storage-related .so found under lib/** in $apk_path"
