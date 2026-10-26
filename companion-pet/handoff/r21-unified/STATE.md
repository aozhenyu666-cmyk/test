# R21 统一整合：当前状态

分支 `deepseek/r21-proactive`（按指令以 DeepSeek 线为整合基线）。版本 **37 / 0.7.7-r21-unified-preview**。

## 基线

| 对象 | SHA |
|---|---|
| 共同祖先 | `c5438a2169086c45ce279138801374d53627913b` |
| DeepSeek 基线（本轮起点） | `1b0e0abe472f349f602291a01e2108d3f9d79a4a` |
| Claude 补丁来源 | `08f4dbe22a4b5819dada9e92e1f55d5c68abf2be` |

## 本轮已完成：D01–D07 收敛

| ID | 问题（经本轮代码核实） | 处理 |
|---|---|---|
| **D01** | `runEquipmentChallenge()` 判定排在 `timeout+500`，而 judge 在 `now > deadline` 判超时 → **健康永远恒超时** | 回执到达即判定（用接收时刻）；超时兜底只处理仍 pending 的挑战；`AtomicBoolean` 单次结算；`activeChallengeId` 防并发覆盖 |
| **D02** | `lastActionState` 无写入方；`BINDER` 层未汇总 | 新增 `recordActionReceipt()` 真实生产者；`summarize()` 补齐 BINDER（未使用标 UNKNOWN/不适用）；健康加 10 分钟新鲜度 |
| **D03** | 完成一题后点“下一题”，仲裁器仍持有旧 sessionId → 新会话被自己拒绝 | 新增 `advanceToNextQuestion()`：先按 owner 释放再开新题，不做全局 force release |
| **D04** | 题组状态只在内存；`answerRevealed` 写死 false；发券后无持久化 | 新增 `QuestionSetStore`（cycleId/grantId 落盘）；`answerRevealed` 用真实事实（看过支架即泄露）；发券后持久化 |
| **D05** | 宽限只显示 90 秒但计时没接；录音上限未调用 | `TrainingTimer.extendUntil()` 真正把截止交给计时器；录音启动即挂 `InputDurationPolicy` 上限并可见收束 |
| **D06** | `OfflineTraining.supported()` 只接受第一题；LOCATE 靠关键词误判 | 扩到全部内置题；LOCATE 改为“点选 / 引用整句”，不再因提到单个词而通过 |
| **D07** | 多处 showBubble 未走仲裁 | `showTrainingQuestion` 走 TRAINING、`present` 走 AMBIENT、`endConversation` 释放 AMBIENT |

### D06 收紧后发现的真实缺陷

实测发现「不能保证任何人都提高」原被判 PASS —— 因为它含“任何人”，被当成“指出了依据”。
这其实是**否定了结论**，不是指出依据。收紧后：

| 输入 | LOCATE | CONNECT |
|---|---|---|
| 只提「任何人」 | NEED_CLARIFICATION | — |
| 引用整句「所以任何人使用该训练都会提高」 | PASS | — |
| 「样本不能代表所有人」 | — | PASS |
| 「不能保证任何人都提高」 | — | NEED_CLARIFICATION |
| 「我觉得只要努力就一定行…」 | — | NEED_CLARIFICATION |
| 「懂了」「有道理」 | NEED_CLARIFICATION | NEED_CLARIFICATION |

## 本轮验证（真实跑了）

| 项 | 结果 |
|---|---|
| JVM | **603 tests / 0 failures** |
| lint | **0 errors / 17 warnings** |
| assembleDebug | 成功 |
| assembleDebugAndroidTest | 成功 |
| APK | 30,242,335 bytes |
| SHA-256 | `17E0A408B34FCE81C021FE331FA005294A2C20772BA0B50B90BC3B1F72A10744` |

## 下一动作

1. 按 MERGE_MAP 移植 Claude 的 CL-T5/T6（全屏拦截接线）、CL-T11/T12（训练菜单/语音路由）、CL-S1–S8（严格回合回答入口）。**CL-T7 单题发券不合入。**
2. 在同一分支补 R21-S1 计划触发 + 主动断点恢复。
3. 逐项出 A01–A36 与 U01–U12 结果。

## 阻塞

- 真机 NOT_RUN（无设备）。
- 48 条人工黄金样例需独立审阅，本轮只整理候选。
