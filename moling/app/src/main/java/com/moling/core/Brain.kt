package com.moling.core

import com.moling.core.atlas.Atlas
import com.moling.core.atlas.AtlasOps
import com.moling.core.atlas.Candidates
import com.moling.core.atlas.Node
import com.moling.core.atlas.NodeKind
import com.moling.core.atlas.Relation
import com.moling.core.flow.Cause
import com.moling.core.flow.Flow
import com.moling.core.flow.FlowPlan
import com.moling.core.guard.GuardAction
import com.moling.core.guard.GuardMemory
import com.moling.core.guard.GuardSample
import com.moling.core.guard.ZkGuard
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkResult
import com.moling.core.link.ZkStatus
import com.moling.core.link.ZkWire
import com.moling.core.link.describe
import com.moling.core.link.power
import com.moling.core.mind.Actor
import com.moling.core.mind.Ask
import com.moling.core.mind.BEIJING
import com.moling.core.mind.DirectorConfig
import com.moling.core.mind.DirectorEngine
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource
import com.moling.core.mind.MemoryKind
import com.moling.core.mind.MindStore
import com.moling.core.mind.Opening
import com.moling.core.mind.Sense
import com.moling.core.mind.SenseKind
import com.moling.core.mind.dayKey
import com.moling.core.power.Charter
import com.moling.core.power.Power
import com.moling.core.power.Tier
import com.moling.core.power.TrustStats
import com.moling.core.predict.BetKind
import com.moling.core.predict.BetState
import com.moling.core.predict.Bets
import com.moling.core.predict.ChoiceBet
import com.moling.core.predict.DayStat
import com.moling.core.predict.Predict
import com.moling.core.predict.Prediction
import com.moling.core.talk.Talk
import com.moling.core.talk.TalkAction
import com.moling.core.talk.Turn
import com.moling.core.talk.TalkMode
import com.moling.core.talk.Modes
import com.moling.core.watch.Claim
import com.moling.core.watch.Claims
import com.moling.core.watch.Ladder
import com.moling.core.watch.Ladders
import com.moling.core.watch.Rung
import com.moling.core.watch.Wake
import com.moling.core.watch.Wakes
import com.moling.core.review.About
import com.moling.core.review.AboutVersion
import com.moling.core.review.Council
import com.moling.core.review.Councils
import com.moling.core.review.Reviews
import com.moling.core.review.WeekReport
import com.moling.core.rhythm.Rhythm
import com.moling.core.rhythm.ScreenEvent
import com.moling.core.rhythm.SleepNight
import com.moling.core.flow.Busy
import com.moling.core.live.Activity
import com.moling.core.live.GateRules
import com.moling.core.live.GateSession
import com.moling.core.live.Live
import com.moling.core.live.LiveConfig
import com.moling.core.live.LiveLine
import com.moling.core.live.Mood
import com.moling.core.live.Spirit
import com.moling.core.live.Spirits
import com.moling.core.live.Trigger
import com.moling.core.autopilot.AutopilotSignal
import com.moling.core.live.Can
import com.moling.core.live.Exposure
import com.moling.core.live.HabitBook
import com.moling.core.live.Habits
import com.moling.core.live.Modality
import com.moling.core.live.Quiz
import com.moling.core.live.Quizzes
import com.moling.core.live.Reaction
import com.moling.core.mirror.Absorbed
import com.moling.core.mirror.InboxItem
import com.moling.core.mirror.Mirror
import com.moling.core.mirror.Question
import com.moling.core.mirror.Section
import com.moling.core.think.Recall
import com.moling.core.think.Say
import com.moling.core.think.Think
import com.moling.core.think.ThinkResult
import com.moling.core.think.Thought
import com.moling.core.atlas.Graph
import com.moling.core.forage.Brief
import com.moling.core.forage.Forage
import com.moling.core.review.Rule
import com.moling.core.review.Rules
import com.moling.core.spar.Chess
import com.moling.core.spar.ChessTurn
import com.moling.core.spar.Chesses
import com.moling.core.spar.MindAct
import com.moling.core.spar.MindMark
import com.moling.core.spar.Rpm
import com.moling.core.usage.Session
import com.moling.core.usage.UsageDay
import com.moling.core.usage.UsageLog
import com.moling.core.flow.ChannelKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/*
 * 墨灵的大脑（保守版）：把导演、水道、体系图、预言、权柄和总控约定串成一个入口。
 *
 * App 只和 [Brain] 打交道（BrainHost 持有一个实例）。规矩写在代码里，模型说了不算：
 *  1. 墨灵只提议，总控来执行。凡是要改动总控的（定主线、排行动、专注锁、总控时间、收紧、申请放宽），
 *     一律先变成一条「提议」[Proposal]；用户点“好”之后才通过 [ZkPort] 交给总控，总控按自己的规矩再确认
 *     （放宽走 24 小时冷静期）。权柄等级只决定墨灵多主动地提，不能跳过确认。
 *  2. 对话里提出的其他动作（记进体系图、改道、去聊）也是提议，点“好”才做。
 *     用户自己点的按钮（改道、交出水道、贴材料）直接做。
 *  3. 修复和冻结不归墨灵：总控掉线时只提醒，交给总控自愈和哨兵。
 *  4. 提议在发出前过两道关：[ZkWire.valid]（明显不合理的直接丢掉）和 [Charter.check]（当前等级不允许的丢掉）；
 *     用户点“好”时再查一次（等级可能变了）。
 *  5. 模型失败都有本地兜底；所有改动状态的操作用一把锁串行，状态每次改完就存。
 */

/** 墨灵 → 总控的通道（App 用 ContentProvider.call 实现，见 [com.moling.core.link]）。 */
interface ZkPort {
    /** 总控此刻的状态；连不上返回 null。 */
    fun status(): ZkStatus?

    /** 把一条指令交给总控；连不上返回 null。总控收下不等于执行了，回执里写它怎么处理。 */
    fun send(command: ZkCommand): ZkReceipt?

    /** 交过去的一条后来怎么样了（[ZkReceipt.id]）；连不上返回 null。 */
    fun result(id: String): ZkResult? = null

    /**
     * 请求按总控的授权书执行（[ZkWire.toMapAuto]）：授权内倒计时 60 秒后执行，授权外总控照旧问用户。
     * 没实现时退回普通的 [send]（总控会问用户），所以老的实现不会出错。
     */
    fun sendAuto(command: ZkCommand): ZkReceipt? = send(command)
}

/** 提议要做的事。 */
@Serializable
sealed interface Act {
    /** 交给总控的一条或一组指令（水道定稿时是一组）。 */
    @Serializable @SerialName("zk")
    data class Zk(val commands: List<ZkCommand>) : Act

    /** 去 Gemini / ChatGPT 聊。 */
    @Serializable @SerialName("summon")
    data class Summon(val actor: Actor, val topic: String) : Act

    /** 在体系图里记一条判断。 */
    @Serializable @SerialName("atlas_note")
    data class AtlasNote(val topic: String, val claim: String) : Act

    /** 今天的水道改道。 */
    @Serializable @SerialName("reroute")
    data class Reroute(val cause: Cause, val channelId: String, val day: String) : Act

    /** 她想改“对你的了解”：你点“好”才换成 [text]。 */
    @Serializable @SerialName("about")
    data class About(val text: String, val what: String) : Act
}

@Serializable
enum class ProposalState(val label: String) {
    PENDING("等你决定"),
    DONE("做了"),
    SENT("交给总控了"),
    FAILED("没送到总控"),
    DECLINED("不用了"),
    EXPIRED("过期了"),
}

/** 一条提议：给用户看的一句话，加上点“好”之后要做的事。 */
@Serializable
data class Proposal(
    val id: String,
    val act: Act,
    val text: String,
    val why: String,
    val power: Power,
    val createdAt: Long,
    val expiresAt: Long,
    val state: ProposalState = ProposalState.PENDING,
    /** 一组指令里已经送到总控的条数（失败后重试从这里接着发）。 */
    val sent: Int = 0,
    /** 总控的回执 / 失败原因。 */
    val receipt: String = "",
    val decidedAt: Long = 0,
    /** 总控给每条指令的编号（用来问后来怎么样了）。 */
    val zkIds: List<String> = emptyList(),
    /** 总控那边的结局：空 = 还不知道；例如“总控执行了”“你在总控里点了不用”。 */
    val zkOutcome: String = "",
    /** 墨灵直接交给总控、请求按授权书执行的（没经过“好”）。 */
    val auto: Boolean = false,
)

/** 用户对提议的一次表态（算接受率，挣权柄用）。 */
@Serializable
data class Decision(val at: Long, val accepted: Boolean)

/** 大脑要提醒用户的一件事（通知、小窗、今天页的横条）。 */
@Serializable
data class Notice(
    val kind: String,
    val text: String,
    val at: Long,
    val proposalId: String = "",
    /** 偏离提醒走到了哪一级（[Rung.name]）：NOTICE 发通知，WINDOW 起小窗展开。其他提醒为空。 */
    val rung: String = "",
)

/** 大脑的全部状态，整份存成一段 JSON。 */
@Serializable
data class BrainState(
    val flow: FlowPlan? = null,
    val atlas: Atlas = Atlas(),
    val topicId: String = "",
    val predictions: List<Prediction> = emptyList(),
    /** 思维选择赌局（R5）：同一会话一个 id，重复展示只算一笔。 */
    val bets: List<ChoiceBet> = emptyList(),
    val days: List<DayStat> = emptyList(),
    val talk: List<Turn> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    /** 用户给的权柄上限。 */
    val userMax: Tier = Tier.STEWARD,
    val trust: TrustStats = TrustStats(),
    val guard: GuardMemory = GuardMemory(),
    /** 已经提醒过的偏离（“日期:水道 id”），同一条水道一天只提醒一次。 */
    val nudged: List<String> = emptyList(),
    val lastStatus: ZkStatus? = null,
    val lastNightDay: String = "",
    /** 每个主题的体系总结（主题 id → 一段话）。 */
    val synthesis: Map<String, String> = emptyMap(),
    /** 此刻这次偏离走到哪一级了。 */
    val ladder: Ladder? = null,
    /** 他和演员说过的、能对账的话（最近 50 条）。 */
    val claims: List<Claim> = emptyList(),
    /** “她对你的了解”（你确认过的那一版）和历史版本。 */
    val about: String = "",
    val aboutVersions: List<AboutVersion> = emptyList(),
    /** 每周的成绩单（最近 12 周）。 */
    val weeks: List<WeekReport> = emptyList(),
    /** 他手动选的说话模式（一小时内有效）。 */
    val mode: TalkMode? = null,
    val modeAt: Long = 0,
    /** 最近 30 晚的睡眠（亮屏 / 熄屏推算）。 */
    val nights: List<SleepNight> = emptyList(),
    /** 她给自己定的唤醒。 */
    val wakes: List<Wake> = emptyList(),
    /** 今天（[counterDay]）偏离了几次、她出声打扰了几次。 */
    val counterDay: String = "",
    val driftsToday: Int = 0,
    val nudgesToday: Int = 0,
    /** 她今天的精力（出声、拦人、被冷落）。 */
    val spirit: Spirit = Spirit(),
    /** 正拦在他面前的那一次（null = 没拦）。 */
    val gate: GateSession? = null,
    /** 她想问 GPT 的（贯通，v6）。 */
    val questions: List<Question> = emptyList(),
    /** 收件箱里收过的条目（Notion 块 id）。 */
    val mirrorSeen: List<String> = emptyList(),
    /** 她自己推出来的判断（v8），到点验证。 */
    val thoughts: List<Thought> = emptyList(),
    val lastThinkAt: Long = 0,
    /** 她想好、等合适时候说的话。 */
    val sayQueue: List<Say> = emptyList(),
    /** 她每种出现方式的效果（反免疫，v9）。 */
    val habits: HabitBook = HabitBook(),
    /** 他动脑的动作（思维转速，v12）。 */
    val mindMarks: List<MindMark> = emptyList(),
    /** 正在下的三手棋；下完的留最近 20 局。 */
    val chess: Chess? = null,
    val chessDone: List<Chess> = emptyList(),
    val lastSparAt: Long = 0,
    /** 她采蜜采回来的简报（等他看）。 */
    val briefs: List<Brief> = emptyList(),
    /** 她摸清的他的规律（他点“对”才生效）。 */
    val rules: List<Rule> = emptyList(),
    /** 破雾进行到第几下、上一下的时间（5 分钟内连着点才算同一段）。 */
    val fogRound: Int = 0,
    val fogAt: Long = 0,
    /** 刚拿出来的那个盲盒 / 赌（他点选项时用来揭晓）。 */
    val lastPlay: Say? = null,
    /** 哪天为主线做完出过声（一天一次）。 */
    val cheeredDay: String = "",
    /** 每天的手机使用（只有应用和时间，没有内容），留 30 天。 */
    val usageDays: List<UsageDay> = emptyList(),
    /** 提议编号用的流水号。 */
    val seq: Long = 0,
    /** 给人看的行动日志（做了什么、交给总控什么、总控怎么回的）。 */
    val log: List<String> = emptyList(),
    /** 候选记忆（第十五批 B2）：案板上采用过的论断，他逐条点「收」才进体系图。 */
    val candidates: com.moling.core.atlas.CandidateBook = com.moling.core.atlas.CandidateBook(),
)

/** 她主动出声的一次。[gate] = 她拦在他面前（App 弹出拦人的页面）；[release] = 放他走了。 */
data class LiveTurn(
    val mood: Mood,
    val line: String,
    val gate: Boolean,
    val release: Boolean,
    val proposals: List<Proposal>,
    /** 这次用哪种方式出现（App 照着弹）。 */
    val modality: Modality = Modality.PEEK,
    /** [Modality.QUIZ] 时的题。 */
    val quiz: Quiz? = null,
    /** 点一下就能回的选项；界面显示成按钮，点了调 [Brain.tap]。 */
    val choices: List<String> = emptyList(),
    /** 破雾：他刷懵了，这次只要他点，不要他打字。 */
    val fog: Boolean = false,
    /** 这是一局三手棋（点选项会接着下）。 */
    val chess: Boolean = false,
)

data class ChatResult(
    val text: String,
    val proposals: List<Proposal>,
    val byModel: Boolean,
    val mode: TalkMode = TalkMode.WORK,
    /** 点一下就能回的选项（破雾模式里一定有）。 */
    val choices: List<String> = emptyList(),
)

/** 点“好” / 重试的结果。[opening] 只在“去聊”时有，App 拿它打开演员。 */
data class Outcome(val ok: Boolean, val message: String, val opening: Opening? = null)

