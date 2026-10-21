# v4 · 核心逻辑的技术方案（C4–C7，给下一次对话里的 Claude）

> 2026-10-21。`v4-power.md` 是“要什么”；这一份是“核心逻辑怎么写”。C1–C3 已经做完（见文末“已完成”）。
> C4–C7 都写在墨灵的 `core/` 里（纯 Kotlin、单元测试），App 只调用。每块写完：在 `CONTEXT.md` 记一笔，再写对应的 DeepSeek 文档（第六、七批）。
> 写之前先读：`moling/app/src/main/java/com/moling/core/Brain.kt`（大脑的入口和规矩）、`power/Powers.kt`（权柄）、`mind/DirectorEngine.kt`（导演）。

---

## 已完成（C1–C3，2026-10-21）

- 总控 `core/moling/Authority.kt`：`GrantBook` / `Grant` / `GrantKind`（授权书，给多立即、收回走冷静期）、`Hold` / `Holds`（临时严管、只减今天的娱乐）。`Engine.evaluate(..., holds)` 认它们；紧急放行也认。
- 总控 `core/moling/Moling.kt`：`receive` 认 `auto=true`（授权内 `SCHEDULED`、60 秒后执行，可 `veto`），`execute`（临时开关写进记录、别的走参谋工具箱），新指令 `commit` / `directive`，状态多了 8 个字段。
- 总控 App：`MolingBridge.run / runDue / veto`、倒计时通知、每分钟巡检兜底。
- 墨灵 `link/ZkLink.kt`：`Commit` / `Directive`、`strict` / `cut_play`、`toMapAuto`、`ZkResult`；`Brain`：规划者 / 管家对 AUTO 的事直接 `sendAuto`，`Proposal.auto`，取消计入接受率；`Talk` 能说“严管 / 减娱乐 / 约定”。
- 测试：`tools/core-tests/` 里 `gradle test` 一次跑完总控 core（278）、墨灵 core（61）和两边约定的交叉测试（`ContractTest`，墨灵发的每种指令总控都认、状态字段一个不丢）。改了两边的约定一定要在 `ContractTest` 里加一条。

---

## C4：升级阶梯和说法对证据（`core/watch/Ladder.kt` + `Brain.tick`）

### 升级阶梯

同一次偏离（同一条水道、同一段娱乐）从轻到重，一步一步来：

```kotlin
@Serializable enum class Rung { NOTICE, WINDOW, PROPOSE, AUTO }
@Serializable data class Ladder(
    val key: String,          // "日期:水道id"
    val rung: Rung,
    val at: Long,             // 这一级是什么时候上的
    val answered: Boolean = false,
)
object Ladders {
    /** 这一分钟该不该升一级；返回新的阶梯和要做的事（null = 不动）。 */
    fun step(cur: Ladder?, drift: Drift?, now: Long, tier: Tier, cfg: LadderConfig): Pair<Ladder?, Rung?>
}
data class LadderConfig(val gapMin: Int = 5, val maxRung: Rung = Rung.AUTO)
```

规则：
- 没有偏离（`drift == null`）或他回应了（`answered`）→ 阶梯清零，返回 `null`。
- 第一次发现：`NOTICE`（现在 `Brain.drift` 做的那句通知）。
- 之后每过 `gapMin` 分钟还在偏离，升一级：`WINDOW`（App 用小窗展开那张卡）→ `PROPOSE`（专注锁提议）→ `AUTO`（只有管家：`sendAuto` 专注锁，总控按授权书倒计时）。参谋最高到 `WINDOW`，规划者最高到 `PROPOSE`。
- 陪伴模式、`POOL / FREE` 水道、静默时段、总控时间里不走阶梯（`Flow.drift` 已经排除了水潭和自由水域）。
- “回应了”：他对这张卡 / 提议有任何操作（好、不用、选项、卡住了）。`Brain` 加 `fun answered(key: String)`，App 在卡片 / 小窗回调里调。

