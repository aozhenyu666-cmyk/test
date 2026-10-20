package com.moling.core

import com.moling.core.atlas.Atlas
import com.moling.core.atlas.AtlasOps
import com.moling.core.atlas.Candidates
import com.moling.core.atlas.Node
import com.moling.core.atlas.NodeKind
import com.moling.core.flow.Cause
import com.moling.core.flow.Flow
import com.moling.core.flow.FlowPlan
import com.moling.core.guard.GuardAction
import com.moling.core.guard.GuardMemory
import com.moling.core.guard.GuardSample
import com.moling.core.guard.ZkGuard
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
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
import com.moling.core.predict.DayStat
import com.moling.core.predict.Predict
import com.moling.core.predict.Prediction
import com.moling.core.talk.Talk
import com.moling.core.talk.TalkAction
import com.moling.core.talk.Turn
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
)

/** 用户对提议的一次表态（算接受率，挣权柄用）。 */
@Serializable
data class Decision(val at: Long, val accepted: Boolean)

/** 大脑要提醒用户的一件事（通知、小窗、今天页的横条）。 */
@Serializable
data class Notice(val kind: String, val text: String, val at: Long, val proposalId: String = "")

/** 大脑的全部状态，整份存成一段 JSON。 */
@Serializable
data class BrainState(
    val flow: FlowPlan? = null,
    val atlas: Atlas = Atlas(),
    val topicId: String = "",
    val predictions: List<Prediction> = emptyList(),
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
    /** 提议编号用的流水号。 */
    val seq: Long = 0,
    /** 给人看的行动日志（做了什么、交给总控什么、总控怎么回的）。 */
    val log: List<String> = emptyList(),
)

