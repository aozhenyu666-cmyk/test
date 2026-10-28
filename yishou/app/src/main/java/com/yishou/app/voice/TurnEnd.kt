package com.yishou.app.voice

/**
 * 什么时候算你说完了：边想边说的人一停顿就被抢话，会很烦。
 * - 说“好了 / 说完了”：立刻算完（只结束这一段发言，不是结束整场）；
 * - 说了“我想想 / 等一下”：放宽到 8 秒；
 * - 以“然后、就是、那个、因为……”这种半截话结尾，或者才两三个字：放宽到 2.5 秒；
 * - 一句完整的话：静音 0.8 秒算完；
 * - 听写没给出半截文字（录完才上传的那种）：按 1.5 秒算。
 * 数字都是待真机实测的初值，可调。
 */
object TurnEnd {

    data class Rules(
        val completeMs: Long = 800,
        val incompleteMs: Long = 2_500,
        val thinkingMs: Long = 8_000,
        val unknownMs: Long = 1_500,
    )

    val DONE = listOf("说完了", "我说完了", "好了", "就这样", "就这些")
    val THINKING = listOf("我想想", "让我想想", "想一下", "等一下", "等等", "稍等", "我想一想")
    val DANGLING = listOf(
        "然后", "就是", "那个", "这个", "嗯", "呃", "啊", "因为", "所以", "但是", "可是", "而且", "还有", "比如",
        "如果", "我觉得", "我认为", "就", "和", "跟", "或者", "的话", "首先", "其次", "也就是",
    )
    private val ENDINGS = "。！？!?"

    /** 静音多久算说完；partial 为空表示听写没有半截文字 */
    fun waitMs(partial: String?, rules: Rules = Rules()): Long {
        val t = partial?.trim()?.trimEnd('，', ',', '、', ' ') ?: return rules.unknownMs
        if (t.isEmpty()) return rules.unknownMs
        val core = t.trimEnd { it in ENDINGS || it == '。' }
        if (DONE.any { core.endsWith(it) }) return 0
        if (THINKING.any { it in core.takeLast(8) }) return rules.thinkingMs
        if (DANGLING.any { core.endsWith(it) }) return rules.incompleteMs
        if (t.last() in ENDINGS) return rules.completeMs
        if (core.count { it.isLetterOrDigit() } < 4) return rules.incompleteMs
        return rules.completeMs
    }

    fun ended(partial: String?, silenceMs: Long, rules: Rules = Rules()): Boolean = silenceMs >= waitMs(partial, rules)

    /** 交给模型前，把结尾那句“好了 / 说完了”去掉（它是信号，不是内容） */
    fun strip(text: String): String {
        var t = text.trim().trimEnd('。', '！', '!', '，', ',')
        for (d in DONE.sortedByDescending { it.length }) {
            if (t.endsWith(d) && t.length > d.length) {
                t = t.removeSuffix(d).trimEnd('，', ',', '。', ' ')
                break
            }
        }
        return t
    }
}
