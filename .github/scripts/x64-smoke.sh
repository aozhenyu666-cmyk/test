#!/bin/sh
# 云端模拟器冒烟：装包 → 放 key → 起 App → 等探针 → 断言。
#
# 为什么是独立文件而不是写在 workflow 的 script: 里 ——
# android-emulator-runner 把 script 的**每一行**当成独立的 `sh -c` 执行，
# 变量赋值、循环、if 都不跨行，ADB=... 下一行就没了（实测：`sh: 1: : Permission denied`，退出码 127）。
# 落成一个真脚本再一次性调用，才有真正的 shell 语义。

set -eu

ADB="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools/adb"
APK=app/build/app/outputs/flutter-apk/app-x64-debug.apk
PKG=io.github.ccch1mneyyy.dsh_app

"$ADB" root || true
# adb root 会重启 daemon，不等它回来直接 install 会撞上 device offline。
"$ADB" wait-for-device
sleep 3

"$ADB" install -r "$APK"

# 探针见到这个文件才会跑一次真实回合（假模型回话），跑完自己删掉。
# adb root 在这类 google_apis 镜像上可用；拿不到就退回 run-as（debug 包可调试）。
printf 'sk-mock-not-a-real-key' > probe-key
"$ADB" push probe-key /data/local/tmp/probe-key
"$ADB" shell "mkdir -p /data/user/0/$PKG/files"
if ! "$ADB" shell "cp /data/local/tmp/probe-key /data/user/0/$PKG/files/probe-key"; then
  "$ADB" shell "run-as $PKG sh -c 'mkdir -p files && cp /data/local/tmp/probe-key files/probe-key'"
fi

"$ADB" logcat -c || true
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1

# 首启要解压 rootfs（几百 MB），给它足够时间。
ok=0
for i in $(seq 1 180); do
  if "$ADB" logcat -d | grep -q 'PROBE OK'; then ok=1; break; fi
  if "$ADB" logcat -d | grep -q 'PROBE FAILED'; then ok=2; break; fi
  sleep 5
done

"$ADB" logcat -d > full-logcat.txt || true
grep -E '\[probe\]|PROBE|COMPANION' full-logcat.txt | tail -80 || true
"$ADB" shell screencap -p /sdcard/smoke.png || true
"$ADB" pull /sdcard/smoke.png smoke.png || true
"$ADB" shell "run-as $PKG cat files/loop/proactive/ledger.json" > proactive-ledger.json 2>/dev/null || true

if [ "$ok" != 1 ]; then
  echo "::error::探针没有走到 PROBE OK（ok=$ok），看上传的 logcat"
  exit 1
fi

# 计划层与提交层必须一致：这次修复的核心不变量。
# 没发球时先把 plan 的真实 reason 打出来，别让它伪装成路由坏了。
if ! grep -q 'COMPANION OK' full-logcat.txt; then
  grep -E 'COMPANION plan|COMPANION request' full-logcat.txt || true
  echo "::error::插件没有真正发球（看上面的 plan speak/reason）"
  exit 1
fi

echo "冒烟通过：计划层与提交层一致，设备上真的开口了"