data class ChatResult(val text: String, val proposals: List<Proposal>, val byModel: Boolean)

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

    suspend fun chat(text: String, now: Long): ChatResult = lock.withLock {
        refresh()
        val cfg = config()
        val day = calendarDay(now)
        val flow = st.flow?.takeIf { it.day == day }
        val baton = director.snapshot().baton
        val batonText = baton?.nextAsk?.ifBlank { baton.question }.orEmpty()
        val topic = st.atlas.topics.firstOrNull { it.id == st.topicId }
        val prompt = Talk.prompt(
            persona = cfg.persona,
            now = director.nowText(now),
            flow = flow?.let { Flow.render(it) }.orEmpty(),
            baton = batonText,
            atlas = topic?.let { AtlasOps.outline(st.atlas, it.id) }.orEmpty(),
            tierText = tierText(tier()),
            history = st.talk,
            user = text,
        )
        val parsed = model(cfg.persona, prompt)?.let { Talk.parse(it, Flow.dayStart(day)) }?.takeIf { it.text.isNotBlank() }
        val reply = parsed ?: Talk.local(text, batonText, flow?.let { Flow.current(it, Flow.minuteOf(now)) }?.title.orEmpty())
        val made = reply.actions.mapNotNull { a -> fromTalk(a, now, flow) }
        st = st.copy(talk = (st.talk + Turn("他", text, now) + Turn("墨灵", reply.text, now)).takeLast(TALK_KEEP))
        save()
        ChatResult(reply.text, made, reply.byModel)
    }

    private fun fromTalk(a: TalkAction, now: Long, flow: FlowPlan?): Proposal? = when (a) {
        is TalkAction.Zk -> proposeZk(listOf(a.command), a.command.reason, now)
        is TalkAction.Summon -> propose(Act.Summon(a.actor, a.topic), "去 ${a.actor.label} 聊：${a.topic.ifBlank { "接着想" }}", "对话里说到的", Power.SUMMON, now)
        is TalkAction.AtlasNote -> propose(Act.AtlasNote(a.topic, a.claim), "在「${a.topic}」里记一条：${a.claim.take(40)}", "对话里说到的", Power.PLAN, now)
        is TalkAction.Reroute -> {
            val cause = Cause.entries.firstOrNull { a.cause.contains(it.label) } ?: Cause.CONFLICT
            val c = flow?.let { Flow.current(it, Flow.minuteOf(now)) }
            if (flow == null || c == null) null
            else propose(Act.Reroute(cause, c.id, flow.day), "「${c.title}」改道：${cause.label}", "对话里说到的", Power.PLAN, now)
        }
    }

    // ------------------------------------------------------------------ 水道

    /** 排今天的水道（草稿，没交给总控）。已经有今天的就直接返回，除非 [force]。顺便下今天的预言。 */
    suspend fun planDay(now: Long, force: Boolean = false): FlowPlan = lock.withLock {
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
            patterns = mind.memories.filter { it.kind == MemoryKind.PATTERN }.sortedByDescending { it.hits }.take(3).map { it.text },
            bestHours = emptyList(),
            freeMinutes = s?.playLeftMin?.takeIf { it >= 0 } ?: 60,
        )
        val system = config().persona
        val plan = model(system, Flow.morningPrompt(inputs))?.let { Flow.parse(it, inputs) } ?: Flow.local(inputs)
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
        if (p.expiresAt <= now) {
            update(p.copy(state = ProposalState.EXPIRED, decidedAt = now))
            save()
            return@withLock Outcome(false, "这条过期了，情况可能变了")
        }
        st = st.copy(decisions = (st.decisions + Decision(now, true)).takeLast(300))
        val out = execute(p, now)
        save()
        out
    }

    /** 用户点“不用”。 */
    suspend fun decline(id: String, now: Long) = lock.withLock {
        val p = st.proposals.firstOrNull { it.id == id && it.state == ProposalState.PENDING } ?: return@withLock
        update(p.copy(state = ProposalState.DECLINED, decidedAt = now))
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
    }

    /** 逐条交给总控；连不上或被拒就停下，记成“没送到”，可以重试。 */
    private fun sendAll(p: Proposal, commands: List<ZkCommand>, now: Long): Outcome {
        val tier = tier()
        // 点“好”时再过一遍铁律：等级可能变了，指令也可能在存储里被改坏。
        commands.firstOrNull { !ZkWire.valid(it) || !Charter.check(it.power(), tier).allowed }?.let { bad ->
            update(p.copy(state = ProposalState.DECLINED, decidedAt = now, receipt = "现在的权柄不允许：${bad.describe()}"))
            return Outcome(false, "这条现在不能交给总控（${Charter.check(bad.power(), tier).reason}）")
        }
        var sent = p.sent
        while (sent < commands.size) {
            val r = runCatching { zk.send(commands[sent]) }.getOrNull()
            if (r == null || !r.ok) {
                val why = r?.message?.ifBlank { null } ?: if (r == null) "连不上总控" else "总控拒绝了"
                update(p.copy(state = ProposalState.FAILED, sent = sent, receipt = why, decidedAt = now))
                st = st.copy(log = st.log + "${hm(now)} 没送到总控（$why）：${commands[sent].describe()}")
                return Outcome(false, "没送到总控：$why。可以稍后重试")
            }
            st = st.copy(log = st.log + "${hm(now)} 交给总控：${commands[sent].describe()}｜回执：${r.message}")
            sent++
        }
        update(p.copy(state = ProposalState.SENT, sent = sent, receipt = "总控收下了", decidedAt = now))
        return Outcome(true, "交给总控了，在总控里确认后执行")
    }

    private fun proposeZk(commands: List<ZkCommand>, why: String, now: Long, ttlUntil: Long = 0): Proposal? {
        val tier = tier()
        val ok = commands.filter { ZkWire.valid(it) && Charter.check(it.power(), tier).allowed }
        if (ok.isEmpty()) return null
        val text = if (ok.size == 1) ok[0].describe() else "把今天的水道交给总控：" + ok.joinToString("；") { it.describe().substringBefore("。原因") }
        val power = ok.maxBy { it.power().ordinal }.power()
        return propose(Act.Zk(ok), text, why, power, now, ttlUntil)
    }

    private fun propose(act: Act, text: String, why: String, power: Power, now: Long, ttlUntil: Long = 0): Proposal? {
        if (!Charter.check(power, tier()).allowed) return null
        // 同一句话还挂着，就不再提一次。
        pending(now).firstOrNull { it.text == text }?.let { return it }
        val expires = if (ttlUntil > now) ttlUntil else now + PROPOSAL_TTL_MIN * 60_000L
        val p = Proposal("p-$now-${st.seq}", act, text, why, power, now, expires)
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
        if (s != null) out += settle(s, now)
        if (guard != null) watchZk(guard, now)?.let { out += it }
        drift(s, foregroundCategory, now)?.let { out += it }
        save()
        out
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

    private fun drift(s: ZkStatus?, category: String, now: Long): Notice? {
        val plan = st.flow?.takeIf { it.day == calendarDay(now) && it.confirmed } ?: return null
        val playing = category == "ENTERTAINMENT" || category == "SHOPPING"
        val working = s?.focus == true || category == "WORK" || category == "ACTOR"
        val d = Flow.drift(plan, Flow.minuteOf(now), playing, working) ?: return null
        val key = "${plan.day}:${d.channel.id}"
        if (key in st.nudged) return null
        st = st.copy(nudged = (st.nudged + key).takeLast(50))
        val left = (d.channel.endMin - Flow.minuteOf(now)).coerceIn(5, 60)
        val p = if (tier().level >= Tier.PLANNER.level) {
            proposeZk(listOf(ZkCommand.StartFocus(left, d.channel.title, "水道「${d.channel.title}」过了 ${d.lateMin} 分钟还在娱乐")), "水流偏了", now)
        } else {
            null
        }
        val text = "水该在「${d.channel.title}」（过了 ${d.lateMin} 分钟），现在在别处。" +
            if (p != null) "要不要让总控开 $left 分钟专注锁？或者说一声卡住了 / 累了，我来改道。" else "卡住了还是累了？说一声我来改道。"
        return Notice("drift", text, now, p?.id.orEmpty())
    }

    // ------------------------------------------------------------------ 夜间

    /** 夜里整理一次（同一天只做一次）：记下今天的数、对账、重算权柄，再让导演整理日记。返回给人看的一段话。 */
    suspend fun night(now: Long): String = lock.withLock {
        val day = dayKey(now)
        if (st.lastNightDay == day) return@withLock ""
        val s = refresh()
        if (s != null) {
            val stat = DayStat(day, s.playMinutesToday, s.workMinutesToday, s.mainStarted || s.mainDone, s.spendTodayCents / 100.0)
            st = st.copy(days = (st.days.filter { it.day != day } + stat).sortedBy { it.day }.takeLast(60))
            st = st.copy(predictions = Predict.settle(st.predictions, s, now))
        }
        val oldTier = tier()
        st = st.copy(trust = trust(now, day), lastNightDay = day, nudged = st.nudged.filter { it.startsWith(calendarDay(now)) })
        val newTier = tier()
        director.onSense(Sense(SenseKind.NIGHT, now, "brain"))
        val text = buildString {
            append(Predict.scoreText(st.predictions))
            if (newTier != oldTier) append("。权柄变了：${oldTier.label} → ${newTier.label}")
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