`Brain.tick` 改成：算 `drift` → `Ladders.step` → 按返回的 `Rung` 出 `Notice`（`kind = "drift"`，新增字段 `rung`，App 据此决定通知还是小窗）。`BrainState` 加 `ladder: Ladder?`。

测试：参谋只到 WINDOW；管家 4 步后发 `sendAuto`；中间回应一次就清零；5 分钟内不重复升级。

### 说法对证据

他说“我做了 40 分钟”、演员收线说“完成了”——和总控的数对一下。

```kotlin
@Serializable data class Claim(val id: String, val at: Long, val who: String, val text: String, val metric: Metric?, val value: Double?, val verdict: String = "")
object Claims {
    /** 从一句话里认出可对账的说法（做了 N 分钟 / 玩了 N 分钟 / 做完了 X / 花了 N 元）。认不出返回 null。 */
    fun parse(text: String, now: Long): Claim?
    /** 和总控的状态对：差得不多（±20% 或 ±10 分钟）算对得上。 */
    fun check(c: Claim, s: ZkStatus): String   // "对得上" / "总控记的是 15 分钟" / "看不到"
}
```

- `Brain.chat` 里先 `Claims.parse(他的话)`，有就 `check`；对不上时把“总控记的是 X”作为一行写进提示词（`【数字】…`），让她温和地摆出来，并记进事故簿（`director` 的 `Incident(kind = "说法对不上")`——需要给 `DirectorEngine` 加一个 `fun note(incident)`，或者 `Brain` 自己存 `claims` 列表，二选一，倾向后者，不动导演）。
- 演员收线（`director.onSession` 的 `conclusion`）也走一遍 `parse`。
- 测试：几种中文说法（“写了四十分钟”“搞了快一个小时”“刷了半小时抖音”）都认得出；数字用 `Conclude.cnDigits` 先转。

---

## C5：复盘、四个参谋、她对你的了解、打法本（`core/review/`）

### 周报 / 月报

```kotlin
@Serializable data class WeekReport(
    val week: String,                // 周一的日期
    val flowDone: Double,            // 水道完成率：MAIN/SIDE 里 DONE 的比例（按天平均）
    val drifts: Int, val driftHours: List<Int>,   // 偏离次数、最常偏离的钟点
    val promisesKept: Double,        // 约定兑现率（从总控：openCommitments 的变化不够，需要总控状态里再加 keptWeek / brokenWeek 两个数——总控 C3 补）
    val brier: Double, val acceptRate: Double, val vetoes: Int,
    val nudgesPerProgress: Double,   // 撤支架：打扰次数 / 做事小时
    val change: String,              // 本周最大的两个变化（一句话）
    val tryNext: String,             // 下周试一个改动（来自打法本候选）
    val text: String,                // 模型写的 5 句话（模型不可用就本地拼）
)
object Reviews {
    fun week(state: BrainState, mind: MindState, week: String): WeekReport   // 纯计算
    fun weekPrompt(r: WeekReport, persona: String): String
}
```

- `Brain.night` 里：周日晚（`Night.isWeeklyReviewDay`）出周报，存 `BrainState.weeks`（留 12 周），返回的那段话后面加“周报出来了”。
- 每天的数要先存下来：`BrainState.days` 现在只有 `DayStat(play, work, mainStarted, spend)`，加 `flowDone`、`drifts`、`nudges`（默认 0，旧数据兼容）。
- 月报：每月 1 号，只问三个问题（长期目标还对吗 / 哪件事拖了三周 / 要不要换方向），存 `BrainState.months`，不下结论。

### 四个参谋

```kotlin
object Council {
    fun prompt(question: String, situation: String, persona: String): String   // 让模型以 教练 / 军师 / 对手 / 朋友 各写一行，再写【合起来】一行
    fun parse(reply: String): Council?   // 四行 + 合起来；缺任何一行返回 null
}
data class Council(val coach: String, val strategist: String, val critic: String, val friend: String, val together: String)
```

