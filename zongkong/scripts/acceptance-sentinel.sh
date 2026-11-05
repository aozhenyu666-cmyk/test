#!/usr/bin/env bash
# 哨兵接管验收：冻结总控，看哨兵多久判定掉线、打开娱乐应用会不会被拦。
#
# 前置：
#   1. 总控、哨兵都已安装，两个无障碍服务都已开启
#   2. 总控里配好拦截名单和严格窗口（或在模拟器上用最小 config）
#   3. 想单独验“拦下”时，先收回哨兵的重绑权限：pm revoke ... WRITE_SECURE_SETTINGS
#
# 用法：ADB=/d/code/android-sdk/platform-tools/adb.exe bash acceptance-sentinel.sh

set -u
ADB=${ADB:-adb}
PKG_MAIN=com.zongkong.app
PKG_SENTINEL=com.zongkong.sentinel
ENTERTAIN=${ENTERTAIN:-com.google.android.youtube}
WAIT_SECONDS=${WAIT_SECONDS:-75}

echo "== 前置检查 =="
$ADB shell settings get secure enabled_accessibility_services
PID=$($ADB shell pidof "$PKG_MAIN" | tr -d '\r')
if [ -z "$PID" ]; then echo "总控没在跑，先打开一次总控"; exit 1; fi
echo "总控 pid=$PID"

echo "== 冻结总控 =="
T0=$(date +%s)
$ADB shell kill -STOP "$PID"
echo "frozen at $(date +%H:%M:%S)"

for i in $(seq 1 "$WAIT_SECONDS"); do
  sleep 1
  if $ADB shell run-as "$PKG_SENTINEL" cat /data/data/$PKG_SENTINEL/files/log.txt 2>/dev/null | grep -q "总控掉线"; then
    echo "接管判定：$(( $(date +%s) - T0 )) 秒"
    break
  fi
done

echo "== 打开娱乐应用 =="
$ADB shell monkey -p "$ENTERTAIN" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
$ADB shell dumpsys activity activities 2>/dev/null | grep ResumedActivity | head -2
echo "== 哨兵日志 =="
$ADB shell run-as "$PKG_SENTINEL" cat /data/data/$PKG_SENTINEL/files/log.txt 2>/dev/null | tail -6

echo "== 恢复总控 =="
$ADB shell kill -CONT "$PID"
