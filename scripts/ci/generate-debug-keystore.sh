#!/usr/bin/env bash
# CI-only helper: generates a throwaway Android debug keystore at
# ~/.android/debug.keystore if one doesn't already exist, using the exact
# well-known alias/passwords AGP itself uses for its own auto-generated
# debug keystore (so this is indistinguishable, to Gradle, from the one a
# local `./gradlew assembleDebug` would create on first run).
#
# demo-app-delivery/build.gradle.kts wires buildTypes.release.signingConfig
# to signingConfigs.getByName("debug") pointed at this exact path -- see the
# code comment there for why reusing the debug keystore for release signing
# is POC-appropriate here and explicitly not a production signing setup (no
# GitHub Secrets / dedicated release keystore is provisioned).
#
# Usage:
#   ./scripts/ci/generate-debug-keystore.sh

set -euo pipefail

KEYSTORE_DIR="$HOME/.android"
KEYSTORE_PATH="$KEYSTORE_DIR/debug.keystore"

if [[ -f "$KEYSTORE_PATH" ]]; then
  echo "==> Debug keystore already exists at $KEYSTORE_PATH"
  exit 0
fi

mkdir -p "$KEYSTORE_DIR"

echo "==> Generating throwaway debug keystore at $KEYSTORE_PATH"
keytool -genkeypair \
  -keystore "$KEYSTORE_PATH" \
  -storepass android \
  -keypass android \
  -alias androiddebugkey \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"

echo "==> Debug keystore generated"
