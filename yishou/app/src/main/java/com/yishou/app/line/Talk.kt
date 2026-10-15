package com.yishou.app.line

import com.yishou.app.think.Words

/** 强度：轻（只提醒）、中（规则要过）、强（更严，适合语音对练） */
enum class Level(val label: String) {
    LIGHT("轻"), MID("中"), HARD("强");

    fun down(): Level = entries[(ordinal - 1).coerceAtLeast(0)]
    fun up(): Level = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]

    companion object {
        fun of(name: String?): Level = entries.firstOrNull { it.name == name } ?: MID
    }
}

/**
 * 说话的规则：这几条在本机算，不经过模型，所以不会被“说得好听”糊弄过去。
 * - maxFiller：口头禅、语气词占多少以内；
 * - lead：第一句就要有实在内容（先说结论）；
 * - budget：一手最多说多少实在字（逼着压缩）；
 * - structureEvery：说得越长，越要有结构——每这么多字至少一个“因为/所以/但是/第一……”。
 */
data class TalkRules(
    val maxFiller: Double,
    val lead: Boolean,
    val budget: Int,
    val structureEvery: Int,
    val minSubstance: Int,
    /** 要压成“只有当 X，才 Y”这样的条件句 */
    val conditional: Boolean = false,
) {
    companion object {
        fun of(level: Level): TalkRules = when (level) {
            Level.LIGHT -> TalkRules(maxFiller = 0.4, lead = false, budget = 300, structureEvery = 0, minSubstance = 4)
            Level.MID -> TalkRules(maxFiller = 0.25, lead = true, budget = 160, structureEvery = 70, minSubstance = 6)
            Level.HARD -> TalkRules(maxFiller = 0.15, lead = true, budget = 100, structureEvery = 50, minSubstance = 8)
        }

        /** 先喷后砍的“砍”：压到 40 字以内的一句条件句（捉虫时开口一句“不对”不算铺垫） */
        val CUT = TalkRules(maxFiller = 0.15, lead = false, budget = 40, structureEvery = 0, minSubstance = 6, conditional = true)

        /** 降阶的小问题（一个词、二选一、先猜）：只要有字、不是拒绝就行 */
        val SMALL = TalkRules(maxFiller = 0.6, lead = false, budget = 400, structureEvery = 0, minSubstance = 1)
    }
}

data class Violation(val code: String, val hint: String)

data class TalkCheck(val substance: Int, val total: Int, val fillerRatio: Double, val violations: List<Violation>) {
    val ok: Boolean get() = violations.isEmpty()
    /** 给人看的一句：先说最要紧的那条 */
    val hint: String? get() = violations.firstOrNull()?.hint
}

object Talk {

    /** 说长了用来串结构的词 */
    val CONNECTIVES = listOf("因为", "所以", "但是", "但", "如果", "因此", "然而", "第一", "第二", "第三", "首先", "其次", "最后", "关键", "结论", "也就是", "导致", "取决于", "前提", "否则", "除非", "只有", "一方面", "另一方面")

    /** 一句话里第一个分句（到第一个逗号、句号为止） */
    fun lead(text: String): String = text.trim().split(Regex("[，。！？；,.!?;\\n]")).firstOrNull { it.isNotBlank() }.orEmpty()

    fun check(text: String, rules: TalkRules): TalkCheck {
        val total = Words.norm(text).length
        val substance = Words.substance(text)
        val filler = if (total == 0) 1.0 else 1.0 - substance.toDouble() / total
        val v = mutableListOf<Violation>()
        if (total > 0 && filler > rules.maxFiller) {
            v += Violation("filler", "口水占了 ${(filler * 100).toInt()}%（“就是、那个、然后、我觉得”这些）。去掉它们再说一遍。")
        }
        if (substance < rules.minSubstance) {
            v += Violation("thin", "太少了：去掉口头禅只剩 $substance 个字。说出一个完整的判断。")
        } else {
            if (rules.lead && Words.substance(lead(text)) < 4) {
                v += Violation("lead", "先说结论：第一句就说你的判断，铺垫放后面。")
            }
            if (rules.conditional && !com.yishou.app.think.Logic.conditional(text)) {
                v += Violation("cond", "压成一句条件句：只有当……，才……（或：如果……，就……）。")
            }
            if (substance > rules.budget) {
                v += Violation("long", "说多了：压到 ${rules.budget} 个字以内，只留最要紧的。")
            }
            if (rules.structureEvery > 0 && substance > rules.structureEvery * 3 / 2) {
                val need = substance / rules.structureEvery
                val have = CONNECTIVES.sumOf { c -> Regex(Regex.escape(c)).findAll(text).count() }
                if (have < need) v += Violation("structure", "说长了要有结构：用“因为…所以…”“第一…第二…”把话串起来（现在 $have 处，至少 $need 处）。")
            }
        }
        return TalkCheck(substance, total, filler, v)
    }
}
