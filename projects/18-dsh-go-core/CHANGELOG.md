# CHANGELOG（本轮相对上游 d38a39a）

基线：`10c3519` = 上游 `d38a39a`（DSH Go 1.0.2）。
本轮分支：`feat/proactive-integration`；`review/d5-loop-to-kernel` 为待审补丁。

## 1. Claude 的原型与内核（应用，未改动其逻辑）

| 提交 | 内容 |
|---|---|
| `bc72be3` | `0001-event-loop-v1`：31 个文件，事件闭环原型（9 个 `loop/*.kt`、页面、通道、l10n、17 项测试、docs） |
| `aa64058` | `0002-proactive-kernel`：`loop/core/**`（Coordinator / ProactiveScheduler / AgentWire / CoreCodec / SnapshotStore）+ 66 项 JVM 测试 + `runtime/proactive/dsh-go-proactive/` 宿主插件 + 10 项插件测试 |
| `c949906` | `0003-proactive-android`：`loop/proactive/**`（ProactiveRuntime / ProactivePlugin / ProactivePageActivity / WakeAlarm）+ 共享文件小改 |

`0003` 与我的改动在 `loop/LoopRuntime.kt` 有一处冲突，按"两边都要保留"手工合并（见 §4）。

## 2. 我修复的三个关键缺陷

### P0-01 · 交互页过早标记 SHOWN
- `loop/InteractionActivity.kt`：`onCreate` 不再 `shown()`；改为 decor view 挂载 + `isShown`
  + 宽高 > 0 后才确认；未可见就离开 → `UNAVAILABLE(not_shown)`；`stateOf()` 只读。
- 影响：ROM 允许 Activity 创建但不显示时，不再产生"用户看见了"的假回执。

### P1-01 · 无障碍异常缺少诊断
- 新增 `loop/LoopDiagnostics.kt`：按类计数、连续失败、最近 20 条、每类最近成功时间。
- `loop/LoopRuntime.kt`：`read_foreground` / `verify` / `attach` / `surface` 四条路径都记账；
  异常仍被拦住，**不向 `PhoneA11yService` 传播**。
- `loop/LoopPlugin.kt`：`status` 增加 `diagnostics`、`recoveredFrom`、`triggersToday`；
  新增 `clearDiagnostics`。

### P1-02 · 状态文件替换可靠性
- `loop/LoopStore.kt`（`FileLoopStore`）：临时文件 → fsync → 旧文件转为 `.bak` → 原子 move
  → 目录 fsync；读失败回退 `.bak` 并把坏文件隔离为 `.bad`；空文件视为不可读而非"没存过"。
- `loop/core/SnapshotStore.kt`（`AtomicJsonFile`）：**同一缺陷**，按同一方案修复，
  并在 `SnapshotStore.Read.Loaded` 上增加 `recoveredFrom`。

## 3. 新功能

| 项 | 文件 |
|---|---|
| T3 通知兜底（第一轮闭环） | `loop/InteractionHub.kt`：独立渠道 `dsh-loop`、每次随机 nonce 广播、`RECEIVER_NOT_EXPORTED`、`surface=notification`、事件在兜底期间保持打开 |
| T5 本地规则 | `loop/EventScheduler.kt`：跨零点免打扰、每日上限（`triggerDay`/`triggersToday` 持久化） |
| T7 AgentBridge v0 | `loop/AgentBridge.kt`：接口 + `LocalTemplateBridge` + `DeliveryGuard` |
| T2 设置页 | `lib/ui/phone/loop_test_page.dart`：超时选择、surface、visitId、JSON 导出 |
| T9 披露 | `lib/ui/phone/phone_disclosure_sheet.dart` + `phone_{zh,en}.arb` |
| D1 Dart 转发 | `lib/proactive/proactive_channel.dart`、`lib/state/proactive_transport.dart` |
| D2 通知端口 | `loop/proactive/NotificationPortImpl.kt` |
| D3 主动搭话页 | `lib/proactive/proactive_state.dart`、`lib/ui/phone/proactive_page.dart`、`l10n/parts/proactive_{zh,en}.arb` |
| D4 打包插件 | `runtime/build-runtime.sh`、`runtime/lib/rtbuild.py`、`runtime/test-host.sh` |

## 4. 共享文件的改动（需要 Claude 知道）

| 文件 | 我的改动 | 冲突处理 |
|---|---|---|
| `loop/LoopRuntime.kt` | 诊断记账、时间事实（`minuteOfDay`/`dayKey`）、`bridge()`、`appLabel()` | 与 0003 冲突 2 处：**保留我的错误捕获 + Claude 的 `ProactiveRuntime` 钩子**（attach 刷新、`protected_page` 刷新） |
| `phone/PhoneA11yService.kt` | 无新增（0001 已接入 loop） | — |
| `MainActivity.kt` / `AndroidManifest.xml` | 0003 的注册（`ProactivePlugin` / 页面 / 接收器） | 自动合并 |
| `loop/EventScheduler.kt` | T5 的 `quietStartMinute`/`quietEndMinute`/`dailyCap` | 与内核不冲突（内核另有一套 `SchedulerConfig`） |

## 5. 测试与文档

- JVM 96 项（新增 `LoopHardeningTest` 15、`KernelRiskTest` 8，Claude 的 66 项原样保留）。
- Flutter：`loop_test_page_test` 14、`proactive_page_test` 7、`proactive_channel_test` 12。
- l10n：`tool/l10n_merge.py` 43 parts / 5,259 keys，`gen-l10n` 成功。
- 文档：`BUILD_REPORT.md`、`VALIDATION_REPORT.md`、`ARCHITECTURE_FINAL.md`、
  `TASK_STATUS.md`、`CHANGELOG.md`、`EXECUTION_STATE.md`、`CLAUDE_REVIEW.md`、`PATCHES/`。

## 6. 明确没有做的事

- 没有改上游 `main`，没有删除或放宽任何既有测试（golden 基线一张未动）。
- 没有改 `phone_accessibility_service.xml`、`SafetyGate`、`ProtectedPages`、`PromptArbiter`
  的既有行为；没有动默认输入法；没有绕过系统权限确认。
- 没有读取任何页面节点内容（自定义目录只读包名）。
- 没有提交任何 API Key / Token / 用户数据。
- 没有连接、安装或卸载任何真机应用。
