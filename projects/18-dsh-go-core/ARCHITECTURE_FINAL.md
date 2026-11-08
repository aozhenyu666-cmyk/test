# ARCHITECTURE_FINAL（实际落地）

本文只写**代码里真实存在**的结构，不写计划。上游 `docs/loop/ARCHITECTURE.md` 是原型计划，
`docs/ROUND2.md` 是 Claude 的内核设计；这里是合并后的实际状态。

## 1. 两条链路并存

```
【第一轮闭环】触发测试
无障碍窗口事件
  └─ PhoneA11yService.onAccessibilityEvent
       └─ LoopRuntime.onEvent            (350ms 去抖)
            └─ LoopRuntime.verifyNow      (复核前台包名，只读包名)
                 └─ LoopEngine.onForeground
                      ├─ ForegroundTracker（访问状态机）
                      ├─ TriggerRules.decide（开关/灭屏/锁屏/DSH忙/未关页面/时钟/免打扰/上限/冷却）
                      ├─ Suppose ──▶ 回执 SUPPRESSED
                      └─ Show ──▶ InteractionHub.show
                                    ├─ InteractionActivity（3s 内 decor 可见才算 SHOWN）
                                    └─ 3s 未可见 ──▶ 通知兜底（dsh-loop 渠道）
                                                      └─ 回应/划掉/超时 ──▶ LoopEngine.answer
                                                                             └─ state.json（原子写 + .bak）

【第二轮内核】主动搭话
随机闹钟 / 窗口活动
  └─ ProactiveScheduler.evaluate（确定性判定；随机只在 plan）
       └─ ProactiveRuntime.submit(EventRequest)
            └─ Coordinator（队列/合并/丢弃/去重/令牌/让路）
                 ├─ Gate（只读 PhoneTasks / PromptArbiter / ProtectedPages / 锁屏 / 通知可用）
                 ├─ needsAgent ──▶ AgentPort ──▶ dsh/proactive/agent ──▶ Dart
                 │                                       └─ POST/GET 到 dsh-go-proactive 插件
                 │                                            └─ agent.followup ──▶ 真回合
                 │                                                 └─ poll 取文字
                 └─ Present ──▶ ProactivePageActivity（可见才算 Shown）/ NotificationPortImpl（永不 Shown）
                                  └─ 回应/超时 ──▶ Coordinator ──▶ Engagement ──▶ 调度器决定下次
                                                       └─ coordinator.json / scheduler.json（原子写 + .bak）
```

**两套事件结构的边界**：第一轮用 `loop/LoopEvent`（收据语义），第二轮用 `loop/core/ProactiveEvent`
（仲裁语义）。两者用 `eventId` 对齐——D5（待审）就是把第一轮的 `Show` 交给内核，让 `eventId`
成为同一条链路。当前候选版里两条链路**各自独立运行、互不干扰**，这是有意的：D5 未审不合并。

## 2. 关键不变量（都有测试）

| 不变量 | 落点 | 测试 |
|---|---|---|
| 页面"创建成功"≠"用户看见" | `InteractionActivity` / `ProactivePageActivity` 的可见性证明 | `LoopHardeningTest`、`KernelRiskTest` |
| 通知"发出"≠"用户看见"，通知永不报 `Shown` | `NotificationPortImpl` | `KernelRiskTest.postedIsNotShown…` |
| 一个事件只被回答一次 | `LoopEngine.answer` 幂等 + `Coordinator` 令牌 | 两边测试 |
| 旧令牌/旧页面不能回答新事件 | `Coordinator.onSurface` 只认当前 token | `KernelRiskTest.aStaleToken…` |
| 写盘失败不丢上次好状态 | `FileLoopStore` / `AtomicJsonFile` 的 `.bak` + 原子 move | 6 项测试 |
| 读坏了不等于没存过 | 两者都区分 `Unreadable` 与 `Empty` | 2 项测试 |
| Agent 端口未就绪不等于失败 | `ProactiveRuntime.holdAgent/flushHeldAgent` + `onAgentPortReady` | `KernelRiskTest.anAgentRequestInsideItsDeadline…` |
| 模型失败不伪造文字 | 内核：无 `fallbackText` 就失败；`DeliveryGuard` 一律 `Fallback` | `KernelRiskTest`、`LoopHardeningTest` |
| DSH 操作手机时让路 | `Gate` 只读，不取所有权 | `CoordinatorTest` 25 项 |

## 3. 通道清单

| 通道 | 方向 | 用途 |
|---|---|---|
| `dsh/loop`（Method） | Dart → Kotlin | `status` / `setConfig` / `events` / `clearEvents` / `clearDiagnostics` / `apps` |
| `dsh/proactive`（Method） | Dart → Kotlin | `status` / `setConfig` / `receipts` / `agentHttp` / `userActivity` / `tick` |
| `dsh/proactive/agent`（Event） | Kotlin → Dart | 每条 `{op, requestId, path, body?}`，`op ∈ {request, poll, cancel}` |

## 4. 隐私边界（代码层面）

- 前台识别只读**包名**：`LoopRuntime.foreground()` 取窗口根节点 `packageName`，
  不读 `text`/`contentDescription`/节点树内容。
- 交给 Agent 的 payload 只有事实：`AgentWire.requestBody` + `LoopRuntime.submitToKernel` 的
  `packageName`、`localTime`；`AgentContext.of()` 只给 `eventId/source/packageName/timestamp/visitId`，
  有测试断言"没有任何屏幕字段"。
- 默认关闭：`LoopConfig.enabled = false`、`SchedulerConfig.enabled = false`。
- 通知都是 `VISIBILITY_PRIVATE`，锁屏只显示中性标题。

## 5. 未接通的环节（如实标注）

| 环节 | 现状 |
|---|---|
| D5：第一轮事件走内核 | 补丁已出，**未合并**，等 Claude 审 |
| 通知的真实投递 | 代码完整，无设备证据（NOT_TESTED） |
| rootfs 打包 | 脚本已改，未在 Linux 上跑（NOT_TESTED） |
| 真实模型 | 插件侧只在 Claude 沙箱用假模型验证；本机无 Key（NOT_TESTED） |
| 目标会话自动选择 | 未做；要求用户指定（`GET /status` 列会话，`POST /config` 保存） |
