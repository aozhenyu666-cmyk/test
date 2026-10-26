# S6 修复：材料绑定、真实出题、按会话结算

日期：2026-10-26。基线 `codex/tongzhuo-r21-core` @ `c9f6970`（32 / 0.7.2-r21-core-preview）。
分支 `deepseek/r21-proactive`，版本 **34 / 0.7.4-r21-s6fix-preview**。

## 一、先纠正上一版不成立的声明

上一版（33 / 0.7.3）我写了“S6 已完成”，**这是错的**。实际只做了：
- 材料标题 + 固定样例题（题目根本不来自材料正文）
- 结算取 `opportunities.lastOrNull()`——那可能是后来插入的抑制记录
- 展示成功硬编码为 `DeliveryState.SHOWN`，没检查是否真的显示
- 存储失败被当成成功继续跑

本轮按验收意见重做，并把“完成”的口径改回行为目标：
**发现机会 → 相关提问 → 核验回答 → 继续或恢复**。

## 二、本轮真正修的六件事

### 1. 按事件绑定准确材料与版本

`core/training/BoundMaterial.kt`

- 从 `SharedMaterial` 绑定：materialId、version、bodyHash、contentType、正文、来源时间。
- `version` 由「类型+标题+正文」的 SHA-256 派生 → **正文改动版本就变**，旧题/旧成绩不会串到新内容。
- `hasReadableBody`：`LINK_ONLY` 恒为 false；正文短于 40 字也为 false。
- `evidenceNote` 如实标注“摘录不等于全文 / 只有链接没有正文”。

取材料时按 `sourceEventId == event.id` 精确匹配，**不再取“最新一条”**。

### 2. 从真实正文生成问题，保留引用位置、评分依据、下一步

`core/training/MaterialQuestionBuilder.kt`

- 切句 → 找带范围词（任何人/所有/全部/都…）或因果词（所以/因此/可见…）的句子。
- 选中句作为待指认证据，其余为干扰项；**所有句子都是原文**，不改写。
- 输出 `questionId = mat-<bodyHash>-s<句序>`、`rubricPoints`、`nextStep`。
- **找不到任何可核查线索 → 返回 null，宁可不问也不编题。**

`core/training/MaterialTraining.kt`：
- 装配成训练题（`builtInSample = false`，带 materialId/version/rubric/nextStep）。
- 本地判分：点对正确句 或 说出范围/因果词 → PASS；「懂了」「有道理」→ NEED_CLARIFICATION。
- 第二槽要求具体理由（范围/样本/别的原因），空泛表态不通过。

### 3. 机会完整生命周期 + 按 sessionId 结算

`core/training/Opportunity.kt`

- `Opportunity.sessionId` 与 `TrainingSession.opportunityId`双向绑定。
- `settleBySession(sessionId, ...)`：**按会话对齐，不按列表顺序**。
- 幂等：`resolution != UNANSWERED` 直接返回 false，**重复结算不重复计数**。
- 完成 → VERIFIED_PROGRESS（计数归零）；中止/到期 → NO_PROGRESS（计数 +1）。
- `findBySession()` 供恢复与诊断使用。

### 4. 展示失败不能记 SHOWN

- 先记 `PROPOSED`，气泡真的显示成功才 `markDelivery(SHOWN)`。
- 显示抛异常 → `FAILED`，并结算 `NO_PROGRESS`，同时气泡明说。
- 新增 `DeliveryState.FAILED`，与 `BLOCKED_BY_OS`、`NOTIFIED` 分开。

### 5. 连续无效 → 有限冷却 + 恢复条件

- `COOLDOWN_AFTER = 3`，`COOLDOWN_MILLIS = 15 分钟`。
- 冷却写入 `Snapshot.cooldownUntil`，仲裁读到就抑制，原因可读：“先安静 N 分钟”。
- **到期自动恢复**，不需要用户操作；`cooling()` / `cooldownLeft()` 可查。

### 6. 存储失败必须可见

