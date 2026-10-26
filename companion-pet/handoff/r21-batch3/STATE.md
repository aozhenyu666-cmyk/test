# R2.1 批次三：题库补足、题组结算接 UI、健康挑战接执行队列、语义确认闭环

日期：2026-10-26。基线 `deepseek/r21-proactive` @ `66ce3ca`（35 / 0.7.5-r21-batch2-preview）。
版本 **36 / 0.7.6-r21-batch3-preview**。只改 companion-pet/。

> **重要**：本轮按用户要求只写代码、不再重复跑构建验证。build 由 Claude 侧负责。
> 因此下面的测试数量与构建结果是**未验证**的，不得当成已通过。

## 一、本轮四项

### 1. 题库补足到 6 道（`BuiltInQuestions.kt`）

之前只有 3 道，其中 2 道靠模型判分，凑不出「五题换休息」。现在 6 道、题号唯一，覆盖五种可判的认知动作：

| 题号 | 缺口类型 | 槽位 |
|---|---|---|
| sample-scope-01 | 范围过度推广 | LOCATE + CONNECT |
| sample-cause-01 | 因果倒置（先后当因果） | LOCATE + CONNECT |
| sample-sample-01 | 样本偏差（三店一城推全国） | LOCATE + CONNECT |
| sample-hasty-01 | 以偏概全（三个同事推所有用户） | LOCATE + CONNECT |
| sample-conflict-01 | 用个例否定整体 | LOCATE + CONNECT |
| sample-open-01 | 外推性未知（开放题） | LOCATE + CONNECT + EXAMINE |

每题都带 `rubricPoints` 与 `nextStep`，随题存盘，恢复后仍可用。新增 `pickMany()` 供题组取题。

### 2. 题组结算接进 UI 路径（`PetBrain.recordQuestionCredit`）

之前 `maybeGrantRest` 存在但没接到按题组计数的路径，等于完成一题就发券。现在：

- 每完成一题 → `questionSet.record()` 记一道凭据；
- 凭据按 `questionId@rubricVersion` 去重（同题反复做只算一次）；
- 独立通过 / 提示后通过分开记账；
- 未达门槛 → 提示“再完成 N 道就能换一段休息”；
- 达 5 道 → 走 `RestGrantStore.grant()` 单次发券，重启不续时；
- 发券后 `grantIssued = true`，不再 eligible。

### 3. 健康挑战接真实执行队列（`PerceptionHub` / `PerceptionService` / `PetBrain`）

之前 `HealthChallengeJudge` 只是纯逻辑判定器。现在打通：

- `PerceptionHub.challengeResponder` 由 `PerceptionService.onServiceConnected` 注册；
- `PerceptionHub.postChallenge()` 把挑战**投递到无障碍服务自己的主线程 Handler**——与前台事件同一条队列；
- 应答 stage 固定为 `a11y_main_queue`，**不是** entry/binder_echo，所以入口回显会被判拒；
- `PetBrain.runEquipmentChallenge()` 发挑战、等回执、按判定更新 `equipmentHealthy`；
- `canClaimReliable()` 仍要求：权限 + 服务 + 执行路径健康 + 最近动作**已观察确认**；
- `capabilityLines()` 输出五层状态供诊断页显示。

关键点：健康通过**不等于**动作成功。`lastActionState` 单独记录，API 报成功但未观察仍是 UNKNOWN。

### 4. 语义确认闭环（`SemanticConfirm.kt`）

这是补上此前那个真实缺口：本地只能验形式，语义没核验就不能算完成。

流程：
```
CONNECT 槽提交
  → MaterialTraining.evaluateConnect（只验形式）
      形式不合格 → 直接拒，不消耗模型额度
      形式合规   → SemanticConfirmPrompt 交给模型确认一次
          SUPPORTS     → 产生真正 PASS（uncertainty 清空，本地可采纳）
          NOT_SUPPORTS → 要求澄清，给出具体缺口
          UNCLEAR      → 要求说具体
          NotConfirmed → RETRY：保留答案、可重试，不冒充通过
```

迟到结果按 `revision + requestId` 核对，过期丢弃。模型请求期间暂停计时。

## 二、待 Claude 验证的事项

以下**未验证**（本轮不跑构建）：

1. 编译是否通过（新增 3 个文件 + PetBrain 多处改动）。
2. `SemanticConfirmPrompt.build` 的字符串拼接是否正确（我用拼接而非模板，避免嵌套问题）。
3. `PerceptionService.mainHandler()` 与 `challengeResponder` 的可访问性（`internal` 跨包需同模块）。
4. `PetBrain` 新增 import 是否齐全（`ActionReceiptState`、`CapabilitySummary`、`HealthChallengeJudge`、`HealthReceipt`、`SemanticConfirm*`、`CreditResult`、`PassKind`、`QuestionSetSettlement`、`SetState`）。
5. 新增测试：`SemanticConfirmTest`(6)、`QuestionSetSettlementTest`(6)、`BuiltInQuestionsTest`(4)。

## 三、与 Claude `companion-unify-v1` 的关系

Claude 的 `companion-unify-v1`（08f4dbe2，0.7.1/31）基于 `c5438a2`，做了 S1–S9 / T1–T12 修复，
与本分支（基于 `66ce3ca`）**已分叉**：它领先 1 个提交、落后 5 个提交。

两边的修复**大量重叠但不冲突**方向一致：都在做“统一会话 + 单次发券 + 不冒充通过”。
建议合并顺序：以 `deepseek/r21-proactive` 为底（它含 S6 材料链、输出仲裁、会话互斥、S7 宽限、
健康挑战、题组结算、语义确认），再挑 Claude 的 S1–S9/T1–T12 逐条比对合入。

两处需要人工判断的冲突点：
1. `TrainingTimer`：Claude 修了混用墙上/单调时钟（T1/T2），我这版沿用其修复，未再改。
2. `TrainingController.acceptAdvice`：Claude 要求 PASS 无 uncertainty；我这版利用这个校验，
   把“形式合规但语义未核验”故意判成不可采纳——语义确认通过后才清空 uncertainty。这是**有意设计**，合并时不要把它改回去。

## 四、仍然不成立的完成

1. **真机全部 NOT_RUN**：无设备。健康挑战、拦截层、A01–A36 真机项一项没跑。
2. **语义确认依赖模型**：没 Key 时第二槽只能 RETRY，闭环不完整。
3. **48 条黄金样例仍是开发者标注**，不是独立人工集。
4. **只在材料这一个入口**：计划触发（S1）、主动断点恢复、偏离触发仍不是主动发起。
5. **`runEquipmentChallenge` 未接入定时调度**：目前是公开方法，需要有人调用（例如进入诊断页或守护启动时）。

## 五、回退

- 新增文件全部独立，不侵入既有契约；
- `questionSet` 默认需 5 道，未达门槛只提示，不阻断训练；
- `runEquipmentChallenge` 不被调用时，`equipmentHealthy` 保持 false → `canClaimReliable()` 为 false（保守，不会误报可靠）；
- 语义确认失败只 RETRY，不影响既有单题流程。
