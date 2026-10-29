# ProactiveEngine V1 实施计划（同桌 app）

日期：2026-10-29
仓库：`aozhenyu666-cmyk/test` → `companion-pet/`
分支：`feat/proactive-engine-v1`（从 `claude/r21-strict-takeover` @ `af271d7` 切出）
APK：`cn.companion.pet` vc45 / 0.8.5

---

## 一、先纠正任务描述里的一个前提

任务书要求修 focusmate 的 8 个缺陷。我在两个仓库都做了核实，结论必须说清楚：

**focusmate 确实有那 8 个缺陷，全部属实。** 我逐条验证：
- `CompanionConfig.kt`：`allowSmallTalk=false`、`dailyMax=3`、`gapMin=90min/gapMax=240min` ✅
- `CompanionRuntime.kt:565`：`roll = 0.5` 写死 ✅
- `WakePlanner.kt`：`minOfOrNull { ... } ?: return null` —— 没有 PENDING 事项就取消所有唤醒 ✅
- `CompanionRuntime.maybeSmallTalk`：`FactEngine.recall(...).firstOrNull() ?: return` —— 无种子直接放弃 ✅
- `CompanionScreen.kt:86`：`runtime.onUtterance` 只在 Compose `LaunchedEffect` 里绑定 ✅
- `Notifier.kt`：`manager.notify(ID_PARTNER, n)` 固定 ID ✅

**但同桌（companion-pet）没有这些问题。** 它走的是另一套设计：
- 频率用 `partnerPerHour`（默认 20，范围 5–40），不是"每天 3–6 次"
- 有 `ProactiveGovernor` 按 usage context 加权、有连续忽略退避、有每小时硬上限
- 有 `SayingLedger` 防重样、`ThreadBook` 话题线

**所以本任务在同桌上的真实缺陷是另外两条**（我实测确认）：

### 缺陷 A：完全没有随机性

`ProactiveGovernor.decide()` 的间隔是纯确定性计算：

```kotlin
val gap = (baseGapMillis * gapMultiplier(ctx) * silencePenalty() * importanceFactor).toLong()
lastShownAt?.let { if (now - last < gap) return silent(...) }
```

`baseGapMillis = 3_600_000 / targetPerHour`。20 次/小时 → **固定每 3 分钟一次**。
这违反了任务书核心要求："触发时间不能固定，也不能机械均匀分布""随机性必须具有真实行为变化，而非简单把固定提醒增加几个随机秒数"。

### 缺陷 B：消息投递依赖 UI，后台发不出去

`PartnerConversation.deliver()` 的两条路径都要求：

```kotlin
if (!pageVisible) {
    val shown = displayConfirmed?.invoke(text) == true   // overlay 气泡
}```

`displayConfirmed` 由 `PetBrain` 在 `start()` 里绑定到 `overlay.showBubbleConfirmed`。**没有 Notification 通道。**
这正是用户长期反馈"它明明在想但从来不说"的根因之一——页面/悬浮窗一旦不可用，生成成功也投不出去。

### 缺陷 C：没有独立持久化的投递状态

`deliver()` 返回 `Boolean`，无法区分「生成成功 / 通知发布成功 / 实际展示 / 用户已读」。
任务书明确要求这四种状态必须分开。

---

## 二、实施范围（只动主动引擎，不碰专注/无障碍）

**明确不改的：** `core/focus/*`、`overlay/FocusOverlay.kt`、`a11y/*`、`core/training/*`。
守护与拦截链路已完成并有真机证据，这次一行动。

**新增（`core/proactive/`，纯 Kotlin，零 Android 依赖，可 JVM 测）：**

| 文件 | 职责 |
|---|---|
| `ProactiveMode.kt` | 三档频率模式（日常 8/h、高频 12/h、实验 20/h） |
| `RandomTriggerEngine.kt` | 概率摇骰：真随机、突发限流、最小/最大间隔、沉默后恢复 |
| `ContextTriggerEngine.kt` | 情境候选（完成任务/久未回应/未完话题/约定到期/旧话题/退出专注/轻闲聊） |
| `AutonomousConversationEngine.kt` | 话题类型轮换 + 最近话题去重，避免只读第一条记忆 |
| `InteractionPolicy.kt` | 回应频率仲裁：常回应→提机会，连续不回应→退避 |
| `MessageDispatcher.kt` | （接口层）投递状态机：GENERATED / PUBLISHED / DISPLAYED / READ |

**新增（Android 侧）：**

| 文件 | 职责 |
|---|---|
| `notify/ProactiveNotifier.kt` | 独立通知通道，**每条消息独立 ID**，不依赖 Activity/Compose |
| `core/proactive/DeliveryRecordStore.kt` | 投递记录持久化，重启可恢复待发 |

**修改：**
| 文件 | 改动 |
|---|---|
| `ProactiveGovernor.kt` | 间隔改为调用 `RandomTriggerEngine`，删除确定性 gap |
| `PartnerConversation.kt` | `deliver()` 增加通知通道，UI 只作为可选展示 |
| `data/Settings.kt` | 增加 `proactiveMode`、`proactiveRateOverride` |

---

## 三、随机触发算法（核心要求：非均匀、有真实行为变化）

