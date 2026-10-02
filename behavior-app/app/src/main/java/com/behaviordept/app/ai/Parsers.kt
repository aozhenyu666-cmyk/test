package com.behaviordept.app.ai

import com.behaviordept.app.data.Rating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** 【小标题】+ 若干条。红笔批改块按它渲染。 */
data class Section(val title: String, val lines: List<String>)

data class GradeItem(val index: Int, val mark: String, val comment: String)

data class Grade(val items: List<GradeItem>, val suggestion: String, val reason: String)

class FormatException(message: String) : Exception(message)

/**
 * AI 输出的固定格式解析（PRD M7）。格式不对就抛 FormatException，
 * 由调用方转成“可重试”的提示，绝不静默吞掉。
 */
object Parsers {
    private val headerRegex = Regex("【([^】\\n]{1,20})】")

    val CRITIQUE_TITLES = listOf("讲对了", "讲错了", "漏掉的关键点", "预习问题答到了吗", "下一步")
    val TRANSFER_TITLES = listOf("成立的地方", "不成立的地方", "再远一点")

    /** 按【小标题】切段。标题前的文字丢弃；每段按行拆成条目，去掉“- ”“• ”等前缀。 */
    fun sections(text: String): List<Section> {
        val matches = headerRegex.findAll(text).toList()
        return matches.mapIndexed { i, m ->
            val bodyEnd = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val body = text.substring(m.range.last + 1, bodyEnd)
            Section(normalize(m.groupValues[1]), splitLines(body))
        }
    }

    private fun normalize(title: String): String = title.replace(Regex("\\s+"), "")

    private fun splitLines(body: String): List<String> =
        body.lines()
            .map { it.trim().removePrefix("-").removePrefix("•").removePrefix("*").removePrefix("·").trim() }
            .filter { it.isNotEmpty() }

    /** 要求固定的几个小标题全部出现，按规定顺序返回。 */
    private fun requireTitles(text: String, titles: List<String>): List<Section> {
        val found = sections(text).associateBy { it.title }
        val missing = titles.filter { it !in found }
        if (missing.isNotEmpty()) throw FormatException("缺少【${missing.joinToString("】【")}】")
        return titles.map { t -> found.getValue(t).let { s -> s.copy(lines = s.lines.ifEmpty { listOf("无") }) } }
    }

    fun critique(text: String): List<Section> = requireTitles(text, CRITIQUE_TITLES)

    fun transfer(text: String): List<Section> = requireTitles(text, TRANSFER_TITLES)

    /** 出题：只接受 JSON 字符串数组，取前 3 道。 */
    fun questions(text: String): List<String> {
        val cleaned = text.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) throw FormatException("没有找到 JSON 数组")
        val array = try {
            Json.parseToJsonElement(cleaned.substring(start, end + 1)) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: throw FormatException("JSON 数组无法解析")
        val items = array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            .filter { it.isNotEmpty() }
        if (items.size < 3) throw FormatException("题目少于 3 道")
        return items.take(3)
    }

    private val gradeHeader = Regex("【\\s*第\\s*(\\d+)\\s*题\\s*】")
    private val suggestionHeader = Regex("【\\s*建议\\s*】")

    /** 自测批改：每题【第 N 题】✓/△/✗ + 一句话，最后【建议】记得/模糊/忘了 + 理由。 */
    fun grade(text: String, questionCount: Int): Grade {
        val sugMatch = suggestionHeader.find(text) ?: throw FormatException("缺少【建议】")
        val gradePart = text.substring(0, sugMatch.range.first)
        val headers = gradeHeader.findAll(gradePart).toList()
        val items = headers.mapIndexed { i, m ->
            val end = if (i + 1 < headers.size) headers[i + 1].range.first else gradePart.length
            val body = gradePart.substring(m.range.last + 1, end).trim()
            val mark = markOf(body) ?: throw FormatException("第 ${m.groupValues[1]} 题没有 ✓ / △ / ✗")
            val comment = body.drop(1).trim().removePrefix("：").removePrefix(":").trim()
            GradeItem(m.groupValues[1].toInt(), mark, comment.lines().joinToString(" ") { it.trim() }.trim())
        }.sortedBy { it.index }
        val indexes = items.map { it.index }.toSet()
        val missing = (1..questionCount).filter { it !in indexes }
        if (missing.isNotEmpty()) throw FormatException("缺少第 ${missing.joinToString("、")} 题的批改")

        val sugBody = text.substring(sugMatch.range.last + 1).trim()
        val suggestion = when {
            sugBody.startsWith("记得") -> Rating.REMEMBER
            sugBody.startsWith("模糊") -> Rating.FUZZY
            sugBody.startsWith("忘了") -> Rating.FORGOT
            else -> throw FormatException("【建议】不是 记得 / 模糊 / 忘了")
        }
        val reason = sugBody.drop(2).trim().removePrefix("：").removePrefix(":").removePrefix("，").trim()
            .lines().joinToString(" ") { it.trim() }.trim()
        return Grade(items.filter { it.index in 1..questionCount }, suggestion, reason)
    }

    private fun markOf(body: String): String? = when (body.firstOrNull()) {
        '✓', '✔', '√' -> "✓"
        '△', '▲' -> "△"
        '✗', '✘', '×', 'X', 'x' -> "✗"
        else -> null
    }

    /** 把批改结果重新写成规范文本，存进数据库，详情页按 sections() 渲染。 */
    fun gradeToText(grade: Grade): String = buildString {
        grade.items.forEach { appendLine("【第 ${it.index} 题】"); appendLine("- ${it.mark} ${it.comment}") }
        appendLine("【建议】")
        append("- ${Rating.label(grade.suggestion)}")
        if (grade.reason.isNotBlank()) append("：${grade.reason}")
    }

    fun sectionsToText(sections: List<Section>): String =
        sections.joinToString("\n") { s -> "【${s.title}】\n" + s.lines.joinToString("\n") { "- $it" } }
}
