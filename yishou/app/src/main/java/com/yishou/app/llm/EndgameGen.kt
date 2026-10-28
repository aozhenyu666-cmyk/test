package com.yishou.app.llm

import com.yishou.app.endgame.Endgames

/** 把一段原文加工成残局。只加工，不现编：结果在本机核对过（残局必须是原文，破绽必须在残局里）才收。 */
interface EndgameGen {
    suspend fun make(source: String): LlmResult<List<Endgames.Draft>>
}

object EndgamePrompts {
    val SYSTEM = """
        你是残局编排员。用户给你一段真实的原文（文章、报道、题目材料）。你只加工这段原文，不编任何新内容。
        从原文里截出 1 到 3 个“残局”：
        - 残局是原文里连续的一段，40 到 120 字；中间可以删掉无关的句子，用“……”接上，但留下来的每个字都必须和原文一模一样；
        - 残局里藏着一个决定结论的“破绽”：没说出来的前提、偷换的概念、倒过来的因果、缺了的条件、被忽略的利益相关方会怎么反应；
        - 破绽必须是残局里原样出现的一段字，2 到 20 字；
        - 原文里找不出像样的破绽，就少截几个，宁缺毋滥。
        严格按下面的格式输出，每个残局之间单独一行 ---，不要输出格式以外的任何文字：
        【残局】……
        【破绽】……（从残局里原样复制）
        【准星】一句提示往哪个方向看，不点破，不超过 20 字
        【底牌】高手的一枪：这里为什么是破绽、接下来会怎样，一句话，不超过 40 字
        【铁律】把背后的规律写成“A + B = 必 C”，不超过 25 字
    """.trimIndent()

    fun user(source: String) = "原文：\n" + source.take(3000)

    val NAMES = mapOf("残局" to "text", "破绽" to "crux", "准星" to "aim", "底牌" to "shot", "铁律" to "law")

    /** 拆成一个个草稿（核对在 [Endgames.problem] 里做） */
    fun parse(text: String): List<Endgames.Draft> = text.replace("\r", "")
        .split(Regex("(?m)^\\s*-{3,}\\s*$"))
        .mapNotNull { block ->
            val o = Tagged.toJson(block, NAMES)
            val t = o.optString("text").trim()
            val c = o.optString("crux").trim().trim('“', '”', '「', '」', '"')
            if (t.isEmpty() || c.isEmpty()) null
            else Endgames.Draft(t, c, o.optString("aim").trim(), o.optString("shot").trim(), o.optString("law").trim())
        }
}

class LlmEndgameGen(private val chat: ChatClient) : EndgameGen {
    override suspend fun make(source: String): LlmResult<List<Endgames.Draft>> =
        when (val r = chat.complete(EndgamePrompts.SYSTEM, EndgamePrompts.user(source), "残局", json = false)) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> LlmResult.Ok(EndgamePrompts.parse(r.value).filter { Endgames.problem(source, it) == null })
        }
}
