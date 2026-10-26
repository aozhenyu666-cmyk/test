# R2.1 验收审查（对 deepseek/r21-proactive @ 66ce3ca）

日期：2026-10-26。版本 36 / 0.7.6-r21-review-preview。

## 已修的 bug

| # | 问题 | 后果 | 修法 |
|---|---|---|---|
| 1 | 训练**完成**时不释放 `SessionArbiter` / `OutputArbiter` 锁，只有“结束本次”才放 | 完成后点“下一题”被自己挡住（“当前已有训练在进行”）；严格陪想、外部 AI 也一直开不了 | 完成时 `releaseTrainingLocks()` |
| 2 | 温和搭话 `present()` 用 `acquire(AMBIENT, "ambient-"+now)` 占输出权，从不释放 | 第一句闲聊之后，所有温和主动搭话都被“同级不可抢占”挡掉，桌宠基本哑了 | 改为 `allows(AMBIENT)` 只检查不占用 |
| 3 | `SessionArbiter.release(kind, force=true)` 完全不核对种类 | `endThink()`/`strictAbort()`/`finishSession()` 会把**训练**或外部 AI 的锁一起放掉，互斥失效 | `force` 只跳过实例号，种类仍须一致；仅 `NONE+force` 清空 |
| 4 | 严格陪想**自然到期**时不放锁（`tickStrict` 先把 `flow.strict` 置空，`endThink` 里的释放走不到） | 严格回合结束后训练永远开不了 | 到期时释放；`endThink` 无条件释放 STRICT_THINK |
| 5 | 重启恢复训练/严格回合时不重新占锁 | 恢复后的训练能与严格陪想/外部 AI 并行 | 恢复时 `acquire` |
| 6 | S6 材料题第二步：本地形式合规的 PASS 带 `uncertainty`，直接交给 `acceptAdvice` 必被判技术故障，且从不调用模型 | **材料题第二步永远过不去**，S6 闭环无法完成、机会永远结算不了“有推进” | 形式合规 → 交给模型核对语义；无模型时如实说明“需联网核对”，不放行 |
| 7 | 材料匹配找不到 `sourceEventId` 时回退到“最新一条材料” | 与注释矛盾，连续导入会串题 | 删除回退，找不到就不出题（记抑制原因） |
| 8 | “我在想”批准后用 `trainingTimer.restore()`，实际是重置成默认 60 秒（且丢了读题加时），不是承诺的 90 秒 | 提示说“再给你 90 秒”，实际 60 秒就催 | 新增 `TrainingTimer.extend(now, millis)` |
| 9 | “我在想”额度只在内存里 | 进程重启后额度重置，可无限刷新（违反 S7） | 额度存入 `TrainingSession.extensionTimes/extensionMillis` 随断点落盘 |
| 10 | 核验中/恢复中也能点“我在想” | 无意义地消耗额度、打乱计时 | 仅 WAITING_INPUT / SCAFFOLDED 允许 |
| 11 | 材料题之后开内置题，`materialPlan` 残留 | 潜在串判分计划 | 开新内置题时清空 |

新增 `ReviewFixRegressionTest`（8 条）。

## 验证

- 本环境无法访问 Google Maven（dl.google.com 被网络策略拒绝），**不能本地构建 APK**。
- 把不依赖 Android 的源码和测试单独用 Kotlin/JVM 编译：**580 tests / 0 failures**（原 572 + 新 8）。
- `PetBrain.kt` 依赖 Android，本地未编译，靠推送后 GitHub Actions 的完整构建验证。

---

# 第二轮：替 DeepSeek 完成的两项（版本 37 / 0.7.7-r21-break-health-preview）

## 一、五题换休息端到端（S4；A05/A08/A17 的逻辑层）

