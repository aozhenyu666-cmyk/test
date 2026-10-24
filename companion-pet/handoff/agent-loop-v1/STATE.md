# A1：把大脑接到已有的“手”上

基线：`7124768104a423c9c1fe09e2ae4b23995eeefbbf` / 0.3.6 code9。分支 `deepseek/moling-agent-loop-v1`。
版本：0.3.7 / versionCode 10。只修改 `companion-pet/`。

## 0. 一页审计：现有调用链与缺口

**已有链路**

1. `PerceptionService` → `PerceptionHub` 记录前台/滚动/停滞；`PetBrain` 的 ticker + `SpeakScheduler` 决定何时开口。
2. `PetBrain.speak()` → `ProbePrompt/CompanionContextAssembler` → `DeepSeekClient.complete()` → `ProbeReplyParser` → 气泡；用户回复走 `replyRound()`。
3. `ActionRunner` + 五个 `AndroidActionAdapters` + `ActionSession` + `FileActionJournal` 已经能执行、幂等、取消、跨重启核对，但**只由设置里的能力面板手动调用**。
4. `ActionPlanner` 已有来源检查，但只作为 dry-run 组件，`PetBrain` 从未调用；`Settings` 没有自主开关，也没有决策轨迹。
5. 预算 `AtomicBudget` 只包住聊天/夜间请求；模型没有工具 schema，工具结果也不会回到任何决策层。

**缺口**

- 模型看不到可用工具，不能选工具；工具执行与模型完全脱节。
- 没有事件驱动的“决策 → 执行 → 回执 → 说明”闭环。
- 没有只读自动/写入需确认的授权分层；没有轨迹、限流、超时、取消。
- 没有把工具回执反馈给决策层，也没有把动作结果写进可核对记录。

## 1. 本批实现（A1）

新增 `core/agent/`：

- `AgentModels.kt`：AgentContext/Decision/TurnStatus/Authorization/报告；只读可自动，写入/打开/朗读需确认。
- `AgentPrompt.kt`：工具 schema + JSON 解析；未知工具、坏 JSON、坏字段都判 Invalid，不猜。
- `AgentLoop.kt`：一次决策 → 授权 → 复用 `ActionRunner` 执行一次 → 回执生成一句可核对的话 → 写轨迹；单轮最多 1 个工具；15s 模型超时；支持取消。
- `AgentTraceStore.kt`：`memory/agent/traces.jsonl`，只存触发/工具/状态/短原因，不存屏幕原文、聊天全文、动作正文或 Key。
- `ModelAgentDecisionSource.kt`：OpenAI 兼容 Chat Completions，输出 JSON；模型调用前由 `AgentLoop` 预留 `AtomicBudget`。

接线：

- `Settings.agentAutonomy`（默认关）。
- `PetApp`：全局 `agentTrace` / `agentLoop` / `agentModelConfig()`。
- `PetBrain`：新的 `MATERIAL` 或用户 `NOTE` 事件、且晚于上次轨迹时，走一次 agent 决策；不发散循环；锁屏/取消时结束。
- `SettingsTab`：自主开关 + 最近 5 条决策轨迹（刷新）。
- 复用现有 `ActionRunner`/`ActionSession`/账本/适配器，不另造执行器；没有新增 Android 权限。

**关键安全边界**

- 自主模式只自动执行 `READ_MEMORY` / `READ_MATERIALS`。
- `SAVE_NOTE` / `OPEN_ASSISTANT` / `SPEAK` 只生成“需要你确认”的提议，不执行；现有设置（TTS 开关、AI App 配置）仍是硬条件。
- 模型输入只含场景标签、可见记忆摘要、素材标题、事件 ID；不含原始屏幕、完整聊天或 Key。
- 没有新权限、没有 Shell、没有自动评论/私信、没有 A2 截图/A3 Shizuku/A4 长期目标。

## 2. 验证

```text
:app:testDebugUnitTest          PASS   278 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 5 warnings
:app:assembleDebug              PASS   28,634,093 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译
```

APK SHA-256：`0369BE8F808A3CB82C89FAC0A687F4EDEA977489B79F6D5C32173EB2404B322F`

新增 14 个 JVM 用例：工具执行与回执、no_action、坏 JSON/坏字段、预算不足、写入需确认、来源拒绝、超时、取消、重复只读快照、轨迹重启、关闭模式、模型源请求契约。基线 264 个测试保留，原断言未改。

## 3. 未执行 / 边界

- **没有真机/模拟器**：真实模型自主选工具、气泡展示、锁屏取消、设置页轨迹均未设备验证；仪器 APK 只编译。
- 没有真实 API Key 的端到端模型决策记录；测试用 fake 决策源。
- 模型输出的是 **JSON 内容契约**，不是 OpenAI 原生 tools 字段；仍是真实 HTTP 请求，但需要模型按提示返回 JSON。
- 写入/打开/朗读没有确认 UI；本轮只记录“需要确认”，不自动执行。
- 单轮只有 1 次决策 + 1 个工具；没有 A2 视觉、A3 Shizuku、A4 长期目标/自发回访。
- 轨迹是摘要，不是完整审计日志；敏感内容只留在原有记忆/素材存储，不进轨迹。
