# R2.1 批次二：会话互斥、输出仲裁、S7 宽限、健康挑战、题组结算

日期：2026-10-26。基线 `deepseek/r21-proactive` @ `8db09d9`（34 / 0.7.4-r21-s6fix-preview）。
版本 **35 / 0.7.5-r21-batch2-preview**。只改 companion-pet/。

## 一、本轮五项（按验收意见的顺序）

### 1. 会话互斥（`core/training/SessionArbiter.kt`）

系统里有三套会话，之前可能同时运行：训练器、旧 `StrictSessionMachine`、外部 AI `ChatSession`。

`SessionKind = TRAINING / STRICT_THINK / EXTERNAL_AI`，规则写死在代码里：
- 已有持有者时**任何新会话都不得抢占**，返回可读原因。
- 同一实例重复申请幂等成功（不会换持有者）。
- 只能由持有者释放；`force` 仅供恢复路径。

接入点：`openTraining`、`startMaterialTraining`、`startStrict`、`startSession`(外部 AI)。
结束路径全部释放：`endTraining`、`strictAbort`、`endThink`、`finishSession`。

### 2. UI/TTS 统一仲裁（`core/training/OutputArbiter.kt`）

`OutputPriority = URGENT / FAILURE / TRAINING / PROTECTIVE / AMBIENT`：
- 同优先级不可抢占；更高优先级可抢占，被抢占者收到回调（用于保存断点）。
- `present()`（温和搭话）走 AMBIENT：**不能盖掉正在显示的题目或拦截提示**。
- `showTrainingQuestion` 走 TRAINING：可盖闲聊，盖不掉紧急/故障。
- 抢占回调里会自动 `persistTraining()`，不丢断点。

### 3. S7 有限宽限（`core/training/ExtensionPolicy.kt`）

`我在想 / 还在读` 现在真的可用，但有硬上限：
- 次数上限 2 次、累计 3 分钟、每次 90 秒，给明确新截止。
- 用完后拒绝并给可执行提示（“先答这一问，或说‘我不会’”）。
- **技术故障不消耗额度**（麦克风坏了不算用户拖延）。
- 换题/换会话时额度重置（`reset()`）。

`InputDurationPolicy`：录音总时长上限 120 秒，防止长时间占用 `WAITING_INPUT` 冻结任务。
UI 增加「我在想」按钮，与「打字」「我不会」「收起」「结束本次」并列。

### 4. 真实执行路径健康挑战（`core/training/CapabilityHealth.kt`）

健康拆成五层：PERMISSION / BINDER / SERVICE / EXECUTION_PATH / LAST_ACTION。

`HealthChallengeJudge`：
- 回执必须带同一 `challengeId` + `nonce`，否则拒绝（防伪造/陈旧）。
- `stage` 为空或在 `binder_echo/provider_echo/entry` 中 → 拒绝（**入口回显不算走通执行路径**）。
- 超时由**发起端单调时钟**判定，迟到响应作废。
- 通过只表示“执行路径近期可响应”，**不表示动作一定成功**。

`ActionReceiptState`：REQUESTED → DISPATCHED → API_REPORTED_SUCCESS → OBSERVED_CONFIRMED，另有 FAILED / UNKNOWN。

`CapabilitySummary.canClaimReliable()`：只有 权限 OK + 服务连接 + 执行路径健康 + 动作**已观察确认** 才为 true。
API 报成功但未观察 → UNKNOWN；权限开着但执行路径卡住 → DEGRADED（不是 OK）。

### 5. 题组结算与回归样例（`QuestionSetSettlement.kt` + `GoldenSetTest`）

`QuestionSetSettlement`：
- 凭据按 `questionId@rubricVersion` 去重：同题重复回答、回调重放都不重复计数。
- `PassKind` 分 FIRST_TRY_INDEPENDENT / PROMPTED_PASS / TRANSFER_PASS，**分别统计**。
- 泄露过答案的题 → `NeedsTransfer`，必须补一道迁移题才产生资格。
- `independentRate()` 只算未获实质提示的首次作答。
- 默认 5 道不同题才 `eligible`。

## 二、重要：一个必须说清的降级

