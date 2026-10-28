package com.yishou.app.judge

import com.yishou.app.card.Card
import com.yishou.app.think.Words

/**
 * 入口题卡转锁题。
 *
 * 要点优先取题包自带的【要点】；题包没写要点时（粉笔这类只有「解析」的题包很常见），
 * 退回从解析里拆判据——不然一整库题都进不了入口，只能在收费站点“等 10 分钟”。
 * 拆不出来就返回 null，不拿一道没锁的题凑数。
 */
object LockedQuestions {
    private val SPLIT = Regex("[。；;\\n]+")

    fun fromCard(id: Long, card: Card): LockedQ? {
        val own = card.points.flatMap { it.split(SPLIT) }.map { it.trim() }
            .filter { Words.substance(it) >= 4 }.distinct()
        val points = own.ifEmpty { Locks.pointsFromExplain(card.explain) }
        if (points.size !in 2..4) return null
        val prompt = buildString {
            if (card.sentences.isNotEmpty()) appendLine(card.sentences.joinToString("")).appendLine()
            appendLine(card.question.trim())
            if (card.options.isNotEmpty()) {
                appendLine()
                append("先别看选项：说说正确答案必须长什么样、为什么。说中了再亮选项。")
            }
        }.trim()
        val letter = if (card.answer in card.options.indices) "正确答案 ${'A' + card.answer}。" else ""
        return LockedQ(
            id = "card-$id",
            prompt = prompt,
            options = card.options,
            answer = card.answer,
            points = points.mapIndexed { i, c -> Point("P${i + 1}", c, card.skill.hint) },
            need = Locks.need(points.size),
            reference = letter + card.explain.trim(),
        )
    }
}
