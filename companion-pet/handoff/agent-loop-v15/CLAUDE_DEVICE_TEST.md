# 交给 Claude Code 的真机测试包

目的：把“手动点手机”改成 Claude Code 在这台 Windows 上通过 ADB 执行、截图、跑仪器测试和写证据。
代码基线：`deepseek/moling-agent-fix-v1.5` / `a34adf975b7c1df4c48f56ad84ca8c458253e800`。
版本：0.3.7 / versionCode 10。APK：`_pet-reports/agent-loop-v15/app-debug.apk`。
APK SHA-256：`31C605EBE97F799EB00B9A9D6C3F2CF2EDDD92C4CCE58D7CA2ECFCED1E9C9C55`。

## 0. Claude Code 启动提示（可直接复制）

```text
cd C:\Users\敖震宇\Documents\deepseek-harness\default-workspace\pet-agent-loop-v15

先读：
- companion-pet/handoff/agent-loop-v15/STATE.md
- companion-pet/handoff/agent-loop-v15/CLAUDE_DEVICE_TEST.md

然后按本文件用 adb 对 10AE5U23LT004DN 跑真机测试；先检查 adb devices。
允许：安装/覆盖 debug APK、启动应用、ADB 截图、uiautomator dump、input tap、logcat、dumpsys、run-as 读取本应用测试文件、运行 instrumentation、必要时做最小代码修复并重跑。
禁止：卸载或清数据、读取/打印 API Key、上传屏幕原文或私人聊天、安装任意 Shell/Shizuku 自动化、改他人分支、把未执行的项写成通过。
每项记录：命令、结果、截图/日志路径；没有执行就写 NOT_RUN。
最后输出：commit SHA、测试数、失败数、APK SHA-256、每项 PASS/FAIL/NOT_RUN、未完成项。
```

## 1. 前置

- 手机：iQOO 12，序列号 `10AE5U23LT004DN`；USB 调试已开，解锁状态。
- 测试 APK 安装可能被 vivo 安全确认拦截；若返回 `INSTALL_FAILED_ABORTED: User rejected permissions`，让用户点允许，或把该项标 NOT_RUN，不要卸载主包。
- 不允许清数据/卸载；覆盖安装保留现有数据。
- 不打印 Key；不把屏幕内容上传到任何外部服务。

```powershell
$adb='C:\Users\敖震宇\Documents\deepseek-harness\default-workspace\_android-sdk\platform-tools\adb.exe'
$env:TEMP='C:\Users\敖震宇\Documents\deepseek-harness\default-workspace\_adb-temp'
$env:TMP=$env:TEMP
& $adb devices -l
```

## 2. 安装与启动

```powershell
$apk='C:\Users\敖震宇\Documents\deepseek-harness\default-workspace\_pet-reports\agent-loop-v15\app-debug.apk'
$test='C:\Users\敖震宇\Documents\deepseek-harness\default-workspace\_pet-reports\agent-loop-v15\app-debug-androidTest.apk'
& $adb -s 10AE5U23LT004DN install -r -g $apk
& $adb -s 10AE5U23LT004DN shell am start -W -n cn.companion.pet/.MainActivity
Start-Sleep -Seconds 3
& $adb -s 10AE5U23LT004DN shell dumpsys package cn.companion.pet | Select-String 'versionName|versionCode'
```

PASS 条件：安装成功、主界面出现、versionName=0.3.7、versionCode=10。

## 3. 仪器测试（先编译好，未执行）

安装测试 APK：

```powershell
& $adb -s 10AE5U23LT004DN install -r -g -t $test
& $adb -s 10AE5U23LT004DN shell pm path cn.companion.pet.test
```

如果安装被拒，记录 NOT_RUN，继续手工项，不要写通过。

逐类运行并保存完整输出：

```powershell
$runner='cn.companion.pet.test/androidx.test.runner.AndroidJUnitRunner'
foreach ($c in @(
  'cn.companion.pet.MaterialShareInstrumentedTest',
  'cn.companion.pet.CapabilityPanelTest',
  'cn.companion.pet.CapabilityRotationTest',
  'cn.companion.pet.DurableActionInstrumentedTest',
  'cn.companion.pet.BootstrapSmokeTest'
)) {
  Write-Output "=== $c ==="
  & $adb -s 10AE5U23LT004DN shell am instrument -w -r -e class $c $runner
}
```

结果按 instrumentation 输出记 PASS/FAIL；安装失败就 NOT_RUN。

## 4. B1：记忆页不得清空事件

