package com.moling.core.live

import com.moling.core.mind.Tags

/*
 * 捉虫题（v9）：把他自己的一条论断 / 她的一个判断改错一处，切成几段，他点出错的那段。
 * 用来把“自动驾驶”的脑子拉回手动挡——要动一下前额叶才答得出。不碰“一手”，是墨灵自己的小题。
 */

data class Quiz(
    val parts: List<String>,
    /** 错的是第几段（从 0 起）。 */
    val wrong: Int,
    /** 原本正确的说法。 */
    val fix: String,
    val source: String,
)

object Quizzes {
    fun prompt(source: String) = buildString {
        appendLine("下面是一条他自己的论断（或你对他的判断）：")
        appendLine(source)
        appendLine()
        appendLine("把它改错一处（换掉一个关键的词或数字，让意思变得不对，但读起来顺），再切成 3–5 段。只输出：")
        appendLine("【句子】第一段｜第二段｜第三段（｜第四段）")
        appendLine("【错的】错的那段是第几段（从 1 数）")
        append("【正确】那一段原本应该是什么")
    }

    fun parse(reply: String, source: String): Quiz? {
        val t = Tags.parse(reply)
        val parts = t["句子"]?.split('｜', '|')?.map { it.trim() }?.filter { it.isNotBlank() } ?: return null
        if (parts.size !in 3..5) return null
        val wrong = t["错的"]?.filter { it.isDigit() }?.toIntOrNull()?.minus(1) ?: return null
        if (wrong !in parts.indices) return null
        return Quiz(parts, wrong, t["正确"]?.trim().orEmpty().take(60), source)
    }

    private val flips = listOf("不会" to "会", "不能" to "能", "不是" to "是", "没有" to "有", "会" to "不会", "能" to "不能", "是" to "不是", "有" to "没有", "先" to "后", "前" to "后", "多" to "少", "高" to "低")

    /** 模型不可用时自己出：翻一个数字或一个否定词。翻不了返回 null。 */
    fun local(source: String): Quiz? {
        val s = source.trim()
        if (s.length < 6) return null
        val num = Regex("""\d+""").find(s)
        val (broken, fixWord, at) = when {
            num != null -> Triple(s.replaceRange(num.range, (num.value.toInt() * 2 + 1).toString()), num.value, num.range.first)
            else -> flips.firstNotNullOfOrNull { (a, b) -> s.indexOf(a).takeIf { it >= 0 }?.let { i -> Triple(s.replaceRange(i, i + a.length, b), a, i) } } ?: return null
        }
        // 按标点切，切不出 3 段就按长度切 3 段
        var parts = broken.split(Regex("(?<=[，,；;。])")).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size !in 3..5) {
            val n = broken.length
            parts = listOf(broken.substring(0, n / 3), broken.substring(n / 3, 2 * n / 3), broken.substring(2 * n / 3))
        }
        var acc = 0
        val wrong = parts.indexOfFirst { p -> acc += p.length; acc > at }.coerceAtLeast(0)
        return Quiz(parts, wrong, fixWord, source)
    }
}
