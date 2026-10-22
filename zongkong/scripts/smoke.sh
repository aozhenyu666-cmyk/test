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
# 哨兵：独立应用（0.13）
SP=com.zongkong.sentinel
GUARD=$PKG/$PKG.guard.GuardService
SVC=$SP/$SP.SentinelService
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
# 一手：端到端测试里会真的跨应用调用它（读状态、交任务）
YISHOU_APK=$(find "$APKDIR" -path "*yishou*" -name "*.apk" | head -1)
if [ -n "$YISHOU_APK" ]; then adb install -r "$YISHOU_APK" && echo "一手已安装"; else echo "没找到一手 APK"; fi
SENTINEL_APK=$(find "$APKDIR" -path "*sentinel*" -name "*.apk" | head -1)
if [ -n "$SENTINEL_APK" ]; then adb install -r "$SENTINEL_APK" && echo "哨兵已安装"; else echo "没找到哨兵 APK"; fi
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell pm grant $SP android.permission.POST_NOTIFICATIONS || true

# ---------- 端到端闭环 ----------
: > "$OUT/result.txt"
adb shell am instrument -w $PKG.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$OUT/e2e.txt"
if grep -q "^OK (" "$OUT/e2e.txt"; then echo "E2E_LOOP=OK" >> "$OUT/result.txt"; else echo "E2E_LOOP=FAIL" >> "$OUT/result.txt"; fi
adb pull /sdcard/Android/data/$PKG/files/shots/. "$OUT/" >/dev/null 2>&1 || true

# ---------- 连接口只认对方：别的调用者（这里是 adb shell）一律拒绝 ----------
adb shell content call --uri content://com.yishou.app.link --method status 2>&1 | tee "$OUT/link-yishou-from-shell.txt"
adb shell content call --uri content://com.zongkong.app.link --method status 2>&1 | tee "$OUT/link-zongkong-from-shell.txt"
if grep -q "只接受总控的连接" "$OUT/link-yishou-from-shell.txt" && grep -q "只接受一手的连接" "$OUT/link-zongkong-from-shell.txt"; then
  echo "LINK_REJECTS_OTHERS=OK" >> "$OUT/result.txt"
else
  echo "LINK_REJECTS_OTHERS=FAIL" >> "$OUT/result.txt"
fi
# 哨兵和总控之间的口子也只认对方
adb shell content call --uri content://com.zongkong.sentinel.link --method ping 2>&1 | tee "$OUT/link-sentinel-from-shell.txt"
adb shell content call --uri content://com.zongkong.app.sentinel --method wake 2>&1 | tee "$OUT/link-wake-from-shell.txt"
if grep -q "只接受总控的连接" "$OUT/link-sentinel-from-shell.txt" && grep -q "只接受哨兵的连接" "$OUT/link-wake-from-shell.txt"; then
  echo "SENTINEL_REJECTS_OTHERS=OK" >> "$OUT/result.txt"
else
  echo "SENTINEL_REJECTS_OTHERS=FAIL" >> "$OUT/result.txt"
fi
# 注意：不能 force-stop 总控——系统会把被强行停止的应用从“已开启的无障碍服务”里移除，
# 而且这一步可能比下面的开启晚执行，把刚开的又关掉。

# 开无障碍，确认真的绑定上了再往下走
for i in 1 2 3 4 5 6; do
  adb shell settings put secure enabled_accessibility_services $GUARD:$SVC
  adb shell settings put secure accessibility_enabled 1
  sleep 3
  if adb shell dumpsys accessibility | grep -q "label=总控,"; then echo "无障碍已绑定"; break; fi
  echo "无障碍还没绑定，重试 $i"
done
adb shell am start -n $PKG/.ui.MainActivity >/dev/null
sleep 6
adb shell am broadcast -a $PKG.SEED -p $PKG --es blocked $TARGET --es mode strict
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
route dossier;            shot 07b-dossier
route chat;               shot 07c-chat

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

# 放行时打开娱乐应用：先答一题（0.10）
adb shell am broadcast -a $PKG.SEED -p $PKG --es mode gate
sleep 2
launch_target
shot 08b-open-gate
resumed | tee "$OUT/resumed-gate.txt"
if grep -q "$PKG/.guard.GateActivity" "$OUT/resumed-gate.txt"; then echo "OPEN_GATE=OK" >> "$OUT/result.txt"; else echo "OPEN_GATE=FAIL" >> "$OUT/result.txt"; fi

adb shell am broadcast -a $PKG.SEED -p $PKG --es mode free
sleep 2
launch_target
shot 09-free-target
resumed | tee "$OUT/resumed-free.txt"
if grep -q "$TARGET" "$OUT/resumed-free.txt" && ! grep -q "BlockActivity" "$OUT/resumed-free.txt"; then echo "FREE_ALLOWS=OK" >> "$OUT/result.txt"; else echo "FREE_ALLOWS=FAIL" >> "$OUT/result.txt"; fi

route home;               shot 10-home-free

# ---------- 总控时间：白名单以外的应用一律回到总控时间 ----------
adb shell am broadcast -a $PKG.SEED -p $PKG --es mode hq
sleep 2
route hq;                 shot 13-hq-session
launch_target
shot 14-hq-lockdown
resumed | tee "$OUT/resumed-hq.txt"
if grep -q "$PKG/.ui.MainActivity" "$OUT/resumed-hq.txt" && ! grep -q "$TARGET" "$OUT/resumed-hq.txt"; then
  echo "HQ_LOCKDOWN=OK" >> "$OUT/result.txt"
