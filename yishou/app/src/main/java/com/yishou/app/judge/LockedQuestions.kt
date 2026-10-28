package com.yishou.app.judge

import com.yishou.app.card.Card
import com.yishou.app.think.Words

/**
 * 入口题卡转锁题。要点优先用题包里的【要点】；旧题没有【要点】时从【解析】里拆。
 * 拆解析时去掉两种句子：提到选项字母的（答题时选项还没亮，没法对着说），
 * 和基本是在复述材料的（照抄材料就能命中，等于没锁）。拆不出 2 句就不进入口。
 */
object LockedQuestions {
    private val SPLIT = Regex("[。；;！!？?\\n]+")
    private val MENTIONS_OPTION = Regex("[A-DＡ-Ｄ]\\s*[项選选]|选\\s*[A-DＡ-Ｄ]|[A-DＡ-Ｄ]\\s*[、,，.．]|故\\s*(本题)?\\s*(答案|正确)")

    private fun split(lines: List<String>) = lines.flatMap { it.split(SPLIT) }.map { it.trim() }

    fun fromCard(id: Long, card: Card): LockedQ? {
        val material = (card.sentences + card.question).joinToString("")
        val own = split(card.points).filter { Words.substance(it) >= 4 }.distinct()
        val points = if (own.size >= 2) own.take(4) else split(listOf(card.explain))
            .filter { Words.substance(it) >= 6 && !MENTIONS_OPTION.containsMatchIn(it) && Guard.novel(it, listOf(material)) >= 4 }
            .distinct().take(4)
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
