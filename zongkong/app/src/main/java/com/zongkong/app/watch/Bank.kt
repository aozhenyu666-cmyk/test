package com.zongkong.app.watch

import kotlinx.serialization.Serializable

/**
 * 题库里的一道题。低屏模式弹回来时随机抽一道，让人写一句话再放行。
 *
 * [stem] 是题干（也可以是“现在打开它要做什么？回来第一步是什么？”这类开放题），
 * [answer] 是参考答案，只用来给自己对一眼，判分不靠它。
 */
@Serializable
data class BankQuestion(
    val id: String,
    val stem: String,
    val answer: String = "",
)

/** 整个题库，存成一份 JSON。 */
@Serializable
data class Bank(
    val questions: List<BankQuestion> = emptyList(),
)

/**
 * 题库文本的解析（纯逻辑，不碰 Android，方便单元测试）。
 *
 * 规矩很简单：一行一题。
 * - `题干||答案`：两个竖线隔开，答案可省略；全角 `｜` 也认（写一个也算分隔）。
 * - 只有题干也行，答案留空。
 * - 空行、代码块围栏、分隔线（--- / ***）、Markdown 标题行（# 开头）跳过；
 *   行首的序号、列表点（1. / - / •）会去掉。
 * - 同一份文本里重复的题干只留第一条；太短的行（少于 4 个字，通常是标题或页码）不要。
 */
object BankParse {

    private val LIST_PREFIX = Regex("^(?:[-*•·]\\s+|\\d{1,3}\\s*[.、．)）]\\s*|[（(]\\s*\\d{1,3}\\s*[)）]\\s+)")
    private const val MAX_TEXT = 1000

    /** 去空白、去重、比对都用它：只关心“是不是同一道题”，不关心排版。 */
    fun normalize(stem: String): String = stem.replace(Regex("\\s+"), "")

    /** 由题干生成一个稳定的 id；同一道题每次算出来一样，重复导入不会换 id。 */
    fun idOf(stem: String): String {
        val n = normalize(stem)
        return "q" + n.hashCode().toUInt().toString(16) + "_" + n.length.toString(16)
    }

    /** 解析一行；空行、分隔线这类返回 null。 */
    fun parseLine(line: String): BankQuestion? {
        val text = line.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("```") || text == "---" || text == "***" || text.startsWith("<!--")) return null
        // Markdown 标题行（# 开头）是结构不是题，跳过
        if (text.startsWith("#")) return null
        // 全角竖线先换成半角，再找分隔符
        val flat = text.replace('｜', '|')
        val bar = flat.indexOf('|')
        val (stemPart, answerPart) = when {
            bar < 0 -> flat to ""
            // 两个竖线（或更多）都算分隔
            bar + 1 < flat.length && flat[bar + 1] == '|' -> flat.substring(0, bar) to flat.substring(bar + 2)
            // 只有一个竖线：也当分隔（用户写“题干｜答案”）
            flat.count { it == '|' } == 1 -> flat.substring(0, bar) to flat.substring(bar + 1)
            // 竖线太多又不成对：分不清，整行当题干
            else -> flat to ""
        }
        val stem = stemPart.trim().replace(LIST_PREFIX, "").trim()
        if (normalize(stem).length < 4) return null
        return BankQuestion(
            id = idOf(stem),
            stem = stem.take(MAX_TEXT),
            answer = answerPart.trim().take(MAX_TEXT),
        )
    }

    /** 整段文本 → 题目列表（本段内去重，保留顺序）。 */
    fun parse(text: String): List<BankQuestion> {
        val out = mutableListOf<BankQuestion>()
        val seen = mutableSetOf<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val q = parseLine(raw) ?: continue
            if (seen.add(normalize(q.stem))) out += q
        }
        return out
    }
}
