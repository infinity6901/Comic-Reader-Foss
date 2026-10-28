#!/usr/bin/env bash
# Creates the stable signing key used by CI. Keep the resulting file and passwords safe.
set -euo pipefail
keytool -genkeypair -v -keystore comicreader-release.jks -alias comicreader \
  -keyalg RSA -keysize 4096 -validity 36500
echo
echo "Base64 for the KEYSTORE_BASE64 secret:"
base64 -w0 comicreader-release.jks 2>/dev/null || base64 comicreader-release.jks | tr -d '\n'
echo
