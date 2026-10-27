# 同桌 R21 严格守护接管 — 唯一验收实情表

- 分支：`claude/r21-strict-takeover`（与 `ccr-f3b6cf6b-6gsh3f` 同步）
- **构建源码提交：`4c8433f10e15d918986078a4bb07578659e1e499`**（本文件所在的文档提交不参与构建）
- 版本：0.8.1-r21-strict-multistep-preview（versionCode 41），debug 包，仓库公开调试签名，`debuggable=true`，**不是发行包**
- APK：`https://github.com/aozhenyu666-cmyk/test/releases/download/tongzhuo-preview-claude-r21-strict-takeover/tongzhuo.apk`
  - 30,340,635 字节；SHA-256 `009c6ced2cfa0d0298df1e8f84d40cf2089b0473a379fd88b374927b39d9869a`（已独立下载复核，与 Release 说明一致）
  - 构建：GitHub Actions run 40428123619（`:app:testDebugUnitTest`、lint、`assembleDebug`、`assembleDebugAndroidTest` 全部成功）
- 真机：**无设备，所有 DEVICE 项均为 NOT_RUN**

图例：PASS-L = 纯逻辑单测通过；PASS-I = 集成单测（多个真实类串起来）通过；WIRED = 已接线但没有自动化测试覆盖；NOT_RUN = 未执行；FAIL = 失败。

## 一、本轮做了什么（按阶段提交）

| 提交 | 内容 |
|---|---|
| `40a0d59` | 合并 DeepSeek `8fb3cc1`/`4c591de`；严格守护单一拦截拥有者 + 回到目标后带回原题；练习换来的休息真正暂停守护；通话/紧急界面不算离开；看过支架即算泄题；按要求移除 S1 计划触发 |
| `60a46f5` | 并入本交接说明（`166528c`） |
| `9f3a387` | 严格陪想/外部 AI 锁按实例号释放；延迟收尾只结束自己那一回合；删除未接线的 `extendUntil` |
| `4c8433f` | 合并 DeepSeek `1234c6a`（CL-S3/S4/S7/S8）；**把多步接线**：严格回合固定三步，每步只按自己的标准核验；提示显示“第 n/总 步” |

DeepSeek 两个提交的取舍：

| 内容 | 结果 | 原因 |
|---|---|---|
| 训练菜单、语音回答、严格回合回答入口与按题标准、录音时长上限 | 采纳（按行为移植） | 交接说明 CL-T11/T12、CL-S1/S2/S6 |
| 关键词离线判分 + 联网语义确认 | 不采纳 | D06：“样本”二字即可通过；断网无法完成；测试用伪造确认冒充离线完成 |
| 测试改成“改版本也按旧量表判” | 不采纳，恢复原测试 | 交接说明禁止放宽版本保护 |
| `QuestionSetStore` / `currentAnswerRevealed` 等题组实现 | 不采纳 | 已有 `BreakCycle`（落盘、迁移题、单次发券、硬截止） |
| 无契约时也弹全屏拦截 | 不采纳 | 交接说明：只在有效、用户授权的守护契约内联动严格模式 |
| `BINDER` 能力层、`TrainingFocusBridge` 起点/桌面不算离开 | 采纳 | 不冲突 |

### 严格回合 CL-S3/S4/S7/S8（DeepSeek `1234c6a` + 本轮接线）

| 项 | 状态 | 证据 / 说明 |
|---|---|---|
| CL-S3 多步接续 | PASS-L + WIRED | DeepSeek 的状态机支持多步，但合并时**没有任何地方填入后续步骤**，App 里仍是一步就结束。本轮在 `startStrict` 填入三步，并加 `upcomingCriteria`：每一步只按自己的标准核验（否则第一步会被第二步的“原文依据”卡住）。`StrictPerStepCriteriaTest`、`StrictMultiStepTest` |
| CL-S4 按步帮助等级 | PASS-L | `StrictMultiStepTest` |
| CL-S7 恢复后读状态机问题、打开回答通道 | WIRED | `restoreStrict → showStrictPrompt` |
| CL-S8 恢复时卡在核验中 | PASS-L + WIRED | `evaluationUnavailable` 退回等待；恢复时按实例号重新占锁；`StrictPerStepCriteriaTest.midRoundRestartKeepsStepAndRemainingSteps` |
| 已知限制 | — | 屏幕原文按隐私约束不落盘，所以**重启后**第二步“原文依据”只能凭用户自述核验 |

## 二、六项最小用户验收

