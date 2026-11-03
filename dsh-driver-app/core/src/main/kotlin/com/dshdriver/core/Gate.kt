package com.dshdriver.core

/**
 * 拦截门的一道题：打开娱乐 App 被 Curbox 拦下后，回答它才放行。
 *
 * 出题优先级（都不需要模型；配对了大肥鱼时由它出题，这里是兜底和判卷）：
 *   1. 议程里没解决的断点 → 追问缺的那个依据
 *   2. 上一次锁定的结论 → 追问它的依据
 *   3. 你写的主线目标 → 下一步是什么、为什么
 *   4. 都没有 → 说一个今天学到的、讲得出依据的点
 *
 * 判卷只认"有依据"：说出因果/引原文/具体规律，或命中这题要的那个词，或至少 15 个字的实在话。
 * 点一下、"嗯"、"不知道"都不放行。
 */
object Gate {
    data class Question(
        val text: String,
        val anchor: String?,
        val missing: String?,
        val agendaId: String?,
        val source: String, // agenda | conclusion | goal | default | llm
    )

    data class Verdict(val pass: Boolean, val why: String, val features: Features)

    const val MIN_CHARS = 15

    fun pick(agenda: Agenda?, lastConclusion: String?, lastTask: String?, profile: Profile, seed: Int = 0): Question {
        agenda?.open()?.maxByOrNull { it.lastSeenAt }?.let { a ->
            val ask = a.missing?.let { "「$it」的依据是什么？" } ?: "现在能把它讲清楚吗？说出依据。"
            return Question("上次停在【${a.anchor}】没解决。$ask", a.anchor, a.missing, a.id, "agenda")
        }
        if (!lastConclusion.isNullOrBlank()) {
            val where = lastTask?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
            return Question("上次你的结论是「${Text.clip(lastConclusion, 40)}」$where。它的依据是什么？", lastTask, null, null, "conclusion")
        }
        profile.goals.takeIf { it.isNotEmpty() }?.let { goals ->
            val g = goals[Math.floorMod(seed, goals.size)]
            return Question("离「${Text.clip(g, 30)}」最近的下一步是什么？为什么是它？", g, null, null, "goal")
        }
        return Question("说一个你今天学到、能讲出依据的点。", null, null, null, "default")
    }

    fun judge(answer: String?, q: Question): Verdict {
        val f = Text.features(answer, listOfNotNull(q.missing))
        return when {
            f.giveUp -> Verdict(false, "放弃不算。想一下再答，或者先去做正事。", f)
            f.filler || f.choice != null -> Verdict(false, "这样不算回答。说出依据：因为什么、题干哪句、什么规律。", f)
            f.evidence || f.anchorHit -> Verdict(true, if (f.anchorHit) "anchor_hit" else "has_basis", f)
            f.chars >= MIN_CHARS -> Verdict(true, "substantive", f)
            else -> Verdict(false, "太短了，还看不出你想过。至少说清一个依据。", f)
        }
    }

    /** 拦截门要的是能"讲依据"的问题：模型给了 A/B 或念两个词，这里不收，改用兜底题。 */
    fun acceptsLlm(d: Draft?): Boolean = d != null && (d.kind == "open" || d.kind == "one_word") && d.text.isNotBlank()
}
