package com.yishou.app.bank

import com.yishou.app.card.Card
import com.yishou.app.card.Sentences
import com.yishou.app.card.Skill

/** 题库里的一道题：选择题（有标准答案）或申论题（有参考要点） */
sealed interface BankItem {
    val type: String

    data class Choice(
        override val type: String,
        val material: String,
        val question: String,
        val options: List<String>,
        /** 0–3；题包里没写答案时为 null（做完要你点官方答案） */
        val answer: Int?,
        val evidence: Set<Int>,
        val explain: String,
        /** 原题号（“3.”“第 3 题”）；文末答案表按它对 */
        val number: Int? = null,
        /** 干扰项的错因（【错因】A：范围扩大；C：……），没有就空 */
        val flaws: Map<Int, String> = emptyMap(),
    ) : BankItem

    data class Essay(
        override val type: String,
        val material: String,
        val question: String,
        val points: List<String>,
        /** 字数上限（题目里的“不超过 N 字”） */
        val limit: Int?,
        val explain: String,
    ) : BankItem
}

/**
 * 题包解析：把你从 GPT、Gemini、粉笔或别处复制来的一批题，在本机拆成一道一道。
 * 不经过模型，不要求 JSON，所以不会“格式没对上出不了题”。
 *
 * 认这几种写法（可以混用）：
 * - 标签：【题型】【材料】【题目】【答案】【依据】【解析】【要点】，方括号、冒号写法也认（“答案：B”“[解析]”）；
 * - 选项：A. / A、/ A．/ (A) / A)；
 * - 分题：一行 ---，或者“第 3 题”“3.”开头的新题；
 * - 申论：有【要点】（1. 2. 3. 或 ①②③ 分条）而没有选项的，算申论题。
 */
object BankParser {

    private enum class Tag { TYPE, MATERIAL, QUESTION, ANSWER, EVIDENCE, EXPLAIN, POINTS, LIMIT, FLAWS }

    private val TAGS = listOf(
        Tag.TYPE to listOf("题型", "类型", "题类"),
        Tag.MATERIAL to listOf("材料", "给定资料", "资料", "阅读材料", "文段"),
        Tag.QUESTION to listOf("题目", "问题", "题干", "作答要求"),
        Tag.POINTS to listOf("参考要点", "答案要点", "要点", "参考答案", "采分点"),
        Tag.ANSWER to listOf("正确答案", "答案"),
        Tag.EVIDENCE to listOf("依据"),
        Tag.EXPLAIN to listOf("解析", "解释", "说明", "精解", "详解"),
        Tag.LIMIT to listOf("字数"),
        Tag.FLAWS to listOf("错因", "干扰项"),
    )

    /** 行首标签：【答案】B、[答案] B、答案：B、答案:B */
    private val TAG_LINE = Regex("^\\s*(?:【\\s*([^】]{1,6})\\s*】|\\[\\s*([^\\]]{1,6})\\s*]|([\\u4e00-\\u9fa5]{2,6})\\s*[:：])\\s*(.*)$")
    private val OPTION = Regex("^\\s*(?:[（(]\\s*([A-Da-d])\\s*[)）]|([A-Da-d])\\s*[.、．:：)）])\\s*(.*)$")
    private val SEPARATOR = Regex("^\\s*(?:-{3,}|={3,}|—{2,}|\\*{3,})\\s*$")
    private val NUMBERED = Regex("^\\s*(?:第\\s*(\\d+)\\s*题[.、．:：]?|(\\d{1,3})\\s*[.、．)）](?!\\d))\\s*(.*)$")
    private val POINT_PREFIX = Regex("^\\s*(?:\\d{1,2}\\s*[.、．)）]|[（(]\\s*\\d{1,2}\\s*[)）]|[①②③④⑤⑥⑦⑧⑨⑩]|[-•·*])\\s*")
    private val LIMIT_IN_QUESTION = Regex("不超过\\s*(\\d{2,4})\\s*字|(\\d{2,4})\\s*字(?:以内|左右)")
    /** 文末答案表的标题：参考答案、答案及解析…… */
    private val KEY_HEADER = Regex("^\\s*[【\\[]?\\s*(?:参考答案及解析|参考答案与解析|参考答案|答案及解析|答案与解析|答案解析|答案速查)\\s*[】\\]]?\\s*[:：]?\\s*$")
    /** 1-5 BCADA / 1～5：BCADA */
    private val KEY_RANGE = Regex("(\\d{1,3})\\s*[-—～~至到]\\s*(\\d{1,3})\\s*[:：.、．]?\\s*([A-Da-d]{2,})")
    /** 1.B / 1．【答案】B / 1、答案：B / 1 B */
    private val KEY_ONE = Regex("^\\s*(\\d{1,3})\\s*[.、．:：)）]?\\s*(?:【\\s*(?:正确)?答案\\s*】|(?:正确)?答案\\s*[:：]?)?\\s*([A-Da-d])(?![A-Za-z])\\s*[。.、，,]?\\s*(.*)$")
    private val KEY_INLINE = Regex("(\\d{1,3})\\s*[.、．:：]?\\s*([A-Da-d])(?![A-Za-z])")