我原本想让本地判分直接判断“语义是否正确”。写完 48 条样例后**测试失败，暴露了真实问题**：

关键词匹配无法区分「具体正确」和「具体但错误」。例如：
- 「样本太小」应通过，但最初没命中词表；
- 「因为自由职业者不喜欢软件」方向错误，却被判通过；
- 「材料里有一句把结论推到了很大的范围」是复述题目，却被判通过。

这正是 R2.1 §5.2 警告的“不做关键词一票否决”。所以我**降级了本地判分**：

| 槽位 | 本地能可靠判定什么 | 不能判定什么 |
|---|---|---|
| LOCATE | 是否点中正文那句证据（可枚举） | — |
| CONNECT | 空泛表态、复述题目、明显残缺（形式缺陷） | **语义是否正确** |

形式合规时返回 PASS，但：
- `evaluationSource = "LOCAL_FORM_ONLY"`；
- `uncertainty` 明确写“语义正确性未经模型或人工确认”；
- `TrainingController.acceptAdvice` 会**因此拒绝**把它当作完成（Codex 的 PASS 需无 uncertainty 校验）。

集成测试里明确验证了这个行为：`realTrainingCompletionSettlesTheRightOpportunity` 中，
形式合规被拒 → 状态回退 → 补一次“已确认语义”的建议 → 才完成。

**这意味着：本地无法独立完成一次真实训练闭环。** 真实语义核验仍依赖模型或人工。

## 三、验证

| 项 | 结果 |
|---|---|
| JVM | **587 tests / 0 failures** |
| lint | **0 errors / 17 warnings** |
| assembleDebug | 成功 |
| assembleDebugAndroidTest | 成功 |
| APK | 30,193,179 bytes |
| SHA-256 | `40383677EB52F4EA4891F2BBCD301A899FAC58CD6857FF829D49671C25C26EA3` |

新增测试：
| 文件 | 数量 | 覆盖 |
|---|---|---|
| SessionArbiterTest | 10 | 互斥、幂等、只能持有者释放、force 恢复 |
| OutputArbiterTest | 10 | 同级不可抢占、高优先级抢占并通知、释放后可说 |
| ExtensionPolicyTest | 8 | 次数/累计双上限、技术故障不扣额度、录音上限 |
| CapabilityHealthTest | 11 | nonce 校验、入口回显拒绝、发起端超时、API≠效果、可靠性声明门槛 |
| GoldenSetTest | 10 | 8 类形式缺陷拦截、形式合规留痕、同题不重复计数、5 题门槛 |

## 四、交付结论（R2.1 §12.1）

- `BUILD_READY`：**是**
- `FEATURE_VERIFIED`：**部分**。批次二五项的逻辑与集成用例 PASS
- `DEVICE_VERIFIED`：**否**。无设备，真机全 NOT_RUN

## 五、仍然不成立的完成

1. **本地判分不能判语义**（上面第二节）。真实训练闭环仍需模型/人工确认语义。
2. **健康挑战未接真机执行队列**。`HealthChallengeJudge` 是纯逻辑判定器，Android 侧仍需把它接到无障碍服务的真实调度路径；本轮未接。
3. **题组结算未接 UI**。`QuestionSetSettlement` 是纯逻辑，尚未与 `RestGrantStore` 和 `maybeGrantRest` 串成一条完整发券流程。
4. **五题流程仍是单题模板**：只有 `sample-scope-01` 一个离线题组，凑不出 5 道不同题。
5. **48 条样例由开发者标注**，不是独立人工黄金集，不能声称已达 §7.1 门槛。

## 六、下一批

1. 把 `HealthChallengeJudge` 接到无障碍真实执行队列（A13/A35 才能真过）。
2. 题组结算接 UI：5 题 → 发券 → 休息 → 到期恢复，并接 `RestGrantStore` 单次语义。
3. 补足 5 道可判离线题（当前只有 1 道），否则五题换休息无法端到端演示。
4. 语义核验：把 `LOCAL_FORM_ONLY` 的结果交给模型做一次结构化确认，形成完整闭环。
5. 真机验证（等设备）。
