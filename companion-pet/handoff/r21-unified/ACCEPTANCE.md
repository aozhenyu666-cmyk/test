# ACCEPTANCE：R21-A01–A36 与 U01–U12

图例：`PASS-L` = 纯逻辑测试通过；`PASS-I` = 集成测试通过；`NOT_RUN` = 未执行；`DEVICE` = 需真机。
本轮为纯 JVM 环境，**无设备**。

## R21-A01–A36

| ID | 项 | 层级 | 结果 | 证据 |
|---|---|---|---|---|
| A01 | 普通形态无专注契约时沉默 | 逻辑 | PASS-L | `OpportunityTest` 无授权/无训练时抑制 |
| A02 | 多次未回触发严格规则 | 逻辑 | PASS-L | `EnforcementRulesTest` 第 2 次 FORCE_COACH |
| A03 | 严格会话收起/切应用保持 questionId/stepId/deadline | 逻辑 | PASS-L | `TrainingStoreTest` 恢复保槽位 |
| A04 | 「懂了」vs 具体短答 | 逻辑 | PASS-L | `MaterialQuestionTest` / `SessionArbiterTest` |
| A05 | 提示后答对 + 迁移题 | 逻辑 | PASS-L | `QuestionSetSettlementTest` |
| A06 | 云端超时/STT 不可靠 | 逻辑 | PASS-L | `TrainingContinuityRegressionTest.networkFailure...` |
| A07 | 换题后旧响应到达 | 逻辑 | PASS-L | `TrainingControllerTest` 旧 requestId 拒绝 |
| A08 | 重复点击/事件/回执 | 逻辑 | PASS-L | `OpportunityTest.repeatedSettlement...` |
| A09 | 写入后派发前崩溃 | 逻辑 | PASS-L | `TrainingStoreTest` 恢复待执行 |
| A10 | 派发后回执前崩溃 | 逻辑 | PASS-L | `CapabilityHealthTest` UNKNOWN 保留 |
| A11 | 回收进程后恢复断点 | 逻辑 | PASS-L | `TrainingContinuityRegressionTest` |
| A12 | 强停/重启/改时钟 | — | DEVICE | 需真机 |
| A13 | 服务连着但执行队列卡住 | 逻辑 | PASS-L | `CapabilityHealthTest` 入口回显拒绝 + 超时 |
| A14 | Shizuku Binder 消失 | 逻辑 | PASS-L | `CapabilityHealthTest` BINDER 层 |
| A15 | 三方同时控制 | 逻辑 | PASS-L | `SessionArbiterTest` 互斥 |
| A16 | 退出同时迟到动作 | 逻辑 | PASS-L | `TrainingControllerTest` 中止后丢弃 |
| A17 | 休息期间重启/重复消费 | 逻辑 | PASS-L | `RestGrantTest` 幂等 + 到期 |
| A18 | 计划到期但延迟调度 | — | NOT_RUN | S1 未实现 |
| A19 | 升级且存在未完会话 | 逻辑 | PASS-L | `TrainingStoreTest` 旧快照带默认值 |
| A20 | 外部 Gemini 停留但无内容回传 | 逻辑 | PASS-L | 只记前台事实（无认知通过） |
| A21 | 导入材料后不主动开聊 | 逻辑 | PASS-L | `OpportunityTest` + `MaterialOpportunityIntegrationTest` |
| A22 | 两机会选一 | 逻辑 | PASS-L | 已有训练优先接续 |
| A23 | 同一事件重复 | 逻辑 | PASS-L | `OpportunityTest.sameEventRecordedOnlyOnce` |
| A24 | 材料过期/无授权 | 逻辑 | PASS-L | `OpportunityTest` |
| A25 | 安静时段/系统禁止 | 逻辑 | PASS-L | `OpportunityTest.quietOrProtected...` |
| A26 | 收起后接续 | 逻辑 | PASS-L | `TrainingContinuityRegressionTest` |
| A27 | 连续「我在想」 | 逻辑 | PASS-L | `ExtensionPolicyTest` 次数+累计双上限 |
| A28 | 连续不回应到上限 | 逻辑 | PASS-L | `TrainingTimerTest` + `EnforcementRulesTest` |
| A29 | 三个低质量主动问题 | 逻辑 | PASS-L | `OpportunityTest` 冷却降频 |
| A30 | 空泛/复述/答非所问 | 逻辑 | PASS-L | `GoldenSetTest` 类别 1–3 |
| A31 | 准确简短/题目歧义 | 逻辑 | PASS-L | `GoldenSetTest` 类别 4/7 |
| A32 | 泄露后复述 → 迁移题 | 逻辑 | PASS-L | `QuestionSetSettlementTest` NeedsTransfer |
| A33 | STT 截断/缺字段 | 逻辑 | PASS-L | `SemanticConfirmTest.parseHandles...` |
| A34 | 断网有本地题 | 逻辑 | PASS-L | `BuiltInQuestionsTest` + 离线判分覆盖全部内置题 |
| A35 | 线程卡死但 Binder 活 | 逻辑 | PASS-L | `CapabilityHealthTest` 拒绝入口回显 |
| A36 | 到期/退出同时迟到回调 | 逻辑 | PASS-L | `TrainingControllerTest` |

**DEVICE / NOT_RUN**：A12（真实强停重启）、A18（计划触发未实现）。其余真机行为均未在手机上验证。

## U01–U12

| ID | 项 | 结果 |
|---|---|---|
| U01 | 连做五题不停 | **本轮修复 D03**，逻辑层可用；连续五题端到端未跑 |
| U02 | 训练+严格双存档重启 | **部分**：各自恢复已实现，统一仲裁未接 |
| U03 | 挑战及时/无响应/迟到/重复/重叠 | **本轮修复 D01**，逻辑 PASS |
| U04 | 断连重连/未知 Binder/API 成功未观察 | **本轮修复 D02 的层次**，真机未验证 |
| U05 | 第三题重启/第五题崩溃 | **本轮修复 D04**，逻辑 PASS |
| U06 | 泄题后复述再迁移 | **本轮修复 D04 的 answerRevealed**，逻辑 PASS |
| U07 | 进入休息、切受限应用、到期 | **未接**：许可未驱动 FocusController |
| U08 | 90 秒宽限/重启/超上限录音 | **本轮修复 D05**，逻辑 PASS |
| U09 | 断网完成五题 | **本轮修复 D06**，离线覆盖全部内置题 |
| U10 | 计划重复/迟到/修改/撤销 | **NOT_RUN**：S1 未实现 |
| U11 | 收起/切走/恢复条件 | **部分**：被动接续已有，主动恢复未做 |
| U12 | 到期/退出旧回调 | 逻辑 PASS |

## 未完成（明确）

1. **R21-S1 计划触发**：完全未实现。
2. **主动断点恢复**：只有被动接续。
3. **U07 休息驱动实际专注暂停**：`RestGrant` 只落盘，未交给 FocusController。
4. **Claude 的 CL-T5/T6/T11/T12、CL-S1–S8**：待移植。
5. **48 条人工黄金样例**：仅整理候选，未独立审阅。
