package com.yishou.app.judge

import com.yishou.app.card.Card
import com.yishou.app.think.Words

/** 入口题卡转锁题：要点只认题包里的【要点】，没有就不进入口（不从解析里猜） */
object LockedQuestions {
    private val SPLIT = Regex("[。；;\\n]+")

    fun fromCard(id: Long, card: Card): LockedQ? {
        val points = card.points.flatMap { it.split(SPLIT) }.map { it.trim() }
            .filter { Words.substance(it) >= 4 }.distinct()
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
