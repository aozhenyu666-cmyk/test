# MERGE_MAP：Claude 修复如何处理

来源：`claude/companion-unify-v1` @ `08f4dbe2`（基于共同祖先 `c5438a2`）。
**禁止整文件覆盖**：Claude 的 PetBrain 是旧基线，覆盖会丢掉 D01–D07、S6 材料链、健康挑战、题组结算、语义确认。

## 本轮已移植（按行为，不复制文件）

| Claude 项 | 落地做法 | 差异 |
|---|---|---|
| CL-T12 训练菜单 | 新增 `showTrainingMenu()`；`onPetTapped` 在训练中直接给菜单 | 保留 SessionArbiter/OutputArbiter 守卫 |
| CL-T12 语音路由 | 新增 `trainingVoice()`，录音暂停计时，结果进 `submitTraining` | 沿用 D05 录音上限 |
| CL-T11 重显当前步 | 新增 `showTrainingAsk(prefix)`，不新建会话、不重置槽位 | 额外占 TRAINING 输出权 |
| CL-S1 严格回答入口 | 新增 `showStrictPrompt()`，打开 awaitingAnswer，给说一句/打字/我不会/结束 | — |
| CL-S2 按题验收标准 | `startStrict` 按有无素材生成标准，不再写死作者转折 | — |
| CL-S6 点桌宠重显 | `onPetTapped` 中严格回合活跃时走 `showStrictPrompt` | — |
| CL-T5 共用全屏拦截层 | `onTrainingForeground` 改用 `focusOverlay.show`；Bridge 增加 basePackage/homePackages | 保守差异：守护在跑时训练让位；回起点应用/桌面不拦 |
| CL-T6 返回训练 | `trainingBlockReturn()` 回 trainingBase 应用，失败才回桌面，再重显当前步 | 不再开主界面 |
| CL-T7 单题发券 | **拒绝** | R2.1 是五题合格才发券 |

## 已等价（不重复移植）

CL-T1/T2（单调时钟）、CL-T3/T4（技术降级+快照校验）、CL-T8（完成不记中止）、CL-T9（RECOVERY 继续）、CL-S5（失败恢复）已有等价实现并被回归测试覆盖。
**不要用旧版覆盖**，否则会把单调时钟换回墙上时间。

## 仍待移植

| 项 | 原因 |
|---|---|
| CL-S3 多步接续 | 需配合 StrictSessionMachine 的 stepIndex |
| CL-S4 按步帮助等级 | 当前按整回合 attempts 计 |
| CL-S7 恢复读状态机问题 | answerThink 仍读 result |
| CL-S8 恢复卡死/坏档留底 | 可能停在 EVALUATING |
| CL-S9 缺原文改宽松理由 | **拒绝**：材料缺失应标证据不足 |

## 关键防回退点

1. `acceptAdvice` 要求 PASS 无 uncertainty —— 语义确认层支点。
2. `SessionArbiter` 不得全局 force release。
3. 单题不得发券，只能 5 题门槛。
4. `blockOwner` 保证同一时刻只有一个拦截拥有者。
