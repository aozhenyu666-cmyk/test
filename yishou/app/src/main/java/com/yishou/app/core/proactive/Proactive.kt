package com.yishou.app.core.proactive

import com.yishou.app.core.CompanionConfig
import com.yishou.app.core.JudgementCheck
import com.yishou.app.core.JudgementProblem
import com.yishou.app.core.JudgementRules

/** 把界面上的伴读配置映射成唯一调度器的配置。 */
fun CompanionConfig.toProactive(): ProactiveConfig = ProactiveConfig(
    enabled = enabled,
    activeStartHour = activeStartHour,
    activeEndHour = activeEndHour,
    minGapMinutes = (gapSeconds / 60).coerceAtLeast(1),
    maxPerHour = maxPerHalfHour,
    maxPerDay = maxPerDay,
    presenceMinutes = (triggerSeconds / 60).coerceAtLeast(1),
    restMinutes = restMinutes,
    deferMinutes = deferMinutes,
    topicCooldownMinutes = topicCooldownMinutes,
    noReplyBackoff = stopAfterMisses.coerceAtLeast(2),
    opportunityPercent = opportunityPercent,
).sane()

/**
 * 一手唯一的主动调度器。
 *
 * 所有主动开口都必须经过 [ProactiveEngine.decide]；其他模块只提供上下文和候选话题，
 * 不再各自判断“该不该说话”。
 *
 * 纯逻辑，不碰 Android，可单测。
 */
data class ProactiveConfig(
    val enabled: Boolean = true,
    /** 只在白天打扰。 */
    val activeStartHour: Int = 8,
    val activeEndHour: Int = 23,
    /** 两次开口之间至少隔多少分钟（提问、回答都算起算点）。 */
    val minGapMinutes: Int = 12,
    /**
     * 秒级间隔：高频陪练会话用（默认 90 秒）。大于 0 时优先于 [minGapMinutes]。
     * 平时为 0，仍按分钟走；会话结束回到 0，不会把日常节奏带快。
     */
    val minGapSeconds: Int = 0,
    /** 每小时最多几次。 */
    val maxPerHour: Int = 4,
    /** 每天最多几次。 */
    val maxPerDay: Int = 24,
    /** 同一个应用至少连续待多少分钟才进入候选。 */
    val presenceMinutes: Int = 5,
    /** 用户说“休息”后安静多久。 */
    val restMinutes: Int = 30,
    /** 用户说“定个时间”后安静多久。 */
    val deferMinutes: Int = 30,
    /** 同一个话题至少隔多久才再跟进。 */
    val topicCooldownMinutes: Int = 180,
    /** 连续几次没回应后进入退避。 */
    val noReplyBackoff: Int = 2,
    /** 每次机会只有这个百分比会真的开口；其余保持安静。 */
    val opportunityPercent: Int = 70,
) {
    fun sane(): ProactiveConfig = copy(
        activeStartHour = activeStartHour.coerceIn(0, 23),
        activeEndHour = activeEndHour.coerceIn(1, 24),
        minGapMinutes = minGapMinutes.coerceIn(1, 240),
        minGapSeconds = minGapSeconds.coerceIn(0, 7_200),
        // 高频会话最短 30 秒一次，一小时理论最多 120 次；上限放到 60 留余量
        maxPerHour = maxPerHour.coerceIn(1, 60),
        // 高频会话会临时把当天额度抬高（日常 24 + 这一场预算），上限放到 200
        maxPerDay = maxPerDay.coerceIn(1, 200),
        presenceMinutes = presenceMinutes.coerceIn(1, 60),
        restMinutes = restMinutes.coerceIn(5, 240),
        deferMinutes = deferMinutes.coerceIn(5, 240),
        topicCooldownMinutes = topicCooldownMinutes.coerceIn(30, 1440),
        noReplyBackoff = noReplyBackoff.coerceIn(1, 6),
        opportunityPercent = opportunityPercent.coerceIn(1, 100),
    )
}

