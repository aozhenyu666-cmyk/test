# 任务拆分（给 DeepSeek 执行）

规则：
- 每个任务一个分支、一个 PR，基于 `feat/event-loop-v1`，**不要直接改上游 `main`**。
- 标了 **🔒 Claude 审核** 的任务，提交前必须让 Claude 审核；其余普通编码 DeepSeek 自己完成并补测试。
- 不许做的事（所有任务通用）：改默认输入法；用 `settings put` 或任何方式绕过系统权限确认；读取页面节点内容（只允许包名）；改 `phone_accessibility_service.xml`、`SafetyGate`、`ProtectedPages`、`PromptArbiter` 的既有行为；删除或放宽任何测试。
- 本地检查：纯逻辑跑 `LoopEngineTest`；Flutter 跑 `flutter analyze` 和 `flutter test test/phone`；动了文案跑 `python3 tool/l10n_merge.py`。
- 没有真机证据的结论写 `NOT_TESTED`。

---

### T0 真机跑 v1 验证清单（人工/DeepSeek 协助）
按 `docs/loop/VALIDATION.md` 第 3 节逐项跑，把 `state.json` 和 `logcat -s DshLoop` 贴进该文件对应行。
验收：每行要么有证据，要么写明 NOT_TESTED 和原因。

### T1 Gradle 接入与构建（普通）
确认 `./gradlew :app:testDebugUnitTest` 能跑到 `loop/LoopEngineTest`，`./gradlew assembleDebug` 通过。
验收：两条命令的输出贴到 PR。只允许修编译问题，不改逻辑。

### T2 设置页补全（普通）
- 超时时间选择（15/30/60/120 秒），走 `setConfig` 的 `timeoutMs`（原生已支持）。
- 回执行显示 `surface`、`visitId` 前 8 位。
- "导出回执"：把 `events` 原样 JSON 复制到剪贴板。
验收：`test/phone/loop_test_page_test.dart` 加对应用例；中英文文案都加在 `lib/l10n/parts/loop_*.arb`。

### T3 InteractionHub 通知兜底（普通，参照 `NotificationGate.showPrompt`）
页面 `UNAVAILABLE(not_shown)` 时改发一条通知（独立渠道 `dsh-loop`），按钮"返回"→ `RETURNED`，划掉 → `DISMISSED`，`setTimeoutAfter` 到时 → `TIMEOUT`。按钮用每次随机 nonce 的动态广播，`RECEIVER_NOT_EXPORTED`。
验收：事件 `surface="notification"`；同一事件只关一次（复用 `LoopEngine.answer` 的幂等）；JVM 测试覆盖"页面没弹出 → 通知 → 回执"的状态序列。

### T4 DSH 任务开始时收起页面 🔒 Claude 审核
监听 `PhoneControl.tasks.addListener`，任务变为 active 时关闭已打开的页面，回执 `DISMISSED(reason=dsh_task_started)`。
原因：触及 `PhoneTasks` 生命周期和监听线程。

### T5 EventScheduler v1：本地规则（普通）
把 focusmate `SchedulePolicy` 的免打扰（跨零点）、每日上限移植进 `TriggerRules`（纯 Kotlin、时区由调用方传 `minuteOfDay`）。
验收：新增 JVM 测试：23:00→08:00 区间、上限当天生效次日清零、时钟回拨。

### T6 随机主动触发 + 重启恢复 🔒 Claude 审核
- 排期：`planNext(now)` 才用随机（抖动），`decide` 保持确定；`nextDueAt` 落盘。
- 闹钟：有精确闹钟权限用 `setExactAndAllowWhileIdle`，否则 `setAndAllowWhileIdle` 并在设置页如实说明会迟到；闹钟只是"去读盘比对"的提醒。
- `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 接收器重排；**强行停止后不会收到广播**，设置页要写明。
- 事件 `source=SCHEDULER`，同样过 `TriggerRules`。
原因：新增 Manifest 接收器和后台唤醒，影响耗电与上架合规。

### T7 AgentBridge v0：接口 + 本地降级（普通）
实现 `docs/loop/ARCHITECTURE.md` 里的 `AgentBridge` 接口，v0 只有 `LocalTemplateBridge`（按包名返回固定文案），页面显示返回文本。
验收：接口与 `LoopEngine` 解耦；超时/异常一律 `Fallback`，回执 `reason=agent_fallback`。

### T8 AgentBridge v1：接入 DSH Agent 🔒 Claude 审核
参照 dsh-speak-up：DSH 宿主插件收事件 → `agent.followup(message)` → 等回合结束取回文本。只传 `eventId/source/packageName/timestamp`，不传任何屏幕内容。
原因：跨进程协议、鉴权、隐私边界。

### T9 披露文案（普通）
`phone_disclosure_sheet.dart` 和无障碍说明里加一句："开启触发测试后，DSH 会读取当前前台 App 的名称（不读页面内容）"。
