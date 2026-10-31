package com.yishou.app.gate

import com.yishou.app.data.CardAttempt
import com.yishou.app.data.CardMode
import com.yishou.app.data.CardSource

/** 排序用得着的几样：题卡本身，加上最近一次做它的时间（没做过是 null） */
data class ChoiceCandidate(val id: Long, val verified: Boolean, val source: String, val createdAt: Long, val lastDoneAt: Long?)

/**
 * 收费站的题和「题库与素材」页是同一批：导入、拍照收进来的题卡都算，模型自己出的不算。
 * 以前入口只认“没做过、带【要点】、配好模型”的题，题库里明明有题，入口却说没题。
 * 现在：没做过的先出；都做过了就从最久没碰的开始轮；没写答案的题本机判不了，跳过。
 */
object GateChoices {
    /** 本机判对错的选择题，这一回答对几道放行 */
    const val NEED = 2

    fun order(rows: List<ChoiceCandidate>, skip: Set<Long> = emptySet()): List<Long> =
        rows.filter { it.verified && it.source != CardSource.AI && it.id !in skip }
            .sortedWith(compareBy<ChoiceCandidate>({ it.lastDoneAt != null }, { it.lastDoneAt ?: it.createdAt }, { it.createdAt }, { it.id }))
            .map { it.id }

    /** 放行前按记下的作答重算：这一回入口里，自己做对了几道不同的题（看了提示的不算） */
    fun rightSince(attempts: List<CardAttempt>, since: Long): Int =
        attempts.filter { it.createdAt >= since && it.mode == CardMode.GATE && it.verified && it.firstCorrect && it.help == 0 }
            .map { it.cardId }.distinct().size

    fun passed(attempts: List<CardAttempt>, since: Long): Boolean = rightSince(attempts, since) >= NEED
}
