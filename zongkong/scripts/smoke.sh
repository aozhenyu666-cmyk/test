#!/usr/bin/env bash
# 模拟器冒烟测试：装 APK、开无障碍、预置拦截名单，验证严管时打开拦截应用会被弹回、放行时不会。
# 用法：smoke.sh <apk 目录> <截图目录>
# 先跑端到端闭环测试（模拟 Notion + 真实界面），再测拦截。
set -u
APKDIR="$1"
OUT="$2"
APK="$APKDIR/debug/app-debug.apk"
TESTAPK="$APKDIR/androidTest/debug/app-debug-androidTest.apk"
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
adb install -r "$TESTAPK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

# ---------- 端到端闭环 ----------
: > "$OUT/result.txt"
adb shell am instrument -w $PKG.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$OUT/e2e.txt"
if grep -q "^OK (" "$OUT/e2e.txt"; then echo "E2E_LOOP=OK" >> "$OUT/result.txt"; else echo "E2E_LOOP=FAIL" >> "$OUT/result.txt"; fi
adb pull /sdcard/Android/data/$PKG/files/shots/. "$OUT/" >/dev/null 2>&1 || true
# 注意：不能 force-stop 总控——系统会把被强行停止的应用从“已开启的无障碍服务”里移除，
# 而且这一步可能比下面的开启晚执行，把刚开的又关掉。

# 开无障碍，确认真的绑定上了再往下走
for i in 1 2 3 4 5 6; do
  adb shell settings put secure enabled_accessibility_services $PKG/$PKG.guard.GuardService
  adb shell settings put secure accessibility_enabled 1
  sleep 3
  if adb shell dumpsys accessibility | grep -q "Bound services:{Service\[label=总控"; then echo "无障碍已绑定"; break; fi
  echo "无障碍还没绑定，重试 $i"
done
adb shell am start -n $PKG/.ui.MainActivity >/dev/null
sleep 6
adb shell am broadcast -a $PKG.SEED -p $PKG --es blocked $TARGET
sleep 2
adb shell dumpsys accessibility | grep -iE "zongkong|Bound services|Enabled services" | head -8 | tee "$OUT/accessibility.txt"

route home;               shot 01-home
route rhythm;             shot 01b-rhythm
route gate/plan_morning;  shot 02-gate
route gate/think_one;     shot 03-think
route capture;            shot 04-capture
route results;            shot 04b-results
route depts;              shot 05-depts
route settings;           shot 06-settings
route settings/perm;      shot 07-perm

launch_target
shot 08-blocked
resumed | tee "$OUT/resumed-strict.txt"
if grep -q "$PKG/.guard.BlockActivity" "$OUT/resumed-strict.txt"; then
  echo "STRICT_BLOCKS=OK" >> "$OUT/result.txt"
else
  echo "STRICT_BLOCKS=FAIL" >> "$OUT/result.txt"
  echo "---- 诊断：总控的判断 ----"
  adb logcat -d -s GuardService:V | tail -40
  echo "---- 诊断：后台启动 / 任务栈 ----"
  adb logcat -d | grep -iE "BlockActivity|Background activity|BAL|abort background" | tail -20
  adb shell dumpsys activity activities | grep -E "Task|ActivityRecord" | head -20
fi

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
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then echo "CRASH found"; grep -A20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60; exit 1; fi
grep -q FAIL "$OUT/result.txt" && exit 1
exit 0