    private fun tagOf(name: String): Tag? = TAGS.firstOrNull { (_, names) -> names.any { name.trim() == it } }?.first

    private val OPTION_A = Regex("(?m)^\\s*(?:[（(]\\s*[AＡ]\\s*[)）]|[AＡ]\\s*[.、．:：)）])")

    /** 文件里看着像几道题：数“A.”开头的行和【要点】（用来判断本机是不是漏拆了很多） */
    fun estimate(text: String): Int = OPTION_A.findAll(text).count() + Regex("【\\s*(?:参考)?要点\\s*】").findAll(text).count()

    /** 粗看一眼：是不是一份题包（至少一道带答案的选择题，或一道带要点的申论题） */
    fun looksLike(text: String): Boolean = parse(text).isNotEmpty()

    private class Draft {
        var type = ""
        var number: Int? = null
        val material = StringBuilder()
        val question = StringBuilder()
        val options = arrayOfNulls<String>(4)
        var answerRaw = ""
        var evidenceRaw = ""
        var flawsRaw = ""
        val explain = StringBuilder()
        val points = mutableListOf<String>()
        var limitRaw = ""
        var current: Tag? = null
        var lastOption = -1

        val hasQuestion get() = question.isNotBlank()
        val hasOptions get() = options.any { it != null }
        val empty get() = !hasQuestion && material.isBlank() && !hasOptions && points.isEmpty()
    }

    /** 文末答案表里的一条 */
    private class KeyEntry(var answer: Int?, val explain: StringBuilder = StringBuilder())

