package com.yishou.app.llm

import com.yishou.app.card.BadCard
import com.yishou.app.card.Card
import com.yishou.app.card.Flaws
import com.yishou.app.card.Skill
import com.yishou.app.log.RunLog

/** 出题、另做一遍核对答案、把拍下的真题整理成题卡。 */
interface CardGen {
    suspend fun generate(skill: Skill, difficulty: Int, avoid: List<String>): LlmResult<Card>

    /** 不看答案独立做一遍，返回选项下标；用来核对出题时定的答案 */
    suspend fun solve(card: Card): LlmResult<Int>

    /** 把一段题目文字（照片转写）整理成题卡；答案是模型的推测，要用户对过官方答案才算核对过 */
    suspend fun structure(text: String): LlmResult<Card>
}

object CardPrompts {
    val SYSTEM = """
        你是行测（言语理解与表达、逻辑判断）的出题人，给一个练思维的人出单选题。
        题目要像真题：材料短（三到六句），条件清楚，只有一个正确答案，答案能在材料里逐句找到依据。
        不要考数学计算，不要考常识记忆。
        错误选项要像真的干扰项：每一个都属于下面这张错因表里的一种，并且错得明确、可以指认。
        错因表：${Flaws.ALL.joinToString("、")}。
        严格按要求的【标签】格式输出纯文本，不要 JSON，不要代码块，不要开场白。
    """.trimIndent()

    val FORMAT = """
        【材料】第一句。第二句。第三句。
        【题目】……
        A. ……
        B. ……
        C. ……
        D. ……
        【答案】B
        【依据】第 3 句
        【错因】A：错因表里的一项；C：……；D：……
        【解析】两三句：依据是哪一句，干扰项各错在哪
    """.trimIndent()

    fun difficultyText(d: Int) = when (d) {
        1 -> "难度 1：干扰项错得比较明显，答案句里有明显的提示词。"
        2 -> "难度 2：至少一个干扰项和正确项很接近，只差一个限定词或一层关系。"
        else -> "难度 3：干扰项都很接近，需要逐句回原文核对；正确项的说法要换一种表达，不能照抄原文。"
    }

    fun generate(skill: Skill, difficulty: Int, avoid: List<String>): String = buildString {
        appendLine("请出一道题。")
        appendLine("题型：${skill.name}（${skill.label}）。${skill.hint}")
        appendLine(difficultyText(difficulty))
        appendLine("材料写成一段，句子之间用句号分开；【依据】写依据句的序号（从 1 开始）；【错因】给每个错误选项写一个错因。")
        appendLine("话题换着来：社会、科技、自然、历史、生活、经济，都可以。")
        if (avoid.isNotEmpty()) {
            appendLine("最近出过这些题，换一个话题和问法：")
            avoid.take(8).forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("格式如下：")
        append(FORMAT)
    }

    fun solve(card: Card): String = buildString {
        appendLine("请独立做这道题，只根据材料判断，不要猜出题人的意图。")
        card.sentences.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
        appendLine("问题：${card.question}")
        card.options.forEachIndexed { i, o -> appendLine("${Card.letter(i)}. $o") }
        appendLine()
        append("只回答一行：【答案】A–D 中的一个字母")
    }

    fun structure(text: String): String = buildString {
        appendLine("下面是用户拍下的一道题（照片转写，可能有错字）。请把它整理成题卡：")
        appendLine("保留原来的材料、问题和四个选项，不要改写；你自己做一遍，把你的答案写在【答案】，依据句写在【依据】，")
        appendLine("给每个你认为错误的选项写错因（错因表里的一项）。")
        appendLine("如果不是四个选项的单选题，就尽量改写成四个选项的单选题，问题不变。")
        appendLine()
        appendLine(text)
        appendLine()
        appendLine("格式如下：")
        append(FORMAT)
    }
}

class LlmCardGen(private val chat: ChatClient) : CardGen {

    override suspend fun generate(skill: Skill, difficulty: Int, avoid: List<String>): LlmResult<Card> =
        request(CardPrompts.generate(skill, difficulty, avoid), "出题") { readCard(it, skill).copy(skill = skill, difficulty = difficulty) }

    override suspend fun solve(card: Card): LlmResult<Int> =
        request(CardPrompts.solve(card), "核对") { text ->
            val line = text.lines().firstOrNull { it.contains("答案") } ?: text
            Card.answerOf(line.substringAfter("答案")) ?: Card.answerOf(text.trim().take(4)) ?: throw BadCard("没认出答案字母")
        }

    override suspend fun structure(text: String): LlmResult<Card> =
        request(CardPrompts.structure(text), "整理题目") { readCard(it, null) }

    /**
     * 读一张题：先按【标签】纯文本在本机拆（和题库导入同一套），拆不出来再试 JSON。
     * 只有缺题目、缺选项、缺答案这种真没法做的才算失败。
     */
    private fun readCard(text: String, skill: Skill?): Card {
        com.yishou.app.bank.BankParser.parse(text).filterIsInstance<com.yishou.app.bank.BankItem.Choice>().firstOrNull()?.let { c ->
            val answer = c.answer ?: throw BadCard("没写答案")
            val card = com.yishou.app.bank.BankParser.toCard(c, answer)
            return if (skill != null) card.copy(skill = skill) else card
        }
        return try {
            Card.parse(CoachParser.extractJsonObject(text), skill)
        } catch (e: BadJsonException) {
            throw BadCard("没认出题目格式")
        }
    }

    private suspend fun <T> request(user: String, kind: String, parse: (String) -> T): LlmResult<T> {
        var last: LlmError? = null
        repeat(2) {
            when (val r = chat.complete(CardPrompts.SYSTEM, user, kind, json = false)) {
                is LlmResult.Err -> return r
                is LlmResult.Ok -> try {
                    return LlmResult.Ok(parse(r.value))
                } catch (e: BadCard) {
                    last = LlmError.BadFormat(e.message ?: "")
                } catch (e: org.json.JSONException) {
                    last = LlmError.BadFormat(e.message ?: "")
                }
            }
        }
        val error = last ?: LlmError.BadFormat("")
        RunLog.e(kind, "两次都没得到能用的题：${error.message}")
        return LlmResult.Err(error)
    }
}
