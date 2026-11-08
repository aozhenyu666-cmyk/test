# DSH Go 源码审计：事件闭环改造前

审计对象：`ccch1mneyyy/dsh-go` @ `d38a39a`（DSH Go 1.0.2），重点是 `app/android/.../phone/` 与 Flutter 平台通信。
历史项目只看有明确复用价值的部分，结论在文末分类表。

---

## 1. 事件怎么进入

| 入口 | 位置 | 现状 |
|---|---|---|
| 无障碍事件 | `phone/a11y/PhoneA11yService.onAccessibilityEvent` | **只做一件事**：`EventClock.tick(packageName)`，给"等界面稳定"计数。没有任何监听者，服务注释写明 *"It reads nothing on its own"*。 |
| 订阅的事件类型 | `res/xml/phone_accessibility_service.xml` | `windowStateChanged / windowContentChanged / windowsChanged / viewTextChanged / viewScrolled`，`notificationTimeout=100`，`flagRetrieveInteractiveWindows`。**新闭环不需要改这个配置。** |
| Agent 工具调用 | `PhoneBridge`（抽象 unix socket，校验 uid + 一次性 token）→ `McpServer`（MCP JSON-RPC）→ `PhoneCore` → `PhoneDriver` | 只有 Agent 主动调用工具时才读屏/操作。 |
| Flutter 调用 | `PhonePlugin`：MethodChannel `dsh/phone`、EventChannel `dsh/phone/events` | 设置页、审批弹窗 `presentPrompt`、`finishTask` 等。 |
| 屏幕关闭 | `PhoneA11yService` 内部 `ACTION_SCREEN_OFF` 接收器 | 触发 `stopAll` 停止当前任务。 |

结论：**DSH 现在是纯"被动执行"模型**，手机状态变化不会主动产生任何事件。EventObserver 必须新建。

## 2. 如何识别前台 APP

- `TreeCapture.foregroundPackage(service, screenBox)`：按 layer 从高到低遍历 `service.windows`，只看 `TYPE_APPLICATION` 和 `TYPE_SYSTEM`，系统窗口占屏 < 50% 跳过（状态栏），取窗口根节点的 `packageName`。
- 输入法窗口（`TYPE_INPUT_METHOD`）、DSH 自己的悬浮条（`TYPE_ACCESSIBILITY_OVERLAY`）天然被排除。
- `EventClock.lastPackage` 只是事件携带的原始包名，**不可靠**（输入法、Toast、通知栏都会带来），现有代码也只拿它做调试。
- `ProtectedPages`：安装器、权限控制器、各厂商安全中心整包保护；设置 App 按页面标题关键词保护。驱动层"能读不能点"。

风险：`TYPE_APPLICATION` 没有面积判断，画中画/自由窗口这类小应用窗口如果 layer 更高会"赢"。新闭环先找**获得焦点的应用窗口**，找不到再退回 `TreeCapture`。

## 3. 如何调用 Activity / 通知

`PhonePrompts`（进程唯一的提问协调器）按顺序尝试三种界面：

1. **悬浮层** `ControlOverlay.showPrompt` —— `TYPE_ACCESSIBILITY_OVERLAY`，不需要悬浮窗权限，也不受后台启动限制；
2. **通知** `NotificationGate.showPrompt` —— 高优先级通知 + 每次随机 nonce 的动态广播接收器，按钮只认本次展示；
3. **App 内对话框** —— `reopen()` 把 `MainActivity` 拉到前台，最多等 3 秒变成 resumed，再弹 `PhonePromptUi.showDialog`。

关键机制（新闭环要遵守同样的原则）：
- `PromptArbiter` 一次只允许一个问题；`PromptTicket` 有总截止时间（≤ 600 s），每个展示有独立 nonce，旧界面的按钮回答不了新问题；
- `PromptInputPolicy` 防点击劫持（遮挡、注入、虚拟设备）；
- 任务结束后的返回走 `PhoneTaskReturn`：拉起 App → 3 秒内确认可见 → 否则发结果通知。

## 4. 哪些调用必须依赖已有任务

| 调用 | 依赖 | 没有任务时 |
|---|---|---|
| `PhonePrompts.present`（引擎审批/复核） | `tasks.activeOwner(taskId)` 或 `beforeTaskOwner()` | 回 `unavailable("stale")` |
| `PhonePrompts.confirm`（原生确认） | `tasks.activeOwner()` | 回 `Decision.STOP` |
| `PhonePrompts.handoff`（交还给人） | `tasks.activeOwner()` | 回 `HandoffResult.STOP` |
| 票据存活检查 | `enabled() && !locked() && tasks.isCurrent(owner)` 每 250 ms 复查 | 自动 `STOPPED("stale")` |

