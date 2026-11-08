# 第一条闭环：验证记录

闭环：设置里选测试 APP → 打开它 → 无障碍收到窗口事件 → 去抖后复核真实前台包名 → 本地规则确认 → 弹出交互页 → 点"返回" → 保存回执。

标注规则：`PASS` = 在本文写明的环境里跑过并通过；`NOT_TESTED` = 没有真机证据。**本轮所有真机项都是 NOT_TESTED。**

## 1. 自动化证据（2026-10-30，云端沙箱，无 Android SDK、无真机）

| 检查 | 命令 / 方式 | 结果 |
|---|---|---|
| 闭环逻辑 JVM 测试 | `kotlinc` 编译 `loop/` 纯 Kotlin 文件 + `LoopEngineTest`，JUnit 4.13.2 运行 | PASS，17/17 |
| Android 胶水编译检查 | `kotlinc` 对照 Robolectric `android-all` (API 35) + Flutter embedding jar（与仓库 Flutter 3.47.7 同引擎）编译全部 `loop/*.kt`；DSH 内部依赖（`R`、`AppStrings`、`PhoneControl`、`TreeCapture`、`PhoneA11yService`）用照抄签名的桩代替 | PASS（仅类型检查，**不等于 Gradle 构建**） |
| Dart 静态检查 | `flutter analyze`（整个 app） | PASS，No issues |
| 新增 Flutter 测试 | `flutter test test/phone/loop_test_page_test.dart` | PASS，6/6 |
| 手机操控原有测试（含截图对比） | `flutter test test/phone` | PASS，208/208 |
| 全量 Flutter 测试 | `flutter test -j 4` | 8220 通过、287 跳过、8 失败；失败的 8 个在干净上游 `d38a39a` 上同样失败，与本改动无关（见第 4 节） |
| 多语言 | `tool/l10n_merge.py`（合并 + gen-l10n）；`tool/l10n_scan.py` 无新增硬编码中文 | PASS |
| Gradle `assembleDebug` / `testDebugUnitTest` | — | NOT_TESTED（沙箱无法下载 Android SDK） |

## 2. 任务要求的验证项

| 验证项 | 自动化覆盖（LoopEngineTest） | 真机 |
|---|---|---|
| 20 次应用切换 | `twentySwitchesTriggerTwentyTimesWithOneReceiptEach`：冷却 0，测试 App ↔ 其他 App/桌面 交替 20 次 → 20 次弹出、20 条 `RETURNED` 回执，eventId/visitId 各不相同；同一次进入的重复事件、页面自身都不重复触发 | NOT_TESTED |
| 前后台切换 | `backgroundAndBackWithinTheCooldownIsSuppressedThenTriggersAgain`：回桌面再回来，冷却内记 `SUPPRESSED(cooldown)`，冷却后再触发 | NOT_TESTED |
| 用户主动退出 | `leavingThePageBackKeyAndTimeoutAreReceiptsAndTheFirstAnswerWins`：离开页面 `DISMISSED`、系统返回 `BACK`、超时 `TIMEOUT`；重复回答被忽略 | NOT_TESTED |
| 服务重新连接 | `serviceReconnectWhileStillInTheAppDoesNotTriggerAgain`：断开期间读不到前台不改状态，重连后仍在测试 App 不再弹 | NOT_TESTED |
| 进程重启 | `processRestartClosesOpenEventsAsAbandonedAndKeepsTheVisit`：未关闭事件重启后记 `ABANDONED(process_restart)`，旧页面不能再回答；配置、访问、冷却都保留 | NOT_TESTED |
| 异常回执 | `unreadableStateIsReportedNotTreatedAsEmpty`（文件损坏→`ERROR`，坏文件改名 `.bad`）、`storageFailureIsSurfacedAndTheLoopKeepsWorking`（写盘失败设置页可见、恢复后补写）；页面没弹出 `UNAVAILABLE(not_shown)` 在 `InteractionHub` 里，**无自动化测试** | NOT_TESTED |
| 过滤自身/系统界面/输入法/权限弹窗 | `keyboardsShadesAndPermissionDialogsNeverStartANewVisit` | NOT_TESTED |
| 不与 DSH 手机操控冲突 | `dshBusyLockedAndScreenOffSuppress` | NOT_TESTED |
| 其他规则 | 时钟回拨、关闭/无目标不触发、换目标关闭旧页面、200 条上限不淘汰未关闭事件、配置归一化、事件序列化往返 | — |