enum class ProactiveMode {
    IDLE,
    /** 已提出，等待回答。 */
    ASKING,
    ANSWERED,
    /** 用户说“定个时间”。 */
    DEFERRED,
    /** 用户说“休息”。 */
    RESTING,
    /** 连续没回应，退避。 */
    BACKOFF,
}

data class ProactiveState(
    val mode: ProactiveMode = ProactiveMode.IDLE,
    val lastAskAt: Long = 0,
    val lastAnswerAt: Long = 0,
    val restUntil: Long = 0,
    val deferUntil: Long = 0,
    val unanswered: Int = 0,
    val promptsThisHour: Int = 0,
    val promptsToday: Int = 0,
    val lastTopicId: Long = 0,
)

/** 候选话题的精简视图，避免把整条记忆塞进判断。 */
data class TopicBrief(
    val id: Long,
    val title: String,
    val state: String,
    val lastMentionedAt: Long,
    val followUpAt: Long,
)

data class ProactiveContext(
    val now: Long,
    val hour: Int,
    val foregroundPkg: String?,
    /** 前台应用是否在关注名单里。 */
    val watched: Boolean,
    /** 在这个应用连续待了多少分钟。 */
    val foregroundMinutes: Int,
    val taskTitle: String?,
    val stuck: String?,
    val openTopics: List<TopicBrief> = emptyList(),
    val lastAnswer: String? = null,
    val speaking: Boolean = false,
    val ownUiOpen: Boolean = false,
    val hasPending: Boolean = false,
)

enum class AskKind { TASK_NUDGE, TOPIC_FOLLOW_UP, REST_CHECK }

sealed interface ProactiveDecision {
    data object Silent : ProactiveDecision
    data class Ask(val kind: AskKind, val topicId: Long?) : ProactiveDecision
}

object ProactiveEngine {
    /**
     * [roll] 是 0–99 的随机数，由调用方传入；这样测试可以固定机会，不依赖真实随机。
     */
    fun decide(
        cfg: ProactiveConfig,
        state: ProactiveState,
        ctx: ProactiveContext,
        roll: Int,
    ): ProactiveDecision {
        if (!cfg.enabled) return ProactiveDecision.Silent
        if (ctx.ownUiOpen || ctx.speaking || ctx.hasPending) return ProactiveDecision.Silent
        if (ctx.hour !in cfg.activeStartHour until cfg.activeEndHour) return ProactiveDecision.Silent

        val now = ctx.now
        if (state.mode == ProactiveMode.RESTING && now < state.restUntil) return ProactiveDecision.Silent
        if (state.mode == ProactiveMode.DEFERRED && now < state.deferUntil) return ProactiveDecision.Silent

        val dueTopic = dueTopic(cfg, state, ctx)
        // 退避：连续没回应后，只有明确到期的话题才值得再开口；否则保持安静。
        if (state.mode == ProactiveMode.BACKOFF || state.unanswered >= cfg.noReplyBackoff) {
            return if (dueTopic != null) ProactiveDecision.Ask(AskKind.TOPIC_FOLLOW_UP, dueTopic.id)
            else ProactiveDecision.Silent
        }

        val gapMs = if (cfg.minGapSeconds > 0) cfg.minGapSeconds * 1_000L else cfg.minGapMinutes * 60_000L
        if (state.lastAskAt > 0 && now - state.lastAskAt < gapMs) return ProactiveDecision.Silent
        if (state.lastAnswerAt > 0 && now - state.lastAnswerAt < gapMs) return ProactiveDecision.Silent
        if (state.promptsThisHour >= cfg.maxPerHour) return ProactiveDecision.Silent
        if (state.promptsToday >= cfg.maxPerDay) return ProactiveDecision.Silent

        // 机会判断：不是每次 tick 都开口。
        if (roll !in 0 until cfg.opportunityPercent) return ProactiveDecision.Silent

        if (dueTopic != null) return ProactiveDecision.Ask(AskKind.TOPIC_FOLLOW_UP, dueTopic.id)

        if (ctx.watched && ctx.foregroundMinutes >= cfg.presenceMinutes) {
            return if (!ctx.taskTitle.isNullOrBlank() || !ctx.stuck.isNullOrBlank()) {
                ProactiveDecision.Ask(AskKind.TASK_NUDGE, null)
            } else {
                ProactiveDecision.Ask(AskKind.REST_CHECK, null)
            }
        }
        return ProactiveDecision.Silent
    }