- 只在三种时候用：`planDay(force = true)`（重排）、改道原因是 `CONFLICT`、他在对话里说“要不要换方向 / 放弃 / 不做了”。`Brain` 加 `suspend fun council(question, now): Council?`，结果的 `together` 给他看，四行存进日志（调试页可见）。
- 测试：解析容错（顺序乱、多余空行）；缺一行返回 null。

### 她对你的了解（确认流程）

导演的 `MindState.memories`（`MemoryKind.PATTERN / PREFERENCE / FACT`）现在由夜间整理自动产生，没人确认。

```kotlin
@Serializable data class Belief(val memoryId: String, val text: String, val evidence: List<String>, val confidence: String, val status: String = "待确认")  // 待确认 / 对 / 不对 / 不确定
```

- `BrainState.beliefs`：夜里把新出现的 `PATTERN`（`hits >= 3`）变成 `Belief("待确认")`，证据是导演 `tallies` 里出现的日期。
- `Brain.confirm(memoryId, status)`。只有“对”的才进：`Flow.Inputs.patterns`（排水道）、`Predict.prompt` 的模式、对话提示词的【我注意到】。“不对”的进导演的反例（下次夜间整理时不再提，需要在 `Night.patterns` 外面过滤——在 `Brain` 里过滤，不改导演）。
- 测试：待确认的不进排水道；确认后进；“不对”的一周内不再出现。

### 打法本

```kotlin
@Serializable data class Tactic(
    val id: String, val from: String,          // 来自哪条 Belief
    val rule: TacticRule, val since: Long,
    val status: String = "试用",                // 试用 / 留下 / 撤掉
    val beforeScore: Double = -1.0, val afterScore: Double = -1.0,
)
@Serializable sealed interface TacticRule {
    /** 某个钟点之前的主河道前，插一个 N 分钟的源头。 */
    @Serializable data class SpringBefore(val afterHour: Int, val minutes: Int) : TacticRule
    /** 提醒提前 N 分钟。 */
    @Serializable data class EarlierNudge(val atHour: Int, val minutes: Int) : TacticRule
    /** 某段钟点里主河道最长 N 分钟（拆小）。 */
    @Serializable data class ShorterMain(val fromHour: Int, val toHour: Int, val maxMinutes: Int) : TacticRule
}
object Tactics {
    fun apply(plan: FlowPlan, tactics: List<Tactic>): FlowPlan   // 在 Flow.local / parse 之后套用
    fun score(days: List<DayStat>, from: Long, to: Long): Double // 用 flowDone 和 drifts 算
}
```

- 确认为“对”的 Belief，夜里让模型（或本地规则：关键词“下午 / 卡住”→ `SpringBefore(13, 10)`）提一条 `Tactic("试用")`，他在 App 里点“试一周”才生效。
- 一周后周报比较 `beforeScore / afterScore`：变好留下，变差或没变撤掉（他也可以手动撤）。
- `planDay` 在得到 `plan` 后调 `Tactics.apply`。
- 测试：`SpringBefore` 插得对、不重叠；`apply` 后 `fitDay` 规则仍然成立；评分对比。

---

## C6：模式和说话方式（`core/talk/`）

```kotlin
@Serializable enum class TalkMode(val label: String) { WORK("工作"), TRAIN("训练"), START("启动"), REST("休息"), COMPANY("陪伴") }
object Modes {
    /** 根据局面猜模式：主线过点没开始 → START；他说累 / 烦 → REST 或 COMPANY；在源头水道 → TRAIN；默认 WORK。 */
    fun guess(text: String, facts: Facts, flowNow: Channel?): TalkMode
}
```

