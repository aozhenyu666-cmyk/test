#!/usr/bin/env bash
# 模拟器冒烟测试：装 APK、开无障碍、预置拦截名单，验证严管时打开拦截应用会被弹回、放行时不会。
# 用法：smoke.sh <apk> <截图目录>
set -u
APK="$1"
OUT="$2"
PKG=com.zongkong.app
# 被拦截的“娱乐应用”：用系统设置代替（模拟器上一定有）
TARGET=com.android.settings
mkdir -p "$OUT"

shot() { sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; }
route() { adb shell am start -n $PKG/.ui.MainActivity --es route "$1" >/dev/null; sleep 3; }
resumed() { adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -3; }
launch_target() {
  adb shell input keyevent KEYCODE_HOME; sleep 2
  adb shell am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 5
}

adb install -r "$APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.guard.GuardService
adb shell settings put secure accessibility_enabled 1
adb shell am start -n $PKG/.ui.MainActivity >/dev/null
sleep 6
adb shell am broadcast -a $PKG.SEED -p $PKG --es blocked $TARGET
sleep 2
adb shell dumpsys accessibility | grep -iE "zongkong|Bound services|Enabled services" | head -8 | tee "$OUT/accessibility.txt"

route home;               shot 01-home
route gate/plan_morning;  shot 02-gate
route gate/think_one;     shot 03-think
route report;             shot 04-report
route depts;              shot 05-depts
route settings;           shot 06-settings
route settings/perm;      shot 07-perm

: > "$OUT/result.txt"
launch_target
shot 08-blocked
resumed | tee "$OUT/resumed-strict.txt"
if grep -q "$PKG/.guard.BlockActivity" "$OUT/resumed-strict.txt"; then echo "STRICT_BLOCKS=OK" >> "$OUT/result.txt"; else echo "STRICT_BLOCKS=FAIL" >> "$OUT/result.txt"; fi

adb shell am broadcast -a $PKG.SEED -p $PKG --es mode free
sleep 2
launch_target
shot 09-free-target
resumed | tee "$OUT/resumed-free.txt"
if grep -q "$TARGET" "$OUT/resumed-free.txt" && ! grep -q "BlockActivity" "$OUT/resumed-free.txt"; then echo "FREE_ALLOWS=OK" >> "$OUT/result.txt"; else echo "FREE_ALLOWS=FAIL" >> "$OUT/result.txt"; fi

route home;               shot 10-home-free
adb shell cmd uimode night yes; sleep 2
route home;               shot 11-home-dark
route gate/hq_review;     shot 12-gate-dark
adb shell cmd uimode night no

adb logcat -d | grep -E "AndroidRuntime|FATAL|SeedReceiver|GuardService|Store" | tail -200 > "$OUT/logcat.txt"
cat "$OUT/result.txt"
# 截图缩小后以 base64 打进日志，方便在拿不到 Artifacts 的地方查看
for f in "$OUT"/*.png; do
  n=$(basename "$f" .png)
  (convert "$f" -resize 360x -quality 60 "/tmp/$n.jpg" 2>/dev/null || magick "$f" -resize 360x -quality 60 "/tmp/$n.jpg") || continue
  echo "=====SHOT $n"
  base64 -w 4000 "/tmp/$n.jpg"
  echo "=====END $n"
done
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then echo "CRASH found"; grep -A20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60; exit 1; fi
grep -q FAIL "$OUT/result.txt" && exit 1
exit 0
