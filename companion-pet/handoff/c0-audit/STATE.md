# C0：独立审计分诊与定点修复

基线：deepseek/moling-b4-e2e-release / fcc2e61。分支 deepseek/moling-c0-audit-fixes。
版本 0.5.1 / versionCode 19。

注意：本轮未收到 Claude 审计报告文件；以下为对源码的重新审计，不声称读过不存在的外部报告。

## 审计表

| ID | 严重度 | 路径 | 结论 | 处理 |
|---|---|---|---|---|
| C0-1 PendingAction 未绑定 observationId | P1 | AgentLoop/PendingAction | CONFIRMED：确认动作可没有观察来源 | 已加 AgentContext.observationId，创建 PendingAction 时保存 |
| C0-2 APPROVED 后崩溃无法安全恢复 | P1 | PendingActionStore | CONFIRMED：批准后执行前崩溃无 UI 出口 | 新增 reopen（仅 APPROVED，重设 nonce/TTL，需重新确认）；UI“重新确认” |
| C0-3 参数可被改而批准不察觉 | P1 | PendingAction | CONFIRMED：无参数指纹 | 新增 argsHash，create 时计算，approve 时核对 |
| C0-4 GoalStore 假成功/损坏覆盖/容量逐出 | P0 | GoalStore | 已在 B0 修复；C0 复核通过 | 保留 313 基线测试 |
| C0-5 DevicePolicy 前台/节点/可编辑/bounds | P1 | DevicePolicy/AndroidDeviceController | 已在 B0 修复；C0 复核通过 | 保留策略测试 |
| C0-6 AgentEventLedger 超限重复触发 | P1 | AgentEventLedger | 已在 B0 修复；C0 复核通过 | 高水位测试 |
| C0-7 截图/视觉/Shizuku 资源与真实调用 | P1/P2 | 视觉/Shizuku | UNVERIFIED：无设备/真实 API | 保持 NOT_RUN，C2 处理 |
| C0-8 写动作仍不自动执行 | P2 | B2 PendingAction | 设计如此；批准后执行 | 保留安全边界 |

## 修复

- AgentContext 增加 observationId；PetBrain 传递最近有效观察；AgentLoop 创建 PendingAction 时写入。
- PendingAction 增加 argsHash；create 计算，approve 核对；旧记录空哈希兼容。
- PendingActionStore.reopen：仅 APPROVED 可重开，重设 nonce/TTL，必须用户重新确认；UI 显示“重新确认”。
- 未修改 GoalStore/DevicePolicy（B0 已修），未为了清零审计削弱安全策略。

## 验证

:app:testDebugUnitTest          PASS   324 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 13 warnings
:app:assembleDebug              PASS   28,927,907 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译

APK SHA-256：8D4410F047B560CDE4E5DA4C5ED8A8851931E8F77B51CBA00499EF2EB48D8D08

新增 1 个 PendingAction reopen 用例；基线 323 保留。

## 边界

- 无真机/真实 API：C0-7 等设备项 NOT_RUN。
- 未收到外部 Claude 报告；如需逐条对照，请提供报告路径。
- C1 设备诊断页与 C2 端到端验收未完成。