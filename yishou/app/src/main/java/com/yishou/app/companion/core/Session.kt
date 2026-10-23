package com.yishou.app.companion.core

/** 业务阶段。音频/采集各自还有自己的状态，别混在这一层。 */
enum class Phase(val label: String) {
    OBSERVE("听着"),
    PREPARE("准备问"),
    PAUSE_PENDING("等暂停"),
    ASK("正在问"),
    WAIT_ANSWER("等你答"),
    REVIEW("看你怎么答的"),
    RECORD("记账"),
    SUSPENDED("暂停着"),
    ENDED("结束了"),
}

/** 这一次该做什么：继续听、问、追问、收 */
enum class CoachAction { WAIT, ASK, FOLLOW_UP, CLOSE }

/**
 * 一次介入建议。判据是内部用的，永远不念出来；
 * 依据 ID 必须来自真实证据，解析失败或没依据时上层应当给 WAIT。
 */
data class CoachDecision(
    val action: CoachAction,
    val question: String,
    val criteria: String,
    val evidenceIds: List<Long>,
    val reason: String,
    val epoch: Int,
    val expiresAtMs: Long,
) {
    val usable: Boolean get() = action != CoachAction.WAIT && question.isNotBlank() && evidenceIds.isNotEmpty()
}

/** 暂停这件事的盖章结果，连带是给哪一段、什么时候 */
data class MediaReceipt(
    val sessionTag: String,
    val outcome: PauseOutcome,
    val atMs: Long,
    val epoch: Int,
)

/** 会话要外面去做的事。纯逻辑不碰 Android，只把要做的事说出来 */
sealed interface SessionEffect {
    /** 取消在路上的网络请求、清掉待播队列（不这么做，旧问题会在新视频里响） */
    data object CancelPending : SessionEffect

    /** 念这一问。turnId 用来挡住晚到的旧回调 */
    data class Speak(val turnId: Long, val text: String) : SessionEffect

    data class WaitAnswer(val deadlineMs: Long) : SessionEffect

    /** 暂停没核实成功：提示用户自己暂停，不宣称已经停了 */
    data class AskManually(val hint: String) : SessionEffect

    /** 这一问没接上，记一笔（不算答错） */
    data class Missed(val turnId: Long) : SessionEffect

    data class Record(val turnId: Long, val question: String, val answer: String, val note: String) : SessionEffect

    /** 放掉录音 / 投屏 / 播放 */
    data object ReleaseResources : SessionEffect
}

enum class DecisionOutcome { ACCEPTED, EXPIRED, WRONG_EPOCH, BUSY, WAITED, STOPPED }

/**
 * 一场陪练的状态机（纯逻辑）。把「什么时候能出声」这件事管住：
 *
 * - 同一时刻只有一个可发声的问题；
 * - 过期的建议、别的段（epoch 不同）的建议、停掉以后的建议，一律不接；
 * - 暂停没核实成功就不算暂停，先让用户自己按；
 * - 任何阶段都能停，停了之后晚到的所有事件都不再复活。
 *
 * 时钟从外面传，测试里用假时钟走。
 */