    fun parse(text: String): List<BankItem> {
        val out = mutableListOf<BankItem>()
        var d = Draft()
        var sharedType = ""
        // 没有选项、也没有要点的长段文字：通常是“根据以下资料，回答 1–5 题”的共用材料
        var shared = ""
        val keys = linkedMapOf<Int, KeyEntry>()
        var inKey = false
        var keyCurrent: KeyEntry? = null

        fun flush() {
            val built = build(d, sharedType, shared)
            if (built != null) {
                out += built
            } else if (!d.hasOptions && d.points.isEmpty()) {
                val orphan = (d.material.toString() + "\n" + d.question.toString()).trim()
                if (orphan.length >= 60) shared = orphan
            }
            d = Draft()
        }

        for (raw in text.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                d.lastOption = -1
                continue
            }
            // 文末答案表只对有题号的选择题有意义；申论的【参考答案】照常当要点读
            val numberedChoices = (d.hasOptions && d.number != null) || (d.empty && out.any { it is BankItem.Choice && it.number != null })
            if (!inKey && KEY_HEADER.matches(line) && numberedChoices) {
                flush()
                inKey = true
                continue
            }
            if (inKey) {
                val ranges = KEY_RANGE.findAll(line).toList()
                if (ranges.isNotEmpty()) {
                    ranges.forEach { m ->
                        val from = m.groupValues[1].toInt()
                        m.groupValues[3].forEachIndexed { i, c -> keys[from + i] = KeyEntry(Card.index(c.toString())) }
                    }
                    keyCurrent = null
                    continue
                }
                val inline = KEY_INLINE.findAll(line).toList()
                if (inline.size >= 2 && inline.sumOf { it.value.length } >= line.replace(" ", "").length * 0.6) {
                    inline.forEach { m -> keys[m.groupValues[1].toInt()] = KeyEntry(Card.index(m.groupValues[2])) }
                    keyCurrent = null
                    continue
                }
                val one = KEY_ONE.find(line)
                if (one != null) {
                    val e = KeyEntry(Card.index(one.groupValues[2]))
                    one.groupValues[3].trim().takeIf { it.isNotEmpty() }?.let { e.explain.appendLine(it.removePrefix("【解析】").removePrefix("解析：").trim()) }
                    keys[one.groupValues[1].toInt()] = e
                    keyCurrent = e
                    continue
                }
                keyCurrent?.explain?.appendLine(line.trim().removePrefix("【解析】").removePrefix("解析：").trim())
                continue
            }
            if (SEPARATOR.matches(line)) {
                flush()
                continue
            }
            val tagMatch = TAG_LINE.find(line)
            val tagName = tagMatch?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }) }
            val tag = tagName?.let(::tagOf)
            if (tag != null) {
                val rest = tagMatch.groupValues[4].trim()
                // 新的材料 / 题目 / 题型，而当前这道已经有题目了：分题
                if ((tag == Tag.MATERIAL || tag == Tag.TYPE || (tag == Tag.QUESTION && d.hasOptions)) && d.hasQuestion) flush()
                d.current = tag
                d.lastOption = -1
                when (tag) {
                    Tag.TYPE -> {
                        d.type = rest
                        if (d.empty) sharedType = rest
                    }
                    Tag.MATERIAL -> d.material.appendLine(rest)
                    Tag.QUESTION -> d.question.appendLine(rest)
                    Tag.ANSWER -> d.answerRaw += rest
                    Tag.EVIDENCE -> d.evidenceRaw += rest
                    Tag.EXPLAIN -> d.explain.appendLine(rest)
                    Tag.POINTS -> if (rest.isNotBlank()) d.points += rest.replace(POINT_PREFIX, "").trim()
                    Tag.LIMIT -> d.limitRaw = rest
                    Tag.FLAWS -> d.flawsRaw += rest + "；"
                }
                continue
            }
            val opt = OPTION.find(line)
            if (opt != null && d.current != Tag.POINTS && d.current != Tag.EXPLAIN && d.current != Tag.FLAWS) {
                val i = Card.index(opt.groupValues[1].ifEmpty { opt.groupValues[2] })!!
                // 选项又从 A 开始、而这道已经有四个选项：上一道没写分隔，直接分题
                if (i == 0 && d.options.all { it != null }) flush()
                d.options[i] = opt.groupValues[3].trim()
                d.lastOption = i
                d.current = null
                // 一行里挤了几个选项：A. 甲 B. 乙 C. 丙 D. 丁
                splitInline(d, i)
                continue
            }
            val numbered = NUMBERED.find(line)
            val canSplit = d.current != Tag.POINTS && d.current != Tag.MATERIAL && (d.current != Tag.EXPLAIN || d.hasOptions)
            if (numbered != null && canSplit) {
                if (d.hasOptions || d.hasQuestion && d.answerRaw.isNotBlank()) flush()
                if (d.hasQuestion && !d.hasOptions) flush()
                d.number = (numbered.groupValues[1].ifEmpty { numbered.groupValues[2] }).toIntOrNull()
                d.question.appendLine(numbered.groupValues[3])
                d.current = Tag.QUESTION
                continue
            }
            when {
                d.current == Tag.POINTS -> d.points += line.replace(POINT_PREFIX, "").trim()
                d.current == Tag.EXPLAIN -> d.explain.appendLine(line.trim())
                d.current == Tag.MATERIAL -> d.material.appendLine(line.trim())
                d.current == Tag.FLAWS -> d.flawsRaw += line.trim() + "；"
                d.lastOption >= 0 -> d.options[d.lastOption] = d.options[d.lastOption] + line.trim()
                d.current == Tag.ANSWER -> d.answerRaw += line
                else -> {
                    d.question.appendLine(line.trim())
                    d.current = Tag.QUESTION
                }
            }
        }
        flush()
        if (keys.isEmpty()) return out
        // 文末答案表：按题号补上答案和解析
        return out.map { item ->
            if (item !is BankItem.Choice || item.number == null) return@map item
            val k = keys[item.number] ?: return@map item
            item.copy(
                answer = item.answer ?: k.answer,
                explain = item.explain.ifBlank { k.explain.toString().trim() },
            )
        }
    }

    private val INLINE_OPTION = Regex("\\s+([B-Db-d])\\s*[.、．:：)）]\\s*")

    private fun splitInline(d: Draft, start: Int) {
        val text = d.options[start] ?: return
        val parts = INLINE_OPTION.split(text)
        val letters = INLINE_OPTION.findAll(text).map { Card.index(it.groupValues[1])!! }.toList()
        if (letters.isEmpty() || letters.any { it <= start }) return
        d.options[start] = parts[0].trim()
        letters.forEachIndexed { i, idx -> d.options[idx] = parts.getOrNull(i + 1)?.trim().orEmpty() }
        d.lastOption = letters.last()
    }

    private fun build(d: Draft, sharedType: String, shared: String): BankItem? {
        val type = d.type.ifBlank { sharedType }
        val question = d.question.toString().trim()
        val material = d.material.toString().trim()
        val explain = d.explain.toString().trim()
        if (question.isEmpty()) return null
        if (d.options.all { !it.isNullOrBlank() }) {
            // 有人把选择题的答案写成“参考答案：B”
            val answer = Card.answerOf(d.answerRaw) ?: d.points.firstOrNull()?.takeIf { it.length <= 6 }?.let { Card.answerOf(it) }
            val ev = Regex("\\d+").findAll(d.evidenceRaw).map { it.value.toInt() }.toSet()
            return BankItem.Choice(
                type, material.ifEmpty { shared }, question, d.options.map { it!!.trim() }, answer, ev, explain,
                number = d.number, flaws = flawsOf(d.flawsRaw),
            )
        }
        if (d.hasOptions) return null
        val points = d.points.filter { it.isNotBlank() }
        if (points.isEmpty()) return null
        val limit = d.limitRaw.filter { it.isDigit() }.toIntOrNull()
            ?: LIMIT_IN_QUESTION.find(question)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }
        return BankItem.Essay(type, material.ifEmpty { shared }, question, points, limit, explain)
    }

    private val FLAW_ITEM = Regex("([A-Da-d])\\s*[:：.、]\\s*([^；;，,\\n]+)")

    /** 【错因】A：范围扩大；C：无中生有 → {0: 范围扩大, 2: 无中生有}（不在错因表里的记成“答非所问”） */
    fun flawsOf(raw: String): Map<Int, String> = FLAW_ITEM.findAll(raw).mapNotNull { m ->
        val i = Card.index(m.groupValues[1]) ?: return@mapNotNull null
        val f = m.groupValues[2].trim()
        i to (com.yishou.app.card.Flaws.ALL.firstOrNull { it == f } ?: com.yishou.app.card.Flaws.ALL.firstOrNull { f.contains(it) || it.contains(f) } ?: "答非所问")
    }.toMap()

    /** 题型落到哪个小技能上（掌握度按它算） */
    fun skillOf(type: String): Skill = when {
        listOf("主旨", "意图", "概括", "标题").any { it in type } -> Skill.MAIN
        listOf("细节", "理解", "相符").any { it in type } -> Skill.DETAIL
        listOf("加强", "削弱", "前提", "假设", "论证").any { it in type } -> Skill.GAP
        listOf("翻译", "推出", "条件", "真假").any { it in type } -> Skill.COND
        listOf("排序", "分析推理", "数量", "资料", "计算").any { it in type } -> Skill.CHAIN
        listOf("类比").any { it in type } -> Skill.TWIN
        listOf("逻辑", "判断", "定义").any { it in type } -> Skill.COND
        listOf("言语", "语句", "填空").any { it in type } -> Skill.SPLIT
        else -> Skill.DETAIL
    }

    /** 选择题落成题卡。材料按句拆开，依据句号超出范围的丢掉。 */
    fun toCard(c: BankItem.Choice, answer: Int): Card {
        val sentences = Sentences.split(c.material).take(12)
        return Card(
            skill = skillOf(c.type + c.question),
            difficulty = 2,
            sentences = sentences,
            question = c.question,
            options = c.options,
            answer = answer,
            evidence = c.evidence.filter { it in 1..sentences.size }.toSet(),
            flaws = c.flaws.filterKeys { it != answer }.takeIf { it.size == 3 } ?: emptyMap(),
            explain = c.explain,
            type = c.type,
        )
    }
}