- `record()` 返回 `RecordResult`：`Recorded` / `Duplicate` / `StorageFailed`。
- 存储失败 → 气泡显式提示“机会记录没存下来：…”，**不静默当空记录继续跑**。
- 文件损坏 `load()` 记 `lastError` 但不崩。

## 三、集成测试（走真实链路，不是手传 materialFresh=true）

`MaterialOpportunityIntegrationTest` 8 项：

| 用例 | 验证 |
|---|---|
| `twoDifferentMaterialsNeverCrossContaminate` | 连续导入两份不同材料，引用句各自来自自己正文，互不串 |
| `eachMaterialGetsItsOwnOpportunityAndSession` | 两份材料各自独立机会；结算 A 不影响 B |
| `realTrainingCompletionSettlesTheRightOpportunity` | 真实两槽完成 → 只结算对应机会；插入的抑制记录不被误结算 |
| `duplicateCallbackDoesNotDoubleCount` | 重复结算不重复计数 |
| `blockedDisplayIsNotCountedAsShown` | 展示失败记 FAILED，不记 SHOWN |
| `threeIneffectiveThenCooldownThenRecover` | 三次无效 → 冷却；到期恢复 |
| `answeringCorrectlyAdvancesToSecondStep` | 答对 → 进入相关第二步，且要求依据 |
| `vagueAnswerDoesNotAdvance` | 空泛回答不推进，且给出具体缺口 |

另有 `MaterialQuestionTest` 9 项（只有链接不出题/短正文不出题/无线索不出题/版本变化换题号/原句校验）。

## 四、验证结果

| 项 | 结果 |
|---|---|
| JVM | **538 tests / 0 failures** |
| lint | **0 errors / 16 warnings** |
| assembleDebug | 成功 |
| assembleDebugAndroidTest | 成功 |
| APK | 30,111,259 bytes |
| SHA-256 | `04E05F68F8AA05EA3B19858B04850C09345A4DB7D225DA62E6924D493D14C363` |

## 五、交付结论（R2.1 §12.1）

- `BUILD_READY`：**是**
- `FEATURE_VERIFIED`：**部分**。S6 纯逻辑与集成用例 PASS；**但尚未在真实 App 内跑通过一次端到端**
- `DEVICE_VERIFIED`：**否**。无设备，真机全 NOT_RUN

## 六、仍然不成立的“完成”

明确记下来，避免下一轮又被当成已完成：

1. **不是所有材料都能出题**：目前只认“含范围词/因果词”的句子。纯叙述型材料会返回 null（这是有意的：宁可不问），但**意味着真实场景覆盖率有限**。
2. **不是模型理解文章**：规则是确定性的关键词+句法抽取。做不到“读懂再选最有价值的问题”。
3. **第二步仍是固定模板**：`nextStep` 按范围/因果两种情况写死，不是针对该材料的动态追问。
4. **只有材料这一个入口**：计划触发（S1）、断点主动恢复（除被动接续）、偏离触发都还没做成主动发起。

## 七、下一批（按验收意见的顺序）

1. **会话互斥**：确保只有一个有效训练会话，彻底避免旧 `StrictSessionMachine` 与新训练器并行。
2. **统一 UI/TTS 仲裁**：现在只在部分入口做占用保护，不是覆盖所有异步 UI 与语音输出的仲裁器。
3. **S7 有限宽限**：`我在想/还在读` 的次数上限、累计时长、明确新截止、录音总时长上限。
4. **真实执行路径健康挑战**：区分权限/Binder/执行路径/动作结果；API success ≠ observed effect。
5. **五题结算 + 48 条人工黄金样例**：本轮仍只有一个两步材料题，`maybeGrantRest` 未接完整结算。

## 八、回退

- `settings.materialAssist` 默认关；关掉后行为与 r21-core 一致。
- 新增文件（BoundMaterial/MaterialQuestionBuilder/MaterialTraining/Opportunity）互不侵入既有训练契约。
- 只在 `TrainingSession` / `TrainingQuestion` 加带默认值字段，旧快照可读。
- 未改签名、包名、数据库版本。
