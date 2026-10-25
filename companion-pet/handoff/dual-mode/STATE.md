# 同桌双形态训练（批次 A）

日期：2026-10-25。基线 `9d9ad27`（0.6.2-preview / 29）。
分支 `feature/companion-dual-mode-training`，版本 **0.7.0-preview / 30**。只改 companion-pet/。

## 一、模块核验（全部存在，非占位）

| 模块 | 结果 |
|---|---|
| FocusGuard | 存在 `core/focus/FocusGuard.kt` |
| ForegroundGate | 存在 `core/focus/ForegroundGate.kt` |
| GoalSupervisor | 存在 `core/goal/GoalSupervisor.kt` |
| AgentLoop | 存在 `core/agent/AgentLoop.kt` |
| StuckDetector | 存在 `core/scene/StuckDetector.kt` |
| IgnoreEscalation | 存在 `core/rhythm/IgnoreEscalation.kt` |
| ThinkPrompt | 存在 `core/think/Think.kt` |
| StrictSessionMachine | 存在 `core/continuity/StrictSession.kt` |
| interactionMode | **原来不存在**（本轮新增） |
| 题库 / QuestionSource | **原来不存在**（本轮新增内置示例） |

## 二、用户明确的强制要求（已实现为规则，不是建议）

用户原话：不完成、拒绝回复、胡编乱造 → 应切换到刷题模态；跨 APP 切换与新增题库属同类更强约束。

实现：`core/training/EnforcementRules.kt`

| 触发 | 判定 | 动作 |
|---|---|---|
| 技术故障（麦克风/网络/识别） | TECHNICAL | `DOWNGRADE_TECHNICAL`，不计违规 |
| 正常回答 | NORMAL | `NONE` |
| 没有待答问题 | NO_RESPONSE | `NONE`，不算沉默违规 |
| 第 1 次拒绝/未答/编造 | — | `ONE_MORE_CHANCE`，只追一次具体缺口 |
| 第 2 次起（温和模态） | — | **`FORCE_COACH`：强制切严格刷题** |
| 已在严格模态再违规 | — | **`LOCK_COACH_SLOT`：锁定当前槽位，不退化** |

关键点：
- **不会自动退回温和模态**。已在严格模态时反复违规仍是 `LOCK_COACH_SLOT`。
- 技术故障永不触发强制，不把网络问题算成用户失败。
- 紧急退出始终可用，不需要答题。
- 只改变形态与训练入口，不扩大到整机锁控。

## 三、双形态产品契约

| 维度 | WARM | COACH |
|---|---|---|
| 目的 | 讨论、理解、谋划、陪伴 | 完成一个明确认知动作 |
| 开场 | 轻量建议或安静陪伴 | 立即出一个微问题，不留空白 |
| 验收 | 不必评分 | 指出通过依据与缺失步骤 |
| 语气 | 温和、可提异议 | 简短、坚定、具体、不羞辱 |

形态与专注锁是两个独立维度：切换形态不清除已有专注会话，进入 COACH 也不自动获得整机锁控。

## 四、训练会话契约

`core/training/TrainingSession.kt`：
- `InteractionMode`：WARM / COACH
- `PremisePolicy`：CLOSED（题设推理）/ OPEN（现实分析）
- `TrainingState`：READY / PRESENTING / WAITING_INPUT / EVALUATING / SCAFFOLDED / NEXT_SLOT / RECOVERY / INTERRUPTED / COMPLETED / ENDED
- `Verdict`：PASS / RETRY / NEED_CLARIFICATION / QUESTION_ISSUE
- `AssistanceLevel`：INDEPENDENT / PROMPTED / RECOGNIZED_WITH_CHOICES / DEMONSTRATED_ONLY
- 槽位：LOCATE / CONNECT / EXAMINE，首批默认前两槽（第三槽按题目要求）

`core/training/TrainingController.kt` 不变量：
1. 一轮只推进一个槽位。
2. 旧建议（sessionId / revision / questionId / slot / requestId 任一不符）全部丢弃。
3. 提示后完成记 PROMPTED，不写成独立掌握。
4. 模型失败不判用户失败，也不冒充通过。
5. 形态切换保留当前题与槽位。

## 五、内置示例题（明确标注）

`core/training/BuiltInQuestions.kt` 三道虚构材料题：
- `sample-scope-01`：20 名自愿参加者 → “任何人都会提高”（CLOSED，两槽）
- `sample-cause-01`：上打卡软件后迟到下降 → 归因于软件（CLOSED，两槽）
- `sample-open-01`：只调查城市白领的报告（OPEN，三槽）

UI 显示“内置示例题 ·”，**不冒充正式题库**。未使用真实事故或争议事实。

## 六、接入路径

桌宠菜单 → **开始陪练** → 自动出题（题干 + 第一问）→
点选句子 / 打字 / 说“我不会” → 结构化核验 → 下一步或支架。

每次状态变化写事件日志（`训练完成|中止 | q:<id> | 完成 N 步…`），用于后续统计独立通过率。

## 七、验证

JVM：**463 tests / 0 failures**（基线 441 + TrainingController 14 + EnforcementRules 8）
lint：0 errors / 15 warnings
APK：29,947,363 bytes，SHA-256 `3BA06EBD3AAB3D664DD8AB1F112890118D013B5375C602B1E83030FB0647BD38`

测试覆盖：首问只要求一个槽位、空输入忽略、通过推进并保留前面尝试、全槽完成、RETRY 保持同槽、旧请求号拒绝、错槽位/错题/错会话拒绝、提示后通过不记独立、支架不关会话、恢复不丢槽位、题目问题进恢复、形态切换保留任务、中断恢复、结束拒绝新建议、强制规则 8 项。

## 八、明确未完成（批次 B/C）

- **计时与支架节奏**：25 秒 / 20 秒参数、单调时钟、读题与录音期间暂停计时——未实现。
- **持久化恢复**：训练会话未落盘，杀进程后不恢复。
- **点选句子 UI**：当前用“选第 1 句/选第 2 句”按钮，不是可点选文本高亮。
- **语音优先**：仍走现有按住说话/打字，无全双工。
- **跨 APP 强制**：用户要求的“切到别的 APP 也强制”依赖 ForegroundGate 与训练会话联动，本轮未接。
- **题库导入**：未实现文件选择器导入。
- **专注联动**：固定专注 / 练习换休息两套政策未接。
- **真机全部 NOT_RUN**：无设备，无截屏。

## 九、回退方式

- 训练入口是菜单新增项，不启动不影响原有陪伴。
- `interactionMode` 默认 WARM；强制规则只在训练进行中触发。
- 不改动无障碍拦截、防卸载、专注契约。
- 数据兼容：只新增事件日志行，不改既有字段。