else
  echo "HQ_LOCKDOWN=FAIL" >> "$OUT/result.txt"
  adb logcat -d -s GuardService:V | tail -30
fi
adb shell am broadcast -a $PKG.SEED -p $PKG --es mode hq-end
sleep 2
launch_target
resumed | tee "$OUT/resumed-hq-end.txt"
if grep -q "$TARGET" "$OUT/resumed-hq-end.txt"; then echo "HQ_RELEASES=OK" >> "$OUT/result.txt"; else echo "HQ_RELEASES=FAIL" >> "$OUT/result.txt"; fi
route home;               shot 15-home-after-hq
adb shell cmd uimode night yes; sleep 2
route home;               shot 11-home-dark
route gate/hq_review;     shot 12-gate-dark
adb shell cmd uimode night no

# ---------- 哨兵：总控的无障碍掉了（这里直接把它从列表里拿掉），哨兵接管，娱乐照样拦 ----------
adb shell am broadcast -a $PKG.SEED -p $PKG --es blocked $TARGET --es mode strict
sleep 2
# 先确认总控一直在给哨兵写名单（哨兵自己的文件里有拦截名单）
adb shell run-as $SP cat files/state.json 2>&1 | tee "$OUT/sentinel-state.txt"
if grep -q "$TARGET" "$OUT/sentinel-state.txt"; then echo "SENTINEL_LINKED=OK" >> "$OUT/result.txt"; else echo "SENTINEL_LINKED=FAIL" >> "$OUT/result.txt"; fi
adb shell settings put secure enabled_accessibility_services $SVC
sleep 5
adb shell dumpsys accessibility | grep -iE "Bound services|Enabled services" | head -4 | tee "$OUT/accessibility-sentinel.txt"
# 总控的心跳停了（模拟掉线 90 秒以上）
adb shell am broadcast -a $PKG.SEED -p $PKG --es mode main-down
sleep 1
launch_target
sleep 4
shot 16-sentinel-takeover
resumed | tee "$OUT/resumed-sentinel.txt"
if grep -q "$SP/.BlockActivity" "$OUT/resumed-sentinel.txt"; then echo "SENTINEL_TAKEOVER=OK" >> "$OUT/result.txt"; else echo "SENTINEL_TAKEOVER=FAIL" >> "$OUT/result.txt"; adb logcat -d -s Sentinel:V SentinelSys:V SentinelStore:V SentinelClient:V | tail -30; fi

# ---------- 哨兵独立：总控被“强行停止”（vivo 一键清理就是这个），哨兵不受影响，照样接管 ----------
adb shell settings put secure enabled_accessibility_services $GUARD:$SVC
adb shell settings put secure accessibility_enabled 1
sleep 6
adb shell am broadcast -a $PKG.SEED -p $PKG --es blocked $TARGET --es mode strict
route home
adb shell input keyevent KEYCODE_HOME; sleep 3
SPID=$(adb shell pidof $SP | tr -d '\r')
adb shell am force-stop $PKG
sleep 3
adb shell settings get secure enabled_accessibility_services | tee "$OUT/acc-after-force-stop.txt"
adb shell dumpsys accessibility | grep -iE "Bound services" | head -2 | tee -a "$OUT/acc-after-force-stop.txt"
echo "等总控的心跳过期（90 秒）"
sleep 95
launch_target
sleep 5
shot 17-sentinel-after-force-stop
resumed | tee "$OUT/resumed-force-stop.txt"
SPID2=$(adb shell pidof $SP | tr -d '\r')
echo "哨兵进程：停总控前 $SPID，之后 $SPID2" | tee -a "$OUT/acc-after-force-stop.txt"
if [ -n "$SPID" ] && [ "$SPID" = "$SPID2" ] && grep -q "$SP/.BlockActivity" "$OUT/resumed-force-stop.txt"; then
  echo "SENTINEL_SURVIVES_FORCE_STOP=OK" >> "$OUT/result.txt"
else
  echo "SENTINEL_SURVIVES_FORCE_STOP=FAIL" >> "$OUT/result.txt"
  adb logcat -d -s Sentinel:V SentinelSys:V SentinelStore:V | tail -30
fi
# 给哨兵“写安全设置”权限：它自己把总控挂回无障碍列表（两分钟试一次）
adb shell pm grant $SP android.permission.WRITE_SECURE_SETTINGS
HEALED=FAIL
for i in $(seq 1 14); do
  sleep 15
  if adb shell settings get secure enabled_accessibility_services | grep -q "$GUARD"; then HEALED=OK; break; fi
done
echo "SENTINEL_HEALS_MAIN=$HEALED" >> "$OUT/result.txt"
adb shell run-as $SP cat files/log.txt > "$OUT/sentinel-log.txt" 2>&1 || true
adb shell settings put secure enabled_accessibility_services $GUARD:$SVC

adb logcat -d | grep -E "AndroidRuntime|FATAL|SeedReceiver|GuardService|Store|Sentinel|CrashShield" | tail -200 > "$OUT/logcat.txt"
cat "$OUT/result.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then echo "CRASH found"; grep -A20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60; exit 1; fi
grep -q FAIL "$OUT/result.txt" && exit 1
exit 0
