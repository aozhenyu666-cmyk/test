package com.yishou.app.reflex

import com.yishou.app.judge.LockedQ
import com.yishou.app.judge.Point

/**
 * 脚手架退坡：同一类题，做得越顺，帮得越少，最后发球权交回给你。
 * - 喂：答题前先给提示（看过的提示进 seen，抄提示不算命中）；
 * - 你发球：先问出“解这题第一个必须搞清楚的问题”，问准了才开始答；
 * - 裸题：只有题目。
 * 连着两次没过降一级。
 */
enum class FadeLevel { FEED, ASK_FIRST, BARE }

data class FadeState(val wins: Int = 0, val misses: Int = 0) {
    val level: FadeLevel get() = when {
        wins >= 6 -> FadeLevel.BARE
        wins >= 3 -> FadeLevel.ASK_FIRST
        else -> FadeLevel.FEED
    }

    fun after(passed: Boolean): FadeState = when {
        passed -> FadeState(wins + 1, 0)
        misses + 1 >= 2 -> FadeState(downTo(level), 0)
        else -> copy(misses = misses + 1)
    }

    /** 降一级：回到下一级的起点 */
    private fun downTo(l: FadeLevel) = when (l) {
        FadeLevel.BARE -> 3
        FadeLevel.ASK_FIRST -> 0
        FadeLevel.FEED -> 0
    }
}

object Fade {

    /** 你发球：判你问的第一个问题是不是对准了第一个要点 */
    fun askFirstQ(q: LockedQ): LockedQ = LockedQ(
        id = q.id + "-ask",
        prompt = q.prompt + "\n\n这次先别答。要解这道题，第一个必须搞清楚的问题是什么？问出来。",
        points = listOf(Point("P1", "提出的是一个问题，而且问的正是要弄清这一点：${q.points.first().criterion}")),
        need = 1,
        reference = "",
    )

    /** 喂的时候，答题前先看的提示 */
    fun feedHints(q: LockedQ): List<String> = q.points.map { it.hint }.filter { it.isNotBlank() }.distinct()
}
