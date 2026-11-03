package com.dshdriver.core

/**
 * 回复特征：只看"有没有依据"，不评判对错。
 * 和插件 plugin/lib/core/text.js 同一套规则（那边是参照实现，这里是 APP 里真正跑的）。
 */
data class Features(
    val chars: Int,
    val choice: String?,
    val filler: Boolean,
    val giveUp: Boolean,
    val evidence: Boolean,
    val anchorHit: Boolean,
    val score: Double,
)

object Text {
    private val FILLERS = setOf(
        "嗯", "嗯嗯", "恩", "哦", "噢", "喔", "啊", "好", "好的", "好吧", "行", "行吧", "对", "对的", "对对",
        "是", "是的", "知道了", "知道", "明白", "明白了", "懂了", "懂", "了解", "ok", "okay", "可以", "随便",
        "都行", "还行", "差不多", "应该是", "大概", "嗯对", "嗯好",
    )
    private val GIVE_UP = Regex("^(不知道|不懂|不清楚|不会|忘了|没想法|想不出|不想|算了|跳过|pass|不确定)")

    // 带"依据"的标记：因果词、引原文、指位置、举例、数字，以及描述具体规律的词
    private val EVIDENCE = Regex(
        "(因为|所以|由于|根据|题干|原文|材料|说了|提到|写着|第[一二三四五六七八九十\\d]+|关键|依据|条件|前提|比如|例如|而是|排除|" +
            "对比|说明|意味着|规律|每[个格次条行列]|递增|递减|增加|减少|相同|不同|一样|对称|[“”\"「」]|\\d)",
    )
    private val PUNCT = Regex("[\\s,，.。!！?？、;；:：~～…—\\-()（）【】\\[\\]]")
    private val CHOICE = Regex("^(选)?([ab])(吧|的|项)?$")

    fun normalize(text: String?): String = (text ?: "").replace(PUNCT, "").lowercase()

    /** 回复里点选了 A 还是 B（"A"、"选a"、"b吧" 都算）。 */
    fun choiceOf(text: String?): String? = CHOICE.find(normalize(text))?.groupValues?.get(2)?.uppercase()

    fun features(text: String?, anchors: List<String> = emptyList()): Features {
        val raw = (text ?: "").trim()
        val t = normalize(raw)
        val chars = t.codePointCount(0, t.length)
        val choice = choiceOf(raw)
        val filler = chars == 0 || t in FILLERS
        val giveUp = GIVE_UP.containsMatchIn(t)
        val evidence = !filler && EVIDENCE.containsMatchIn(raw)
        val anchorHit = anchors.any { it.isNotBlank() && t.contains(normalize(it)) }
        val score = when {
            choice != null -> 0.5
            giveUp || chars == 0 -> 0.0
            filler -> 0.05
            else -> minOf(1.0, minOf(chars / 20.0, 0.5) + (if (evidence) 0.3 else 0.0) + (if (anchorHit) 0.3 else 0.0))
        }
        return Features(chars, choice, filler, giveUp, evidence, anchorHit, Math.round(score * 100) / 100.0)
    }

    /** 截短给提示词和界面用。 */
    fun clip(text: String?, max: Int = 60): String {
        val s = (text ?: "").replace(Regex("\\s+"), " ").trim()
        val n = s.codePointCount(0, s.length)
        if (n <= max) return s
        return s.substring(0, s.offsetByCodePoints(0, max)) + "…"
    }
}