## 3. 真机验证步骤（给测试的人）

准备：装本分支构建的 debug 包；在系统设置里**手动**开启 DSH 的无障碍服务；DSH → 设置 → 手机操控 → 页面最底部「触发测试」→ 选一个测试 APP（例如计算器）→ 冷却点成 `0 秒` → 打开开关。
另开一个终端看日志：`adb logcat -s DshLoop DshA11y`。
查看回执原文（debug 包）：`adb shell run-as io.github.ccch1mneyyy.dsh_app cat files/loop/state.json`

把 `T` 换成测试 APP 的包名：

1. **20 次应用切换**
   ```sh
   T=com.android.calculator2
   for i in $(seq 1 20); do
     adb shell monkey -p $T -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 3
     adb shell input keyevent KEYCODE_BACK; sleep 1      # 在测试页上按返回 → BACK
     adb shell input keyevent KEYCODE_HOME; sleep 2
   done
   ```
   期望：20 条回执，`result` 全是 `BACK`；无一条重复 visitId；屏幕上没有出现连续弹窗。
   手点"返回"按钮做几次，期望 `RETURNED`。
2. **前后台切换**：冷却改回 60 秒。进测试 APP（弹页）→ 返回 → 按 Home → 10 秒内再进：期望 `SUPPRESSED cooldown`；等 60 秒后再进：期望再弹。期间在测试 APP 里拉通知栏、弹输入法：期望不弹。
3. **用户主动退出**：页面弹出时按 Home、或锁屏：期望 `DISMISSED`；放着不管 60 秒：期望 `TIMEOUT`。
4. **服务重新连接**：停在测试 APP 里，去系统设置关闭再打开 DSH 无障碍，再回到测试 APP：期望不弹（同一次访问）；切到别的 App 再回来：期望弹。
5. **进程重启**：页面弹出时执行 `adb shell am crash io.github.ccch1mneyyy.dsh_app`（或在开发者选项里结束进程）→ 等无障碍自动重连 → 打开设置页看回执：期望最新一条 `ABANDONED process_restart`。另测一次 `adb shell am force-stop io.github.ccch1mneyyy.dsh_app`，记录无障碍是否自动恢复（风险 #6）。
6. **异常回执**：在「设置 → 应用 → DSH → 其他权限」里关掉「后台弹出界面」（MIUI/ColorOS 有此项）→ 进测试 APP：期望 3 秒后 `UNAVAILABLE not_shown`，之后仍能正常记录下一次。
7. **不打扰 DSH**：让 DSH Agent 执行一个"打开测试 APP"的手机操控任务：期望记 `SUPPRESSED dsh_busy`，Agent 不受影响。

每项把 `state.json` 中对应事件和 logcat 片段贴到下表。

| # | 机型 / 系统 | 结果 | 证据 |
|---|---|---|---|
| 1 | | NOT_TESTED | |
| 2 | | NOT_TESTED | |
| 3 | | NOT_TESTED | |
| 4 | | NOT_TESTED | |
| 5 | | NOT_TESTED | |
| 6 | | NOT_TESTED | |
| 7 | | NOT_TESTED | |

## 4. 全量 Flutter 测试

`flutter test -j 4`（Flutter 3.47.7，Noto Sans CJK 2.004 来自 Ubuntu 24.04 软件包）：**8220 通过、287 跳过、8 失败**。

失败的 8 个都是截图对比：
- `drawer/drawer_golden_test.dart`：drawer_g9_folder_editor（深/浅）
- `chat/run_info_golden_test.dart`：step detail 两个场景（深/浅）
- `chat/activity_strip_golden_test.dart`：states（深/浅）

切回干净上游 `d38a39a` 单独跑这 3 个文件，**同样这 8 个失败**，所以是沙箱环境（字体版本等）造成的，不是本改动引起。本改动影响到的手机操控截图测试全部通过。
