package com.moling.app.ui

import com.moling.core.Brain
import com.moling.core.BrainState
import com.moling.core.ProposalState
import com.moling.core.flow.Flow
import com.moling.core.live.Mood

/**
 * 她此刻的样子（顶栏和今天页的小墨滴用）：只看状态，不调模型。
 * 拦着你 → 气鼓鼓；深夜 → 困；主线做完 → 开心；今天偏离多 → 担心；有事等你决定 → 好奇；其余平静。
 */
fun moodOf(s: BrainState, now: Long): Mood {
    val m = Flow.minuteOf(now)
    val today = Brain.calendarDay(now)
    return when {
        s.gate != null -> Mood.POUT
        m >= 23 * 60 + 30 || m < 6 * 60 -> Mood.SLEEPY
        s.lastStatus?.mainDone == true -> Mood.HAPPY
        s.counterDay == today && s.driftsToday >= 3 -> Mood.WORRIED
        s.proposals.any { it.state == ProposalState.PENDING && it.expiresAt > now } -> Mood.CURIOUS
        s.questions.any { it.answer.isNotBlank() && now - it.answeredAt < 6 * 3_600_000L } -> Mood.SMUG
        else -> Mood.CALM
    }
}

/** 一句她现在的状态（顶栏小字）。 */
fun moodLine(mood: Mood): String = when (mood) {
    Mood.POUT -> "正拦着你呢"
    Mood.SLEEPY -> "该睡了"
    Mood.HAPPY -> "主线做完了"
    Mood.WORRIED -> "今天有点散"
    Mood.CURIOUS -> "有事等你点头"
    Mood.SMUG -> "GPT 回她了"
    Mood.SAD -> "被冷落了"
    Mood.CALM -> "在"
}
