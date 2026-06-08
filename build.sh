#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/build-tools/36.0.0:$PATH"

echo "==> 1/4 Ensure Gradle wrapper (gradle-8.10.2)"
if [ ! -f gradlew ]; then
  # --no-validate-url: skip the distribution-URL connectivity probe, which can
  # fail behind CDNs/proxies even when the distribution is reachable/cached.
  gradle wrapper --gradle-version 8.10.2 --distribution-type bin --no-validate-url --no-daemon
fi

echo "==> 2/4 Build debug APK"
./gradlew :app:assembleDebug --no-daemon

echo "==> 3/4 Copy APK to project root"
cp app/build/outputs/apk/debug/app-debug.apk ./WorkProfileScheduler.apk
echo "    APK: $(ls -lh WorkProfileScheduler.apk | awk '{print $5}')"

echo "==> 4/4 Verify signature + badging"
apksigner verify --print-certs WorkProfileScheduler.apk | head -4
aapt2 dump badging WorkProfileScheduler.apk | grep -E "package:|targetSdkVersion|uses-permission" || true

cat <<EOF

============================================================
 Build complete: $(pwd)/WorkProfileScheduler.apk

 Install + enable silent mode (one time):
   adb install -r WorkProfileScheduler.apk
   adb shell pm grant com.worksched android.permission.MODIFY_QUIET_MODE
============================================================
EOF
