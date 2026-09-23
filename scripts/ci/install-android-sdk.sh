#!/usr/bin/env bash
# CI-only helper: installs Android SDK platform 37 + matching build-tools,
# directly via `sdkmanager` from the command-line-tools package, rather than
# through android-actions/setup-android's own platform-selection input.
#
# Why not android-actions/setup-android for this: this project's sibling
# repo (fryorcraken/logos-storage-nim) hit a hard failure in that action's
# `sdkmanager --install ndk;...` bootstrap step ("Warning: Failed to find
# package 'tools'" -- a legacy package no longer present in current Android
# SDK repository metadata; see that repo's android-build.yml commit
# f0bbeb96). The NDK side of that problem is sidestepped entirely here by
# downloading the NDK directly (see ci-nim-android.yml /
# generate-debug-keystore.sh's neighboring steps) -- this script applies the
# same "don't trust the action's sdkmanager bootstrap" lesson to the SDK
# platform/build-tools install, driving `sdkmanager` directly instead.
#
# compileSdk/targetSdk = 37 is pinned in android/gradle/libs.versions.toml
# because the pinned Compose BOM (2026.09.00) requires compileSdk 37+.
#
# Sets ANDROID_HOME / ANDROID_SDK_ROOT and writes android/local.properties
# so Gradle can find the installed SDK.
#
# Usage (from repo root):
#   ./scripts/ci/install-android-sdk.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

SDK_ROOT="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708" # commandlinetools-linux-*_latest at time of writing
# "platforms;android-37" alone is not a real package -- the SDK repository
# only ships versioned sub-releases (37.0, 37.1, 37.2, ...). 37.2 is the
# version verified locally (confirmed present via `sdkmanager --list`);
# compileSdk/targetSdk = 37 in android/gradle/libs.versions.toml is
# satisfied by any 37.x platform package.
PLATFORM="android-37.2"
BUILD_TOOLS="37.0.0"

mkdir -p "$SDK_ROOT/cmdline-tools"

if [[ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "==> Downloading Android command-line tools"
  curl -fsSL -o cmdline-tools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  unzip -q cmdline-tools.zip -d "$SDK_ROOT/cmdline-tools"
  # The zip extracts to cmdline-tools/cmdline-tools/ -- sdkmanager expects
  # cmdline-tools/latest/ (its own relative-path assumptions for locating
  # the SDK root break otherwise).
  mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

echo "==> Installing platform-tools, $PLATFORM, build-tools;$BUILD_TOOLS"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;$PLATFORM" \
  "build-tools;$BUILD_TOOLS"

echo "==> Verifying installed platform"
test -d "$SDK_ROOT/platforms/$PLATFORM" || { echo "::error::$SDK_ROOT/platforms/$PLATFORM was not installed"; exit 1; }
test -d "$SDK_ROOT/build-tools/$BUILD_TOOLS" || { echo "::error::$SDK_ROOT/build-tools/$BUILD_TOOLS was not installed"; exit 1; }

echo "sdk.dir=$SDK_ROOT" > "$REPO_ROOT/android/local.properties"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  echo "ANDROID_HOME=$SDK_ROOT" >> "$GITHUB_ENV"
  echo "ANDROID_SDK_ROOT=$SDK_ROOT" >> "$GITHUB_ENV"
fi

echo "==> Android SDK ready at $SDK_ROOT (platform $PLATFORM, build-tools $BUILD_TOOLS)"