| 部分 | 文件 | 做了什么 |
|---|---|---|
| 离线题库 | `core/training/OfflineBank.kt` | 1 道 → **11 道**本机可判题，5 类缺陷（以偏概全、先后≠因果、样本太小、幸存者偏差、缺对照），每类 ≥2 道互为迁移题。每题有：认知动作、选项、正确项、可接受极简答案、判据原文、常见误解、提示、量表版本。**AI 编写，待人工核对**，UI 仍标“内置示例题”。 |
| 本地判分 | `OfflineTraining.kt` | 改为按题库查表；原 `sample-scope-01` 判据不变。只认选项/列出的极简答案（忽略空格标点），其余要求澄清。 |
| 一轮结算 | `core/training/BreakCycle.kt` | 开轮时固定快照：几题、休息几分钟、硬截止（取“设置的整轮时长”与“专注契约到期”中较早者）。 |
| | | 只有**本地确定性判分**的通过产生凭据；模型判的通过算训练完成、不发券。 |
| | | 点了选项（答案被说破）→ 这题不给凭据，下一题自动排同类没见过的迁移题，独立做对记 `TRANSFER_PASS`。 |
| | | `FIRST_TRY_INDEPENDENT / PROMPTED_PASS / TRANSFER_PASS` 分开记；同一会话重放、同题再答都不重复计。 |
| | | 攒满 → **先落盘“已发放”再写许可**；许可按同一 eventId 幂等，崩在中间由 tick 补发，不会发两次、重启不重新计时；休息结束不晚于硬截止。 |
| | | 休息到点且硬截止未到 → 开新一轮（同截止、不重复出题）；硬截止到 → 结束本轮，记未完成，不加题不延长。 |
| 接线 | `PetBrain.kt` | `openTraining` 从本轮挑题；完成时结算并在气泡里说清“合格 x/5”或为什么不算；休息期内暂停训练拦截；每 5 秒推进轮次；原来从未被调用的 `maybeGrantRest` 删除。 |
| 设置 | `Settings.kt` / `SettingsTab.kt` | 新增“攒满几道换一次休息”（默认 5）、“整轮最长分钟数”（默认 45）。诊断页显示本轮进度。 |

## 二、无障碍执行路径健康挑战（A13 / A35 的逻辑层 + 真实接线）

- `PerceptionService.runHealthChallenge`：挑战投到**与前台确认同一个 Handler**，在那里做同样的 `rootInActiveWindow` 读取，**做完才回执**（stage=`a11y_handler_root_read`，带服务实例号）。
- `PerceptionHub.challenge / onHealthReceipt`：服务没连上直接记 `UNAVAILABLE`。
- `core/training/ExecutionPathMonitor.kt`：发起端单调时钟判 3 秒超时；超时后到的回执、ID/nonce 不符、入口回显、窗口读失败一律不算健康；结果超过 3 个周期没刷新变 `UNKNOWN`；有约束时 60 秒一次，平时 5 分钟一次，不堆积。
- 有约束（专注守护或训练）进行时执行路径坏了 → toast 告知一次（不盖题目），恢复后重置；设置 → 诊断和诊断报告里都能看到。

## 验证

- JVM：**602 tests / 0 failures**（新增 BreakCycleTest 12、ExecutionPathMonitorTest 10）。
- 完整 Android 构建：见 GitHub Actions。

## 仍然没做 / 不能宣称

- 真机全部 NOT_RUN：主线程真卡死时 toast 能否及时出现、各 ROM 上 `rootInActiveWindow` 行为都未验证。
- 健康挑战只覆盖无障碍前台确认这条路径；Shizuku 与系统锁屏的动作效果回执（`OBSERVED_CONFIRMED`）仍未接。
- 题库 11 道与判据未经人工核对；本地判分只认列出的说法，换种正确说法会被要求澄清（宁可误拒，不误发券）。
- 休息只暂停“训练拦截”，不暂停 Gemini 专注守护（那是另一份契约）。

---

# 第三轮：S1 到点主动出题 + S6 材料题加固（版本 38 / 0.7.8-r21-plan-material-preview）

## 一、S1 到点主动出题（A18 / A25 的逻辑层 + 接线）

- 设置 → 陪练与专注策略 → “到点主动出题”（默认关）+ 时间点（如 `08:30, 20:00`）。
- `core/training/TrainingPlan.kt`（纯逻辑）：每个时间点按 `slotId` 只处理一次；本地规则依次检查：暂停/安静时段（SKIPPED）→ 严格陪想、外部 AI、想一想、聊天、技能占用（DEFERRED，宽限内再试）→ 熄屏或悬浮窗不可用（发一次通知，记 NOTIFIED，**不算看见**；亮屏后真正出题）→ 出题。
- 有没做完的训练就**接着原题原步骤**（RESUMED），否则出本地题（开了“练习换休息”就从题组里挑）。
- 每条记录都写“计划时间 → 实际处理”的延迟；宽限 30 分钟，超过记 MISSED 并写明原因（没在运行 / 通知过但一直没能展示 / 一直被占用），**不补发旧题**；两个时间点挤在一起只做最近一个；计划开启之前的时间点不算错过；展示失败两分钟内不重试。
- 是否“显示了”以题目气泡真在屏幕上且训练真在进行为准，不以调用成功为准。
- `service/PlanAlarm.kt`：`setAndAllowWhileIdle` 闹钟叫醒；服务在跑就交给它；不在跑就试着拉起；系统不允许后台拉起时退回发通知并记账（NOTIFIED / 通知被关则 BLOCKED_BY_OS）。服务启动时重排闹钟；亮屏时立即补处理。
- 设置 → 诊断能看到最近 5 次计划的真实结果。

