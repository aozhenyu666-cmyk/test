# 同桌 R21 严格守护接管 — 唯一验收实情表

> 当前伙伴版本与构建以末尾“伙伴 v1”小节为准；下面原有 vc42 记录为历史验收证据。

- 分支：`claude/r21-strict-takeover`（与 `ccr-f3b6cf6b-6gsh3f` 同步）
- **构建源码提交：`1794ed1c9e0b519a387c184852b718682afe055a`**（本文件所在的文档提交不参与构建）
- 版本：0.8.2-r3-aliveness-preview（versionCode 42），debug 包，仓库公开调试签名，`debuggable=true`，**不是发行包**
- APK：`https://github.com/aozhenyu666-cmyk/test/releases/download/tongzhuo-preview-claude-r21-strict-takeover/tongzhuo.apk`
  - 30,471,703 字节；SHA-256 `604a77f2275e27595f80f46cbe7c19fce915c8a7d2353fc2740f6f616406de9a`（已独立下载复核，与 Release 说明一致）
  - 构建：GitHub Actions run 40606293938（`:app:testDebugUnitTest`、lint、`assembleDebug`、`assembleDebugAndroidTest` 全部成功）
- 真机：**无设备，所有 DEVICE 项均为 NOT_RUN**

图例：PASS-L = 纯逻辑单测通过；PASS-I = 集成单测（多个真实类串起来）通过；WIRED = 已接线但没有自动化测试覆盖；NOT_RUN = 未执行；FAIL = 失败。

## 一、本轮做了什么（按阶段提交）

| 提交 | 内容 |
|---|---|
| `40a0d59` | 合并 DeepSeek `8fb3cc1`/`4c591de`；严格守护单一拦截拥有者 + 回到目标后带回原题；练习换来的休息真正暂停守护；通话/紧急界面不算离开；看过支架即算泄题；按要求移除 S1 计划触发 |
| `60a46f5` | 并入本交接说明（`166528c`） |
| `9f3a387` | 严格陪想/外部 AI 锁按实例号释放；延迟收尾只结束自己那一回合；删除未接线的 `extendUntil` |
| `995b81f` + `1794ed1` | 合并 `feature/r3-aliveness`（R3 活人感 + 正式发布流程修复）；补 4 处接线问题（见下）；补 2 个导致编译失败的导入 |
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

### R3「活人感」（`feature/r3-aliveness`，另一个代理编写）

| 项 | 状态 | 说明 |
|---|---|---|
| 生活状态、阅读挑选、搜索解析、意图队列、说过的话账本、时刻规则、时段预算、人设提示词 | PASS-L | 本地全部跑过，包括它记为“从未跑过”的 `OwnLifeCycleTest` 12 条 |
| 接进 PetBrain / PetApp 的最后一轮 | WIRED | 它没编译过：CI 首次构建因缺两个导入失败，已补 |
| 自己读书不计每日额度 | 已修 | 搜索与挑选各占一次每日额度 |
| 后台线程直接改主线程状态 | 已修 | 并入生活状态、攒意图改回主线程 |
| 模型回 JSON 带文字/代码块即解析失败 | 已修 | 取第一个 `{` 到最后一个 `}` |
| 每秒跨进程读活动窗口 | 已修 | 只在开了主动陪伴时、每 3 秒一次 |
| 搜索 API 真实可用性 | NOT_RUN | 没有用真实 Key 请求过 |
| 主动陪伴默认开启、时段预算会盖过设置里的“每天上限” | 用户确认保持 | 2026-10-28：默认开启保留；时段预算不改 |
| 正式发布白名单加入现主线 `claude/r21-strict-takeover` | 已改（用户 2026-10-28 同意） | 分支白名单与 tag 来源校验都已加入；`release_gate_test.ps1` 默认名单同步（本环境无 PowerShell，未执行） |

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


## 五、伙伴 v1（2026-10-28）

