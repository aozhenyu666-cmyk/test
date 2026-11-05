package com.yishou.app.core

/**
 * 问题去重：同一类问题在短时间内反复问，会把伴读变成噪音。
 *
 * 用字符二元组的 Jaccard 相似度，不引入向量、不联网。
 */
object QuestionLedger {
    /** 相似到这个程度就算重复。 */
    const val SIMILAR = 0.72

    /** 最多记多少条；再多也没有参考价值。 */
    const val MAX_KEEP = 20

    fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }

    fun bigrams(text: String): Set<String> {
        val s = normalize(text)
        if (s.length < 2) return if (s.isEmpty()) emptySet() else setOf(s)
        return (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()
    }

    fun similarity(a: String, b: String): Double {
        val x = bigrams(a)
        val y = bigrams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val inter = x.intersect(y).size
        val union = x.union(y).size
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    fun isDuplicate(question: String, recent: List<String>): Boolean =
        recent.any { similarity(question, it) >= SIMILAR }

    /** 新的放最前，去重后只留 [max]。 */
    fun remember(recent: List<String>, question: String, max: Int = MAX_KEEP): List<String> {
        val q = question.trim()
        if (q.isEmpty()) return recent.take(max)
        val merged = listOf(q) + recent.filter { normalize(it) != normalize(q) }
        return merged.distinct().take(max)
    }
}