不用"固定间隔 + 随机秒数"。用**双过程模型**：

### 3.1 基础：对数正态间隔

人的说话间隔是右偏的（常说短句、偶尔长时间沉默），用对数正态：

```
median = 3_600_000 / ratePerHour
sigma  = 0.8
gap = median * exp(sigma * gauss(random))
```

20/h → median 3 分钟，但实际分布从 40 秒到 15 分钟都有。

### 3.2 突发：伯努利连发

每次开口后，以 `burstProbability`（默认 0.15）概率进入"连说"状态，
再连说 1–2 句，间隔压到 `minGapMillis`。这模拟"想到一件有意思的事接着说"。

### 3.3 沉默恢复

若距上次开口 > `resumeAfterMillis`（默认 90 分钟），下一次间隔乘 `resumeFactor`（0.5），
避免"冷了就再也不热"。

### 3.4 硬约束（不可被随机突破）

- `minGapMillis` = 45 秒（硬下限，防刷屏）
- `maxGapMillis` = 25 分钟（硬上限，防"沉默到像死了"）
- `burstWindowMillis` 内最多 `burstMax` 次（默认 10 分钟 4 次）
- 专注中 / 免打扰 / 通话 / 用户暂停 → 一律不说，且不消耗随机次数

---

## 四、消息投递状态机（最高优先级）

四种状态严格分开，不许互相冒充：

```
GENERATED  → 模型返回非空正文
PUBLISHED  → NotificationManager.notify() 未抛异常，且通知通道未禁用
DISPLAYED  → 悬浮气泡实际 attach（overlay 回报）或聊天页 pageRendered 回报
READ       → 用户点了通知 / 打开了对应会话
```

关键规则：
1. **生成成功 ≠ 已送达。** 只有 `PUBLISHED` 或 `DISPLAYED` 才算"说出去了"。
2. **通知是主通道，UI 是可选。** 通知失败再退到悬浮气泡，两者都不成 → `FAILED` 并留痕。
3. **每条消息独立通知 ID**（`base + seq`），不覆盖上一条。
4. 失败重试最多 3 次，指数退避；重启后从磁盘恢复待发，**过期不补发**（超过 `lateTolerance` 直接标 `EXPIRED`）。

---

## 五、测试清单（对应任务书第七章）

全部在 `app/src/test/java/cn/companion/pet/core/proactive/`：

| # | 测试 | 验证点 |
|---|---|---|
| 1 | `RandomTriggerEngineTest` | 不同种子 → 不同触发分布（断言分布差异 > 阈值） |
| 2 | 同上 | 60 分钟模拟：记录全部候选与实际触发，断言触发次数落在合理区间 |
| 3 | 同上 | 无待办时仍有闲聊机会（`SMALL_TALK` 候选永远可用） |
| 4 | 同上 | 连续不回应 → 间隔单调拉长（退避正确） |
| 5 | 同上 | 硬约束：任何种子都不产生 < 45 秒的间隔 |
| 6 | `ContextTriggerEngineTest` | 七种情境各自能产生候选 |
| 7 | `AutonomousConversationEngineTest` | 无记忆时产出通用闲聊；最近话题不重复 |
| 8 | `InteractionPolicyTest` | 常回应→机会增加；连不回应→退避；专注/免打扰→0 次 |
| 9 | `DeliveryStateTest` | 生成≠送达；通知失败→退到气泡；两者失败→FAILED |
| 10 | `DeliveryRecordStoreTest` | 重启恢复待发；过期不补发 |

**回归：** 现有 836 个测试必须全通过。特别不能碰 focus 的 `FocusEvidenceIntegrationTest`。

---

## 六、Android 后台可靠性（如实边界）

保持现有架构，只补通知通道：

| 项 | 做法 | 不承诺什么 |
|---|---|---|
| 前台服务 | 已有 `PetService`（`isForeground=true`） | 不承诺省电白名单外准点 |
| 本地短间隔 | 已有 1 秒 tick | Doze 下会被推迟 |
| AlarmManager 兜底 | 已有 | 不假设精确闹钟权限 |
| 开机恢复 | 已有 `BootReceiver` | 强行停止后**收不到任何广播**，这点写进 UI |
| 通知权限 | 运行时请求 + 状态检查 | 被禁用时降级到气泡并留痕 |

**不用**高频 WakeLock，**不用**无障碍绕过限制。

---

## 七、执行顺序

1. `core/proactive/` 六个纯 Kotlin 文件 + 单测（不碰 Android，最快闭环）
2. `notify/ProactiveNotifier.kt` + `DeliveryRecordStore.kt`
3. 改 `ProactiveGovernor` 接入 `RandomTriggerEngine`
4. 改 `PartnerConversation.deliver()` 接入通知通道
5. `Settings` 增加模式选择
6. 全量构建：`testDebugUnitTest lintDebug assembleDebug`
7. 真机验证（iQOO 12 已连接）：不打开聊天页，看通知是否真的弹出

---

## 八、交付

- 分支：`feat/proactive-engine-v1`
- PR 到 `claude/r21-strict-takeover`
- APK + SHA-256
- 真机验收结果（含 NOT_RUN 项如实标注）
- 同桌/墨灵接入接口说明
