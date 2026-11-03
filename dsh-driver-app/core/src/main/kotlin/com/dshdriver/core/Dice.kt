package com.dshdriver.core

/** 一张卡的草稿：还没编号、没登记。模板、骰子、模型出的题都先是草稿。 */
data class Draft(
    val kind: String,
    val text: String,
    val expects: String,
    val options: Options? = null,
    val intent: String? = null,
    val source: String = "template",
    val anchor: String? = null,
    val missing: String? = null,
    val resumes: String? = null,
)

data class Options(val a: String, val b: String)

data class DieStat(var tries: Int = 0, var advanced: Int = 0)

/** 六骰子降维法：脑子空的时候，不问开放题，只给一个秒级原子动作。 */
object Dice {
    val ALL = listOf("精简", "预判", "回想", "联想", "提问", "体系")

    /** 不连着用同一个；有样本（≥3 次）时优先推进率最高的；平手按用得少、再按固定顺序。 */
    fun pick(usedThisSession: List<String>, history: Map<String, DieStat> = emptyMap()): String {
        val last = usedThisSession.lastOrNull()
        fun rate(d: String): Double = history[d]?.takeIf { it.tries >= 3 }?.let { it.advanced.toDouble() / it.tries } ?: 0.5
        fun count(d: String) = usedThisSession.count { it == d }
        return ALL.filter { it != last }.sortedWith(
            compareByDescending<String> { rate(it) }.thenBy { count(it) }.thenBy { ALL.indexOf(it) },
        ).first()
    }

    fun card(die: String, anchor: String?): Draft {
        val a = anchor?.let { "【$it】" }
        val draft = when (die) {
            "精简" -> Draft("read_two_words", "别想整段。看着屏幕，把${a ?: "这一段"}压成两个词，念出来或打出来。", "words")
            "预判" -> Draft("ab", "先别推，直接猜：${a ?: "这一步"}的方向更像哪个？", "choice", Options("肯定 / 支持", "否定 / 排除"))
            "回想" -> Draft("ab", "回想一下刚才${a ?: "那一步"}：你是看到了依据，还是凭感觉？", "choice", Options("看到了依据", "凭感觉"))
            "联想" -> Draft("ab", "${a ?: "这个"}让你想到哪类？", "choice", Options("以前做过的同类", "完全没见过"))
            "体系" -> Draft("ab", "${a ?: "这一步"}在整件事里属于哪一段？", "choice", Options("还在找条件", "已经该下结论了"))
            else -> Draft("ab", "你现在卡在哪${anchor?.let { "（$it）" } ?: ""}？", "choice", Options("没看懂它在说什么", "看懂了但不会选/不会下结论"))
        }
        return draft.copy(intent = "dice:$die")
    }
}
