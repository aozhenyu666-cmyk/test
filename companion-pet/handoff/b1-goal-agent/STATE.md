# B1：Goal → AgentLoop → ActionRunner → 回执 → GoalStore

基线：`deepseek/moling-b0-integrity` / `af71cc1`。分支 `deepseek/moling-b1-goal-agent`。
版本 0.4.2 / versionCode 15。

## 已完成

- `AgentContext` 增加 `goalSummary/goalId/taskId`，`AgentPrompt` 将其作为计划背景（不是授权）。
- `GoalAgentCoordinator`：
  - 用 `GoalSupervisor.decide` 选活动目标/下一步，只对有真实 task 的 READ_TOOL/PROPOSE_WRITE 调既有 `AgentLoop`；
  - 不新建另一个 ActionRunner，不把目标自然语言解释成命令；
  - 回执写成 `TaskStatus.WAITING/BLOCKED`，不因动作已发出而 DONE；
  - 生成 `AgentEpisode`，引用 goalId/taskId/actionId；
  - 删除/暂停目标后不再执行，不复活。
- `PetBrain`：存在活动目标时优先走 Coordinator；否则仍走原 AgentLoop。全部在 IO 执行，UI 回主线程。
- 没有自动宣布 COMPLETE；仍需成功标准或用户确认（本轮未实现确认 UI，B2）。

## 验证

```text
:app:testDebugUnitTest          PASS   317 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 13 warnings
:app:assembleDebug              PASS   28,862,375 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译
```

APK SHA-256：`590CC65DC10B9B249AEA03DB11116DEE1FC99976BB647CFEC061455751F0653D`

新增 4 个 B1 集成用例：goal→read→receipt→WAITING/episode、暂停不执行、预算不足不执行、删除不复活。基线 313 保留。

## 边界

- 无真机/真实模型：整条闭环只在 JVM fake source/Adapter 验证，NOT_RUN。
- 写动作仍只返回 NEEDS_CONFIRMATION 文本，没有 B2 的待确认面板；因此不会自动执行写。
- 任务成功只代表只读回执；不把“动作已发出”当目标完成。
- 没有通知/后台调度；B4 处理。
