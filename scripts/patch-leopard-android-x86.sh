#!/usr/bin/env bash
# Patches the vendored Leopard-RS CMakeLists.txt (resolved by Nimble as a
# transitive dependency of nim-src/logos-delivery, via status-im/nim-leopard)
# to fix an Android x86/x86_64 NDK cross-compile failure.
#
# Root cause (verified, not guessed -- see
# https://github.com/fryorcraken/leopard's README/commit for the full
# writeup): LeopardCommon.h's `#if defined(ANDROID) || defined(IOS) ->
# #define LEO_TARGET_MOBILE` conflates "is this Android" with "is this ARM".
# On an Android x86/x86_64 NDK target, LEO_TARGET_MOBILE ends up set (the
# build passes -DANDROID) but neither HAVE_ARM_NEON_H nor LEO_USE_SSE2NEON is
# defined, so <emmintrin.h> is never included -- yet LeopardCommon.cpp still
# calls _mm_xor_si128/_mm_loadu_si128/_mm_storeu_si128 unconditionally,
# failing with "use of undeclared identifier '_mm_loadu_si128'". This is
# specific to x86/x86_64: arm64-v8a and armeabi-v7a never hit this codepath
# (NEON, not SSE2), which is why Milestone 3's original arm64-only build
# never surfaced it.
#
# The real, durable fix lives in two forks:
#   - github.com/fryorcraken/leopard (the actual CMakeLists.txt fix)
#   - github.com/fryorcraken/nim-leopard (points its vendor/leopard
#     submodule at the fork above)
# This script does NOT make the build consume those forks via git --
# logos-messaging/logos-delivery is deliberately not forked (see
# docs/adr/0002), so nim-src/logos-delivery's nimble.lock still resolves
# `leopard` from upstream status-im/nim-leopard as normal. Instead, this
# script re-applies the identical, small CMakeLists.txt edit directly to
# whatever nimbledeps/ already resolved, after `make deps` has populated it
# and before the Android build compiles it. The forks above are the
# canonical, reviewable, upstream-able source of the fix; this script is a
# mechanical shortcut that avoids depending on Nimble's dependency-override
# tooling (nimble.lock URL matching / --localdeps), which is fragile to
# script reliably for a one-off CI patch.
#
# Usage:
#   ./scripts/patch-leopard-android-x86.sh
#
# Idempotent: safe to run multiple times (checks for the marker comment
# before patching). No-op (with a warning, not a failure) if nimbledeps/
# hasn't been populated yet or no leopard package is found -- callers that
# only build arm64-v8a/armeabi-v7a may never need this patch to have run.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DELIVERY_DIR="$REPO_ROOT/nim-src/logos-delivery"

# `find` on a nonexistent nimbledeps/pkgs2/ (e.g. a fresh clone/CI runner
# where only the Nim/Nimble toolchain has been bootstrapped so far, before
# `nimble setup --localdeps` has ever resolved any dependency) exits 1 and,
# under `set -euo pipefail`, that nonzero status propagates through the
# `| head -1` pipeline into this command substitution and aborts the whole
# script right here -- before the intended "no leopard-* package found,
# skipping" message below ever gets a chance to run. `|| true` on the `find`
# keeps that a soft, expected case instead of a hard, silent-looking
# failure (verified in CI: this previously took down the calling
# build-nim-android.sh with no error output at all).
leopard_cmake=$(find "$DELIVERY_DIR/nimbledeps/pkgs2" -maxdepth 1 -iname "leopard-*" -type d 2>/dev/null \
  | head -1 || true)

if [[ -z "$leopard_cmake" ]]; then
  echo "==> No leopard-* package found under nimbledeps/pkgs2/ -- skipping (run 'make deps' first if x86/x86_64 Android build is needed)."
  exit 0
fi

cmake_file="$leopard_cmake/vendor/leopard/CMakeLists.txt"

if [[ ! -f "$cmake_file" ]]; then
  echo "==> $cmake_file not found -- skipping (submodule not checked out yet?)."
  exit 0
fi

# Check for the functional fix itself, not a comment string -- a
# differently-worded manual patch (e.g. from earlier interactive debugging)
# would otherwise not be recognized as already-fixed and get a redundant
# second copy of the same target_compile_options block appended.
if grep -q "target_compile_options(libleopard PRIVATE -msse2 -UANDROID)" "$cmake_file"; then
  echo "==> $cmake_file already patched."
  exit 0
fi

echo "==> Patching $cmake_file for Android x86/x86_64 NDK cross-compile"

python3 - "$cmake_file" <<'PYEOF'
import sys

path = sys.argv[1]
with open(path) as f:
    content = f.read()

anchor = "add_library(libleopard STATIC ${LIB_SOURCE_FILES})"
patch = anchor + """

# LOCAL PATCH (scripts/patch-leopard-android-x86.sh in
# github.com/fryorcraken/logos-android-wrap-poc) -- mirrors the fix in
# github.com/fryorcraken/leopard. See that fork's README/commit, or the
# comment block at the top of patch-leopard-android-x86.sh, for the full
# root-cause writeup.
if(CMAKE_CXX_COMPILER MATCHES "i686-linux-android|x86_64-linux-android")
    target_compile_options(libleopard PRIVATE -msse2 -UANDROID)
endif()"""

if anchor not in content:
    print(f"ERROR: anchor line not found in {path} -- CMakeLists.txt structure changed upstream, patch needs updating", file=sys.stderr)
    sys.exit(1)

content = content.replace(anchor, patch, 1)
with open(path, "w") as f:
    f.write(content)
PYEOF

echo "==> Patched."
