#!/usr/bin/env bash
# CI-only helper: actions/download-artifact@v4 flattens each downloaded
# artifact into the target path (it does not restore the uploader's
# working-directory-relative subpaths). ci-nim-android.yml uploads each ABI
# as its own artifact ("delivery-android-<abi>") containing three files at
# their build-tree-relative paths, so after downloading both ABIs into
# /tmp/artifacts/<abi>/ this script reassembles the exact directory layout
# scripts/stage-jnilibs.sh expects to read from:
#
#   nim-src/logos-delivery/build/android/<abi>/liblogosdelivery.so
#   nim-src/logos-delivery/build/android/<abi>/librln.so
#   build/jni/<abi>/libdelivery_jni.so
#
# Usage (from repo root, after downloading each ABI's artifact into
# /tmp/artifacts/<abi>/):
#   ./scripts/ci/reassemble-nim-artifacts.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ARTIFACTS_ROOT="/tmp/artifacts"
ABIS=(arm64-v8a x86_64)

for abi in "${ABIS[@]}"; do
  src_dir="$ARTIFACTS_ROOT/$abi"
  if [[ ! -d "$src_dir" ]]; then
    echo "::error::Expected downloaded artifact directory $src_dir not found" >&2
    exit 1
  fi

  nim_out_dir="$REPO_ROOT/nim-src/logos-delivery/build/android/$abi"
  jni_out_dir="$REPO_ROOT/build/jni/$abi"
  mkdir -p "$nim_out_dir" "$jni_out_dir"

  # upload-artifact preserves the uploader's relative path structure inside
  # a single artifact, so the downloaded tree under $src_dir mirrors
  # ci-nim-android.yml's `path:` list verbatim.
  find "$src_dir" -name 'liblogosdelivery.so' -exec cp {} "$nim_out_dir/" \;
  find "$src_dir" -name 'librln.so' -exec cp {} "$nim_out_dir/" \;
  find "$src_dir" -name 'libdelivery_jni.so' -exec cp {} "$jni_out_dir/" \;

  for f in "$nim_out_dir/liblogosdelivery.so" "$nim_out_dir/librln.so" "$jni_out_dir/libdelivery_jni.so"; do
    test -f "$f" || { echo "::error::Failed to reassemble $f from $src_dir" >&2; exit 1; }
  done

  echo "==> [$abi] reassembled: $nim_out_dir/{liblogosdelivery.so,librln.so}, $jni_out_dir/libdelivery_jni.so"
done
