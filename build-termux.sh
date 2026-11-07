#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT/android"
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle is missing. Install it with: pkg install gradle"
  exit 1
fi
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME/platforms" ]; then
  echo "Android SDK is not configured. Set ANDROID_HOME to a valid Android SDK directory first."
  echo "This project targets compileSdk 34 and requires Android build-tools/platform 34."
  exit 1
fi
gradle assembleDebug --no-daemon
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
test -f "$APK"
cp "$APK" "$ROOT/Saara-debug.apk"
echo "Build complete: $ROOT/Saara-debug.apk"
