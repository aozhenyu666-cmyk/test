#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/outputs/androidTest-results/device
collect_evidence() {
  adb logcat -d -v threadtime > app/build/outputs/androidTest-results/device/logcat.txt || true
  adb pull /sdcard/Android/data/com.yishou.app/files/integration-evidence app/build/outputs/androidTest-results/device/ || true
}
trap collect_evidence EXIT
adb install -r app/build/outputs/apk/mainline/debug/app-mainline-debug.apk
adb install -r gate-fixture/build/outputs/apk/debug/gate-fixture-debug.apk
adb shell input keyevent 82
./gradlew --no-daemon connectedMainlineDebugAndroidTest --stacktrace
