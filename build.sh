#!/usr/bin/env bash
# Build the ConstructTV APK from the command line using Android Studio's bundled JDK.
#   ./build.sh            → debug build
#   ./build.sh release    → signed release build (needs keystore.properties; see README)
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"

if [ ! -f gradlew ]; then
  echo "No Gradle wrapper yet — open this folder in Android Studio once (it generates it),"
  echo "or run:  gradle wrapper --gradle-version 8.7"
  exit 1
fi

VARIANT="${1:-debug}"; shift || true

if [ "$VARIANT" = "release" ]; then
  if [ ! -f keystore.properties ]; then
    echo "release needs keystore.properties (gitignored). Generate a keystore with keytool"
    echo "and write storeFile/storePassword/keyAlias/keyPassword into it. See README."
    exit 1
  fi
  ./gradlew assembleRelease "$@"
  APK=app/build/outputs/apk/release/app-release.apk
else
  ./gradlew assembleDebug "$@"
  APK=app/build/outputs/apk/debug/app-debug.apk
fi

echo
echo "APK → $APK"
echo "Install to a connected TV:  adb install -r $APK"