class Brain(
    /** 手机这边能看到的（时间、静默、前台应用、无障碍健康）；总控和水道的部分由大脑补上。 */
    private val phone: FactsSource,
    private val ask: Ask?,
    /** 大脑自己的状态。 */
    private val store: MindStore,
    /** 导演的记忆（和大脑分开存）。 */
    mindStore: MindStore,
    private val zk: ZkPort,
    private val config: () -> DirectorConfig = { DirectorConfig() },
    /** 想得深的事（夜里整理、收件箱、想问 GPT 的、归纳、周报）用的模型；null = 和平时同一个。 */
    private val deep: Ask? = null,
) {
    private val lock = Mutex()

    @Volatile
    private var st: BrainState = decode(store.load())

    /** 导演：小窗、开场白、收线、夜间整理。它看到的事实由大脑合成（手机 + 总控 + 水道）。 */
    val director = DirectorEngine(FactsSource { facts(it) }, ask, mindStore, config)

    /** 界面用：此刻的状态快照。 */
    fun state(): BrainState = st

    /** 实际等级 = min(用户上限, 挣到的等级)。 */
    fun tier(): Tier = Charter.effective(st.userMax, st.trust)

    fun pending(now: Long): List<Proposal> = st.proposals.filter { it.state == ProposalState.PENDING && it.expiresAt > now }

    /** 合成给导演的事实：手机看到的 + 总控的状态 + 今天的水道。 */
    fun facts(now: Long): Facts = BrainFacts.merge(phone.facts(now), st.lastStatus, st.flow?.takeIf { it.day == calendarDay(now) }, now)

    // ------------------------------------------------------------------ 对话

    /**
     * 和她说一句。[mode] 不为空 = 他在模式条上选的（记一小时）；为空就用他选过的，或者按局面猜。
     * 他说的“做了 N 分钟 / 刷了 N 分钟”会和总控的数对一下，对不上的数字写进提示词，让她温和地摆出来。
     */
    suspend fun chat(text: String, now: Long, mode: TalkMode? = null): ChatResult = lock.withLock {
        val s = refresh()
        val cfg = config()
        val day = calendarDay(now)
        val flow = st.flow?.takeIf { it.day == day }
        val flowNow = flow?.let { Flow.current(it, Flow.minuteOf(now)) }
        val baton = director.snapshot().baton
        val batonText = baton?.nextAsk?.ifBlank { baton.question }.orEmpty()
        val topic = st.atlas.topics.firstOrNull { it.id == st.topicId }
        if (mode != null) st = st.copy(mode = mode, modeAt = now)
        val late = s?.takeIf { it.mainPlannedAt > 0 && now > it.mainPlannedAt && !it.mainStarted && !it.mainDone }
            ?.let { ((now - it.mainPlannedAt) / 60_000).toInt() } ?: 0
        val use = mode ?: st.mode?.takeIf { now - st.modeAt < 3_600_000L }
            ?: Modes.guess(text, late, s?.mainStarted == true, flowNow)
        val numbers = noteClaim(text, "他", now)
        val prompt = Talk.prompt(
            persona = cfg.persona,
            now = director.nowText(now),
            flow = flow?.let { Flow.render(it) }.orEmpty(),
            baton = batonText,
            atlas = topic?.let { AtlasOps.outline(st.atlas, it.id) }.orEmpty(),
            tierText = tierText(tier()),
            history = st.talk,
            user = text,
            mode = use,
            about = aboutPlus(),
            numbers = numbers,
            thinking = Think.open(st.thoughts).takeLast(5).map { Think.line(it) },
            recall = Graph.recall(text, st.atlas, 6) + Recall.relevant(text, knowledgeBesidesAtlas(), 3),
        )
        val parsed = model(cfg.persona, prompt)?.let { Talk.parse(it, Flow.dayStart(day), now = now) }?.takeIf { it.text.isNotBlank() }
        val reply = parsed ?: Talk.local(text, batonText, flowNow?.title.orEmpty())
        val made = reply.actions.mapNotNull { a -> fromTalk(a, now, flow) }
        // 训练模式的分只留在日志里（她每轮最后写【分】），正文不显示
        val shown = reply.text.lines().filterNot { it.trim().startsWith("【分】") }.joinToString("\n").trim()
        Modes.score(reply.text).takeIf { it >= 0 }?.let { st = st.copy(log = st.log + "${hm(now)} 训练：这一轮 $it 分") }
        st = st.copy(talk = (st.talk + Turn("他", text, now) + Turn("墨灵", shown, now)).takeLast(TALK_KEEP))
        if (use == TalkMode.TRAIN) mark(MindAct.TRAIN, now) else if (text.trim().length >= 20) mark(MindAct.SAY, now)
        save()
        ChatResult(shown, made, reply.byModel, use, reply.choices)
    }

    /** 认出能对账的说法就记下、对一下；对不上返回给提示词的一句，否则空串。 */
    private fun noteClaim(text: String, who: String, now: Long): String {
        val c = Claims.parse(text, who, now) ?: return ""
        val v = Claims.check(c, st.lastStatus)
        st = st.copy(claims = (st.claims + c.copy(verdict = v)).takeLast(50))
        if (v == "对得上" || v == "看不到") return ""
        st = st.copy(log = st.log + "${hm(now)} 说法对不上：「${c.text.take(30)}」，$v")
        return "${if (who == "他") "他说" else "${who}说"}「${c.text.take(40)}」，$v"
    }

    private fun fromTalk(a: TalkAction, now: Long, flow: FlowPlan?): Proposal? = when (a) {
        is TalkAction.Zk -> proposeZk(listOf(a.command), a.command.reason, now)
        is TalkAction.Summon -> propose(Act.Summon(a.actor, a.topic), "去 ${a.actor.label} 聊：${a.topic.ifBlank { "接着想" }}", "对话里说到的", Power.SUMMON, now)
        is TalkAction.AtlasNote -> propose(Act.AtlasNote(a.topic, a.claim), "在「${a.topic}」里记一条：${a.claim.take(40)}", "对话里说到的", Power.PLAN, now)
        is TalkAction.Wake -> {
            setWakeLocked(a.at, a.note, false, now)
            null
        }
        is TalkAction.AskGpt -> {
            addQuestion(a.question, "对话里想到的", "她", now)
            null
        }
        is TalkAction.Reroute -> {
            val cause = Cause.entries.firstOrNull { a.cause.contains(it.label) } ?: Cause.CONFLICT
            val c = flow?.let { Flow.current(it, Flow.minuteOf(now)) }
            if (flow == null || c == null) null
            else propose(Act.Reroute(cause, c.id, flow.day), "「${c.title}」改道：${cause.label}", "对话里说到的", Power.PLAN, now)
        }
    }

    // ------------------------------------------------------------------ 水道

    /**
     * 排今天的水道（草稿，没交给总控）。已经有今天的就直接返回，除非 [force]。顺便下今天的预言。
     * [busy]：日历上的固定安排（App 读系统日历传进来），水道绕开它们。
     * 用到“对你的了解”里的条目和昨晚的睡眠；睡得少，主河道每段不超过 45 分钟。
     */
    suspend fun planDay(now: Long, force: Boolean = false, busy: List<Busy> = emptyList()): FlowPlan = lock.withLock {
        val day = calendarDay(now)
        st.flow?.takeIf { it.day == day && !force }?.let { return@withLock it }
        val s = refresh()
        val mind = director.snapshot()
        val baton = mind.baton
        val inputs = Flow.Inputs(
            day = day,
            now = now,
            mainTitle = s?.mainTitle.orEmpty(),
            mainPlannedAt = s?.mainPlannedAt ?: 0L,
            baton = baton?.nextAsk?.ifBlank { baton.question }.orEmpty(),
            aims = s?.aims.orEmpty(),
            deliverables = s?.weekDeliverables.orEmpty(),
            patterns = (About.bullets(st.about).ifEmpty {
                mind.memories.filter { it.kind == MemoryKind.PATTERN }.sortedByDescending { it.hits }.take(3).map { it.text }
            } + listOf(Rhythm.advice(st.nights.firstOrNull { it.day == day })).filter { it.isNotBlank() }),
            bestHours = emptyList(),
            freeMinutes = s?.playLeftMin?.takeIf { it >= 0 } ?: 60,
            busy = busy,
        )
        val system = config().persona
        val drafted = model(system, Flow.morningPrompt(inputs))?.let { Flow.parse(it, inputs) } ?: Flow.local(inputs)
        val short = Rhythm.advice(st.nights.firstOrNull { it.day == day }).isNotBlank()
        val plan = if (!short) drafted else drafted.copy(channels = drafted.channels.map {
            if (it.kind == ChannelKind.MAIN && it.minutes > 45) it.copy(minutes = 45, why = (it.why + "（昨晚睡得少，先 45 分钟）").trim()) else it
        })
        st = st.copy(flow = plan, log = st.log + "${hm(now)} 排了今天的水道：${plan.intent}")
        if (st.predictions.none { calendarDay(it.madeAt) == day }) {
            val due = Flow.dayStart(day) + PREDICTION_DUE_MIN * 60_000L
            val made = model(system, Predict.prompt(st.days, inputs.patterns, Flow.render(plan)))?.let { Predict.parse(it, now, due) }
                ?.takeIf { it.isNotEmpty() } ?: Predict.local(st.days, now, due)
            st = st.copy(predictions = (st.predictions + made).takeLast(60))
        }
        save()
        plan
    }

    /** 用户定稿水道：标记确认，并生成一条“交给总控”的提议（主河道定为主线，主河道和支流排成行动）。 */
    suspend fun confirmFlow(now: Long): Proposal? = lock.withLock {
        val plan = st.flow?.takeIf { it.day == calendarDay(now) } ?: return@withLock null
        st = st.copy(flow = plan.copy(confirmed = true))
        val cmds = Flow.toCommands(plan, Flow.dayStart(plan.day))
        val p = proposeZk(cmds, "墨灵的水道：${plan.intent}", now, ttlUntil = Flow.dayStart(plan.day) + 23 * 3_600_000L)
        save()
        p
    }

    /** 用户在今天页点的改道（直接做）。返回一句说明；没有今天的水道返回空串。 */
    suspend fun reroute(cause: Cause, now: Long, note: String = ""): String = lock.withLock {
        val plan = st.flow?.takeIf { it.day == calendarDay(now) } ?: return@withLock ""
        val c = Flow.current(plan, Flow.minuteOf(now)) ?: return@withLock ""
        val msg = doReroute(plan, c.id, cause, now, note)
        save()
        msg
    }

    private fun doReroute(plan: FlowPlan, channelId: String, cause: Cause, now: Long, note: String = ""): String {
        val (next, msg) = Flow.reroute(plan, channelId, cause, Flow.minuteOf(now), note)
        if (msg.isBlank()) return ""
        // 已经交给总控的水道改道后，总控里排的行动不会自动跟着改：提醒一句，由用户决定要不要重新交。
        val tail = if (plan.confirmed) "（总控里排的行动还是旧的，需要的话重新交一次）" else ""
        st = st.copy(flow = next, log = st.log + "${hm(now)} 改道：$msg")
        return msg + tail
    }

    // ------------------------------------------------------------------ 提议

    /** App 的按钮（例如今天页的“开专注”）也走这里：生成一条给总控的提议；不合理或不允许返回 null。 */
    suspend fun suggest(command: ZkCommand, now: Long): Proposal? = lock.withLock {
        val p = proposeZk(listOf(command), command.reason, now)
        save()
        p
    }

    /** 用户点“好”。 */
    suspend fun accept(id: String, now: Long): Outcome = lock.withLock {
        val p = st.proposals.firstOrNull { it.id == id }
        if (p == null || p.state != ProposalState.PENDING) return@withLock Outcome(false, "这条已经处理过了")
        mark(MindAct.DECIDE, now)
        if (p.expiresAt <= now) {
            update(p.copy(state = ProposalState.EXPIRED, decidedAt = now))
            save()
            return@withLock Outcome(false, "这条过期了，情况可能变了")
        }
        st = st.copy(decisions = (st.decisions + Decision(now, true)).takeLast(300))
        markAnswered(p.id)
        val out = execute(p, now)
        save()
        out
    }

    /** 用户点“不用”。 */
    suspend fun decline(id: String, now: Long) = lock.withLock {
        val p = st.proposals.firstOrNull { it.id == id && it.state == ProposalState.PENDING } ?: return@withLock
        update(p.copy(state = ProposalState.DECLINED, decidedAt = now))
        markAnswered(p.id)
        mark(MindAct.DECIDE, now)
        st = st.copy(decisions = (st.decisions + Decision(now, false)).takeLast(300), log = st.log + "${hm(now)} 不用：${p.text}")
        save()
    }

    /** 没送到总控的提议重发（从断的那条接着发）。 */
    suspend fun retry(id: String, now: Long): Outcome = lock.withLock {
        val p = st.proposals.firstOrNull { it.id == id && it.state == ProposalState.FAILED }
            ?: return@withLock Outcome(false, "没有要重发的")
        val out = execute(p, now)
        save()
        out
    }

    private suspend fun execute(p: Proposal, now: Long): Outcome = when (val act = p.act) {
        is Act.Zk -> sendAll(p, act.commands, now)
        is Act.Summon -> {
            update(p.copy(state = ProposalState.DONE, decidedAt = now))
            st = st.copy(log = st.log + "${hm(now)} 去 ${act.actor.label} 聊：${act.topic}")
            Outcome(true, "去 ${act.actor.label}，开场白准备好了", director.opening(act.actor, null, now))
        }
        is Act.AtlasNote -> {
            val (a, t) = AtlasOps.topic(st.atlas, act.topic, now)
            val node = Node("n-$now-${a.nodes.size}", t.id, act.claim.take(80), NodeKind.JUDGMENT, "墨灵对话", createdAt = now, confirmed = true)
            val (merged, added) = AtlasOps.merge(a, Candidates(listOf(node), emptyList()))
            st = st.copy(atlas = merged, topicId = t.id, log = st.log + "${hm(now)} 体系图「${t.name}」记一条：${act.claim.take(40)}")
            update(p.copy(state = ProposalState.DONE, decidedAt = now))
            Outcome(true, if (added > 0) "记进「${t.name}」了" else "「${t.name}」里已经有差不多的一条")
        }
        is Act.Reroute -> {
            val plan = st.flow?.takeIf { it.day == act.day }
            val msg = plan?.let { doReroute(it, act.channelId, act.cause, now) }.orEmpty()
            update(p.copy(state = ProposalState.DONE, decidedAt = now))
            if (msg.isBlank()) Outcome(false, "今天的水道已经变了，这次改道用不上") else Outcome(true, msg)
        }
        is Act.About -> {
            setAbout(act.text, "墨灵（你同意的）", now)
            update(p.copy(state = ProposalState.DONE, decidedAt = now))
            Outcome(true, "好，我对你的了解更新了：${act.what}")
        }
    }

    /** 逐条交给总控；连不上或被拒就停下，记成“没送到”，可以重试。 */
    /**
     * 逐条交给总控，都请求按授权书执行：授权内总控倒计时 60 秒后做（可以取消），授权外总控照旧问他。
     * （他在墨灵点过“好”的也这样交：授权书是他亲手写的，授权外总控还会再问。）
     */
    private fun sendAll(p: Proposal, commands: List<ZkCommand>, now: Long): Outcome {
        val tier = tier()
        // 点“好”时再过一遍铁律：等级可能变了，指令也可能在存储里被改坏。
        commands.firstOrNull { !ZkWire.valid(it) || !Charter.check(it.power(), tier).allowed }?.let { bad ->
            update(p.copy(state = ProposalState.DECLINED, decidedAt = now, receipt = "现在的权柄不允许：${bad.describe()}"))
            return Outcome(false, "这条现在不能交给总控（${Charter.check(bad.power(), tier).reason}）")
        }
        var sent = p.sent
        val ids = p.zkIds.toMutableList()
        var lastAuto = false
        while (sent < commands.size) {
            val r = runCatching { zk.sendAuto(commands[sent]) }.getOrNull()
            if (r == null || !r.ok) {
                val why = r?.message?.ifBlank { null } ?: if (r == null) "连不上总控" else "总控拒绝了"
                update(p.copy(state = ProposalState.FAILED, sent = sent, receipt = why, decidedAt = now, zkIds = ids))
                st = st.copy(log = st.log + "${hm(now)} 没送到总控（$why）：${commands[sent].describe()}")
                return Outcome(false, "没送到总控：$why。可以稍后重试")
            }
            st = st.copy(log = st.log + "${hm(now)} 交给总控：${commands[sent].describe()}｜回执：${r.message}")
            if (r.id.isNotBlank()) ids += r.id
            lastAuto = r.auto
            sent++
        }
        val counting = lastAuto
        update(p.copy(state = ProposalState.SENT, sent = sent, receipt = if (counting) "授权内：总控倒计时执行" else "总控收下了", decidedAt = now, zkIds = ids))
        return Outcome(true, if (counting) "交给总控了：在授权书内，60 秒后执行（可以在总控的通知里取消）" else "交给总控了，在总控里确认后执行")
    }

    private fun proposeZk(commands: List<ZkCommand>, why: String, now: Long, ttlUntil: Long = 0): Proposal? {
        val tier = tier()
        val ok = commands.filter { ZkWire.valid(it) && Charter.check(it.power(), tier).allowed }
        if (ok.isEmpty()) return null
        val text = if (ok.size == 1) ok[0].describe() else "把今天的水道交给总控：" + ok.joinToString("；") { it.describe().substringBefore("。原因") }
        val power = ok.maxBy { it.power().ordinal }.power()
        // 每一条都是 AUTO：不等“好”，直接交给总控请求按授权书执行（总控那边还有授权书和 60 秒反悔）
        val auto = ok.all { Charter.check(it.power(), tier).mode == com.moling.core.power.Mode.AUTO }
        val p = propose(Act.Zk(ok), text, why, power, now, ttlUntil, auto) ?: return null
        if (!auto || p.state != ProposalState.PENDING) return p
        sendAll(p, ok, now)
        return st.proposals.first { it.id == p.id }
    }

    private fun propose(act: Act, text: String, why: String, power: Power, now: Long, ttlUntil: Long = 0, auto: Boolean = false): Proposal? {
        if (!Charter.check(power, tier()).allowed) return null
        // 同一句话还挂着，就不再提一次。
        pending(now).firstOrNull { it.text == text }?.let { return it }
        val expires = if (ttlUntil > now) ttlUntil else now + PROPOSAL_TTL_MIN * 60_000L
        val p = Proposal("p-$now-${st.seq}", act, text, why, power, now, expires, auto = auto)
        st = st.copy(proposals = (st.proposals + p).takeLast(PROPOSALS_KEEP), seq = st.seq + 1)
        return p
    }

    private fun update(p: Proposal) {
        st = st.copy(proposals = st.proposals.map { if (it.id == p.id) p else it })
    }

    // ------------------------------------------------------------------ 定时

    /**
     * 每分钟（或每次亮屏）调一次。读总控、过期旧提议、对账到点的预言、看总控是不是掉了、看水是不是流偏了。
     * [foregroundCategory]：前台应用的类别（ENTERTAINMENT / SHOPPING / WORK / ACTOR / OTHER），拿不到给空串。
     * [guard]：总控健康的观察；App 拿不到就给 null。墨灵不修复，[GuardSample.canRepair] 会被当成 false。
     */
    suspend fun tick(now: Long, foregroundCategory: String = "", guard: GuardSample? = null): List<Notice> = lock.withLock {
        val out = mutableListOf<Notice>()
        val s = refresh()
        st = st.copy(proposals = st.proposals.map {
            if (it.state == ProposalState.PENDING && it.expiresAt <= now) it.copy(state = ProposalState.EXPIRED, decidedAt = now) else it
        })
        // 到点还没观测到的赌局：未知，不算输（R5）。
        if (st.bets.any { it.state == BetState.OPEN && now >= it.deadline }) st = st.copy(bets = Bets.expireAll(st.bets, now))
        if (s != null) out += settle(s, now)
        if (s != null) out += zkOutcomes(now)
        if (guard != null) watchZk(guard, now)?.let { out += it }
        drift(s, foregroundCategory, now)?.let { out += it }
        out += dueWakes(now)
        if (out.any { it.kind == "drift" || it.kind == "wake" }) count(now, nudges = out.count { it.kind == "drift" || it.kind == "wake" })
        save()
        out
    }

    /** 到点的唤醒：她“自己想起来”要找他。 */
    private fun dueWakes(now: Long): List<Notice> {
        if (st.wakes.isEmpty()) return emptyList()
        val (due, keep) = Wakes.due(st.wakes, now)
        if (due.isEmpty()) return emptyList()
        st = st.copy(wakes = keep, log = st.log + due.map { "${hm(now)} 唤醒：${it.note}" })
        return due.map { Notice("wake", Wakes.text(it, now), now) }
    }

    /** 今天的计数（日历日）：偏离几次、打扰几次。 */
    private fun count(now: Long, drifts: Int = 0, nudges: Int = 0) {
        val day = calendarDay(now)
        val base = if (st.counterDay == day) st else st.copy(counterDay = day, driftsToday = 0, nudgesToday = 0)
        st = base.copy(driftsToday = base.driftsToday + drifts, nudgesToday = base.nudgesToday + nudges)
    }

    /** 他回应了这次偏离的卡 / 提议（好、不用、选项、卡住了……）：这次偏离不再升级。 */
    suspend fun answered() = lock.withLock {
        st.ladder?.let { st = st.copy(ladder = it.copy(answered = true)) }
        save()
    }

    private fun markAnswered(proposalId: String) {
        val l = st.ladder ?: return
        if (l.proposalId == proposalId) st = st.copy(ladder = l.copy(answered = true))
    }

    /** 交给总控的提议，问问总控后来怎么样了（24 小时内的；全部有结局才算完）。 */
    private fun zkOutcomes(now: Long): List<Notice> {
        val out = mutableListOf<Notice>()
        st.proposals.filter { it.state == ProposalState.SENT && it.zkOutcome.isBlank() && it.zkIds.isNotEmpty() && now - it.decidedAt < DAY_MS }.forEach { p ->
            val rs = p.zkIds.map { id -> runCatching { zk.result(id) }.getOrNull() ?: return@forEach }
            if (rs.any { it.state == "PENDING" || it.state == "SCHEDULED" }) return@forEach
            val bad = rs.firstOrNull { it.state != "DONE" }
            val outcome = when (bad?.state) {
                null -> "总控执行了"
                "DECLINED" -> "你在总控里点了不用"
                "VETOED" -> "你在总控里取消了"
                "EXPIRED" -> "总控里没确认，过期了"
                "FAILED" -> "总控没执行成：${bad.message}"
                else -> "总控里找不到这条"
            }
            update(p.copy(zkOutcome = outcome))
            // 墨灵直接交的（没经过“好”）：总控那边的结局就是他的表态，算进接受率
            if (p.auto) st = st.copy(decisions = (st.decisions + Decision(now, bad == null)).takeLast(300))
            st = st.copy(log = st.log + "${hm(now)} $outcome：${p.text}")
            out += Notice("zk_result", "$outcome：${p.text.substringBefore("。原因")}", now, p.id)
        }
        return out
    }

    private fun settle(s: ZkStatus, now: Long): List<Notice> {
        val before = st.predictions
        val after = Predict.settle(before, s, now)
        st = st.copy(predictions = after)
        return after.zip(before).filter { (a, b) -> b.hit == null && a.hit != null }.map { (a, _) ->
            Notice("prediction", "对账：「${a.text}」${if (a.hit == true) "对了" else "错了"}（实际 ${fmtNum(a.actual)}）", now)
        }
    }

    private fun watchZk(sample: GuardSample, now: Long): Notice? {
        val d = ZkGuard.step(st.guard, sample.copy(canRepair = false), dayKey(now))
        // 墨灵不冻结：上报时 ZkGuard 会把 frozen 记成 true，这里改回来。
        st = st.copy(guard = d.memory.copy(frozen = false))
        val text = when (d.action) {
            GuardAction.NONE, GuardAction.UNFREEZE -> d.message
            GuardAction.NOTE -> d.message
            GuardAction.REPAIR, GuardAction.ESCALATE ->
                "${d.memory.state.label}，已经 ${(now - d.memory.since) / 60_000} 分钟。我不去修、也不冻结 App，交给总控自愈和哨兵；方便时打开总控看一眼？"
        }
        if (text.isBlank()) return null
        st = st.copy(log = st.log + "${hm(now)} $text")
        return Notice("guard", text, now)
    }

    /**
     * 水流偏了：按升级阶梯一步一步来（每步至少隔 5 分钟，他回应了就停）——
     * 提醒一句 → 小窗展开 → 提议开专注锁（规划者起）→ 按授权书开锁（管家，总控 60 秒倒计时可取消）。
     */
    private fun drift(s: ZkStatus?, category: String, now: Long): Notice? {
        val plan = st.flow?.takeIf { it.day == calendarDay(now) && it.confirmed }
        val playing = category == "ENTERTAINMENT" || category == "SHOPPING"
        val working = s?.focus == true || category == "WORK" || category == "ACTOR"
        val d = plan?.let { Flow.drift(it, Flow.minuteOf(now), playing, working) }
        val key = if (plan != null && d != null) "${plan.day}:${d.channel.id}" else null
        val (ladder, rung) = Ladders.step(st.ladder, key, now, tier())
        st = st.copy(ladder = ladder)
        if (d == null || rung == null) return null
        if (rung == Rung.NOTICE) count(now, drifts = 1)
        val title = d.channel.title
        val left = (d.channel.endMin - Flow.minuteOf(now)).coerceIn(5, 60)
        return when (rung) {
            Rung.NOTICE -> Notice("drift", "水该在「$title」（过了 ${d.lateMin} 分钟），现在在别处。卡住了还是累了？说一声我来改道。", now, rung = rung.name)
            Rung.WINDOW -> Notice("drift", "还在别处。现在是「$title」的时间：先做 10 分钟，还是跟我说卡在哪？", now, rung = rung.name)
            Rung.PROPOSE, Rung.AUTO -> {
                val cmd = ZkCommand.StartFocus(left, title, "水道「$title」过了 ${d.lateMin} 分钟还在娱乐")
                // 上一级挂着没回应的提议作废，换成这一级的
                st.ladder?.proposalId?.takeIf { it.isNotBlank() }?.let { old ->
                    st.proposals.firstOrNull { it.id == old && it.state == ProposalState.PENDING }?.let { update(it.copy(state = ProposalState.EXPIRED, decidedAt = now)) }
                }
                val p = if (rung == Rung.AUTO) proposeZk(listOf(cmd), "水流偏了", now)
                // 提议这一级一律等他点“好”，哪怕是管家：按授权书直接做是下一级的事
                else if (ZkWire.valid(cmd) && Charter.check(cmd.power(), tier()).allowed) propose(Act.Zk(listOf(cmd)), cmd.describe(), "水流偏了", cmd.power(), now)
                else null
                st = st.copy(ladder = st.ladder?.copy(proposalId = p?.id.orEmpty()))
                val how = if (p?.auto == true) "我让总控开 $left 分钟专注锁了（60 秒内可以在总控里取消）。" else "要不要让总控开 $left 分钟专注锁？"
                Notice("drift", "过了 ${d.lateMin} 分钟还没回到「$title」。$how", now, p?.id.orEmpty(), rung.name)
            }
        }
    }

    // ------------------------------------------------------------------ 夜间

    /** 夜里整理一次（同一天只做一次）：记下今天的数、对账、重算权柄，再让导演整理日记。返回给人看的一段话。 */
    suspend fun night(now: Long): String = lock.withLock {
        val day = dayKey(now)
        if (st.lastNightDay == day) return@withLock ""
        val s = refresh()
        if (s != null) {
            val counted = st.counterDay == day
            val stat = DayStat(
                day, s.playMinutesToday, s.workMinutesToday, s.mainStarted || s.mainDone, s.spendTodayCents / 100.0,
                mainScore = when { s.mainDone -> 1.0; s.mainStarted -> 0.5; s.mainTitle.isNotBlank() -> 0.0; else -> -1.0 },
                drifts = if (counted) st.driftsToday else 0,
                nudges = if (counted) st.nudgesToday else 0,
            )
            st = st.copy(days = (st.days.filter { it.day != day } + stat).sortedBy { it.day }.takeLast(60))
            st = st.copy(predictions = Predict.settle(st.predictions, s, now))
        }
        val oldTier = tier()
        st = st.copy(trust = trust(now, day), lastNightDay = day, nudged = st.nudged.filter { it.startsWith(calendarDay(now)) })
        val newTier = tier()
        director.onSense(Sense(SenseKind.NIGHT, now, "brain"))
        val aboutNote = proposeAboutUpdate(day, now)
        val week = if (LocalDate.parse(day).dayOfWeek == java.time.DayOfWeek.SUNDAY) weekReport(day, now) else null
        val text = buildString {
            append(Predict.scoreText(st.predictions))
            if (newTier != oldTier) append("。权柄变了：${oldTier.label} → ${newTier.label}")
            if (aboutNote.isNotBlank()) append("。$aboutNote")
            if (week != null) append("。周报出来了：${week.text.take(60)}")
        }
        st = st.copy(log = st.log + "${hm(now)} 夜间整理：$text")
        save()
        text
    }

    /** 最近 14 天的成绩：提议接受率、猜测准确率、预言 Brier、连续使用天数。 */
    private fun trust(now: Long, day: String): TrustStats {
        val from = now - 14 * DAY_MS
        val ds = st.decisions.filter { it.at >= from }
        val hs = director.snapshot().hypotheses.filter { it.at >= from && (it.outcome == "对了" || it.outcome == "错了") }
        val brier = Predict.brier(st.predictions.filter { it.madeAt >= from })
        val have = st.days.map { it.day }.toSet()
        var streak = 0
        var d = LocalDate.parse(day)
        while (d.toString() in have) { streak++; d = d.minusDays(1) }
        return TrustStats(ds.count { it.accepted }, ds.size, hs.count { it.outcome == "对了" }, hs.size, brier, streak)
    }

    // ------------------------------------------------------------------ 体系图

    /** 用户贴进来的材料（GPT / Gemini 的长回答、自己的笔记）：拆成论断挂进体系图。返回新增几条；模型不可用返回 0。 */
    suspend fun ingest(text: String, source: String, topicName: String, now: Long): Int = lock.withLock {
        if (text.isBlank()) return@withLock 0
        val (a, t) = AtlasOps.topic(st.atlas, topicName.ifBlank { "杂项" }, now)
        st = st.copy(atlas = a, topicId = t.id)
        val reply = model(config().persona, AtlasOps.ingestPrompt(t, a.nodes.filter { it.topicId == t.id }, text, source))
        val added = reply?.let { AtlasOps.merge(st.atlas, AtlasOps.parse(it, t.id, source, now)) }?.let { (merged, n) ->
            st = st.copy(atlas = merged)
            n
        } ?: 0
        st = st.copy(log = st.log + "${hm(now)} 体系图「${t.name}」收了 $added 条（来自 $source）")
        save()
        added
    }

    /** 用户核实了一条论断（例如假设验证过了）：写上结果，把握改成高。 */
    suspend fun verifyNode(nodeId: String, result: String, now: Long): Boolean = lock.withLock {
        if (st.atlas.nodes.none { it.id == nodeId } || result.isBlank()) return@withLock false
        st = st.copy(atlas = AtlasOps.verify(st.atlas, nodeId, result), log = st.log + "${hm(now)} 体系图核实一条：${result.take(30)}")
        mark(MindAct.VERIFY, now)
        save()
        true
    }

    /** 用户删掉一条论断（连同它的关系）。 */
    suspend fun removeNode(nodeId: String) = lock.withLock {
        val a = st.atlas
        st = st.copy(atlas = a.copy(nodes = a.nodes.filter { it.id != nodeId }, edges = a.edges.filter { it.from != nodeId && it.to != nodeId }))
        save()
    }

    /** 让模型把当前主题归纳成一个体系（主干、分支、关键链、最大缺口）。模型不可用返回 null。 */
    suspend fun synthesize(now: Long): String? = lock.withLock {
        val t = st.atlas.topics.firstOrNull { it.id == st.topicId } ?: return@withLock null
        if (st.atlas.nodes.none { it.topicId == t.id }) return@withLock null
        val text = deepModel(config().persona, AtlasOps.synthesisPrompt(st.atlas, t.id))?.let { AtlasOps.parseSynthesis(it) } ?: return@withLock null
        st = st.copy(synthesis = st.synthesis + (t.id to text), log = st.log + "${hm(now)} 归纳了「${t.name}」")
        save()
        text
    }

    /** 体系图的一个缺口 → 一条“去聊”的提议（点“好”才去）。 */
    suspend fun askGap(question: String, actor: Actor, now: Long): Proposal? = lock.withLock {
        if (question.isBlank()) return@withLock null
        val p = propose(Act.Summon(actor, question), "去 ${actor.label} 聊：${question.take(40)}", "体系图的缺口", Power.SUMMON, now)
        save()
        p
    }

    // ------------------------------------------------------------------ 贯通（v6）：Notion 黑板和收件箱

    /** 黑板：她知道的、她这几天的判断、她想问 GPT 的。App 把它写进 Notion（没变就不写）。 */
    fun board(now: Long): List<Section> {
        val s = st
        val day = calendarDay(now)
        val flow = s.flow?.takeIf { it.day == day }
        val today = buildString {
            flow?.let { appendLine(Flow.render(it)) }
            s.lastStatus?.let { z ->
                append("总控记的：做事 ${z.workMinutesToday} 分钟，娱乐 ${z.playMinutesToday} 分钟")
                if (z.mainTitle.isNotBlank()) append("，主线「${z.mainTitle}」${if (z.mainDone) "做完了" else if (z.mainStarted) "开始了" else "还没开始"}")
            }
        }
        val days = s.days.takeLast(7).map { "${it.day}：做事 ${it.work} 分钟、娱乐 ${it.play} 分钟、偏离 ${it.drifts} 次" }
        val atlas = s.atlas.topics.flatMap { t ->
            s.atlas.nodes.filter { it.topicId == t.id }.takeLast(4)
                .map { "「${t.name}」${it.kind.label}：${it.claim}（把握${it.confidence}${if (it.verified.isNotBlank()) "，已核实" else ""}）" }
        }
        val said = s.talk.filter { it.who == "他" && it.text.length >= 8 }.takeLast(8).map { "${hm(it.at)} ${it.text.take(100)}" }
        return Mirror.board(
            config().callUser, director.nowText(now), s.questions, s.about, today, misjudged(now), days, atlas, said,
            thoughts = Think.open(s.thoughts).takeLast(6).map { Think.line(it) },
        )
    }

    /** 交接包：直接发给任何 AI 应用的那段话（内容同黑板）。 */
    fun handoff(now: Long): String = Mirror.handoff(config().callUser, board(now))

    /**
     * 收收件箱：别人写来的先由她把关（深想模型），回答了的问题记上答案，论断进体系图，
     * 要改“对你的了解”的变成提议（他点“好”才算数）。模型不通就不标“收过”，下次再收。
     */
    suspend fun absorb(items: List<InboxItem>, now: Long): Absorbed = lock.withLock {
        val fresh = Mirror.unseen(items, st.mirrorSeen)
        if (fresh.isEmpty()) return@withLock Absorbed()
        val cfg = config()
        val topicName = st.atlas.topics.firstOrNull { it.id == st.topicId }?.name ?: "来自 GPT"
        val (a, t) = AtlasOps.topic(st.atlas, topicName, now)
        st = st.copy(atlas = a, topicId = t.id)
        val existing = st.atlas.nodes.filter { it.topicId == t.id }.map { "${it.id}：${it.claim}" }
        val reply = deepModel(cfg.persona, Mirror.absorbPrompt(cfg.callUser, t.name, existing, Mirror.open(st.questions), st.about, fresh))
            ?: return@withLock Absorbed()
        val source = fresh.map { it.source }.distinct().joinToString("、").take(30)
        val (merged, added) = AtlasOps.merge(st.atlas, AtlasOps.parse(reply, t.id, source, now))
        val sorted = Mirror.parseAbsorb(reply)
        val answered = mutableListOf<String>()
        st = st.copy(
            atlas = merged,
            questions = st.questions.map { q ->
                val ans = sorted.answers[q.id]
                if (ans != null && q.answer.isBlank()) { answered += q.id; q.copy(answer = ans, answeredAt = now) } else q
            },
        )
        var aboutAsked = false
        if (sorted.about.isNotBlank()) {
            val doc = st.about.ifBlank { About.SEED.trimIndent() }.trimEnd() + "\n- ${sorted.about}（来自 $source）"
            val p = propose(Act.About(doc, "加一句：${sorted.about.take(30)}"), "更新我对你的了解：${sorted.about.take(30)}", "收件箱（$source）", Power.SPEAK, now, ttlUntil = now + 72 * 3_600_000L)
            aboutAsked = p?.state == ProposalState.PENDING
        }
        st = st.copy(
            mirrorSeen = (st.mirrorSeen + fresh.flatMap { listOf(it.id) + it.also }).takeLast(1000),
            log = st.log + "${hm(now)} 收件箱收了 ${fresh.size} 条（$source）：体系图 +$added${if (answered.isNotEmpty()) "，回答了 ${answered.joinToString("、")}" else ""}",
        )
        save()
        Absorbed(answered, added, aboutAsked, sorted.notes)
    }

    /** 他想让她问 GPT 的（或她自己想问的）。 */
    suspend fun askGpt(text: String, now: Long, by: String = "你"): Question? = lock.withLock {
        val q = addQuestion(text, "", by, now)
        save()
        q
    }

    suspend fun dropQuestion(id: String) = lock.withLock {
        st = st.copy(questions = st.questions.filter { it.id != id })
        save()
    }

    /** 她自己想想要问 GPT 什么（开着 Notion 时，夜里整理后调一次；也可以他点按钮）。返回新加的。 */
    suspend fun prepareQuestions(now: Long, n: Int = 2): List<Question> = lock.withLock {
        val open = Mirror.open(st.questions)
        val room = minOf(n, Mirror.OPEN_MAX - open.size)
        if (room <= 0) return@withLock emptyList()
        val cfg = config()
        val gaps = st.atlas.topics.flatMap { t -> AtlasOps.gaps(st.atlas, t.id).take(2) }.map { it.question }.filter { it.isNotBlank() }.take(5)
        val reply = deepModel(cfg.persona, Mirror.questionsPrompt(cfg.callUser, st.about, misjudged(now), gaps, st.questions.takeLast(10).map { it.text }, room))
            ?: return@withLock emptyList()
        val made = Mirror.parseQuestions(reply).take(room).mapNotNull { (q, why) -> addQuestion(q, why, "她", now) }
        if (made.isNotEmpty()) save()
        made
    }

    private fun addQuestion(text: String, why: String, by: String, now: Long): Question? {
        val t = text.trim().take(120)
        if (t.length < 4 || st.questions.any { it.answer.isBlank() && it.text == t }) return null
        if (Mirror.open(st.questions).size >= Mirror.OPEN_MAX + 3) return null
        val n = (st.questions.mapNotNull { it.id.removePrefix("Q").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val q = Question("Q$n", t, why.take(60), now, by = by)
        st = st.copy(questions = (st.questions + q).takeLast(40), log = st.log + "${hm(now)} 想问 GPT：${t.take(30)}（$by）")
        return q
    }

    // ------------------------------------------------------------------ 她自己的思考（v8）

    /** 她能翻的知识：体系图的论断、GPT 的回答、她的判断、“了解”里的条目。 */
    private fun knowledge(): List<String> {
        val topics = st.atlas.topics.associate { it.id to it.name }
        return st.atlas.nodes.map { "「${topics[it.topicId].orEmpty()}」${it.kind.label}：${it.claim}（把握${it.confidence}）" } +
            st.questions.filter { it.answer.isNotBlank() }.map { "GPT 回答过「${it.text.take(40)}」：${it.answer}" } +
            st.thoughts.filter { it.outcome != "错了" }.takeLast(20).map { "我推断过：${it.text}${if (it.outcome.isNotBlank()) "（${it.outcome}）" else ""}" } +
            st.about.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { "了解：${it.removePrefix("- ")}" }
    }

    /** 该想了吗：醒着、隔够了；他这段时间说了不少（或有说法对不上）可以早一点。 */
    fun needsThink(now: Long): Boolean {
        val fresh = st.talk.count { it.who == "他" && it.at > st.lastThinkAt } + st.claims.count { it.at > st.lastThinkAt }
        return Think.due(st.lastThinkAt, now, fresh)
    }

    /**
     * 想一次：推出新判断、验证到点的旧判断、想好要说的话、需要时提动作（照样走权柄）。
     * [usage] 是 App 整理好的手机使用（没有就空）。模型不可用返回 null（不记“想过了”，下次再想）。
     */
    suspend fun think(now: Long, usage: String = ""): ThinkResult? = lock.withLock {
        refresh()
        val cfg = config()
        val day = calendarDay(now)
        val flow = st.flow?.takeIf { it.day == day }
        val today = buildString {
            flow?.let { appendLine(Flow.render(it)) }
            st.lastStatus?.let { z ->
                append("总控记的：做事 ${z.workMinutesToday} 分钟，娱乐 ${z.playMinutesToday} 分钟")
                if (z.mainTitle.isNotBlank()) append("，主线「${z.mainTitle}」${if (z.mainDone) "做完了" else if (z.mainStarted) "开始了" else "还没开始"}")
                appendLine()
            }
            if (st.counterDay == day) appendLine("今天偏离 ${st.driftsToday} 次，她出声 ${st.nudgesToday} 次")
        }
        val days = st.days.takeLast(5).map { "${it.day}：做事 ${it.work} 分钟、娱乐 ${it.play} 分钟、偏离 ${it.drifts} 次" }
        val said = st.talk.filter { it.who == "他" && now - it.at < 2 * DAY_MS }.takeLast(10).map { "${hm(it.at)} ${it.text.take(80)}" }
        val open = Think.open(st.thoughts)
        val query = (said.takeLast(4) + flow?.intent.orEmpty() + open.map { it.text }).joinToString(" ")
        // 跨域连线：这一天新进来的论断，和以前相关的旧论断
        val freshNodes = st.atlas.nodes.filter { it.createdAt > now - DAY_MS }.takeLast(6)
        val olderPool = st.atlas.nodes.filter { it.createdAt <= now - DAY_MS }
        val olderHit = Recall.relevant(freshNodes.joinToString(" ") { it.claim }, olderPool.map { it.claim }, 6).toSet()
        val olderNodes = if (freshNodes.isEmpty()) emptyList() else olderPool.filter { it.claim in olderHit }
        val reply = deepModel(
            cfg.persona,
            Think.prompt(cfg.persona, cfg.callUser, director.nowText(now), aboutPlus(), today, days, usage, said, misjudged(now),
                Graph.recall(query, st.atlas, 6) + Recall.relevant(query, knowledgeBesidesAtlas(), 3), open.takeLast(8), Think.overdue(st.thoughts, now),
                fresh = freshNodes.map { "${it.id}：${it.claim}" },
                older = olderNodes.map { "${it.id}：${it.claim}" }),
        ) ?: return@withLock null
        val r = Think.parse(reply, Flow.dayStart(day), now)
        var n = (st.thoughts.mapNotNull { it.id.removePrefix("T").toIntOrNull() }.maxOrNull() ?: 0)
        val made = r.made.map { n += 1; it.copy(id = "T$n") }
        val thoughts = st.thoughts.map { t ->
            r.settled[t.id]?.takeIf { t.outcome.isBlank() }?.let { (o, why) -> t.copy(outcome = o, why = why, settledAt = now) } ?: t
        } + made
        // 过了验证时间两天还没人管的，算过期
        val aged = thoughts.map { if (it.outcome.isBlank() && it.checkAt in 1..(now - 2 * DAY_MS)) it.copy(outcome = "过期", settledAt = now) else it }
        st = st.copy(
            thoughts = aged.takeLast(Think.KEEP),
            lastThinkAt = now,
            sayQueue = (st.sayQueue + r.say.map { Say(it, now) } + r.play).takeLast(6),
            log = st.log + "${hm(now)} 她想了一会儿：新判断 ${made.size} 条" +
                (if (r.settled.isNotEmpty()) "，验证 ${r.settled.entries.joinToString("、") { "${it.key}${it.value.first}" }}" else "") +
                (if (r.say.isNotEmpty()) "，想好要说 ${r.say.size} 句" else ""),
        )
        Talk.parse(reply, Flow.dayStart(day), "她自己想到的", now).actions.forEach { fromTalk(it, now, flow) }
        // 她连的线
        var linked = 0
        r.links.forEach { l ->
            val rel = Relation.entries.firstOrNull { it.label == l[1] } ?: return@forEach
            val before = st.atlas.edges.size
            st = st.copy(atlas = Graph.link(st.atlas, l[0], l[2], rel, "她连的：" + l[3]))
            if (st.atlas.edges.size > before) linked++
        }
        if (linked > 0) st = st.copy(log = st.log + "${hm(now)} 她把新旧论断连了 $linked 条线")
        save()
        ThinkResult(made, r.settled, r.say, r.play)
    }

    /** 他点了“这个判断不对 / 对”（今天页上）。 */
    suspend fun judgeThought(id: String, right: Boolean, now: Long) = lock.withLock {
        st = st.copy(thoughts = st.thoughts.map { if (it.id == id && it.outcome.isBlank()) it.copy(outcome = if (right) "对了" else "错了", why = "你说的", settledAt = now) else it })
        mark(MindAct.JUDGE, now)
        save()
    }

    /**
     * 记下今天的手机使用（App 每次她思考前、和夜里交进来），返回给她看的一段话。
     * [sessions] 是今天 0 点到现在的前台记录。
     */
    suspend fun noteUsage(sessions: List<Session>, now: Long): String = lock.withLock {
        val day = calendarDay(now)
        val start = Flow.dayStart(day)
        val today = UsageLog.day(day, sessions, start)
        st = st.copy(usageDays = (st.usageDays.filter { it.day != day } + today).sortedBy { it.day }.takeLast(30))
        save()
        UsageLog.text(today, UsageLog.planVsActual(st.flow?.takeIf { it.day == day }, sessions, start, now), st.usageDays)
    }

    /** 他点了“聊这个”：她把想好的那句说出来（进对话），返回这句；没有返回 null。 */
    suspend fun sayNow(now: Long): String? = lock.withLock {
        val say = st.sayQueue.firstOrNull() ?: return@withLock null
        st = st.copy(sayQueue = st.sayQueue.drop(1), talk = (st.talk + Turn("墨灵", say.text, now)).takeLast(TALK_KEEP))
        save()
        say.text
    }

    /** 想好要说的那句不用说了。 */
    suspend fun dropSay(now: Long) = lock.withLock {
        st = st.copy(sayQueue = st.sayQueue.drop(1))
        save()
    }

    // ------------------------------------------------------------------ 定时的判断（给后台心跳用）

    /** 早上该排水道了：6:00–22:00 之间、今天还没有水道。 */
    fun needsPlan(now: Long): Boolean {
        val m = Flow.minuteOf(now)
        return m in 6 * 60 until 22 * 60 && st.flow?.day != calendarDay(now)
    }

    /** 夜里该整理了：23:30 到凌晨 4 点之间、这一天还没整理过。 */
    fun needsNight(now: Long): Boolean {
        val m = Flow.minuteOf(now)
        return (m >= 23 * 60 + 30 || m < 4 * 60) && st.lastNightDay != dayKey(now)
    }

    // ------------------------------------------------------------------ 活的墨灵（v5）

    /**
     * 这一刻有没有什么值得把她叫醒（由大脑判断，App 每次心跳把前台交进来）：
     * 该做事时在刷 5 分钟以上 / 夜里躺着刷 15 分钟以上 / 连着刷 40 分钟以上。正拦着他时不再叫。
     */
    /**
     * [leftPlayMin]：他刚离开的那一段娱乐有多长（3 分钟内离开的才算；App 用使用记录算）。
     */
    fun liveTrigger(
        a: Activity?,
        now: Long,
        unlockGapMin: Int = 0,
        cfg: LiveConfig = LiveConfig(),
        autopilot: AutopilotSignal? = null,
        leftPlayMin: Int = 0,
    ): Trigger? {
        if (st.gate != null) return null
        val m = Flow.minuteOf(now)
        val day = calendarDay(now)
        val playing = a != null && (a.category == "ENTERTAINMENT" || a.category == "SHOPPING")
        if (a != null && playing) {
            val plan = st.flow?.takeIf { it.day == day && it.confirmed }
            val c = plan?.let { Flow.current(it, m) }
            val working = c != null && m in c.startMin until c.endMin && c.kind in setOf(ChannelKind.MAIN, ChannelKind.SIDE, ChannelKind.SPRING)
            when {
                working && a.minutes >= 5 -> return Trigger.SCROLL_AT_WORK
                autopilot?.on == true -> return Trigger.AUTOPILOT
                (m >= 23 * 60 || m < 5 * 60) && a.minutes >= 15 -> return Trigger.SCROLL_IN_BED
                a.minutes >= 40 -> return Trigger.LONG_SCROLL
            }
        }
        // 下面这些不打断正事：在做事 / 在和演员聊 / 深夜都不来
        if (a?.category == "WORK" || a?.category == "ACTOR" || m < 7 * 60 || m >= 23 * 60 + 30) return null
        // 不在娱乐里也可能在自动驾驶（横跳、拿起又放下）
        if (autopilot?.on == true) return Trigger.AUTOPILOT
        if (st.lastStatus?.mainDone == true && st.cheeredDay != day) return Trigger.DONE
        if (st.sayQueue.isNotEmpty() && !playing) return Trigger.SAY
        if (unlockGapMin >= 30) return if (m < 10 * 60 && st.spirit.day != day) Trigger.MORNING else Trigger.UNLOCK
        // 刚刷完一大段离开：收一回合“过路费”
        if (cfg.spar && !playing && leftPlayMin >= 15) return Trigger.TOLL
        // 闲着、3 小时里没怎么动脑：来下一局三手棋（9:00–22:30，至少隔 90 分钟，体系图里得有东西）
        if (cfg.spar && !playing && m in 9 * 60 until 22 * 60 + 30 && Rpm.low(st.mindMarks, now) &&
            now - st.lastSparAt >= 90 * 60_000L && st.atlas.nodes.size >= 3
        ) return Trigger.SPAR
        if (cfg.idle && !playing && a?.category == "OTHER") return Trigger.IDLE
        return null
    }

    /**
     * 叫醒她一次：她自己决定说不说、拦不拦。返回 null = 她这次不出声（精力不够、间隔太近、她懒得说、或模型说不说）。
     * [roll] 是 0..1 的随机数（不重要的事有一半时候她懒得出声）。
     */
    suspend fun live(
        trigger: Trigger,
        a: Activity?,
        now: Long,
        roll: Double = kotlin.random.Random.nextDouble(),
        cfg: LiveConfig = LiveConfig(),
        can: Can = Can(),
        cues: List<String> = emptyList(),
    ): LiveTurn? = lock.withLock {
        if (st.gate != null) return@withLock null
        // 安静期（R3）：他说了“先不聊了 / 别管我”，自发出现一律不发声；手动开电台 / 找他说话不算。
        if (Habits.isQuiet(st.habits, now)) return@withLock null
        refresh()
        val day = calendarDay(now)
        var sp = Spirits.today(st.spirit, day)
        if (!Spirits.mayAct(sp, trigger, now, cfg, roll)) {
            st = st.copy(spirit = sp)
            return@withLock null
        }
        // 主线做完那句一天一次：这次她出不出声都算过了
        if (trigger == Trigger.DONE) st = st.copy(cheeredDay = day)
        val canGate = GateRules.eligible(a) && Spirits.mayGate(sp, cfg) &&
            trigger in setOf(Trigger.SCROLL_AT_WORK, Trigger.SCROLL_IN_BED, Trigger.LONG_SCROLL, Trigger.AUTOPILOT)
        // 破雾：他多半刷懵了（自动驾驶、夜里躺着刷、刷了很久、该做事时已经刷了一刻钟，或者最近老划掉她）
        // （v13）所有娱乐入口都走破雾，不再分“刷了一刻钟以内先审一句卡在哪”
        val fog = Live.fog(trigger, sp.ignored)
        val flow = st.flow?.takeIf { it.day == day }
        val flowNow = flow?.let { Flow.current(it, Flow.minuteOf(now)) }?.title.orEmpty()
        val persona = config().persona
        val toSay = st.sayQueue.firstOrNull()?.text.orEmpty()
        val prompt = Live.prompt(
            persona, trigger, a, director.nowText(now), flow?.let { Flow.render(it) }.orEmpty(), aboutPlus(), misjudged(now), talkLines(), canGate,
            thinking = Think.open(st.thoughts).takeLast(4).map { Think.line(it) }, toSay = toSay,
            cues = cues, ignored = sp.ignored, fog = fog,
        )
        // 三手棋：不走平常的提示词，直接开一局（开不出来就不出声）
        if (trigger == Trigger.SPAR) {
            val c = openChess(now) ?: run { st = st.copy(spirit = sp, lastSparAt = now); save(); return@withLock null }
            sp = sp.copy(spoke = sp.spoke + 1, lastAt = now)
            val line = "来一局三手棋？我把你体系图里的一条改了改：「${c.bait}」——这步棋有个破绽，你看得出来吗？"
            val modality = Habits.pick(st.habits, can, now, (roll * 7919.0) % 1.0).let { if (it == Modality.QUIZ || it == Modality.GATE) Modality.PEEK else it }
            st = st.copy(
                spirit = sp, chess = c, lastSparAt = now, habits = Habits.shown(st.habits, modality, now),
                talk = (st.talk + Turn("墨灵", line, now)).takeLast(TALK_KEEP),
                log = st.log + "${hm(now)} 来下三手棋：${c.source.take(30)}",
            )
            count(now, nudges = 1)
            save()
            return@withLock LiveTurn(Mood.CURIOUS, line, false, false, emptyList(), modality, choices = c.options, chess = true)
        }
        // 她攒好的盲盒 / 小剧场 / 赌：原样拿出来，不再调模型
        val play = st.sayQueue.firstOrNull()?.takeIf { trigger == Trigger.SAY && it.kind != "say" }
        val reply = if (play != null) null else model(persona, prompt)
        val line = play?.let { LiveLine(true, if (it.kind == "bet") Mood.SMUG else Mood.CURIOUS, playLine(it), false, false, it.choices) }
            ?: reply?.let { Live.parse(it, canGate) }
            ?: if (fog) Live.fogOpen(a, canGate)
            else if (trigger == Trigger.SAY && toSay.isNotBlank()) LiveLine(true, Mood.CURIOUS, toSay, false, false)
            else Live.local(trigger, a, canGate, flowNow)
        if (!line.speak) {
            st = st.copy(spirit = sp, log = st.log + "${hm(now)} ${trigger.label}：她看了一眼，没出声")
            save()
            return@withLock null
        }
        val made = reply?.let { Talk.parse(it, Flow.dayStart(day), "她主动提的", now).actions }.orEmpty().mapNotNull { fromTalk(it, now, flow) }
        sp = sp.copy(spoke = sp.spoke + 1, lastAt = now)
        val gate = if (line.gate && a != null) GateSession(now, trigger, a.pkg, a.label, fog = fog).also { sp = sp.copy(gates = sp.gates + 1) } else null
        // 不拦的时候换着方式出现（反免疫）；捉虫题出不来就退回别的
        var modality = if (gate != null) Modality.GATE else Habits.pick(st.habits, can, now, (roll * 7919.0) % 1.0)
        val quiz = if (modality == Modality.QUIZ) makeQuiz(now) else null
        if (modality == Modality.QUIZ && quiz == null) modality = if (can.overlay) Modality.PET else Modality.PEEK
        st = st.copy(
            spirit = sp,
            gate = gate,
            // 想好要说的说出去了（模型可用时她会揉进这次的话里）
            sayQueue = if (toSay.isNotBlank() && (reply != null || trigger == Trigger.SAY)) st.sayQueue.drop(1) else st.sayQueue,
            lastPlay = play ?: st.lastPlay,
            fogRound = if (fog || play != null) 0 else st.fogRound,
            fogAt = if (fog || play != null) now else st.fogAt,
            // 她出声了：偏离阶梯的下一级往后推，不叠着打扰
            ladder = st.ladder?.copy(at = now),
            talk = (st.talk + Turn("墨灵", line.line, now)).takeLast(TALK_KEEP),
            log = st.log + "${hm(now)} ${trigger.label}：${if (gate != null) "拦住了，" else ""}${line.line.take(40)}",
        )
        count(now, nudges = 1)
        save()
        st = st.copy(habits = Habits.shown(st.habits, modality, now))
        save()
        // 破雾一定有按钮、一定能退出：模型漏了【选】就用兜底的
        val choices = if (fog) Live.fogChoices(line.choices, Live.fogOpen(a, canGate).choices) else line.choices
        LiveTurn(line.mood, line.line, gate != null, false, made, modality, quiz, choices, fog)
    }

    // ------------------------------------------------------------------ 平时也让脑子转起来（v12）

    private fun mark(act: MindAct, now: Long) {
        st = st.copy(mindMarks = (st.mindMarks + MindMark(now, act)).takeLast(Rpm.KEEP))
    }

    /** 体系图以外的知识（GPT 的回答、她的判断、了解、她摸清的规律）——体系图走 [Graph.recall]。 */
    private fun knowledgeBesidesAtlas(): List<String> =
        st.questions.filter { it.answer.isNotBlank() }.map { "GPT 回答过「${it.text.take(40)}」：${it.answer}" } +
            st.thoughts.filter { it.outcome != "错了" }.takeLast(20).map { "我推断过：${it.text}${if (it.outcome.isNotBlank()) "（${it.outcome}）" else ""}" } +
            st.about.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { "了解：${it.removePrefix("- ")}" } +
            Rules.kept(st.rules).map { "规律：${Rules.line(it)}" }

    /** 给提示词的“了解”：他确认过的文档 + 他点过“对”的规律。 */
    private fun aboutPlus(): String {
        val r = Rules.kept(st.rules)
        return if (r.isEmpty()) st.about else st.about + "\n## 她摸清的规律（他确认过）\n" + r.joinToString("\n") { "- " + Rules.line(it) }
    }

    /** 开一局三手棋（锁内）。体系图里没有能下的就返回 null。 */
    private suspend fun openChess(now: Long): Chess? {
        val recent = st.chessDone.takeLast(10).map { it.nodeId }
        val node = Chesses.pick(st.atlas, recent, now) ?: return null
        val outline = AtlasOps.outline(st.atlas, node.topicId)
        val shuffle = ((now / 1000L) % 3).toInt()
        return model(config().persona, Chesses.openPrompt(node, outline))?.let { Chesses.parseOpen(it, node, now, shuffle) }
            ?: Chesses.local(node, now, shuffle)
    }

    /** 他点“来一局”（今天页 / 对话页）：开一局，返回她的第一手。 */
    suspend fun spar(now: Long): LiveTurn? = lock.withLock {
        val c = openChess(now) ?: return@withLock null
        val line = "「${c.bait}」——这步棋有个破绽，你看得出来吗？"
        st = st.copy(chess = c, lastSparAt = now, talk = (st.talk + Turn("墨灵", line, now)).takeLast(TALK_KEEP), log = st.log + "${hm(now)} 你开了一局三手棋：${c.source.take(30)}")
        save()
        LiveTurn(Mood.CURIOUS, line, false, false, emptyList(), choices = c.options, chess = true)
    }

    /** 下一手（锁内）。第三手下完：打分、把结论挂进体系图、和原论断连上线。 */
    private suspend fun chessMoveLocked(c: Chess, picked: String, now: Long): ChessTurn {
        mark(MindAct.CHESS, now)
        st = st.copy(talk = (st.talk + Turn("他", "（第 ${c.turn} 手）$picked", now)).takeLast(TALK_KEEP))
        val reply = model(config().persona, Chesses.movePrompt(c, picked))
        val m = reply?.let { Chesses.parseMove(it) }
        val turn: ChessTurn
        var next = c.copy(turn = c.turn + 1, moves = c.moves + picked)
        when (c.turn) {
            1 -> {
                val right = Chesses.firstRight(c, picked)
                val line = m?.line?.ifBlank { null } ?: if (right) "点中了！漏洞就在这：${c.flaw}" else "差一点——漏洞在：${c.flaw}"
                val choices = m?.choices?.takeIf { it.isNotEmpty() }
                    ?: listOf("会更快做成", "会卡在新地方", "没什么变化")
                turn = ChessTurn("$line 第二手：按修正后的去做，下一步最可能怎样？", choices + Chesses.FREE, false)
            }
            2 -> {
                // 第三手：她打分收官（他这一下就是第二手的回答）；下完就结束
                val score = m?.score?.takeIf { it >= 0 } ?: if (Chesses.firstRight(c, c.moves.firstOrNull().orEmpty())) 2 else 1
                val claim = m?.claim?.takeIf { it.length >= 6 }
                var added = ""
                if (claim != null && m != null) {
                    val node = Node("n-$now-c", c.topicId, claim, m.kind ?: NodeKind.JUDGMENT, "三手棋", m.confidence, createdAt = now)
                    val (merged, n) = AtlasOps.merge(st.atlas, com.moling.core.atlas.Candidates(listOf(node), emptyList()))
                    var a = merged
                    if (n > 0) a = Graph.link(a, node.id, c.nodeId, m.relation ?: Relation.SUPPORTS, "三手棋推出来的")
                    st = st.copy(atlas = a)
                    if (n > 0) added = claim
                }
                val line = (m?.line?.ifBlank { null } ?: "这局 $score 分。") + if (added.isNotBlank()) " 新结论挂进体系图了：「$added」" else ""
                next = next.copy(turn = 4, score = score, conclusion = added)
                turn = ChessTurn(line, emptyList(), true, score, added, added)
            }
            else -> turn = ChessTurn("这局下完了。", emptyList(), true)
        }
        st = st.copy(
            chess = if (next.turn > 3) null else next,
            chessDone = if (next.turn > 3) (st.chessDone + next).takeLast(20) else st.chessDone,
            spirit = Spirits.today(st.spirit, calendarDay(now)).copy(ignored = 0),
            talk = (st.talk + Turn("墨灵", turn.line, now)).takeLast(TALK_KEEP),
            log = if (turn.done) st.log + "${hm(now)} 三手棋下完：${turn.score} 分${if (turn.conclusion.isNotBlank()) "，新结论：${turn.conclusion.take(30)}" else ""}" else st.log,
        )
        save()
        return turn
    }

    /** 采蜜：挑一个缺口，深想模型整理正反简报（等他看），同时把问题挂上黑板让 GPT 也答。一天最多一次。 */
    suspend fun forage(now: Long): Brief? = lock.withLock {
        if (st.briefs.any { calendarDay(it.at) == calendarDay(now) }) return@withLock null
        val recent = st.briefs.filter { now - it.at < 3 * DAY_MS }.map { it.nodeId }
        val gaps = st.atlas.topics.flatMap { AtlasOps.gaps(st.atlas, it.id) }
        val g = Forage.pick(gaps, recent) ?: return@withLock null
        val node = st.atlas.nodes.firstOrNull { it.id == g.nodeId } ?: return@withLock null
        val lines = deepModel(config().persona, Forage.prompt(g.question, AtlasOps.outline(st.atlas, node.topicId), st.about))?.let { Forage.parse(it) }
            ?.takeIf { it.size >= 2 } ?: return@withLock null
        val b = Brief("b-$now", now, node.id, node.topicId, g.question, lines)
        addQuestion(g.question.take(100), "体系图的缺口", "她", now)
        st = st.copy(briefs = (st.briefs + b).takeLast(Forage.KEEP), log = st.log + "${hm(now)} 采蜜：${g.question.take(30)}（${lines.size} 条）")
        save()
        b
    }

    /** 他看了简报：收下（进体系图，标“待核”）或不要。 */
    suspend fun keepBrief(id: String, keep: Boolean, now: Long): Int = lock.withLock {
        val b = st.briefs.firstOrNull { it.id == id && it.status == "new" } ?: return@withLock 0
        st = st.copy(briefs = st.briefs.map { if (it.id == id) it.copy(status = if (keep) "kept" else "dropped") else it })
        var added = 0
        if (keep) {
            val t = st.atlas.topics.firstOrNull { it.id == b.topicId }
            if (t != null) {
                val existing = st.atlas.nodes.filter { it.topicId == t.id }
                val reply = model(config().persona, AtlasOps.ingestPrompt(t, existing, Forage.asMaterial(b), "采蜜（待核）"))
                reply?.let { AtlasOps.merge(st.atlas, AtlasOps.parse(it, t.id, "采蜜（待核）", now)) }?.let { (a, n) ->
                    // 采蜜来的一律把握“低”
                    st = st.copy(atlas = a.copy(nodes = a.nodes.map { if (it.source == "采蜜（待核）" && it.createdAt == now) it.copy(confidence = "低") else it }))
                    added = n
                }
            }
            mark(MindAct.DECIDE, now)
        }
        st = st.copy(log = st.log + "${hm(now)} 简报「${b.question.take(20)}」：${if (keep) "收下 $added 条" else "不要"}")
        save()
        added
    }

    // ------------------------------------------------------------------ 案板的适配（v13 第九批；案板本身在 core/workspace，自己存、自己锁）

    /** 他在案板上动了脑子（挑刺、选路线、采用）：记进思维转速。 */
    suspend fun markMind(act: MindAct, now: Long) = lock.withLock {
        mark(act, now)
        save()
    }

    /**
     * 他在案板上点了“收进体系图”：把他采用过的论断（[Artifacts.claims]，练习靶子和待核的已经排除）织进 [topicId]
     * （null = 当前主题，没有就第一个主题）。来源记“案板”。返回收进几条；没有主题 / 模型没接上返回 0。
     */
    suspend fun keepClaims(claims: List<String>, topicId: String?, now: Long): Int = lock.withLock {
        if (claims.isEmpty()) return@withLock 0
        val t = st.atlas.topics.firstOrNull { it.id == (topicId ?: st.topicId) } ?: st.atlas.topics.firstOrNull() ?: return@withLock 0
        val existing = st.atlas.nodes.filter { it.topicId == t.id }
        val reply = model(config().persona, AtlasOps.ingestPrompt(t, existing, claims.joinToString("\n") { "- $it" }, "案板"))
        val added = reply?.let { AtlasOps.merge(st.atlas, AtlasOps.parse(it, t.id, "案板", now)) }?.let { (a, n) -> st = st.copy(atlas = a); n } ?: 0
        mark(MindAct.DECIDE, now)
        st = st.copy(log = st.log + "${hm(now)} 案板收进体系图 $added 条（${t.name.take(10)}）")
        save()
        added
    }

    // ------------------------------------------------------------------ 候选记忆（第十五批 B2）

    /** 从案板的一份稿子里提候选（只提新的）。返回这份稿子对应的、还待他决定的候选。 */
    suspend fun proposeCandidates(artifact: com.moling.core.workspace.WorkspaceArtifact, now: Long): List<com.moling.core.atlas.MemoryCandidate> = lock.withLock {
        val (book, _) = com.moling.core.atlas.MemoryCandidates.propose(st.candidates, artifact, st.atlas, now)
        if (book != st.candidates) {
            st = st.copy(candidates = book)
            save()
        }
        st.candidates.items.filter { it.artifactId == artifact.id && it.status != com.moling.core.atlas.CandidateStatus.REJECTED }
    }

    /**
     * 他点「收」：原话进体系图（不调模型）。[topicId] null = 当前主题 → 第一个主题 → 用 [fallbackTopic] 新建一个。
     * 返回节点编号；重复点返回同一个。
     */
    suspend fun acceptCandidate(id: String, topicId: String?, fallbackTopic: String, now: Long): com.moling.core.workspace.PortResult<String> = lock.withLock {
        var atlas = st.atlas
        val topic = atlas.topics.firstOrNull { it.id == (topicId ?: st.topicId) } ?: atlas.topics.firstOrNull()
            ?: AtlasOps.topic(atlas, fallbackTopic.ifBlank { "案板" }.take(20), now).let { (a, t) -> atlas = a; t }
        val (book, nextAtlas, r) = com.moling.core.atlas.MemoryCandidates.accept(st.candidates, id, atlas, topic.id, now)
        st = st.copy(candidates = book, atlas = nextAtlas)
        if (r is com.moling.core.workspace.PortResult.Ok) {
            mark(MindAct.DECIDE, now)
            st = st.copy(log = st.log + "${hm(now)} 候选收进体系图：${book.get(id)?.claim?.take(20).orEmpty()}")
        }
        save()
        r
    }

    suspend fun rejectCandidate(id: String, now: Long): com.moling.core.workspace.PortResult<Unit> = lock.withLock {
        val (book, r) = com.moling.core.atlas.MemoryCandidates.reject(st.candidates, id, now)
        st = st.copy(candidates = book)
        save()
        r
    }

    suspend fun undoCandidate(id: String, now: Long): com.moling.core.workspace.PortResult<Unit> = lock.withLock {
        val (book, atlas, r) = com.moling.core.atlas.MemoryCandidates.undo(st.candidates, id, st.atlas, now)
        st = st.copy(candidates = book, atlas = atlas)
        save()
        r
    }

    /** 夜里：从这几天的数字里提 1–2 条“当……，就……”（他点“对”才生效）。 */
    suspend fun proposeRules(now: Long): List<Rule> = lock.withLock {
        if (st.rules.count { it.status == "proposed" } >= 3) return@withLock emptyList()
        val stats = ruleStats(now)
        if (stats.isBlank()) return@withLock emptyList()
        val n = (st.rules.mapNotNull { it.id.removePrefix("R").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val made = deepModel(config().persona, Rules.prompt(stats, st.rules.filter { it.status != "dropped" }, st.about))
            ?.let { Rules.parse(it, now, n) }.orEmpty()
        if (made.isNotEmpty()) {
            st = st.copy(rules = (st.rules + made).takeLast(Rules.KEEP), log = st.log + "${hm(now)} 她摸到 ${made.size} 条规律，等你看")
            save()
        }
        made
    }

    suspend fun setRule(id: String, keep: Boolean, now: Long) = lock.withLock {
        st = st.copy(rules = st.rules.map { if (it.id == id) it.copy(status = if (keep) "kept" else "dropped") else it })
        mark(MindAct.JUDGE, now)
        save()
    }

    /** 给“规律”用的数字：出现方式管用率、三手棋、最容易被拉走的时段、几点脑子转得多。 */
    private fun ruleStats(now: Long): String = buildString {
        Habits.summary(st.habits, now).takeIf { it.isNotEmpty() }?.let { appendLine("她的出现方式：" + it.joinToString("；")) }
        st.chessDone.takeLast(10).takeIf { it.isNotEmpty() }?.let { l ->
            appendLine("三手棋最近 ${l.size} 局：平均 ${"%.1f".format(l.map { it.score.coerceAtLeast(0) }.average())} 分，" +
                l.groupBy { hourOf(it.startedAt) / 3 * 3 }.entries.joinToString("、") { "${it.key}–${it.key + 3} 点 ${it.value.size} 局" })
        }
        com.moling.core.usage.UsageLog.weakHours(st.usageDays.takeLast(7)).takeIf { it.isNotEmpty() }?.let { appendLine("最容易被拉走：" + it.joinToString("、")) }
        val marks = st.mindMarks.filter { now - it.at < 7 * DAY_MS }
        if (marks.isNotEmpty()) appendLine("一周动脑的时段：" + marks.groupBy { hourOf(it.at) / 3 * 3 }.entries.sortedBy { it.key }.joinToString("、") { "${it.key}–${it.key + 3} 点 ${it.value.sumOf { m -> m.act.weight }} 分" })
        st.thoughts.filter { it.outcome.isNotBlank() && now - it.settledAt < 7 * DAY_MS }.takeIf { it.isNotEmpty() }?.let { t ->
            appendLine("她的推断一周：对了 ${t.count { it.outcome == "对了" }}、错了 ${t.count { it.outcome == "错了" }}")
        }
    }.trim()

    private fun hourOf(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(BEIJING).hour

    /** 盲盒 / 小剧场 / 赌拿出来时怎么说。 */
    private fun playLine(p: Say): String = when (p.kind) {
        "blindbox" -> "我攒了一句话，你看有没有毛病：「${p.text}」"
        "drama" -> p.text
        "bet" -> "我下个注：${p.text}"
        else -> p.text
    }

    /**
     * 他点了一个选项（破雾、盲盒、赌，或者她给的任何【选】）：她接一句，最多三下把他往外带。
     * 拦着他时：点两下就放行（破雾不要他打字）；不拦时：第三下收尾。点了就算认真回应（不算冷落）。
     */
    suspend fun tap(choice: String, now: Long, cfg: LiveConfig = LiveConfig(), modality: Modality? = null): LiveTurn = lock.withLock {
        // 正在下三手棋：这一下是棋
        st.chess?.takeIf { it.turn in 1..3 && now - it.startedAt < 2 * 3_600_000L }?.let { c ->
            val t = chessMoveLocked(c, choice, now)
            return@withLock LiveTurn(if (t.done) Mood.HAPPY else Mood.CURIOUS, t.line, false, false, emptyList(), choices = t.choices, chess = true)
        }
        mark(MindAct.TAP, now)
        // 明确停止（R3）：“先不聊了 / 别管我”直接进安静期，不再调模型、不再换一种方式继续。
        if (choice == Live.EXIT || choice == "别管我") {
            val gating = st.gate != null
            st = st.copy(
                habits = Habits.explicitStop(st.habits, now, modality),
                gate = null,
                fogRound = 0,
                fogAt = 0,
                // 他点了就不算冷落：不拦人的破雾点退出也一样清零（F11-6）。
                spirit = Spirits.today(st.spirit, calendarDay(now)).copy(ignored = 0),
                ladder = st.ladder?.copy(answered = true),
                talk = (st.talk + Turn("他", "（点了）$choice", now)).takeLast(TALK_KEEP),
                log = st.log + "${hm(now)} 他说先不聊了：安静 ${Habits.QUIET_MS / 60_000L} 分钟",
            )
            save()
            // 只有真的拦着时才说“放行”；不拦人的破雾不能返回 release=true（F11-6）。
            return@withLock LiveTurn(Mood.CALM, "好，我安静一会儿。想找我就去设置里恢复。", false, gating, emptyList(), fog = true)
        }
        val g = st.gate
        val round = (if (now - st.fogAt < 5 * 60_000L) st.fogRound else 0) + 1
        val gating = g != null
        // 点“先不聊了”= 这一下就收尾（拦着时也放行，和「急事」一样随时能走）
        val last = choice == Live.EXIT || (if (gating) round >= 2 || GateRules.expired(g!!, now, cfg) else round >= 3)
        st = st.copy(talk = (st.talk + Turn("他", "（点了）$choice", now)).takeLast(TALK_KEEP))
        // 盲盒 / 赌：先揭晓
        val p = st.lastPlay?.takeIf { round == 1 && now - it.at < 2 * DAY_MS }
        val reveal = when (p?.kind) {
            "blindbox" -> if (choice.contains("漏洞")) "对，漏洞在：${p.answer}" else "再看一眼——漏洞在：${p.answer}"
            "bet" -> if (choice.contains("偏不")) "好，那我们看谁赢。" else "嘿嘿，记下了。"
            else -> ""
        }
        val persona = config().persona
        val reply = model(persona, Live.fogPrompt(persona, choice + if (reveal.isNotBlank()) "（她揭晓：$reveal）" else "", talkLines(), aboutPlus(), round, last, gating))
        val parsed = reply?.let { Live.parse(it, false) }?.takeIf { it.line.isNotBlank() } ?: Live.fogLocal(round, last)
        val line = listOf(reveal, parsed.line).filter { it.isNotBlank() }.joinToString(" ")
        val release = gating && (last || parsed.release)
        val day = calendarDay(now)
        val made = reply?.let { Talk.parse(it, Flow.dayStart(day), "点选时说好的", now).actions }.orEmpty()
            .mapNotNull { fromTalk(it, now, st.flow?.takeIf { f -> f.day == day }) }
        st = st.copy(
            gate = if (gating && !release) g!!.copy(rounds = g.rounds + 1) else if (release) null else st.gate,
            spirit = Spirits.today(st.spirit, day).copy(ignored = 0),
            ladder = st.ladder?.copy(answered = true),
            fogRound = round,
            fogAt = now,
            lastPlay = if (round == 1) null else st.lastPlay,
            talk = (st.talk + Turn("墨灵", line, now)).takeLast(TALK_KEEP),
            log = st.log + "${hm(now)} 他点了「${choice.take(12)}」${if (release) "，放行" else ""}",
        )
        save()
        val choices = if (last || release) emptyList() else Live.fogChoices(parsed.choices, Live.fogLocal(round, false).choices)
        LiveTurn(parsed.mood, line, gating && !release, release, made, choices = choices, fog = true)
    }

    /**
     * 他对她这次出现的反应（App 报回来）：喂反免疫账本；认真回了就不算冷落，秒划 / 不理算一次冷落。
     * 拦人页的“急事 / 超时”另走 [gateEscape]，这里只记账。
     */
    suspend fun react(modality: Modality, reaction: Reaction, ms: Long, now: Long) = lock.withLock {
        val sp = Spirits.today(st.spirit, calendarDay(now))
        // F11-5：那一次其实没被他看到（浮层被系统拦 / 没戴耳机 / 窗口没加上）：不算“他不理我”。
        val observed = Habits.lastExposure(st.habits, modality) != Exposure.UNOBSERVED
        st = st.copy(
            habits = Habits.react(st.habits, modality, reaction, ms, now),
            spirit = when {
                reaction == Reaction.REPLIED -> sp.copy(ignored = 0)
                reaction.bad && observed && modality != Modality.GATE -> sp.copy(ignored = sp.ignored + 1)
                else -> sp
            },
        )
        save()
    }

    /**
     * 这一次出现其实没被他看到（浮层被系统 / 厂商拦了、窗口没加上、没戴耳机）：把最近一笔还没反应的记录标成
     * [Exposure.UNOBSERVED]（F11-5）。之后他就算没理，也不进“冷落”计数。找不到这一次出现就什么都不做。
     */
    suspend fun markUnobserved(modality: Modality, now: Long) = lock.withLock {
        val next = Habits.unobserved(st.habits, modality)
        if (next == st.habits) return@withLock
        st = st.copy(habits = next, log = st.log + "${hm(now)} ${modality.label}：那一次没真的显示出来，不算他冷落")
        save()
    }

    /** 他手动进安静期（设置页 / 面板的“先安静”）。 */
    suspend fun stopLive(now: Long) = lock.withLock {
        st = st.copy(
            habits = Habits.explicitStop(st.habits, now),
            gate = null,
            fogRound = 0,
            fogAt = 0,
            log = st.log + "${hm(now)} 手动进安静期（${Habits.QUIET_MS / 60_000L} 分钟）",
        )
        save()
    }

    /** 他点“恢复”：结束安静期，别的预算和冷却不变。 */
    suspend fun restoreLive(now: Long) = lock.withLock {
        if (!Habits.isQuiet(st.habits, now)) return@withLock
        st = st.copy(habits = Habits.restore(st.habits), log = st.log + "${hm(now)} 结束安静期，恢复")
        save()
    }

    fun quietLine(now: Long): String = Habits.quietLine(st.habits, now)

    // ------------------------------------------------------------------ 思维选择赌局（R5）

    /**
     * 开一笔赌局。同一个 [sessionId] 只一笔（同一张卡片 / 同一个电台换着展示不重复记账）；
     * [guess] 必须在这道题的选项里。猜错了也不扣权柄、不改授权。
     */
    suspend fun openBet(
        sessionId: String,
        prompt: String,
        options: List<String>,
        guess: String,
        deadline: Long,
        now: Long,
        kind: BetKind = BetKind.FUN,
        p: Double = -1.0,
    ): ChoiceBet? = lock.withLock {
        if (sessionId.isBlank()) return@withLock null
        val made = Bets.open(st.bets, "bet-$sessionId", sessionId, prompt, options, guess, deadline, now, kind, p) ?: return@withLock null
        st = st.copy(bets = made.first, log = st.log + "${hm(now)} 她下注：${Bets.line(made.second)}")
        save()
        made.second
    }

    /** 观测到结果（选项文字）。没有这个选项 / 传 null 就是“看不到结果”，不算输。 */
    suspend fun settleBet(id: String, observed: String?, now: Long): ChoiceBet? = lock.withLock {
        val b = st.bets.firstOrNull { it.id == id } ?: return@withLock null
        if (b.state != BetState.OPEN) return@withLock b
        val option = observed?.let { label -> b.options.firstOrNull { it.label == label.trim() }?.id }
        val next = Bets.settle(b, option, now, if (observed == null) "没有观测到结果" else "观测到：${observed}")
        st = st.copy(
            bets = st.bets.map { if (it.id == id) next else it },
            log = st.log + "${hm(now)} 赌局结算：${Bets.line(next)}",
        )
        save()
        next
    }

    /** 他取消 / 关掉赌局：不算输，旧记录保留但不再推送。 */
    suspend fun cancelBet(id: String, now: Long): ChoiceBet? = lock.withLock {
        val b = st.bets.firstOrNull { it.id == id } ?: return@withLock null
        val next = Bets.cancel(b, now)
        st = st.copy(bets = st.bets.map { if (it.id == id) next else it }, log = st.log + "${hm(now)} 赌局取消（不算输）")
        save()
        next
    }

    /** 出一道捉虫题（他自己的论断 / 她的判断 / 了解里的一条）。没有材料返回 null。 */
    suspend fun quiz(now: Long): Quiz? = lock.withLock { makeQuiz(now).also { save() } }

    private suspend fun makeQuiz(now: Long): Quiz? {
        val pool = (st.atlas.nodes.filter { it.kind != com.moling.core.atlas.NodeKind.QUESTION }.map { it.claim } +
            Think.open(st.thoughts).map { it.text } +
            st.about.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ") })
            .filter { it.length in 8..80 }
        if (pool.isEmpty()) return null
        val source = pool[((now / 60_000L) % pool.size).toInt()]
        return model(config().persona, Quizzes.prompt(source))?.let { Quizzes.parse(it, source) } ?: Quizzes.local(source)
    }

    /** 他答了捉虫题：答对了算认真回应。 */
    suspend fun quizResult(right: Boolean, now: Long) = lock.withLock {
        st = st.copy(log = st.log + "${hm(now)} 捉虫题：${if (right) "抓到了" else "没抓到"}")
        mark(MindAct.QUIZ, now)
        save()
    }

    /** 她拦着他时，他回了一句：她决定放不放（最多 [LiveConfig.maxRounds] 轮，最后一轮一定放）。 */
    suspend fun gateReply(text: String, now: Long, cfg: LiveConfig = LiveConfig()): LiveTurn = lock.withLock {
        val g = st.gate ?: return@withLock LiveTurn(Mood.CALM, "", false, true, emptyList())
        val numbers = noteClaim(text, "他", now)
        st = st.copy(talk = (st.talk + Turn("他", text, now)).takeLast(TALK_KEEP))
        val last = g.rounds + 1 >= cfg.maxRounds || GateRules.expired(g, now, cfg)
        val persona = config().persona
        val reply = model(persona, Live.replyPrompt(persona, g, text + if (numbers.isNotBlank()) "（$numbers）" else "", talkLines(), st.about, g.rounds, cfg.maxRounds))
        val parsed = reply?.let { Live.parse(it, false) }?.takeIf { it.line.isNotBlank() }
        // 破雾时不要求他说得具体：回了就行
        val release = last || (parsed?.release ?: (g.fog || !Live.perfunctory(text)))
        val line = parsed?.line ?: if (release) "好，去吧。说好的别忘了。" else "这句太敷衍了。具体点：卡在哪，或者几点停？"
        val mood = parsed?.mood ?: if (release) Mood.CALM else Mood.POUT
        val day = calendarDay(now)
        val made = reply?.let { Talk.parse(it, Flow.dayStart(day), "拦住时说好的", now).actions }.orEmpty()
            .mapNotNull { fromTalk(it, now, st.flow?.takeIf { f -> f.day == day }) }
        st = st.copy(
            gate = if (release) null else g.copy(rounds = g.rounds + 1),
            // 他认真回了：不再算冷落；这次偏离也算回应过
            spirit = if (release && !Live.perfunctory(text)) st.spirit.copy(ignored = 0) else st.spirit,
            ladder = st.ladder?.copy(answered = true),
            talk = (st.talk + Turn("墨灵", line, now)).takeLast(TALK_KEEP),
            log = st.log + "${hm(now)} 拦住时他回：${text.take(30)}${if (release) "，放行" else ""}",
        )
        save()
        LiveTurn(mood, line, !release, release, made)
    }

    /**
     * 不回答也要走：“急事”按钮，或拦到 [LiveConfig.gateMaxMin] 分钟（安全阀）。一律放行，记一笔，算一次冷落。
     * 急事按钮永远可用；用得比 [LiveConfig.escapesPerDay] 多，她今天就不再拦。
     */
    suspend fun gateEscape(reason: String, now: Long, cfg: LiveConfig = LiveConfig()) = lock.withLock {
        val g = st.gate ?: return@withLock
        var sp = Spirits.today(st.spirit, calendarDay(now))
        sp = sp.copy(escapes = sp.escapes + if (reason == "急事") 1 else 0, ignored = sp.ignored + 1)
        if (sp.escapes > cfg.escapesPerDay) sp = sp.copy(gates = cfg.gatesPerDay)
        st = st.copy(
            gate = null,
            spirit = sp,
            decisions = (st.decisions + Decision(now, false)).takeLast(300),
            log = st.log + "${hm(now)} 拦住 ${g.label} 后放行：$reason（没回答）",
        )
        save()
    }

    /** 这几天（3 天）判断上的错：他说的和记录对不上、她猜错的、她提议被拒或取消的、偏离多的那天。最多 5 条。 */
    private fun misjudged(now: Long): List<String> {
        val from = now - 3 * DAY_MS
        val out = mutableListOf<String>()
        st.claims.filter { it.at >= from && it.verdict.isNotBlank() && it.verdict != "对得上" && it.verdict != "看不到" }
            .forEach { out += "他说「${it.text.take(24)}」，${it.verdict}" }
        st.predictions.filter { it.madeAt >= from && it.hit == false }
            .forEach { out += "我猜「${it.text.take(24)}」，错了（实际 ${it.actual?.toInt() ?: "?"}）" }
        director.snapshot().hypotheses.filter { it.at >= from && it.outcome == "错了" }
            .forEach { out += "我以为「${it.guess.take(24)}」，猜错了" }
        st.proposals.filter { it.decidedAt >= from && (it.zkOutcome == "你在总控里取消了" || it.state == ProposalState.DECLINED) }
            .takeLast(2).forEach { out += "我提议「${it.text.take(20)}」，你没要——时机或判断不对" }
        st.thoughts.filter { it.settledAt >= from && it.outcome == "错了" }
            .forEach { out += "我推断「${it.text.take(24)}」，错了${if (it.why.isNotBlank()) "（${it.why.take(20)}）" else ""}" }
        st.days.filter { LocalDate.parse(it.day) >= LocalDate.parse(calendarDay(from)) && it.drifts >= 3 }
            .forEach { out += "${it.day} 偏离了 ${it.drifts} 次" }
        return out.takeLast(5)
    }

    private fun talkLines(): List<String> = st.talk.takeLast(8).map { "${it.who}：${it.text.take(120)}" }

    // ------------------------------------------------------------------ 了解和复盘

    /** 夜里让她提一版“对你的了解”的修改（你点“好”才换）。模型不可用或没什么可改就不提。返回给人看的一句。 */
    private suspend fun proposeAboutUpdate(day: String, now: Long): String {
        val facts = buildString {
            appendLine(com.moling.core.mind.Night.digest(director.snapshot(), day))
            st.days.lastOrNull { it.day == day }?.let { appendLine("今天：做事 ${it.work} 分钟、娱乐 ${it.play} 分钟、主线${if (it.mainStarted) "开始了" else "没开始"}、偏离 ${it.drifts} 次") }
            st.claims.filter { dayKey(it.at) == day && it.verdict.isNotBlank() && it.verdict != "对得上" }.forEach { appendLine("说法对不上：${it.text}（${it.verdict}）") }
            st.talk.filter { dayKey(it.at) == day && it.who == "他" }.takeLast(12).forEach { appendLine("他说：${it.text.take(80)}") }
            st.proposals.filter { dayKey(it.createdAt) == day && it.state != ProposalState.PENDING }.takeLast(10)
                .forEach { appendLine("提议「${it.text.take(30)}」：${it.state.label}${if (it.zkOutcome.isNotBlank()) "，${it.zkOutcome}" else ""}") }
        }
        val (what, doc) = deepModel(config().persona, About.updatePrompt(st.about, facts))?.let { About.parse(it, st.about) } ?: return ""
        val p = propose(Act.About(doc, what), "更新我对你的了解：$what", "夜里整理", Power.SPEAK, now, ttlUntil = now + 36 * 3_600_000L) ?: return ""
        return if (p.state == ProposalState.PENDING) "我想更新对你的了解（$what），等你看一眼" else ""
    }

    /** 他自己改“对你的了解”（直接生效，留一个版本）。 */
    suspend fun editAbout(text: String, now: Long) = lock.withLock {
        setAbout(text, "你", now)
        save()
    }

    private fun setAbout(text: String, by: String, now: Long) {
        val t = text.trim().take(About.MAX_CHARS + 200)
        st = st.copy(about = t, aboutVersions = (st.aboutVersions + AboutVersion(now, t, by)).takeLast(20), log = st.log + "${hm(now)} 对你的了解改了（$by）")
    }

    /** 周报（周日夜里自动出，也可以随时要一份）。 */
    suspend fun week(now: Long): WeekReport = lock.withLock {
        val r = weekReport(dayKey(now), now)
        save()
        r
    }

    private suspend fun weekReport(day: String, now: Long): WeekReport {
        val monday = Reviews.monday(day)
        val from = java.time.LocalDate.parse(monday).atStartOfDay(BEIJING).toInstant().toEpochMilli()
        val accepts = st.decisions.filter { it.at >= from }.map { it.accepted }
        val vetoes = st.proposals.count { it.decidedAt >= from && it.zkOutcome == "你在总控里取消了" }
        val base = Reviews.week(monday, st.days, accepts, vetoes, st.predictions.filter { it.madeAt >= from })
        val last = st.weeks.lastOrNull { it.week < monday }
        val text = deepModel(config().persona, Reviews.prompt(base, last, st.about))?.trim()?.takeIf { it.isNotBlank() } ?: Reviews.localText(base, last)
        val r = base.copy(text = text.take(600))
        st = st.copy(weeks = (st.weeks.filter { it.week != monday } + r).sortedBy { it.week }.takeLast(12), log = st.log + "${hm(now)} 周报：${r.text.take(40)}")
        return r
    }

    /** 四个参谋（大判断前用：重排、换方向、要不要放弃）。模型不可用返回 null。 */
    suspend fun council(question: String, now: Long): Council? = lock.withLock {
        val situation = director.nowText(now) + "\n" + st.flow?.let { Flow.render(it) }.orEmpty() + "\n" + st.about.take(600)
        val c = model(config().persona, Councils.prompt(question, situation, config().persona))?.let { Councils.parse(it) } ?: return@withLock null
        st = st.copy(log = st.log + listOf(
            "${hm(now)} 四个参谋：$question",
            "  教练：${c.coach}", "  军师：${c.strategist}", "  对手：${c.critic}", "  朋友：${c.friend}", "  合起来：${c.together}",
        ))
        save()
        c
    }

    /** “反驳我”：专挑他上一句判断（或 [claim]）的漏洞。 */
    suspend fun devil(now: Long, claim: String = ""): ChatResult = lock.withLock {
        val target = claim.ifBlank { st.talk.lastOrNull { it.who == "他" }?.text.orEmpty() }
        if (target.isBlank()) return@withLock ChatResult("你先说一个判断，我再来挑它的毛病。", emptyList(), false)
        val text = model(config().persona, Modes.devilPrompt(config().persona, target, st.talk))?.trim()?.takeIf { it.isNotBlank() }
            ?: "现在接不上模型。先自己问一句：这个判断最依赖哪一个前提？那个前提不成立会怎样？"
        st = st.copy(talk = (st.talk + Turn("他", "反驳我：${target.take(60)}", now) + Turn("墨灵", text, now)).takeLast(TALK_KEEP))
        save()
        ChatResult(text, emptyList(), true)
    }

    /** “疯想—冷判”：五条离谱路线逐条标事实 / 假设 / 可以试，挑一个最便宜的小实验排进今天（提议）。 */
    suspend fun wild(topic: String, now: Long): ChatResult = lock.withLock {
        val reply = model(config().persona, Modes.wildPrompt(config().persona, topic))
            ?: return@withLock ChatResult("现在接不上模型，疯想要靠她。先自己写三条最离谱的做法，挑一条最便宜的试 15 分钟。", emptyList(), false)
        val (lines, exp) = Modes.parseWild(reply)
        val text = (lines.joinToString("\n") + (exp?.let { "\n小实验：${it.first}（${it.second} 分钟）" } ?: "")).ifBlank { reply.take(400) }
        val start = ((now / 600_000L) + 1) * 600_000L
        val p = exp?.let { (title, min) -> proposeZk(listOf(ZkCommand.AddAction(title, start, start + min * 60_000L, "", "疯想—冷判挑出来的小实验")), "疯想—冷判", now) }
        st = st.copy(talk = (st.talk + Turn("他", "疯想：${topic.take(60)}", now) + Turn("墨灵", text, now)).takeLast(TALK_KEEP))
        save()
        ChatResult(text, listOfNotNull(p), true)
    }

    // ------------------------------------------------------------------ 作息和唤醒

    /** App 把最近两天的亮屏 / 熄屏事件交进来：推算昨晚睡了多久（推不出就不记）。 */
    suspend fun sleep(events: List<ScreenEvent>, now: Long): SleepNight? = lock.withLock {
        val n = Rhythm.lastNight(events, calendarDay(now)) ?: return@withLock null
        st = st.copy(nights = (st.nights.filter { it.day != n.day } + n).sortedBy { it.day }.takeLast(30))
        save()
        n
    }

    /** 给她自己定一个唤醒（App 的按钮也可以用）。 */
    suspend fun setWake(at: Long, note: String, daily: Boolean, now: Long) = lock.withLock {
        setWakeLocked(at, note, daily, now)
        save()
    }

    private fun setWakeLocked(at: Long, note: String, daily: Boolean, now: Long) {
        if (note.isBlank() || at <= now) return
        val w = Wake("w-$now-${st.seq}", at, note.take(80), daily, now)
        st = st.copy(wakes = (st.wakes + w).sortedBy { it.at }.takeLast(30), seq = st.seq + 1, log = st.log + "${hm(now)} 定了唤醒 ${hm(at)}：${note.take(30)}")
    }

    suspend fun cancelWake(id: String) = lock.withLock {
        st = st.copy(wakes = st.wakes.filter { it.id != id })
        save()
    }

    // ------------------------------------------------------------------ 设置

    suspend fun setUserMax(tier: Tier) = lock.withLock {
        st = st.copy(userMax = tier)
        save()
    }

    suspend fun setTopic(name: String, now: Long) = lock.withLock {
        val (a, t) = AtlasOps.topic(st.atlas, name, now)
        st = st.copy(atlas = a, topicId = t.id)
        save()
    }

    // ------------------------------------------------------------------ 工具

    /** 读一次总控（读不到保留上一次的，但事实里会标“看不见”）。 */
    private fun refresh(): ZkStatus? {
        val s = runCatching { zk.status() }.getOrNull() ?: return null
        st = st.copy(lastStatus = s)
        return s
    }

    /** 想得深的事：有深想模型就用它，它不通再退回平时的。 */
    private suspend fun deepModel(system: String, user: String): String? {
        val d = deep ?: return model(system, user)
        val r = try {
            d.ask(system, user)?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return r ?: model(system, user)
    }

    private suspend fun model(system: String, user: String): String? {
        val a = ask ?: return null
        return try {
            a.ask(system, user)?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun save() {
        st = st.copy(log = st.log.takeLast(LOG_KEEP))
        runCatching { store.save(json.encodeToString(BrainState.serializer(), st)) }
    }

    companion object {
        const val TALK_KEEP = 40
        const val PROPOSALS_KEEP = 80
        const val LOG_KEEP = 150
        /** 提议默认挂多久（分钟）；水道交接挂到当天 23:00。 */
        const val PROPOSAL_TTL_MIN = 180
        /** 预言到几点对账（一天中的分钟）。 */
        const val PREDICTION_DUE_MIN = 23 * 60
        private const val DAY_MS = 24 * 3_600_000L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        fun decode(s: String?): BrainState =
            if (s.isNullOrBlank()) BrainState() else runCatching { json.decodeFromString(BrainState.serializer(), s) }.getOrDefault(BrainState())

        fun tierText(t: Tier) = "${t.label}。凡是动总控的都只是建议：他点了“好”、总控确认后才执行；放宽只能向总控申请"

        /** 水道用日历日（0 点换日），和导演的 [dayKey]（4 点换日）不同：水道的时间按当天 0 点算。 */
        fun calendarDay(at: Long): String = Instant.ofEpochMilli(at).atZone(BEIJING).toLocalDate().toString()

        private fun hm(at: Long) = Flow.fmt(Flow.minuteOf(at))

        private fun fmtNum(x: Double?) = x?.let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.1f".format(it) } ?: "?"
    }
}

/** 把手机看到的、总控的状态、今天的水道合成导演要的 [Facts]。 */
object BrainFacts {
    /**
     * 前台应用的类别（[Brain.tick] 用）：总控拦截名单里的算娱乐；Gemini / ChatGPT 算演员；
     * 总控、墨灵、Notion 算做事；其他算 OTHER；空包名给空串（看不见）。
     */
    fun category(pkg: String, s: ZkStatus?): String = when {
        pkg.isBlank() -> ""
        pkg == Actor.GEMINI_LIVE.pkg || pkg == Actor.CHATGPT.pkg -> "ACTOR"
        s != null && pkg in s.blocked -> "ENTERTAINMENT"
        pkg in setOf("com.zongkong.app", "com.moling.app", "notion.id") -> "WORK"
        // 总控连不上时，常见的短视频 / 刷屏应用照样认得出来；连得上就只认他自己在总控里定的名单
        s == null && pkg in KNOWN_PLAY -> "ENTERTAINMENT"
        else -> "OTHER"
    }

    /** 总控连不上时兜底认的娱乐应用。 */
    val KNOWN_PLAY = setOf(
        "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite", "com.smile.gifmaker", "com.kuaishou.nebula",
        "tv.danmaku.bili", "com.xingin.xhs", "com.sina.weibo", "com.google.android.youtube", "com.tencent.qqlive",
    )

    fun merge(phone: Facts, s: ZkStatus?, flow: FlowPlan?, now: Long): Facts {
        val flowNow = flow?.let { Flow.current(it, Flow.minuteOf(now)) }?.title.orEmpty()
        if (s == null) {
            return phone.copy(
                mainTitle = phone.mainTitle.ifBlank { flowNow },
                unknown = (phone.unknown + "总控数据").distinct(),
            )
        }
        val stale = now - s.at > 10 * 60_000L && s.at > 0
        return phone.copy(
            quiet = phone.quiet || s.quiet,
            inFocus = phone.inFocus || s.focus,
            hqActive = phone.hqActive || s.hqActive,
            mainTitle = s.mainTitle.ifBlank { phone.mainTitle.ifBlank { flowNow } },
            mainKey = s.mainKey.ifBlank { phone.mainKey },
            mainPlannedAt = s.mainPlannedAt.takeIf { it > 0 } ?: phone.mainPlannedAt,
            mainStarted = s.mainStarted,
            mainDone = s.mainDone,
            lastProgressAt = maxOf(s.lastProgressAt, phone.lastProgressAt),
            playMinutesToday = s.playMinutesToday,
            workMinutesToday = s.workMinutesToday,
            playLeftMin = s.playLeftMin,
            spendTodayCents = s.spendTodayCents,
            credit = s.credit,
            stance = s.stance,
            aims = s.aims.take(4),
            weekDeliverables = s.weekDeliverables,
            upcoming = s.upcoming,
            unknown = (phone.unknown + if (stale) listOf("总控数据（${(now - s.at) / 60_000} 分钟前的）") else emptyList()).distinct(),
        )
    }
}
