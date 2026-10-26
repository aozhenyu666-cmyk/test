# MERGE_MAP：Claude 修复如何处理

来源：`claude/companion-unify-v1` @ `08f4dbe2`（基于共同祖先 `c5438a2`）。
**禁止整文件覆盖**：Claude 的 PetBrain/TrainingController 是旧基线，覆盖会丢掉 S6 材料链、会话/输出仲裁、健康挑战、题组结算、语义确认。

| Claude 项 | 决定 | 理由 / 防回退 | 本轮状态 |
|---|---|---|---|
| CL-S1 严格回合输入入口 | **待移植** | 当前严格回合回答走 answerThink，需确认是否已接 | 未做 |
| CL-S2 按题验收标准 | **待移植** | 当前 `StrictCriteria` 未在 DeepSeek 线 | 未做 |
| CL-S3 多步接续 | **待移植** | 当前严格回合一步过就结束 | 未做 |
| CL-S4 按步帮助等级 | **待移植** | 当前按整回合 attempts 计 | 未做 |
| CL-S5 失败恢复 | **已等价** | 当前 `technicalBlock` + `evaluationFailed` 已有 | 等价 |
| CL-S6 点桌宠重显 | **待移植** | 当前严格回合点桌宠会收起 | 未做 |
| CL-S7 恢复读状态机问题 | **待移植** | 当前恢复后 answerThink 读 result | 未做 |
| CL-S8 恢复不卡死/坏档留底 | **待移植** | 当前恢复可能卡 EVALUATING | 未做 |
| CL-S9 缺原文改宽松理由 | **拒绝** | 材料缺失应标证据不足，不能照常判过 | 拒绝 |
| CL-T1/T2 单调时钟 | **已等价** | Codex/DeepSeek 已修，本轮沿用 | 等价 |
| CL-T3/T4 技术降级 + 快照校验 | **已等价** | 当前 technicalBlock + revision/requestId 校验已有 | 等价 |
| CL-T5 共用全屏拦截层 | **待移植** | 当前偏离只弹气泡，无全屏层 | 未做 |
| CL-T6 返回训练接线 | **待移植** | 当前“回到训练”开主界面 | 未做 |
| CL-T7 单题发券 | **拒绝** | R2.1 是五题合格才发券；本轮已按题组结算 | 拒绝 |
| CL-T8 完成不记中止 | **已等价** | 当前 endTraining 只在 active 时记中止 | 等价 |
| CL-T9 RECOVERY 可继续 | **已等价** | 当前 resume 接受 RECOVERY | 等价 |
| CL-T10 按题支架选项 | **已部分** | 当前 `scaffolds` 有则用，否则通用 | 部分 |
| CL-T11 气泡回答入口 | **待移植** | 违规/恢复气泡缺回答入口 | 未做 |
| CL-T12 训练菜单 + 语音路由 | **待移植** | 当前训练中点桌宠只收起 | 未做 |
| 版本 31/0.7.1 | **拒绝** | 会回退版本；本轮 37/0.7.7 | 拒绝 |

## 已等价项说明

CL-T1/T2/T3/T4/T8/T9 的行为在 DeepSeek 线已有等价实现（Codex 的 `TrainingContinuityRegressionTest` 覆盖）。
**不要重复移植旧计时逻辑**，否则会把已验证的单调时钟实现换回墙上时间。

## 关键防回退点

1. `TrainingController.acceptAdvice` 要求 PASS 无 `uncertainty` —— 这是**语义确认层的支点**。
   离线判分与材料形式判分都故意带 uncertainty，必须经模型确认后清空才可采纳。
   若把它当 bug 改掉，语义确认层立即失效，会退回“关键词命中就算通过”。
2. `SessionArbiter` 不得用全局 force release 清别人持有的会话。
3. `RestGrant` 只能由 `QuestionSetSettlement` 的题组门槛触发，不能单题发券。
