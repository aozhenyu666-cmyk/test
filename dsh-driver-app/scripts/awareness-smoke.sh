#!/usr/bin/env bash
set -euo pipefail
mkdir -p awareness-smoke
trap 'adb logcat -d -s AndroidRuntime > awareness-smoke/logcat.txt || true' EXIT
adb install -r apk/app/*.apk
adb install -r apk/test/*.apk
adb shell pm grant com.dshdriver.app android.permission.POST_NOTIFICATIONS
# Instrumentation restarts the target process. Bind accessibility after the runner is ready.
adb shell am instrument -w com.dshdriver.app.test/com.dshdriver.app.AwarenessInstrumentation | tee awareness-smoke/result.txt &
runner=$!
for attempt in $(seq 1 25); do
  if grep -qE 'AWARENESS_READY_FOR_ACCESSIBILITY|AWARENESS_SMOKE=FAIL' awareness-smoke/result.txt; then break; fi
  sleep 1
done
if ! grep -q AWARENESS_READY_FOR_ACCESSIBILITY awareness-smoke/result.txt; then wait "$runner"; exit 1; fi
current=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
component=com.dshdriver.app/com.dshdriver.app.WatchService
others=$(python3 -c 'import sys; print(":".join(x for x in sys.argv[1].split(":") if x and x!="null" and x!=sys.argv[2]))' "$current" "$component")
if [ -z "$others" ]; then
  adb shell settings delete secure enabled_accessibility_services
else
  adb shell settings put secure enabled_accessibility_services "$others"
fi
if [ -z "$others" ]; then restored=$component; else restored=$others:$component; fi
adb shell settings put secure enabled_accessibility_services "$restored"
adb shell settings put secure accessibility_enabled 1
adb shell run-as com.dshdriver.app touch cache/awareness-a11y-ready
wait "$runner"
adb exec-out screencap -p > awareness-smoke/screen.png
adb logcat -d -s AndroidRuntime > awareness-smoke/logcat.txt
grep -q 'AWARENESS_SMOKE=PASS' awareness-smoke/result.txt
