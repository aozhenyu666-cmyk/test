package com.yishou.app.core

/**
 * 伴读节奏：什么时候该看一眼、插一句判断。
 *
 * 前提：用户会沉浸、会不回应、会划走浮窗。
 * 所以这里只管三件事，而且全部有明确上限：
 *  1. 同一个应用连续用够 [CompanionConfig.triggerSeconds] 才触发；
 *  2. 两次之间至少隔 [CompanionConfig.gapSeconds]，30 分钟内最多 [CompanionConfig.maxPerHalfHour] 次；
 *  3. 连续 [CompanionConfig.stopAfterMisses] 次没答，当天停手。
 *
 * 纯逻辑，不碰 Android，可单测。
 */
data class CompanionConfig(
    val enabled: Boolean = false,
    /** 同一应用连续在前台多少秒后触发。 */
    val triggerSeconds: Int = 45,
    /** 两次提问之间至少隔多少秒。 */
    val gapSeconds: Int = 180,
    /** 30 分钟内最多问几次。 */
    val maxPerHalfHour: Int = 6,
    /** 不答多少秒后提醒一次。 */
    val nudgeSeconds: Int = 90,
    /** 不答多少秒后收回桌面并记一次没做到。 */
    val closeSeconds: Int = 150,
    /** 当天连续几次没答就停手。 */
    val stopAfterMisses: Int = 3,
    /** 主动交流只在白天。 */
    val activeStartHour: Int = 8,
    val activeEndHour: Int = 23,
    /** 每天最多几次。 */
    val maxPerDay: Int = 24,
    /** 用户说“休息”后安静多久。 */
    val restMinutes: Int = 30,
    /** 用户说“定个时间”后安静多久。 */
    val deferMinutes: Int = 30,
    /** 同一话题至少隔多久再跟进。 */
    val topicCooldownMinutes: Int = 180,
    /** 有机会时真正开口的百分比。 */
    val opportunityPercent: Int = 70,
) {
    /** UI 上可能把时间调反，读出来先夹一遍。 */
    fun sane(): CompanionConfig {
        val nudge = nudgeSeconds.coerceIn(20, 300)
        return copy(
            triggerSeconds = triggerSeconds.coerceIn(10, 300),
            gapSeconds = gapSeconds.coerceIn(30, 1800),
            maxPerHalfHour = maxPerHalfHour.coerceIn(1, 30),
            nudgeSeconds = nudge,
            closeSeconds = closeSeconds.coerceIn(nudge + 10, 600),
            stopAfterMisses = stopAfterMisses.coerceIn(1, 10),
            activeStartHour = activeStartHour.coerceIn(0, 23),
            activeEndHour = activeEndHour.coerceIn(1, 24),
            maxPerDay = maxPerDay.coerceIn(1, 48),
            restMinutes = restMinutes.coerceIn(5, 240),
            deferMinutes = deferMinutes.coerceIn(5, 240),
            topicCooldownMinutes = topicCooldownMinutes.coerceIn(30, 1440),
            opportunityPercent = opportunityPercent.coerceIn(1, 100),
        )
    }
}

data class CompanionState(
    /** 同一个应用已经连续在前台多少秒。 */
    val fgSeconds: Int = 0,
    /** 距离上一次提问多少秒；从没问过用 Int.MAX_VALUE。 */
    val sinceLastPromptSeconds: Int = Int.MAX_VALUE,
    /** 最近 30 分钟已经问了几次。 */
    val promptsInHalfHour: Int = 0,
    /** 今天已经几次没答。 */
    val misses: Int = 0,
    /** 用户正在说话。 */
    val speaking: Boolean = false,
    /** 一手自己的页面开着（别在它上面再叠一层）。 */
    val ownUiOpen: Boolean = false,
    /** 已经有一道题挂着没答。 */
    val hasPending: Boolean = false,
)

enum class CompanionAction { IDLE, ASK }

enum class SilenceStep { WAIT, NUDGE, CLOSE }

object Companion {
    fun decide(cfg: CompanionConfig, st: CompanionState): CompanionAction {
        if (!cfg.enabled) return CompanionAction.IDLE
        if (st.ownUiOpen || st.speaking || st.hasPending) return CompanionAction.IDLE
        if (st.misses >= cfg.stopAfterMisses) return CompanionAction.IDLE
        if (st.fgSeconds < cfg.triggerSeconds) return CompanionAction.IDLE
        if (st.sinceLastPromptSeconds < cfg.gapSeconds) return CompanionAction.IDLE
        if (st.promptsInHalfHour >= cfg.maxPerHalfHour) return CompanionAction.IDLE
        return CompanionAction.ASK
    }

    fun onSilence(cfg: CompanionConfig, seconds: Int): SilenceStep = when {
        seconds < cfg.nudgeSeconds -> SilenceStep.WAIT
        seconds < cfg.closeSeconds -> SilenceStep.NUDGE
        else -> SilenceStep.CLOSE
    }

    /** 模型没出题、或者没引用屏幕原词时的兜底：仍然逼一个判断，不问“讲了什么”。 */
    fun fallbackQuestion(screenText: String): String {
        val span = FinalReviewRules.quotableSpan(screenText)
        if (span.isBlank()) return ""
        return "原文「$span」——你同意吗？因为什么？"
    }
}