| # | 项 | 逻辑层 | 证据 | 真机 |
|---|---|---|---|---|
| 1 | 显式开 STRICT、选外部 AI 目标、5 分钟守护、确认前台 | PASS-L | `StrictTakeoverTest.startInTarget` 路径、`FocusIntegrationTest` | NOT_RUN |
| 2 | 切娱乐 App → 宽限后**全屏层** → 回目标层消失；自动拉回 ≤3 次 | PASS-L | `StrictTakeoverTest.leaveBlocksFullScreenThenReturnIsVerifiedAndReported`、`autoReturnStopsAfterThreeAttempts` | NOT_RUN |
| 3 | 有效短答按本题量表核验；训练中离开、授权条件下回到原题原槽 | 核验 PASS-L；带回原题：守护层 PASS-L，PetBrain 接线 WIRED | `BreakCycleTest.bankHasFiveFamilies…`（错误选项/空泛被拒、正确短答被收）、`MaterialHardeningTest`；`StrictTakeoverTest.manualReturnFromAwayAlsoReported`（守护发出“已回到目标”）→ `PetBrain.returnedToTarget → resumeTraining`（无自动化测试） | NOT_RUN |
| 4 | 第三题杀进程：题组、泄题、宽限次数、当前槽位不丢；迟到/重复回调不加分 | PASS-L | `BreakCycleTest.cycleStateRoundTrips`、`replayedCompletionIsIgnored`；`ReviewFixRegressionTest.extensionQuotaSurvivesRestart`；泄题由落盘的 attempts 推出（`seeingScaffoldThenTypingStillNeedsTransfer`）；`TrainingContinuityRegressionTest` | NOT_RUN |
| 5 | 断网完成 5 道确定性判分题；第五题只发一次休息且守护实际暂停 | PASS-L | `BreakCycleTest.fiveIndependentPassesIssueExactlyOneGrant`（本地判分，不经模型）；`StrictTakeoverTest.earnedRestPausesGuardAndResumesOnTime`；PetBrain 发券后调用 `focusController.pauseUntil` 为 WIRED | NOT_RUN |
| 6 | 休息到期恢复守护；重启不重复发券/不重新计时/不延期；暂停、结束、紧急退出可用 | PASS-L | `StrictTakeoverTest.restNeverExtendsContractAndSurvivesRestart`、`repeatedRestPauseIsIdempotent`、`emergencyExitAlwaysWorksDuringBlock`；`BreakCycleTest.grantSurvivesRestartWithoutRestartingTimer`、`breakEndsAtHardDeadlineAndNeverExtends` | NOT_RUN |

## 三、交接说明第 2 节残留缺陷的现状

| ID | 现状 |
|---|---|
| D05 宽限没真延长 | 已修：`TrainingTimer.extend` 按批准时长等待；额度随会话落盘（`ReviewFixRegressionTest`）。DeepSeek 的 `extendUntil` 已删 |
| D04 泄题/宽限/题组只在内存 | 已修：泄题从落盘的 attempts 推出；宽限在 `TrainingSession`；题组在 `BreakCycleStore` |
| D03 force 释放 | 已修：输出权只按 owner 释放；会话锁按种类 + 实例号释放；冷启动恢复时重新占锁 |
| D06 离线判分 | 已修：只认 `OfflineBank` 里题目**整体一致**的题（改版本/原句即不套旧规则）；逐题逐槽的选项与极简答案；“任何人”作为第一槽答案可通过；不靠字数。`sample-open-01`（EXAMINE）不在本地题库，只能联网判，**不产生休息凭据** |
| D02 动作回执 | **未修**：拦截/拉回的“观察确认生效”回执没有接入能力状态，所以诊断页“能否声称监管可靠”如实显示“否” |
| U07 休息没暂停守护 | 已修：发券后守护 `pauseUntil` 到休息结束（不超过契约到期），到点恢复；崩溃补发幂等 |
| D07 输出路径直连气泡 | **部分**：训练题目、闲聊走输出仲裁；模型回调按快照丢弃迟到结果；仍有不少提示直接调用 `overlay.showBubble`，未逐条加保护 |

## 四、仍存在的限制 / 下一轮候选

1. 全部真机项 NOT_RUN：vivo/iQOO 后台拉起、全屏层在各 ROM 的显示、无障碍丢失后的表现。
2. D02 动作效果回执、D07 剩余直连输出。
3. 48 条人工核对的黄金样例（需要用户标注）。
4. 按交接说明另案：S1 计划触发（用户决定本轮移除；代码在 `da75890` 里，可以随时恢复）、跨应用中央指挥协议、Shizuku。
6. **主动断点恢复已经存在**（`ResumePolicy`：亮屏/离开后自己回来问原题原步，10 分钟冷却、每会话最多 3 次、次数落盘），不要再当作缺口重做。
5. `companion-unify-v1` 的 CL-S1–S9、CL-T1–T12 已全部处理（移植 / 等价 / 明确拒绝），不再有待移植项。