class CompanionSession(
    val sessionId: Long,
    private val clock: () -> Long,
    /** 问题播完以后等用户回答的时间 */
    private val answerWaitMs: Long = 15_000,
) {

    private data class Active(val turnId: Long, val question: String, val askedAtMs: Long)

    private var phase: Phase = Phase.OBSERVE
    private var epoch: Int = 1
    private var turn: Long = 0
    private var pending: CoachDecision? = null
    private var active: Active? = null
    private var answer: String? = null
    private var stopped = false

    fun phase(): Phase = phase
    fun epoch(): Int = epoch
    fun question(): String? = active?.question
    fun pendingDecision(): CoachDecision? = pending
    fun isEnded(): Boolean = stopped

    fun start(): List<SessionEffect> {
        if (stopped) return emptyList()
        phase = Phase.OBSERVE
        return emptyList()
    }

    /** 换视频 / 跳播 / 换目标：开新的一段，手上这一问作废 */
    fun sourceChanged(): List<SessionEffect> {
        if (stopped) return emptyList()
        epoch++
        pending = null
        active = null
        answer = null
        phase = Phase.OBSERVE
        return listOf(SessionEffect.CancelPending)
    }

    /** 场景策略给来一条建议 */
    fun offer(decision: CoachDecision): Pair<DecisionOutcome, List<SessionEffect>> {
        if (stopped) return DecisionOutcome.STOPPED to emptyList()
        if (decision.epoch != epoch) return DecisionOutcome.WRONG_EPOCH to emptyList()
        if (decision.expiresAtMs <= clock()) return DecisionOutcome.EXPIRED to emptyList()
        if (decision.action == CoachAction.WAIT || !decision.usable) return DecisionOutcome.WAITED to emptyList()
        if (pending != null || active != null) return DecisionOutcome.BUSY to emptyList()
        pending = decision
        phase = Phase.PREPARE
        // 先把手上可能还在跑的东西停掉：一个时刻只处理一个
        return DecisionOutcome.ACCEPTED to listOf(SessionEffect.CancelPending)
    }

    /** 暂停试过了，结果是这样 */
    fun onPauseReceipt(receipt: MediaReceipt): List<SessionEffect> {
        if (stopped) return emptyList()
        val d = pending ?: return emptyList()
        if (receipt.epoch != epoch) {
            pending = null
            phase = Phase.OBSERVE
            return listOf(SessionEffect.CancelPending)
        }
        return if (receipt.outcome == PauseOutcome.VERIFIED) {
            ask(d)
        } else {
            phase = Phase.PAUSE_PENDING
            listOf(
                SessionEffect.AskManually(
                    when (receipt.outcome) {
                        PauseOutcome.STILL_PLAYING -> "指令发了但它还在放，你先手动暂停一下。"
                        PauseOutcome.NO_EVIDENCE -> "看不到它的播放状态，你先手动暂停一下。"
                        PauseOutcome.UNSUPPORTED -> "这个播放器停不了（可能需要通知使用权），你先手动暂停一下。"
                        PauseOutcome.WAS_IDLE -> "它本来就没在放，准备好就继续。"
                        PauseOutcome.VERIFIED -> ""
                    },
                ),
            )
        }
    }

    /** 用户说「按好了」：这时候才出声 */
    fun onUserReady(): List<SessionEffect> {
        if (stopped) return emptyList()
        if (phase != Phase.PAUSE_PENDING) return emptyList()
        val d = pending ?: return emptyList()
        return ask(d)
    }

    private fun ask(d: CoachDecision): List<SessionEffect> {
        turn++
        val t = turn
        active = Active(t, d.question, clock())
        pending = null
        answer = null
        phase = Phase.ASK
        return listOf(SessionEffect.Speak(t, d.question))
    }

    /** 这一问真的播完了（没播完不能开始计时） */
    fun onSpoken(turnId: Long): List<SessionEffect> {
        if (stopped) return emptyList()
        val a = active ?: return emptyList()
        if (a.turnId != turnId) return emptyList()
        val now = clock()
        active = a.copy(askedAtMs = now)
        phase = Phase.WAIT_ANSWER
        return listOf(SessionEffect.WaitAnswer(now + answerWaitMs))
    }

    /** 用户答了 */
    fun answer(text: String): List<SessionEffect> {
        if (stopped) return emptyList()
        if (phase != Phase.WAIT_ANSWER) return emptyList()
        if (active == null) return emptyList()
        answer = text
        phase = Phase.REVIEW
        return listOf(SessionEffect.CancelPending)
    }

    /** 判完了，记账。note 是指出的那处差值 */
    fun onReviewed(note: String): List<SessionEffect> {
        if (stopped) return emptyList()
        if (phase != Phase.REVIEW) return emptyList()
        val a = active ?: return emptyList()
        val said = answer.orEmpty()
        active = null
        answer = null
        phase = Phase.RECORD
        return listOf(SessionEffect.Record(a.turnId, a.question, said, note))
    }

    /** 记完了，回去听。留着单独一道，是为了让上层确认真的写进去了 */
    fun onRecorded(): List<SessionEffect> {
        if (stopped) return emptyList()
        if (phase != Phase.RECORD) return emptyList()
        phase = Phase.OBSERVE
        return emptyList()
    }

    /** 每秒走一次：过期作废、没接记账 */
    fun tick(nowMs: Long): List<SessionEffect> {
        if (stopped) return emptyList()
        val p = pending
        if (phase == Phase.PREPARE && p != null && p.expiresAtMs <= nowMs) {
            pending = null
            phase = Phase.OBSERVE
            return listOf(SessionEffect.CancelPending)
        }
        val a = active
        if (phase == Phase.WAIT_ANSWER && a != null && nowMs - a.askedAtMs >= answerWaitMs) {
            active = null
            answer = null
            phase = Phase.OBSERVE
            // 没接不算答错，只记「这一问没接上」
            return listOf(SessionEffect.Missed(a.turnId))
        }
        return emptyList()
    }

    /** 任何阶段都能停。停了以后晚到的事件都不再复活。 */
    fun stop(): List<SessionEffect> {
        if (stopped) return emptyList()
        stopped = true
        phase = Phase.ENDED
        pending = null
        active = null
        answer = null
        return listOf(SessionEffect.CancelPending, SessionEffect.ReleaseResources)
    }
}