```powershell
$month=(Get-Date).ToString('yyyy-MM')
$p="files/memory/events/events-$month.jsonl"
& $adb -s 10AE5U23LT004DN shell "run-as cn.companion.pet sh -c 'wc -l $p'"
& $adb -s 10AE5U23LT004DN shell input tap 787 465
Start-Sleep -Seconds 3
& $adb -s 10AE5U23LT004DN shell "run-as cn.companion.pet sh -c 'wc -l $p'"
& $adb -s 10AE5U23LT004DN shell screencap -p /sdcard/Download/b1.png
& $adb -s 10AE5U23LT004DN pull /sdcard/Download/b1.png .\b1.png
```

PASS：打开前后行数相同；截图里有“清空今天的记录”按钮但没有自动删。
如果行数变了：FAIL，保留前后输出，不要改数据。

## 5. ACTION_SEND 分享预览与保存

```powershell
& $adb -s 10AE5U23LT004DN shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '合成标题 https://example.com/a?x=1 这是一段合成摘录' --es android.intent.extra.SUBJECT '合成标题' -n cn.companion.pet/.MaterialShareActivity
Start-Sleep -Seconds 2
& $adb -s 10AE5U23LT004DN shell uiautomator dump /sdcard/window.xml
& $adb -s 10AE5U23LT004DN shell cat /sdcard/window.xml
```

在 dump 里找“保存这份材料”的 bounds，点中心；保存后：

```powershell
& $adb -s 10AE5U23LT004DN shell "run-as cn.companion.pet sh -c 'cat files/memory/materials.json'"
& $adb -s 10AE5U23LT004DN shell "run-as cn.companion.pet sh -c 'tail -n 5 files/memory/events/events-$(Get-Date -Format yyyy-MM).jsonl'"
```

PASS：预览出现；保存后 materials.json 有 1 条、事件日志有 MATERIAL 事件、没有自动发起模型请求（看 logcat 无 chat completion）；重复分享同一链接不增加卡片。

## 6. 能力面板、持久回执、旋转

- 设置 → 打开能力面板；五项能力可见，保存念头先预览，确认只写一次；快速双击不重复。
- 保存念头后退出/重启，最近写入记录仍可核对；删除念头后不复活。
- 旋转/返回不重新提交；离开面板只取消本作用域。
- 未开启朗读时 SPEAK 显示暂不可用；未安装目标 AI 时显示暂不可用。

每项截图/日志；真实 TTS/打开 App 未做就 NOT_RUN。

## 7. 自主模式（需要模型 Key/budget）

如果设备已配置 Key：

1. 设置 → 开启“自主模式（实验）”。
2. 回记忆页保存一条合成念头（或分享一条素材）。
3. 等待 2–5 秒；应出现一次气泡（可能执行 READ_MEMORY/READ_MATERIALS，或显示“需要确认”）。
4. 读取轨迹：

```powershell
& $adb -s 10AE5U23LT004DN shell "run-as cn.companion.pet sh -c 'tail -n 5 files/memory/agent/traces.jsonl'"
```

PASS：新 NOTE/MATERIAL 恰好触发一次；轨迹有 eventId/trigger/tool/actionStatus；再次重启不重复同一事件。
- 写入/打开/朗读只能是 NEEDS_CONFIRMATION，不能自动执行。
- 没有 Key/额度：NOT_RUN，不要伪造。

## 8. 取消、锁屏、旋转、字体

- 自主模式运行中按锁屏；重新亮屏后不应出现迟到气泡；轨迹保留真实结果。
- 能力面板执行中按返回/旋转；不重复执行、不显示过期结果。
- 字体 1.3、360dp/430dp：按钮换行、输入框、轨迹可滚动。

## 9. 不得声称

- 仪器 APK 编译成功 ≠ 仪器执行通过。
- fake 测试通过 ≠ 真实 App/TTS 打开成功。
- 普通 install 成功 ≠ 覆盖升级/权限/后台存活通过。
- 没有截图/命令输出的项一律 NOT_RUN。

## 10. 交付格式

```text
分支/commit:
APK SHA-256:
设备/系统:
JVM 测试: x/y
仪器测试: 每类 PASS/FAIL/NOT_RUN + 原始输出
B1: 前后行数 + 截图
分享: 预览/保存/重复分享 + 文件片段
能力面板: 每项 PASS/FAIL/NOT_RUN
自主模式: 轨迹行 + 是否真实模型 + 未执行项
取消/锁屏/旋转/字体: PASS/FAIL/NOT_RUN
未完成/风险:
```

证据放 `_pet-reports/claude-device/`，不要提交 Key、Cookie、完整聊天或屏幕原文。
