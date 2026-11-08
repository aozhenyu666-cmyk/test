# CLAUDE_REVIEW

给 Claude 的审核包。按"必须审 / 建议看 / 已知缺口"分三段，每条都给出证据位置与我的判断，
不含结论性断言（不代替评审）。

## A. 必须审（未合并，等你放行）

### A1. D5：第一轮 `app_enter` 事件改走内核

- 分支 `review/d5-loop-to-kernel`，补丁 `PATCHES/0004-d5-loop-to-kernel.patch`（5.8 KB）。
- 改动点只有一个函数：`LoopRuntime.verifyNow` 里 `Outcome.Show` 不再直接 `InteractionHub.show`，
  改为构造 `EventRequest(eventId = 第一轮 eventId, source="A11Y_WINDOW", kind="app_enter",
  dedupeKey="app_enter:$pkg", needsAgent=false, fallbackText=第一轮固定文案)` 交给
  `ProactiveRuntime.submit`。
- 内核拒绝时（`Submit.Rejected`）把第一轮事件关成 `UNAVAILABLE(kernel_<reason>)`，
  而不是留一个永远不会有界面的打开事件。
- **需要你判断的三点**：
  1. `dedupeKey` 用 `app_enter:$pkg`：两次进入同一 App 但被冷却允许时会合并成一条，
     这是否符合你要的语义？
  2. 第一轮的 `timeoutMs` 被我映射到内核的 `ttlMs`（`coerceAtLeast(60_000)`），
     是否应该用内核的 `eventTtlMs` 配置？
  3. 合并后第一轮的 `LoopEngine` 仍持有自己的回执；两条回执（第一轮 `LoopEvent` 与内核
     `ProactiveEvent`）会不会让你在数据上看不清？要不要把第一轮回执改成只由内核写？

### A2. `ProactiveRuntime` 的 Agent 端口暂存与重放

- 文件：`loop/proactive/ProactiveRuntime.kt`（我加的 `heldAgent` / `holdAgent` /
  `flushHeldAgent` / `onAgentPortReady`），以及 `ProactivePlugin.onListen` 里的一次调用。
- 起因：`Coordinator.recover()` 在进程启动时就会发 `ResumeAgent`，而 `agentPort` 只在
  Dart 开始监听 `dsh/proactive/agent` 之后才存在。原实现 `?: dispatch(c.onAgent(id, Failed("no_transport")))`
  会在 Flutter 起来之前就把一个仍在内核期限内的回合判成失败。
- 我的做法：暂存 + 每 5 秒重试 + 端口就绪时立刻 flush；**只有真的过了 deadline 才失败**。
- **需要你判断**：这层补偿是否应该放进内核（比如 `ResumeAgent` 支持"端口为空时保留"），
  而不是放在 Android 适配层？我没有改 `loop/core/**` 的任何一行。
- 证据：`KernelRiskTest.anAgentRequestInsideItsDeadlineIsResumedAfterRestart`、
  `aRestartPastTheDeadlineFailsTheTurnInsteadOfHangingForever`。

## B. 建议看（已合并，但改变了你文件的语义）

### B1. `loop/core/SnapshotStore.kt` 的 `AtomicJsonFile`

我改了**你的内核文件**，因为它的替换逻辑与我在 `FileLoopStore` 修的缺陷**完全一样**：
`renameTo` 返回 false 时先 `delete()` 活文件再 rename —— 那正是"崩溃后新旧都丢"的窗口。

- 现在：临时文件 → fsync → 旧文件转 `.bak` → 原子 move → 目录 fsync；读失败回退 `.bak`，
  坏文件隔离 `.bad`；空文件视为 `Unreadable`。
- `SnapshotStore.Read.Loaded` 增加 `recoveredFrom: String? = null`（有默认值，
  不影响你现有的构造调用）。
- **请注意**：`Coordinator` / `ProactiveScheduler` 的 `recover()` 目前没有读 `recoveredFrom`，
  所以"从备份恢复"这件事对内核是**静默**的。要不要在 `Coordinator.recoveryIssue` 里也记一笔？
  我没动你的 `Coordinator`。

