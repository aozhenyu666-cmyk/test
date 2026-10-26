# 批次三 + 接线修复：当前到底还差什么

日期：2026-10-26。分支 `deepseek/r21-proactive`。版本 **36 / 0.7.6-r21-batch3-preview**。

## 一、本轮验证（这次真的跑了）

| 项 | 结果 |
|---|---|
| JVM | **603 tests / 0 failures** |
| lint | **0 errors / 17 warnings** |
| assembleDebug | 成功 |
| assembleDebugAndroidTest | 成功 |
| APK | 30,209,567 bytes |
| SHA-256 | `BA467E45A758DD93C382B940A846C9F808AF61EDB3F85221DB20E36EAC7151FC` |

上一轮我说“不验证”，这次补跑了，**发现并修掉 3 个真实编译错误**（`Permissions` 未解析 ×2、`Verdict` 跨包冲突 ×1）。说明“只写不验”会留真 bug。

## 二、本轮修的“写了没接线”

| # | 问题 | 处理 |
|---|---|---|
| 1 | `runEquipmentChallenge()` 从未被调用 → `equipmentHealthy` 永远 false → `canClaimReliable()` 永远 false，健康检查形同不存在 | 服务启动 3 秒后跑一次；tick 每 5 分钟复检（仅有服务连接时） |
| 2 | `maybeGrantRest()` 是旧路径，与 `recordQuestionCredit()` 重复 → 会绕过题组门槛直接发券 | 删除死代码，只保留按 5 题结算 |
| 3 | `OutputArbiter` 的 AMBIENT 权从未释放 → 一次闲聊后输出管道可能被永久占住 | `endConversation()` 里释放 AMBIENT |

## 三、对照 R2.1 的完整差距表

### 已做（逻辑层，603 测试覆盖）

| 能力 | 位置 |
|---|---|
| 双形态 WARM/COACH | `TrainingSession` |
| 训练状态机、槽位推进、旧响应丢弃 | `TrainingController` |
| 材料绑定 + 版本 + 真实正文出题 | `BoundMaterial` / `MaterialQuestionBuilder` |
| 本地形式判分 + 语义确认闭环 | `MaterialTraining` / `SemanticConfirm` |
| 机会去重、仲裁、抑制留痕 | `Opportunity` / `OpportunityArbiter` / `OpportunityStore` |
| 按 sessionId 结算、幂等、冷却 | `OpportunityStore.settleBySession` |
| 会话互斥（训练/严格/外部 AI） | `SessionArbiter` |
| 输出互斥（气泡与语音） | `OutputArbiter` |
| S7 有限宽限 + 录音上限 | `ExtensionPolicy` / `InputDurationPolicy` |
| 执行路径健康挑战（5 层） | `CapabilityHealth` |
| 题组结算（凭据去重、迁移题、单次发券） | `QuestionSetSettlement` / `RestGrant` |
| 6 道可判离线题 | `BuiltInQuestions` |
| 跨应用拦截判定 | `TrainingFocusBridge` |
| 强约束（拒绝/沉默/编造 → 强制 COACH） | `EnforcementRules` |

### 仍未做

| # | 缺口 | 影响 | 能否离线做 |
|---|---|---|---|
| 1 | **S1 计划触发**：到点主动发起训练 | 用户不打开 App 就不会练 | 可（WorkManager + 通知） |
| 2 | **主动断点恢复**：不是被动接续，而是主动回来问 | 中途离开后不会自己回来 | 可 |
| 3 | **偏离触发的主动发起**：拦截目前只弹气泡 | 不是主动把题带回 | 部分可 |
| 4 | **`capabilityLines()` 未接诊断页** | 用户看不到五层健康状态 | 可 |
| 5 | **`canClaimReliable()` 未在任何 UI 显示** | 用户无法知道监管是否可靠 | 可 |
| 6 | **48 条黄金样例**仍是我标注的回归集 | 不能声称达到 §7.1 门槛 | 需人工 |
| 7 | **六题覆盖有限**：只认含范围/因果词的句子 | 纯叙述材料出不了题 | 可扩 |
| 8 | **语义确认强依赖模型**：没 Key 时 CONNECT 只能 RETRY | 离线无法完成训练 | 需本地兜底 |
| 9 | **A01–A36 真机项全 NOT_RUN** | 无一真机证据 | 需设备 |

## 四、与 Claude 分支的合并

Claude `companion-unify-v1`（0.7.1/31，基于 `c5438a2`）与本分支（`dd7fe60`）已分叉：+1 / -5。
它做了 S1–S9、T1–T12（严格回合、计时时钟、拦截接线等 UI 级修复），**正好是我这版缺的接线层**。

合并建议：
1. 以 `deepseek/r21-proactive` 为底；
2. 挑 Claude 的 T1/T2（时钟）、T5/T6（前台拦截接线）、T7（休息调用点）、T8（完成不记中止）、T9（RECOVERY 可继续）、T11/T12（气泡回答入口、训练菜单）逐条合入；
3. **不要**改回去：`acceptAdvice` 要求 PASS 无 uncertainty——这是语义确认层的支点。

## 五、回答“是不是只等测试了”

**不是。** 分三层：

- **逻辑层：基本齐了**（603 测试），但有两个**产品级功能缺口**，测试补不了：S1 到点主动发起、主动断点恢复。
- **接线层：还差 3 处**：`capabilityLines()` / `canClaimReliable()` 未进 UI；偏离触发的主动发起未接。
- **真机层：全部未验证**。A01–A36 一项没跑。

## 六、建议顺序

1. 合并 Claude 的接线修复（T1/T2/T5/T6/T7/T8/T9/T11/T12）。
2. 补 S1 计划触发 + 主动断点恢复（决定“它到底是不是主动的”）。
3. 五层健康与可靠性状态进诊断页。
4. 真机验证 A01–A36。
5. 48 条人工黄金样例（需人）。
