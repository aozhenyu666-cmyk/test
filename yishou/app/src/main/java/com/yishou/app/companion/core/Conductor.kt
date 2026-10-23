package com.yishou.app.companion.core

/** 胶囊上分别显示的三件事。不能只用一个绿灯表示一切正常——缺了什么要看得见。 */
data class CapsuleState(
    val sound: Boolean?,
    val frames: Boolean?,
    val activeMs: Long,
    val phase: Phase,
    val soundNote: String = "",
    val frameNote: String = "",
)

/** 胶囊文案：说人话、说真话 */
object Capsule {

    fun text(s: CapsuleState): String {
        val parts = buildList {
            add(soundLine(s))
            add(frameLine(s))
            add("有效 ${minutes(s.activeMs)} 分钟")
            add(phaseLine(s.phase))
        }
        return parts.joinToString(" · ")
    }

    fun soundLine(s: CapsuleState): String = when (s.sound) {
        true -> "声音有"
        false -> "声音缺失" + if (s.soundNote.isNotBlank()) "（${s.soundNote}）" else ""
        null -> "声音未验证"
    }

    fun frameLine(s: CapsuleState): String = when (s.frames) {
        true -> "画面有"
        false -> "画面缺失" + if (s.frameNote.isNotBlank()) "（${s.frameNote}）" else ""
        null -> "画面未验证"
    }

    fun phaseLine(p: Phase): String = when (p) {
        Phase.OBSERVE -> "在听"
        Phase.PREPARE -> "准备问"
        Phase.PAUSE_PENDING -> "等你按暂停"
        Phase.ASK -> "在问"
        Phase.WAIT_ANSWER -> "等你答"
        Phase.REVIEW -> "在想你怎么答的"
        Phase.RECORD -> "记账"
        Phase.SUSPENDED -> "暂停着"
        Phase.ENDED -> "已结束"
    }

    private fun minutes(ms: Long): String = (ms / 60_000).toString()
}

/** 一次「该不该问、问了没有」的结果 */
sealed interface AskOutcome {
    data class Asked(val question: String, val move: ThinkMove) : AskOutcome
    data class Waited(val reason: String) : AskOutcome
    /** 暂停没核实成功：已经提示用户自己按，等他点「好了」再念 */
    data class NeedManual(val hint: String) : AskOutcome
    data class Busy(val reason: String) : AskOutcome
}

/** 一次作答的结果 */
data class AnswerOutcome(val verdict: Verdict, val delta: String)

/**
 * 外面要提供的能力。协调器只依赖这些接口，不碰网络、不碰 Android：
 * 换成真模型、假模型、真播放器、假播放器都一样能跑。
 */
interface CoachPorts {
    /** 让模型按材料出一问（返回原文，由协调器解析） */
    suspend fun propose(plan: AskPlan.Ask): CoachParse

    /** 试着暂停目标播放器并核实 */
    suspend fun pause(): MediaReceipt

    /** 念一句，播完返回 true；被打断返回 false */
    suspend fun speak(text: String): Boolean

    /** 判一次作答 */
    suspend fun judge(question: String, criteria: String, answer: String, material: String): JudgeParse

    /** 记一笔：这一问、他怎么答的、差在哪 */
    suspend fun record(turnId: Long, question: String, answer: String, delta: String, verdict: Verdict, move: ThinkMove)
}

/**
 * 把时间轴、策略、会话状态串成一条线。
 *
 * 只做顺序上的事：能不能问 → 让模型出问题 → 解析 → 走暂停 → 念 → 等他答 → 判 → 记。
 * 每一步都检查会话是不是还在同一段（epoch）、是不是已经停掉——旧的东西不许复活。
 */
