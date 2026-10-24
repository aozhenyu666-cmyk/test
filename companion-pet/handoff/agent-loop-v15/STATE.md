# A1.5：真正接通自主决策

基线：`deepseek/moling-agent-loop-v1` / `a0677100e7ea017624af4ffb6cd6728813430c9e`。独立分支 `deepseek/moling-agent-fix-v1.5`。
版本：0.3.7 / versionCode 10。只修改 `companion-pet/`，保留 A1 与旧数据/UI/278 个测试。

## 1. 修复内容

### 1.1 新念头/素材不漏触发

- 新增 `AgentEventScanner`：按**事件 ID** 找未处理的 `NOTE`/`MATERIAL`，不再只看 `lastOrNull()` 或 `occurredAt`。
- `EventLog` 提供 append 监听；`PetBrain` 收到事件后做 1.2 秒防抖扫描，服务启动/解锁各补一次初始扫描。
- `AgentTrace` 增加 `eventId`；`AgentTraceStore.processedEventIds()` 跨进程保存已处理 ID（保留 500 条轨迹），同毫秒事件、NOTE 后紧跟 EXCHANGE、重启、删除/撤销都覆盖。
- 删除/撤销由 `EventVisibility` 过滤，不再触发旧事件。

### 1.2 主动性脱离 6–12 分钟搭话节奏

- Agent 触发从 `speak()` 中移出，改为事件监听 + 1.2 秒防抖 + 初始/解锁扫描。
- 运行前检查：自主开关、亮屏、非锁屏、非排除 App、安静时段、预算、无会话/思考/念头草稿。
- `NO_ACTION`、`DENIED`、失败/超时/预算不足不显示气泡，调用 `scheduler.retrySoon()` 把机会还给正常搭话；只有实际执行/需要确认才 `onSpoke()`。
- 没有忙等或每秒轮询；事件监听 + 有限初始扫描。

### 1.3 执行结果再推理

- `AgentDecisionSource` 增加受预算保护的 `report()`；生产 `ModelAgentDecisionSource` 发第二次、且只第二次模型请求。
- 输入只含有界回执：工具、四态状态、短原因、裁剪后的 `data["text"]`；不带动作正文、屏幕原文、Key。
- 输出带固定前缀 `（回执：成功/失败/不可用/已取消）`，避免把“发送请求成功”说成动作成功；失败/超时/预算不足回退本地如实模板。
- `modelAttempts` 记录 1 或 2；没有第三次。

### 1.4 主线程与取消

- `PetBrain` 将事件扫描、上下文构建、`AgentLoop`（含 `ActionRunner` 与轨迹文件 IO）全部放到 `Dispatchers.IO`；UI 更新回主线程。
- IO 块内有 `Looper` 主线程断言，防止回归。
- 锁屏/服务停止/关闭自主模式取消模型、工具和待执行扫描；已完成的副作用如实返回，不声称回滚。
- 取消后不显示迟到气泡（重新检查 active/autonomy/convo）。

### 1.5 授权使用真实信息

- `AgentPolicy` 不再传 `quiet=false`、`lastActionAt=0`、默认 `userActive=true`。
- 使用真实安静时段、前后台/亮屏状态、上次轨迹时间和 `ActionPlanner` 的真实来源校验。
- 模型给的 source ID 必须属于本次 `AgentContext.eventIds`，且事件当前可见；只读仍受遗忘/排除/隐私约束。
- 写入/打开/朗读仍只提议“需要确认”，不自动执行。

## 2. 文件

- 新增：`core/agent/AgentEventScanner.kt`
- 修改：`AgentModels.kt`、`AgentPrompt.kt`、`AgentLoop.kt`、`AgentTraceStore.kt`、`ModelAgentDecisionSource.kt`、`EventLog.kt`、`PetBrain.kt`
- 测试：`AgentEventScannerTest.kt`、扩展 `AgentLoopTest.kt` / `ModelAgentDecisionSourceTest.kt`
- 不新增权限；不启用任意 Shell、A2 截图、A3 Shizuku、A4 长期目标。

## 3. 验证

```text
:app:testDebugUnitTest          PASS   288 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 5 warnings
:app:assembleDebug              PASS   28,719,497 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译
```

APK SHA-256：`D1C057A81FAA59863816E7BBB6C6C8A28F00984798AE2A11295C0B74EA433ADA`

新增 10 个 JVM 用例：NOTE 后接 EXCHANGE、同毫秒两条、重启已处理 ID、删除不复活、超过 7 天忽略、事件监听、真实安静/越权来源拒绝、第二次回执模型调用、只够一次预算回退、回执请求契约。基线 278 个测试保留。

## 4. 边界（未执行）

- **真机/模拟器未运行**：事件监听延迟、锁屏取消、真实模型二次回执、设置页轨迹均未设备验证；仪器 APK 只编译。
- 没有真实 API Key 的端到端记录；测试用 fake 模型/Adapter。
- 写入/打开/朗读仍没有确认 UI；只显示“需要确认”，不执行。
- 仍是 JSON 内容契约，不是 OpenAI 原生 tools 字段。
- 没有 A2 视觉、A3 无障碍/Shizuku、A4 持久待办/自发回访。
- 轨迹最多 500 条摘要；不保存原始屏幕、聊天全文、动作正文或 Key。