- 分支：`claude/r21-strict-takeover`；基于 Claude `dbdac74`，最终构建源码提交 **`0ffdf01b6569d1593ff877c716ca93ab2929438b`**。
- 版本：**0.8.3-partner-v1-preview / versionCode 43**。沿用公开调试签名，仍为 `debuggable=true` 预览包。
- GitHub Actions：[Companion Pet Android #129](https://github.com/aozhenyu666-cmyk/test/actions/runs/40641286807)。**成功**：`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 及分支预览发布均通过。
- 单元测试：**795 条，0 失败 / 错误 / 跳过（PASS-L）**；其中原有 781 条不变，新增加 14 条接线相关回归。没有修改或放宽 Claude 的 partner 测试、acceptAdvice、离线判分与严格守护单一拥有者规则。
- APK：[主线预览下载](https://github.com/aozhenyu666-cmyk/test/releases/download/tongzhuo-preview-claude-r21-strict-takeover/tongzhuo.apk)。已独立下载复核：**30,783,059 字节**；SHA-256 **`158ddc395d72f853b410e7fd5e3279c608ceccacb796ed957c032ceb9cae0c41`**，与 Release 说明和资产 digest 一致。`aapt` 确认 vc43 / 版本名，`apksigner verify` 通过，证书 SHA-256 `9f3a524c66974aeb04045f498f83b5970589f9fba3ac3772e95c26e3524695e0`，与固定测试签名一致；安装包包含独立聊天 taskAffinity，仍为 debuggable。
- 每项提交、文件、调用链、额外修复与逐步真机操作见 [伙伴交付](../partner-v1/IMPLEMENTATION.md)。

| 项 | 当前接线 | 自动化证据 | 真机 |
|---|---|---|---|
| G1 多轮与恢复 | WIRED | DialogueRules + DialogueStore 十轮、压缩、二十分钟恢复回归 PASS-L | NOT_RUN |
| G2 完整聊天页 | WIRED | Compose 与 Android 测试 APK 已构建；没有运行设备 UI 测试 | NOT_RUN |
| G3 骑行语音 | WIRED | TTS onStart/onDone 与识别代数接线；不能用纯 Kotlin 测试证明系统音频和后台麦克风效果 | NOT_RUN |
| G4 主动调度 | WIRED | Governor / PartnerDelivery 的失败不消费、重复回执、零时刻、频率更新回归 PASS-L | NOT_RUN |
| G5 回答规则 | WIRED | 合格回复、一次修复、连续不合格拒绝、断网不额外重写、骑行字数回归 PASS-L | NOT_RUN |
| G6 看图与追问 | WIRED | VisionTalk 原有测试；完整观察不写入会话文件回归 PASS-L；未用真实视觉 Key | NOT_RUN |
| G7 话题线与确认记忆 | WIRED | 遗忘后关闭持久化跟进、夜间不绕过确认、提问后重启恢复等待窗口回归 PASS-L | NOT_RUN |
| G8 记忆与话题页 | WIRED | 显示来源原话、重要度、跟进时间；暂停/关闭/删除入口已编译 | NOT_RUN |
| G9 观点留存 | WIRED | 只在发出后留存引用句；素材失效使关联线关闭；生成的摘录不触发材料陪练 | NOT_RUN |
| G10 设置与诊断 | WIRED | 每小时 5–40、默认 20；睡眠/安静 30 分钟；最近五条来源和拦截原因 | NOT_RUN |

### 真机仍需验证

1. 同一话题十轮、切走二十分钟恢复；收起后迟到回复不弹回。
2. 一小时实际开口次数、具体锚点质量、打字/通话/录音时让出通道；目标频率不是实测次数。
3. 耳机语音十分钟、锁屏、来电、识别失败与停止通知；系统回调不能证明人确实听见。
4. 手动带问题看图与主动看视频/游戏；对照画面判断实际理解质量。
5. 记忆确认/拒绝、查看来源、遗忘后不重提；六小时/一天后的真实跟进。

D02 严格守护动作效果回执仍未修，全部旧 DEVICE 项仍为 NOT_RUN。Governor 小时统计和说过的话账本在进程内，重启会重新累计。新伙伴夜间批次不自动新增档案，混合批次中的其他新增档案也会保守跳过。构建通过不等于后台长期可靠。