class Conductor(
    private val session: CompanionSession,
    private val coach: VideoCoach,
    private val ports: CoachPorts,
    private val now: () -> Long,
) {

    private var lastDecision: CoachDecision? = null
    private var lastMove: ThinkMove? = null

    /** 该问就问一次。没到时机、模型没给合格问题、暂停没成功，都如实返回原因。 */
    suspend fun tryAsk(input: CoachInput): AskOutcome {
        if (session.phase() != Phase.OBSERVE) return AskOutcome.Busy("手上还有一问没走完（${session.phase().label}）")
        val plan = coach.consider(input)
        if (plan !is AskPlan.Ask) return AskOutcome.Waited((plan as AskPlan.Wait).reason)

        val parsed = ports.propose(plan)
        val decision = parsed.asDecision(plan.epoch, plan.expiresAtMs)
        if (decision.action == CoachAction.WAIT) return AskOutcome.Waited(decision.reason)
        if (decision.epoch != session.epoch()) return AskOutcome.Waited("材料已经换过一段了")

        val (outcome, _) = session.offer(decision)
        if (outcome != DecisionOutcome.ACCEPTED) return AskOutcome.Waited("会话没接这条建议（$outcome）")
        lastDecision = decision
        lastMove = plan.move

        val receipt = ports.pause()
        val effects = session.onPauseReceipt(receipt)
        effects.filterIsInstance<SessionEffect.AskManually>().firstOrNull()?.let {
            return AskOutcome.NeedManual(it.hint)
        }
        val speak = effects.filterIsInstance<SessionEffect.Speak>().firstOrNull()
            ?: return AskOutcome.Waited("没排出要念的话")
        return speakNow(speak, plan.move)
    }

    /** 用户手动按好暂停了：这时候才念 */
    suspend fun userReady(): AskOutcome {
        lastDecision ?: return AskOutcome.Waited("没有等着念的问题")
        val move = lastMove ?: ThinkMove.RECALL
        val speak = session.onUserReady().filterIsInstance<SessionEffect.Speak>().firstOrNull()
            ?: return AskOutcome.Waited("这一问已经过期了")
        return speakNow(speak, move)
    }

    private suspend fun speakNow(speak: SessionEffect.Speak, move: ThinkMove): AskOutcome {
        val done = ports.speak(speak.text)
        if (!done) {
            // 被打断：这一问作废，回去听；材料没换，所以不动 epoch。
            // 也不计入打扰预算——没问出去就不算问过，不然一次打断会白白吃掉一次机会。
            session.dropQuestion()
            lastDecision = null
            lastMove = null
            return AskOutcome.Waited("念到一半被打断了")
        }
        coach.onAsked(now(), move)
        session.onSpoken(speak.turnId)
        return AskOutcome.Asked(speak.text, move)
    }

    /** 用户答了：判一次，然后记账 */
    suspend fun answer(text: String, material: String): AnswerOutcome? {
        val effects = session.answer(text)
        if (effects.isEmpty()) return null
        val d = lastDecision ?: return null
        val judged = ports.judge(d.question, d.criteria, text, material)
        val (verdict, delta) = when (judged) {
            is JudgeParse.Ok -> judged.verdict to judged.delta
            // 判不出来记「没判成」，不算答错
            JudgeParse.Failed -> Verdict.NONE to ""
        }
        val rec = session.onReviewed(delta).filterIsInstance<SessionEffect.Record>().firstOrNull()
        if (rec != null) {
            ports.record(rec.turnId, rec.question, rec.answer, delta, verdict, lastMove ?: ThinkMove.RECALL)
        }
        session.onRecorded()
        lastDecision = null
        lastMove = null
        return AnswerOutcome(verdict, delta)
    }

    /** 到时间没答：记一笔没接，不算答错 */
    fun tick(): Boolean {
        val missed = session.tick(now()).any { it is SessionEffect.Missed }
        if (missed) {
            lastDecision = null
            lastMove = null
        }
        return missed
    }

    fun stop(): List<SessionEffect> {
        lastDecision = null
        lastMove = null
        return session.stop()
    }

    fun phase(): Phase = session.phase()
}
