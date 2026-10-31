package com.yishou.app.card

import com.yishou.app.data.CardAttempt
import com.yishou.app.think.Recite

/**
 * 防乱选。看到选项就点，是省力的本能；这里用三道闸拦住它：
 * 1. 先说再选：选项先藏着，用自己的话说出答案（几个字就行）才放出选项——先“想出来”，再“认出来”；
 * 2. 读完再选：按材料长短，选项要过一小会儿才能点；
 * 3. 连着快、连着错：最近三张都在读完时间内选、又都错了，这一组剩下的强制先说再选。
 */
object AntiRandom {

    /** 至少读多久才能点选项：每个字 0.06 秒，4–20 秒 */
    fun minReadMs(card: Card): Long {
        val chars = card.sentences.sumOf { it.length } + card.question.length + card.options.sumOf { it.length }
        return (chars * 60L).coerceIn(4_000L, 20_000L)
    }

    /** 选得太快（不到读完时间的一半） */
    fun tooFast(durationMs: Long, card: Card): Boolean = durationMs in 1 until minReadMs(card) / 2

    /** 最近三张都很快、都错：像在乱选 */
    fun rushing(results: List<CardAttempt>): Boolean {
        val last = results.takeLast(3)
        return last.size == 3 && last.all { !it.firstCorrect && it.durationMs in 1 until 9_000L }
    }

    /** 先说的那句话最像哪个选项（按顺序对上的字的比例）；都不像返回 null */
    fun closest(said: String, card: Card): Int? {
        if (said.isBlank()) return null
        val scores = card.options.map { o ->
            val d = Recite.diff(o, said)
            if (d.total == 0) 0.0 else d.matched.toDouble() / d.total
        }
        val best = scores.indices.maxByOrNull { scores[it] } ?: return null
        return best.takeIf { scores[it] >= 0.4 }
    }
}