`PhoneTasks`：第一次工具调用开始任务，3 分钟无调用结束，停止后 30 秒内拒绝一切调用。

**结论：现有弹窗体系整个绑在"Agent 正在执行的任务"上。** 主动事件（没有任务）走不了它；硬塞进去会破坏它的所有权校验。所以新建独立的 InteractionHub，并且在 DSH 有任务或有问题待答时**主动让路**（`dsh_busy`）。

## 5. 如何记录结果

- `ActionLog`：内存环形缓冲 300 条，**进程一死就没了**；
- `PromptResult.toMap()` 回给 Dart，不落盘；
- 持久化的只有 SharedPreferences 里的开关、App 名单策略、悬浮条偏好；
- 没有事件 ID、没有回执、没有"打开了但用户没回应"这种中间状态。

结论：统一事件结构 + 落盘回执是新建的。

## 6. 后台如何恢复

| 机制 | 现状 |
|---|---|
| Flutter 引擎 | `FlutterEngineCache` 缓存，Activity 销毁后 Dart 继续跑 |
| `DshService` | 前台服务，`START_NOT_STICKY`（被杀不自动重启） |
| 无障碍服务 | 系统管理：进程被杀后系统会重新绑定（强行停止例外，见风险） |
| 开机/升级恢复 | **没有** `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED` 接收器 |
| 定时 | **没有** AlarmManager / WorkManager / JobScheduler |
| 任务状态 | 全在内存，重启即丢 |
| `onInterrupt` | 调用 `stopAll` 停任务 —— 对 DSH 合理；但新闭环**不能**把它当成断开（focusmate 踩过这个坑：按返回键就会触发 onInterrupt） |

其他相关事实：`targetSdk = 28`（没有包可见性限制，`queryIntentActivities` 能列出所有应用）；Android 10+ 的后台启动 Activity 限制与 targetSdk 无关，但**系统绑定的无障碍服务是官方豁免项**。

---

## 7. 历史项目复用分类

| 来源 | 内容 | 分类 | 落在哪 |
|---|---|---|---|
| focusmate `core/AllowPolicy` + `SystemUiClassifier` | 前台包名分类：自身/系统界面/输入法/权限框都算"中性"，不算离开 | **直接复用**（思路+名单） | `loop/EventObserver.kt` 的 `PackageFilter` |
| focusmate `core/SessionStore` | 临时文件 + fsync + 原子改名；"读坏了"和"没有"分开报 | **直接复用** | `loop/LoopStore.kt` 的 `FileLoopStore` |
| focusmate `core/Recovery` | 重启后的恢复决策写成纯函数 | **直接复用**（思路） | `LoopEngine.recover()` |
| focusmate `GuardAccessibilityService` | 事件后复核真实前台；`onInterrupt ≠ 断开` | **直接复用**（原则） | `LoopRuntime` |
| focusmate `companion/SchedulePolicy` | 关闭→免打扰→每日上限→未到点→没话题→用户刚说话；随机只出现在排期，判定保持确定性 | **需要改造** | EventScheduler v1 |
| focusmate `alarm/ExpiryScheduler` + `BootReceiver` | 精确闹钟可降级、闹钟只是"去读盘比对"的提醒；开机/升级后重排 | **需要改造** | EventScheduler 重启恢复 |
| focusmate `companion/DeliveryLog` | 生成/展示/播报分开记，"通知已发 ≠ 已读" | **只参考设计** | 回执语义（`SHOWN ≠ 用户已回应`） |
| focusmate Compose UI、`kotlinx.serialization`、`OpenAiCompatibleClient` | — | **不能复用** | DSH 用 Flutter、自带 `Json`；模型调用必须走 DSH 引擎 |
| dsh-speak-up `lib/host.js` | 必须走 `agent.followup(message)` 才是 DSH 自己的回合；先本地掷骰再花模型；`ctx.interval` 定时；followup 后 `sessions.flush` | **需要改造** | AgentBridge v1（宿主侧半边）、EventScheduler 随机触发 |
| test `companion-voice-assistant` | 无障碍截图缩放、系统语音识别/TTS | **只参考设计** | 以后的语音/看屏；DSH 已有 `ScreenShooter` |
| test `companion-voice-assistant` `PhoneAccessService`、`ModelClient` | 不收集事件；直连模型 | **不能复用** | — |
| test `agent-bridge`（Windows 桌面） | — | **不能复用**（平台不同，未深入审计） | — |