/** 去 GPT / Gemini 出题时复制过去的那段话。按这个格式回来，一手就能在本机直接拆。 */
object BankPrompt {

    val TYPES = listOf(
        "言语理解·主旨概括", "言语理解·细节判断", "言语理解·语句表达",
        "判断推理·逻辑判断", "判断推理·定义判断", "判断推理·类比推理",
        "数量关系", "资料分析",
        "申论·归纳概括", "申论·提出对策",
    )

    val LEVELS = listOf("基础", "真题难度", "偏难")

    fun isEssay(type: String) = type.startsWith("申论")

    fun build(type: String, count: Int, level: String): String = if (isEssay(type)) essay(type, count, level) else choice(type, count, level)

    private fun choice(type: String, count: Int, level: String) = """
        请出 $count 道公务员考试行测「$type」单选题，难度：$level（参照近年国考、省考真题）。
        要求：每道题只有一个正确答案；干扰项要像真题那样有迷惑性；材料和选项都写完整。
        严格按下面的格式输出，每道题之间单独一行 ---，不要输出格式以外的任何文字，不要用代码块：

        【题型】$type
        【材料】（有材料就写，没有就省略这一行）
        【题目】……
        A. ……
        B. ……
        C. ……
        D. ……
        【答案】B
        【依据】第 2 句（材料按句号分句，写出能找到答案依据的句子序号；没有材料就省略这一行）
        【解析】……
    """.trimIndent()

    private fun essay(type: String, count: Int, level: String) = """
        请出 $count 道公务员考试「$type」题，难度：$level（参照近年国考、省考申论）。
        每道题给一段 500–900 字的给定资料，题目写明字数要求，并给出按采分点分条的参考要点。
        严格按下面的格式输出，每道题之间单独一行 ---，不要输出格式以外的任何文字，不要用代码块：

        【题型】$type
        【材料】……
        【题目】根据给定资料，……（不超过 150 字）
        【要点】
        1. ……
        2. ……
        3. ……
        【解析】……（怎么从材料里找到这些要点）
    """.trimIndent()

    /** 设置里的出题链接：每行“名字 网址” */
    fun links(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
        val t = line.trim()
        val i = t.indexOf("http")
        if (i < 0) return@mapNotNull null
        val url = t.substring(i).trim().substringBefore(' ')
        val name = t.substring(0, i).trim().ifEmpty { url.removePrefix("https://").removePrefix("http://").substringBefore('/') }
        name to url
    }

    const val DEFAULT_LINKS = "ChatGPT https://chatgpt.com/\nGemini https://gemini.google.com/app\n粉笔 https://www.fenbi.com/"
}