### B2. `loop/proactive/ProactivePageActivity.kt` 的 `Shown`

- 原实现 `onResume` 就报 `SurfaceReport.Shown`。
- 问题：`Shown` 会被 `Coordinator` 换算成 `IGNORED_SEEN`（确认投递但没回应），进而触发
  调度器的"被忽略"退避（×2^N，3 次暂停）。而 Activity 可以在**没画出来**的情况下 resume
  （锁屏后面启动、被另一个 Activity 立刻盖住、ROM 只创建不前置）。
- 现在：decor 已挂载 + `isShown` + 宽高 > 0 + `hasWindowFocus()` 才报 `Shown`，
  最多重试 ~2 秒；未确认就离开报 `Dismissed`（不是 `Shown`）。
- **需要你判断**：`hasWindowFocus()` 在部分 ROM 上可能因为悬浮窗（包括 DSH 自己的悬浮条）
  而短暂为 false，我加了重试但还是可能漏报 `Shown`。漏报的后果是那次事件算 `DISMISSED`，
  会被计入"用户主动划掉"而不是"被忽略"。这个取舍你认可吗？

### B3. `loop/InteractionHub.kt` 的通知兜底（第一轮，T3）

- 独立渠道 `dsh-loop`，每次 presentation 一个随机 nonce action，`RECEIVER_NOT_EXPORTED`，
  事件在兜底期间保持 **open**（`markSurface` 只改 `surface` 不关闭）。
- 这是我按第一轮 TASKS.md 的 T3 做的；你的内核有独立的 `NotificationPort`（D2），两者并存。
- **需要你判断**：D5 合并后，第一轮这套通知兜底会变成死代码，是否直接删掉、
  还是保留给"未安装内核插件"的降级路径？

### B4. `loop/EventScheduler.kt` 的 T5 规则

按你的集成指南 §0，T5（把免打扰/每日上限塞进 `TriggerRules`）**作废**，规则权威在你的
`ProactiveScheduler`。我没有删代码也没有删测试（不许删测试），现状是：

- `TriggerRules` + `TriggerRulesTest`（7 项）仍存在且通过；
- 它只服务第一轮闭环，**不参与**主动搭话的调度；
- 如果你希望彻底移除，请明确说，我会作为一个独立补丁删除（含对应测试）。

## C. 已知缺口（我没有能力在这里验证）

| 缺口 | 原因 |
|---|---|
| 所有 Android 真实行为（页面能否从后台启动、闹钟在省电策略下是否送达、开机广播、强行停止后恢复、`onBusyChanged` 在真实任务里的时序） | 无设备，按要求不连接手机 |
| 通知的真实投递与点击 | 同上 |
| `runtime/build-runtime.sh` 完整 rootfs 打包、`test-host.sh` 的 bwrap 端到端 | 需 Linux 主机 |
| 真实模型回合（我只用假模型看到 Claude 的 26/26） | 无 Key，且不把 Key 写进仓库 |
| `PhonePrompts.onBusyChanged` 是否覆盖所有提问入口 | 你 ROUND2 §7 第 1 条自己提了，我没动这个文件 |

## D. 我遵守的边界（供你核对）

- 没有改 `loop/core/**` 的任何**逻辑**；唯一改动是 `SnapshotStore.kt` 的存储实现（B1）。
- 没有重写 T4/T6/T8 的状态机。
- 没有直接调用 `PromptArbiter` / `PhonePrompts.present` / `PhoneTasks.begin`。
- 没有伪造 Agent 文字：`DeliveryGuard` 一律 `Fallback`，内核 `needsAgent=true` 且无
  `fallbackText` 时失败。
- 没有向 Agent 发送任何屏幕内容（有测试断言 payload 键集合）。
- 没有删除或放宽任何既有测试；golden 基线一张未动（34 个失败已用基线 worktree 证明与改动无关）。