## 二、S6 材料题加固

| 问题 | 修法 |
|---|---|
| “都”在中文里太常见，随便一句都能出题 | 线索分级：明确全称词（任何人/所有/凡是/一律…）> 强因果词（所以/因此…）> 带结论词的“都”；单独的“都”不算 |
| 干扰项也含线索词，“哪一句”可能有两个正确答案 | 干扰项只用不含任何线索的句子 |
| 正确句永远排第一 | 候选句按原文顺序排列 |
| 题干里单独贴出正确那句，恢复进度时等于直接给答案 | 题干列出全部候选句 |
| 说“我都不知道”也能因含“都”通过第一步 | 单字线索只接受很短的回答，拒绝“不知道/不会”类 |
| 重启后材料题的本地判分计划丢失，第一步只能交给模型 | `MaterialTraining.restorePlan` 从断点里的题目重建，不依赖材料还在 |

## 验证

- JVM：**622 tests / 0 failures**（新增 TrainingPlanTest 13、MaterialHardeningTest 7）。
- 完整 Android 构建：见 GitHub Actions。

## 仍未验证

- 真机：各 ROM 上闹钟被推迟多少、后台拉起是否被拒、通知权限被关时的表现，都只能真机测。
- 保护情境只认本机能可靠判断的（暂停、安静时段、其他会话）；**通话、导航无法识别**，没有冒充能判断。

---

# 第四轮：合并 DeepSeek batch3 + 主动断点恢复 + 偏离带回原题（版本 39 / 0.7.9-r21-merged-resume-preview）

## 重要：DeepSeek batch3 做在了旧分支上

`deepseek/r21-proactive` 的 dd7fe60 / 1b0e0ab 接在 66ce3ca 后面，**不含本分支前三轮的任何改动**。
所以它报告里“S1 没有”“6 道离线题”等判断，针对的是旧代码。本轮已把它合进 `ccr-f3b6cf6b-6gsh3f`：

| DeepSeek batch3 的内容 | 处理 | 原因 |
|---|---|---|
| `SemanticConfirm`（按本题要点让模型只做一次语义确认） | **采纳**，接到材料题第二步 | 比本分支原来的“交给通用训练提示词”更贴题 |
| 内置题加 rubricPoints / nextStep，新增 3 道模型判题 | 采纳 | 不冲突 |
| `recordQuestionCredit` 题组结算 | 不采纳（保留 `BreakCycle`） | 进度只在内存（重启丢）、模型判的题也发凭据、选项说破不要求迁移题、发过一次后不再开新一轮 |
| `runEquipmentChallenge` + 服务里的 `challengeResponder` | 不采纳（保留 `ExecutionPathMonitor`） | 回执不读活动窗口、永远 ok=true；每次挑战覆盖全局回调 |
| `endConversation` 里释放 AMBIENT | 不需要 | 本分支闲聊已改为只检查不占用 |
| `capabilityLines` / `canClaimReliable` 没进 UI | **补上** | 设置 → 诊断与诊断报告显示五层状态和“能否声称监管可靠”（设备效果回执未接，所以如实为“否”） |

## 主动断点恢复（新）

`core/training/ResumePolicy.kt`：用户离开后，同桌**自己回来**接着原题原步骤问。
- 条件：亮屏解锁、非安静/暂停、没有别的会话、不在休息期、题目气泡没显示、不在核验中。
- 两次之间至少 10 分钟；每个会话最多 3 次，第 3 次会说明“这是最后一次”，之后只在用户点开时继续（A29）。
- 次数随会话落盘，重启不重置。亮屏 3 秒后评估一次，平时每 15 秒评估。

## 偏离触发带回原题（新）

训练中切到受限应用：原来只弹“先回到训练”，现在**直接把原题原步骤显示在当前屏幕上，可就地作答**；同一应用 30 秒内不重复弹、不重复朗读。

## 验证

- JVM：**644 tests / 0 failures**（含 DeepSeek 的 BuiltInQuestionsTest、QuestionSetSettlementTest、SemanticConfirmTest；新增 ResumePolicyTest 6）。
- 完整 Android 构建：见 GitHub Actions。

## 还没合的：`claude/companion-unify-v1`（08f4dbe）

基于更老的 c5438a2，PetBrain 改了 688 行，很多点后来已用别的方式修过。逐条对照合并需要单独一轮，见给用户的说明。