- `Talk.prompt` 加参数 `mode: TalkMode`，每种模式一段不同的回复规矩（README 第 7 节的表）。`Brain.chat(text, now, mode: TalkMode? = null)`：null 就 `Modes.guess`；`ChatResult` 带回用的模式，App 显示在模式条上，他可以点改（下一句用他选的）。`BrainState.mode` 记住他手动选的，1 小时后失效。
- **训练模式**：`Talk.trainPrompt(question, history)`：一手式追问（依据是哪一句 / 接下来会怎样 / 有没有反例），每轮只指出一处；解析 `【分】0–3` 存进 `BrainState.drills`（日期、题、分）。
- **反驳我**：`Talk.devilPrompt(lastUserClaim)`：只挑漏洞，最多三条，不给结论。
- **疯想—冷判**：`Talk.wildPrompt(topic)` 出 5 条离谱路线；`Talk.coldPrompt(lines)` 逐条标“事实 / 假设 / 可以试”并选一个最便宜的小实验，解析成 `ZkCommand.AddAction`（今天、15–30 分钟）作为提议。
- `Brain` 加 `suspend fun devil(now)`、`wild(topic, now)`、`train(answer, now)`，都返回 `ChatResult`。
- 测试：每种提示词都包含它的规矩；解析器对缺行、乱序容错；`Modes.guess` 的几种典型局面。

---

## C7：日历、作息、收线识别（`core/flow/`、新 `core/rhythm/`）

### 日历进水道

```kotlin
data class Busy(val start: Long, val end: Long, val title: String)
// Flow.Inputs 加：val busy: List<Busy> = emptyList()
```

- `Flow.local`：主河道和支流不放进 `busy` 的时间段（往后挪到第一个够长的空档）；`Flow.morningPrompt` 加一行【固定安排】。
- `Flow.parse` 之后检查：和 `busy` 重叠的水道往后挪（复用 `shift`），挪不下就去掉支流（复用 `fitDay`）。
- `Brain.planDay(now, force, busy = emptyList())`：App（第六批）读日历传进来。
- 测试：14:00–16:00 有课时，主河道不会排在 15:00；空档不够时去掉支流而不是主河道。

### 作息

```kotlin
data class ScreenEvent(val at: Long, val on: Boolean)
@Serializable data class Night(val day: String, val sleepAt: Long, val wakeAt: Long, val wakeUps: Int)
object Rhythm {
    /** 用亮屏 / 熄屏推算昨晚：22:00–11:00 之间最长的一段熄屏算睡觉；中间亮屏不到 3 分钟算“醒了一下”。 */
    fun lastNight(events: List<ScreenEvent>, day: String): Night?
    fun bestHours(nights: List<Night>, days: List<DayStat>): List<Int>   // 以后用；先返回空
}
```

- App（第六批）用 `UsageStatsManager.queryEvents` 的 `SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE` 喂进来：`Brain.sleep(events)`，存 `BrainState.nights`（留 30 天）。
- `planDay`：昨晚睡不到 6 小时 → 主河道最长 45 分钟、多一个水潭；`Predict.prompt` 加一行昨晚睡了多久。
- 测试：典型的一晚、半夜看手机两次、通宵（没有 ≥3 小时的熄屏 → null）。

### 收线识别

- 第四批 P6 让用户确认剪贴板原文后交给 `director.onSession`，导演已经会用 `Conclude` 拆。这里只补：`Conclude.looksLikeClosing(text): Boolean`（有“收线 / 结论 / 下一步”标签，或者“聊出了 / 下一步 / 几点”这类句式），App 用它决定要不要弹“这是刚才聊出来的吗？”。测试几种 GPT / Gemini 常见的总结格式。

---

## 写完之后

1. `CONTEXT.md`：第 5 节记一笔每块做了什么。
2. 写 `moling/DEEPSEEK-6.md`（日历、作息、她对你的了解页、打法本页、周报 / 月报页、成绩单）和 `DEEPSEEK-7.md`（模式条、连续语音对讲、训练模式、反驳我 / 疯想、专注时开勿扰、Notion 镜子），格式照第三、四、五批：开工前、速查表、每步“建什么 / 验收”、底线。两批也要能分开并行（文件不重叠）。
3. 总控那边 C5 需要的两个数（本周约定做到 / 没做到）：在 `MolingStatus` 加 `keptWeek` / `brokenWeek`，从 `dossier.commitments` 算；墨灵 `ZkStatus` / `ZkWire.status` 同步加；两边测试 + 交叉测试。
