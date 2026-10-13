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

    private enum class Tag { TYPE, MATERIAL, QUESTION, ANSWER, EVIDENCE, EXPLAIN, POINTS, LIMIT }

    private val TAGS = listOf(
        Tag.TYPE to listOf("题型", "类型", "题类"),
        Tag.MATERIAL to listOf("材料", "给定资料", "资料", "阅读材料", "文段"),
        Tag.QUESTION to listOf("题目", "问题", "题干", "作答要求"),
        Tag.POINTS to listOf("参考要点", "答案要点", "要点", "参考答案", "采分点"),
        Tag.ANSWER to listOf("正确答案", "答案"),
        Tag.EVIDENCE to listOf("依据"),
        Tag.EXPLAIN to listOf("解析", "解释", "说明"),
        Tag.LIMIT to listOf("字数"),
    )

    /** 行首标签：【答案】B、[答案] B、答案：B、答案:B */
    private val TAG_LINE = Regex("^\\s*(?:【\\s*([^】]{1,6})\\s*】|\\[\\s*([^\\]]{1,6})\\s*]|([\\u4e00-\\u9fa5]{2,6})\\s*[:：])\\s*(.*)$")
    private val OPTION = Regex("^\\s*(?:[（(]\\s*([A-Da-d])\\s*[)）]|([A-Da-d])\\s*[.、．:：)）])\\s*(.*)$")
    private val SEPARATOR = Regex("^\\s*(?:-{3,}|={3,}|—{2,}|\\*{3,})\\s*$")
    private val NUMBERED = Regex("^\\s*(?:第\\s*\\d+\\s*题|\\d{1,3}\\s*[.、．)）](?!\\d))\\s*(.*)$")
    private val POINT_PREFIX = Regex("^\\s*(?:\\d{1,2}\\s*[.、．)）]|[（(]\\s*\\d{1,2}\\s*[)）]|[①②③④⑤⑥⑦⑧⑨⑩]|[-•·*])\\s*")
    private val LIMIT_IN_QUESTION = Regex("不超过\\s*(\\d{2,4})\\s*字|(\\d{2,4})\\s*字(?:以内|左右)")

    private fun tagOf(name: String): Tag? = TAGS.firstOrNull { (_, names) -> names.any { name.trim() == it } }?.first

    /** 粗看一眼：是不是一份题包（至少一道带答案的选择题，或一道带要点的申论题） */
    fun looksLike(text: String): Boolean = parse(text).isNotEmpty()

    private class Draft {
        var type = ""
        val material = StringBuilder()
        val question = StringBuilder()
        val options = arrayOfNulls<String>(4)
        var answerRaw = ""
        var evidenceRaw = ""
        val explain = StringBuilder()
        val points = mutableListOf<String>()
        var limitRaw = ""
        var current: Tag? = null
        var lastOption = -1

        val hasQuestion get() = question.isNotBlank()
        val hasOptions get() = options.any { it != null }
        val empty get() = !hasQuestion && material.isBlank() && !hasOptions && points.isEmpty()
    }

    fun parse(text: String): List<BankItem> {
        val out = mutableListOf<BankItem>()
        var d = Draft()
        var sharedType = ""
        fun flush() {
            build(d, sharedType)?.let { out += it }
            d = Draft()
        }
        for (raw in text.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                // 空行结束一个选项或要点的续行，但不分题
                d.lastOption = -1
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
                }
                continue
            }
            val opt = OPTION.find(line)
            if (opt != null && d.current != Tag.POINTS && d.current != Tag.EXPLAIN) {
                val i = Card.index(opt.groupValues[1].ifEmpty { opt.groupValues[2] })!!
                // 选项又从 A 开始、而这道已经有四个选项：上一道没写分隔，直接分题
                if (i == 0 && d.options.all { it != null }) flush()
                d.options[i] = opt.groupValues[3].trim()
                d.lastOption = i
                d.current = null
                continue
            }
            val numbered = NUMBERED.find(line)
            val canSplit = d.current != Tag.POINTS && d.current != Tag.MATERIAL && (d.current != Tag.EXPLAIN || d.hasOptions)
            if (numbered != null && canSplit) {
                if (d.hasOptions || d.hasQuestion && d.answerRaw.isNotBlank()) flush()
                d.question.appendLine(numbered.groupValues[1])
                d.current = Tag.QUESTION
                continue
            }
            when {
                d.current == Tag.POINTS -> d.points += line.replace(POINT_PREFIX, "").trim()
                d.current == Tag.EXPLAIN -> d.explain.appendLine(line.trim())
                d.current == Tag.MATERIAL -> d.material.appendLine(line.trim())
                d.lastOption >= 0 -> d.options[d.lastOption] = d.options[d.lastOption] + line.trim()
                d.current == Tag.ANSWER -> d.answerRaw += line
                else -> {
                    d.question.appendLine(line.trim())
                    d.current = Tag.QUESTION
                }
            }
        }
        flush()
        return out
    }

    private fun build(d: Draft, sharedType: String): BankItem? {
        val type = d.type.ifBlank { sharedType }
        val question = d.question.toString().trim()
        val material = d.material.toString().trim()
        val explain = d.explain.toString().trim()
        if (question.isEmpty()) return null
        if (d.options.all { !it.isNullOrBlank() }) {
            // 有人把选择题的答案写成“参考答案：B”
            val answer = Card.answerOf(d.answerRaw) ?: d.points.firstOrNull()?.takeIf { it.length <= 6 }?.let { Card.answerOf(it) }
            val ev = Regex("\\d+").findAll(d.evidenceRaw).map { it.value.toInt() }.toSet()
            return BankItem.Choice(type, material, question, d.options.map { it!!.trim() }, answer, ev, explain)
        }
        if (d.hasOptions) return null
        val points = d.points.filter { it.isNotBlank() }
        if (points.isEmpty()) return null
        val limit = d.limitRaw.filter { it.isDigit() }.toIntOrNull()
            ?: LIMIT_IN_QUESTION.find(question)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }
        return BankItem.Essay(type, material, question, points, limit, explain)
    }

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
            flaws = emptyMap(),
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