    private fun dueTopic(cfg: ProactiveConfig, state: ProactiveState, ctx: ProactiveContext): TopicBrief? {
        val now = ctx.now
        val cooldown = cfg.topicCooldownMinutes * 60_000L
        return ctx.openTopics
            .filter { it.id != state.lastTopicId }
            .filter { it.followUpAt in 1..now }
            .filter { now - it.lastMentionedAt >= cooldown }
            .minByOrNull { it.followUpAt }
    }
}

object ProactiveAnswer {
    /** 话题跟进不是考试：只要说出进展就算，不要求立场 + 理由。 */
    fun checkTopic(text: String, attempt: Int = 0): JudgementCheck {
        val sub = JudgementRules.substance(text)
        if (sub < 3) {
            return JudgementCheck(
                pass = false,
                problem = JudgementProblem.TOO_SHORT,
                hint = "展开一点：现在到哪一步了？",
                reask = attempt <= 0,
            )
        }
        return JudgementCheck(pass = true, hint = "记下了。")
    }
}


enum class AnswerAction { REST, DEFER, BACK, DONE, ANSWERED }

data class PolicyResult(
    val state: ProactiveState,
    val action: AnswerAction,
    /** 要写进关系记忆的一句话；空串表示不写。 */
    val memory: String,
)

object ProactivePolicy {
    fun onAnswer(cfg: ProactiveConfig, state: ProactiveState, text: String, now: Long): PolicyResult {
        val t = text.trim()
        val action = when {
            listOf("休息", "歇", "睡", "放松", "缓一下", "缓一缓").any { t.contains(it) } -> AnswerAction.REST
            listOf("定个时间", "晚点", "稍后", "待会", "半小时", "一小时", "等会").any { t.contains(it) } -> AnswerAction.DEFER
            listOf("现在回去", "开始", "去做", "回去做", "马上做").any { t.contains(it) } -> AnswerAction.BACK
            RelationRules.isResolved(t) -> AnswerAction.DONE
            else -> AnswerAction.ANSWERED
        }
        val mode = when (action) {
            AnswerAction.REST -> ProactiveMode.RESTING
            AnswerAction.DEFER -> ProactiveMode.DEFERRED
            else -> ProactiveMode.ANSWERED
        }
        val next = state.copy(
            mode = mode,
            lastAnswerAt = now,
            restUntil = if (action == AnswerAction.REST) now + cfg.restMinutes * 60_000L else 0,
            deferUntil = if (action == AnswerAction.DEFER) now + cfg.deferMinutes * 60_000L else 0,
            unanswered = 0,
        )
        val memory = when (action) {
            AnswerAction.REST -> "休息：$t"
            AnswerAction.DEFER -> "计划稍后回去：$t"
            AnswerAction.BACK -> "决定现在回去：$t"
            AnswerAction.DONE -> "完成了：$t"
            AnswerAction.ANSWERED -> t
        }
        return PolicyResult(next, action, memory)
    }

    /** 没回应：累计并进入退避；时间也从这一刻重新算，避免刚收手马上又问。 */
    fun onMiss(cfg: ProactiveConfig, state: ProactiveState, now: Long): ProactiveState {
        val n = state.unanswered + 1
        return state.copy(
            mode = if (n >= cfg.noReplyBackoff) ProactiveMode.BACKOFF else ProactiveMode.ASKING,
            unanswered = n,
            lastAnswerAt = now,
        )
    }

    /** 跨小时/跨日重置计数。 */
    fun resetCounters(
        state: ProactiveState,
        now: Long,
        hourKey: Long,
        dayKey: String,
        lastHourKey: Long,
        lastDayKey: String,
    ): ProactiveState {
        var s = state
        if (lastHourKey != hourKey) s = s.copy(promptsThisHour = 0)
        if (lastDayKey != dayKey) s = s.copy(promptsToday = 0)
        return s
    }
}
