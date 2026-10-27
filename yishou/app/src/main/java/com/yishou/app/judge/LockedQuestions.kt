package com.yishou.app.judge

import com.yishou.app.card.Card

object LockedQuestions {
    fun fromCard(id: Long, card: Card): LockedQ? {
        val points = card.points.flatMap { it.split(Regex("[。；;\\n]+")) }.map { it.trim() }.filter { it.isNotEmpty() }
        if (points.size !in 2..4) return null
        return LockedQ("card-$id", (card.sentences + card.question).joinToString("\n"), card.options,
            points.mapIndexed { index, criterion -> Point("P${index + 1}", criterion) },
            maxOf(1, points.size - 1), card.explain)
    }
}
