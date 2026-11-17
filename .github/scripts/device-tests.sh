#!/usr/bin/env bash
set -euo pipefail

APK="${1:?Usage: device-tests.sh <apk-path> <out-dir> <cycles>}"
OUT_DIR="${2:-device-out}"
CYCLES="${3:-5}"

mkdir -p "$OUT_DIR/ci-logs" "$OUT_DIR/ci-status"
export CI_LOG_DIR="$OUT_DIR/ci-logs"
export CI_STATUS_DIR="$OUT_DIR/ci-status"

source .github/scripts/ci-step.sh

echo "Waiting for Android emulator to finish booting..."
adb wait-for-device
for _ in $(seq 1 120); do
  BOOT=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  [ "$BOOT" = "1" ] && break
  sleep 2
done
adb shell input keyevent 82 || true
adb shell wm dismiss-keyguard || true
adb logcat -c || true

# Determine package name from the APK
BUILD_TOOLS_DIR=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -n1 || true)
PKG="com.infinity.comicreader"
if [ -n "$BUILD_TOOLS_DIR" ] && [ -x "$BUILD_TOOLS_DIR/aapt" ]; then
  DETECTED=$("$BUILD_TOOLS_DIR/aapt" dump badging "$APK" | grep -oP "package: name='\K[^']+" | head -n1 || true)
  [ -n "$DETECTED" ] && PKG="$DETECTED"
fi
MAIN_ACTIVITY="$PKG/com.infinity.comicreader.ui.ListActivity"
echo "Target package: $PKG | Main activity: $MAIN_ACTIVITY"

# 1. Install Debug APK
ci_step install "Install debug APK ($APK)" adb install -r -g "$APK"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

# 2. Cold Launch
launch_check() {
  adb shell am start -W -n "$MAIN_ACTIVITY"
  sleep 2
  local pid
  pid=$(adb shell pidof "$PKG" | tr -d '\r' || true)
  if [ -z "$pid" ]; then
    echo "ERROR: Process $PKG is not running after cold launch."
    return 1
  fi
  echo "Process $PKG running with PID $pid"
}
ci_step cold-launch "Cold launch ListActivity and verify live process" launch_check

# 3. Lifecycle Cycles (background/foreground, rotation, lock/unlock)
lifecycle_check() {
  adb shell settings put system accelerometer_rotation 0 || true
  for i in $(seq 1 "$CYCLES"); do
    echo "--- Lifecycle cycle $i / $CYCLES ---"
    # Background & foreground
    adb shell input keyevent KEYCODE_HOME
    sleep 1
    adb shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
    sleep 1

    # Rotate landscape -> portrait
    adb shell settings put system user_rotation 1 || true
    sleep 1
    adb shell settings put system user_rotation 0 || true
    sleep 1

    # Lock & unlock
    adb shell input keyevent KEYCODE_SLEEP || true
    sleep 1
    adb shell input keyevent KEYCODE_WAKEUP || true
    adb shell wm dismiss-keyguard || true
    sleep 1

    local pid
    pid=$(adb shell pidof "$PKG" | tr -d '\r' || true)
    if [ -z "$pid" ]; then
      echo "ERROR: Process $PKG died during lifecycle cycle $i"
      return 1
    fi
  done
}
ci_step lifecycle-cycles "Background/foreground, rotation, and lock/unlock ($CYCLES cycles)" lifecycle_check

# 4. Process restart
restart_check() {
  adb shell am force-stop "$PKG"
  sleep 1
  adb shell am start -W -n "$MAIN_ACTIVITY"
  sleep 2
  local pid
  pid=$(adb shell pidof "$PKG" | tr -d '\r' || true)
  if [ -z "$pid" ]; then
    echo "ERROR: Process $PKG failed to restart after force-stop."
    return 1
  fi
  echo "Process restarted cleanly with PID $pid"
}
ci_step process-restart "Force-stop and cold relaunch recovery" restart_check

# 5. Capture final screenshot and logcat
adb exec-out screencap -p > "$OUT_DIR/final-screen.png" 2>/dev/null || true
adb logcat -d > "$OUT_DIR/comicreader-logcat.txt" 2>/dev/null || true

# 6. Crash / ANR inspection
crash_check() {
  grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT_DIR/comicreader-logcat.txt" > "$OUT_DIR/logcat-crash.txt" || true
  if [ -s "$OUT_DIR/logcat-crash.txt" ]; then
    echo "Crash or ANR detected in logcat:"
    cat "$OUT_DIR/logcat-crash.txt"
    return 1
  fi
  echo "Zero crashes or ANRs detected in logcat."
}
ci_step crash-anr-check "Zero FATAL EXCEPTION or ANR entries in logcat" crash_check
