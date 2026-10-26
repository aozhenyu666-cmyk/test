# 同桌 R2.1 / S6：授权材料触发的主动推进

日期：2026-10-26。基线 `codex/tongzhuo-r21-core` @ `c9f6970`（32 / 0.7.2-r21-core-preview）。
本轮分支 `deepseek/r21-proactive`，版本 **33 / 0.7.3-r21-s6-preview**。只改 companion-pet/。

## 一、起点（读了三份文档）

- R2（374 行）、R2.1（510 行）：R2.1 在 R2 上增加 S6–S8、A21–A36、48 条黄金样例、三档交付结论。
- `handoff/r21-core/DEEPSEEK_NEXT.md`：Codex 已完成接续修复，**明确把 S6 留给下一轮**，并写明“不要把这次修复描述成主动性已达标”。

Codex 已完成、本轮直接复用的部分（未重写）：
- `TrainingController.bind` 恢复时不重置 `currentSlot`，不匹配拒绝绑定
- `TrainingStore.restore` 保留状态/题目/作答/帮助等级，递增 revision，清旧请求号
- 秒级提示用 `elapsedRealtime`，快照用日历时间；首等与间隔均 60 秒；提示预算随任务保存
- 模型请求绑定不可变快照，迟到回调先核对 controller/revision/requestId
- JSON 缺字段拒绝；PASS 需有本题材料引用且无 uncertainty；帮助等级不能被模型降低
- `OfflineTraining`：只覆盖 `sample-scope-01` v1 两槽，不调用模型、不发券
- 文字输入每次重新绑定一次性 `InputBridge`，解决第二轮输入失效

## 二、本轮新增（S6）

### `core/training/Opportunity.kt`

| 结构 | 作用 |
|---|---|
| `OpportunitySource` | MATERIAL_IMPORTED / MATERIAL_CHECKPOINT / SCHEDULE / RESUME（计划触发与情境主动分开统计） |
| `DeliveryState` | PROPOSED / QUEUED / SHOWN / NOTIFIED / BLOCKED_BY_OS / EXPIRED / SUPPRESSED |
| `ResolutionState` | UNANSWERED / ANSWERED_UNVERIFIED / VERIFIED_PROGRESS / NO_PROGRESS |
| `Opportunity` | opportunityId、sourceEventId、taskId、materialRef、materialVersion、questionId、whyNow、expectedOutcome |
| `OpportunityArbiter` | 本地决定“能不能发”，模型无权决定 |
| `OpportunityStore` | 原子写、按 key 去重、连续无效计数 |

### 仲裁规则（顺序即优先级）

1. 没有本次材料协助授权 → 抑制（`没有本次材料协助授权`）
2. 同一事件已处理 → 抑制（去重）
3. 材料缺失/过期 → 抑制（不编造看见屏幕）
4. 已有进行中训练 → 抑制（优先接续原题，不并行会话）
5. 不允许打扰（安静时段/暂停/锁屏） → 抑制
6. 连续 3 次无有效推进 → 抑制（**降频而不是加码**）

抑制也会写入 `Opportunity` 并带 `suppressionReason`，便于之后回答“为什么它没主动说话”。

### `PetBrain` 接线

- `agentListener` 收到 `EventKind.MATERIAL` → `considerMaterialOpportunity(event)`
- 通过仲裁后用本地题库启动训练（题来自 `BuiltInQuestions`，不依赖云端）
- 气泡显示「你刚分享的「标题」，先看这一处：题干 + 第一问」，并带 `trainingActions()`
- `resolveOpportunity(progress)` 在训练结束时更新结果，维护连续无效计数

### 设置

新增开关 **「材料分享后可以主动问我」**（`settings.materialAssist`，默认**关**）。
产品口径：保存材料本身不等于允许持续打扰，必须显式授权。

## 三、验证

JVM：**514 tests / 0 failures**（基线 504 + OpportunityTest 10）
lint：0 errors / 16 warnings

新增测试覆盖：有授权+材料新鲜→放行；无授权→抑制不编造；材料过期→抑制；已有训练→优先接续；重复事件→抑制；安静/保护→抑制；连续无效→降频；同 key 只记一次；delivery/resolution 持久化；NO_PROGRESS 递增、VERIFIED_PROGRESS 归零。

APK：30,078,491 bytes，SHA-256 `D1B168119A69D3579A9F762E2956BCBD6A6989477F84500B41CCD9C40A45F86D`

## 四、交付结论：`BUILD_READY`

按 R2.1 §12.1：
- **BUILD_READY**：是（构建成功、可安装，514 tests 通过）
- **FEATURE_VERIFIED**：**否**。A21–A26、A29、A34 尚未逐条跑集成用例
- **DEVICE_VERIFIED**：**否**。无设备，全部真机项 NOT_RUN

不得据此宣称“主动性已达标”。

## 五、本轮仍未做（R2.1 P0 剩余）

1. **S7 有限宽限**：`我在想/还在读` 的次数上限、累计时长、明确新截止、录音总时长上限未实现 → A27 NOT_RUN
2. **真实执行路径健康挑战**：未改哨兵，未区分权限/Binder/执行路径/动作结果 → A13/A14/A35 NOT_RUN
3. **S3/S4/S8 + 48 条黄金样例**：只有一个两步离线示例，不是五题题组；`maybeGrantRest` 未接完整结算 → A04/A05/A30–A33 NOT_RUN
4. **统一动作仲裁器**：只在部分入口做占用保护，不是覆盖所有异步 UI/TTS 的仲裁器
5. **旧 StrictSessionMachine 与训练器未统一**：恢复时可能同时存在两套严格任务，需明确互斥
6. **存储故障 UI**：save 失败只记日志，无可见恢复界面

## 六、A01–A36 状态（本轮范围）

| 组 | 状态 |
|---|---|
| A21（材料导入后主动问） | 纯逻辑 PASS（OpportunityTest），集成 NOT_RUN |
| A22（两机会选一） | 纯逻辑 PASS（已有训练优先接续） |
| A23（重复事件） | 纯逻辑 PASS |
| A24（材料过期/无授权） | 纯逻辑 PASS |
| A25（安静/保护抑制） | 纯逻辑 PASS，Android 实际抑制 NOT_RUN |
| A26（收起后接续） | 依赖 Codex 接续修复；集成 NOT_RUN |
| A29（连续低质量降频） | 纯逻辑 PASS |
| A34（断网本地题） | 依赖 OfflineTraining；集成 NOT_RUN |
| 其余 A01–A20、A27、A28、A30–A33、A35、A36 | NOT_RUN（Codex 部分已覆盖，本轮未复跑） |

## 七、回退

- `materialAssist` 默认关，关掉后行为与 r21-core 完全一致
- 不新增数据库/表；Opportunity 存 `memory/training/opportunity.json`，损坏只记错误不崩
- 未改签名、包名、既有训练/专注/StrictSession 契约
