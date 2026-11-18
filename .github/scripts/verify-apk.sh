#!/usr/bin/env bash
set -euo pipefail

APK="${1:?Usage: verify-apk.sh <path-to-apk>}"

if [ ! -s "$APK" ]; then
  echo "ERROR: APK file '$APK' does not exist or is empty."
  exit 1
fi

echo "Verifying APK: $APK ($(wc -c < "$APK") bytes)"

BUILD_TOOLS_DIR=""
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
  BUILD_TOOLS_DIR=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -n1 || true)
fi

AAPT=""
APKSIGNER=""
if [ -n "$BUILD_TOOLS_DIR" ]; then
  [ -x "$BUILD_TOOLS_DIR/aapt2" ] && AAPT="$BUILD_TOOLS_DIR/aapt2"
  [ -z "$AAPT" ] && [ -x "$BUILD_TOOLS_DIR/aapt" ] && AAPT="$BUILD_TOOLS_DIR/aapt"
  [ -x "$BUILD_TOOLS_DIR/apksigner" ] && APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
fi

if [ -n "$AAPT" ]; then
  BADGING=$("$AAPT" dump badging "$APK" || true)
  echo "$BADGING" | head -n 20 || true
  PKG=$(echo "$BADGING" | grep -oP "package: name='\K[^']+" | head -n1 || true)
  MIN_SDK=$(echo "$BADGING" | grep -oP "sdkVersion:'\K[^']+" | head -n1 || true)
  TARGET_SDK=$(echo "$BADGING" | grep -oP "targetSdkVersion:'\K[^']+" | head -n1 || true)
  echo "Package: $PKG | minSdk: $MIN_SDK | targetSdk: $TARGET_SDK"
  if [ -n "$PKG" ] && [[ "$PKG" != com.infinity.comicreader* ]]; then
    echo "ERROR: Unexpected package name '$PKG' (expected com.infinity.comicreader)"
    exit 1
  fi
else
  echo "WARNING: aapt/aapt2 not found under ANDROID_HOME; skipping badging check."
fi

if [ -n "$APKSIGNER" ]; then
  echo "Checking APK signature with $APKSIGNER..."
  "$APKSIGNER" verify --verbose --print-certs "$APK"
else
  echo "WARNING: apksigner not found under ANDROID_HOME; falling back to jarsigner."
  jarsigner -verify "$APK"
fi

echo "APK verification succeeded for $APK"
