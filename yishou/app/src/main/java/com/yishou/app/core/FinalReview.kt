package com.yishou.app.core

/**
 * 选项题落笔前的“终审”：从原文抄一句证据，再说出最犹豫的另一个选项错在哪。
 *
 * 两件事都在本机核对：
 *  - 证据必须能在原文里原样找到（去标点后比对），不能用自己的话；
 *  - “它错在哪”要去掉口头禅再数字数，避免“嗯、就是、反正”凑够长度。
 *
 * 纯函数，不碰 Android，可以单测。
 */
data class FinalReviewInput(
    /** 题干 / 原文。 */
    val source: String,
    /** 从原文抄下来的那一句。 */
    val quote: String,
    /** 最犹豫的另一个选项，例如 “B” 或 “B 项”。 */
    val otherOption: String,
    /** 那个选项错在哪。 */
    val reason: String,
)

enum class FinalReviewProblem {
    QUOTE_EMPTY,
    QUOTE_TOO_SHORT,
    QUOTE_NOT_IN_SOURCE,
    REASON_EMPTY,
    REASON_TOO_SHORT,
    REASON_NOT_ON_OTHER,
}

data class FinalReviewVerdict(
    val ok: Boolean,
    val problem: FinalReviewProblem? = null,
    val hint: String = "",
)

object FinalReviewRules {
    const val MIN_QUOTE_CHARS = 6
    const val MIN_REASON_CHARS = 8

    /** 口头禅：数字数之前先去掉，避免用语气词凑长度。 */
    private val fillers = listOf(
        "我觉得", "我认为", "反正", "其实", "然后", "就是", "那个", "这个",
        "可能", "应该是", "大概", "感觉", "应该",
        "嗯", "啊", "呃", "哦", "唔", "唉", "吧", "嘛", "呢", "呀", "哈",
        "就", "也", "还", "挺", "比较", "有点", "一下",
    )

    /** 只留文字和数字，去掉空格与标点。 */
    fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }

    /** 去掉口头禅之后剩下的字。 */
    fun substance(text: String): String {
        var s = normalize(text)
        var changed = true
        while (changed) {
            changed = false
            for (f in fillers) {
                if (s.contains(f)) {
                    s = s.replace(f, "")
                    changed = true
                }
            }
        }
        return s
    }

    fun check(input: FinalReviewInput): FinalReviewVerdict {
        val quoteRaw = normalize(input.quote)
        if (quoteRaw.isEmpty()) {
            return no(FinalReviewProblem.QUOTE_EMPTY, "先回原文抄一句，不要用自己的话。")
        }
        if (substance(input.quote).length < MIN_QUOTE_CHARS) {
            return no(FinalReviewProblem.QUOTE_TOO_SHORT, "抄得太短，撑不起证据。把整句抄下来。")
        }
        if (!normalize(input.source).contains(quoteRaw)) {
            return no(FinalReviewProblem.QUOTE_NOT_IN_SOURCE, "这句在原文里找不到。回原文选中再复制。")
        }
        if (substance(input.reason).isEmpty()) {
            return no(FinalReviewProblem.REASON_EMPTY, "还差一步：你最犹豫的那个选项错在哪。")
        }
        if (substance(input.reason).length < MIN_REASON_CHARS) {
            return no(FinalReviewProblem.REASON_TOO_SHORT, "这句还只是口头禅。说清它把什么换成了什么。")
        }
        if (!mentions(input.reason, input.otherOption)) {
            return no(
                FinalReviewProblem.REASON_NOT_ON_OTHER,
                "说的是别的选项。回到 ${label(input.otherOption)}，说它错在哪。",
            )
        }
        return FinalReviewVerdict(ok = true)
    }

    /**
     * 兜底问句要引用屏幕上的原词。句子太长时挑不出可引用的片段，
     * 就取它的最后一段（最后一个短句）；还长就取最后 [maxChars] 个字。
     */
    fun quotableSpan(text: String, maxChars: Int = 12): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        val clauses = clean
            .split('，', ',', '；', ';', '。', '！', '？', '!', '?', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val last = clauses.lastOrNull() ?: clean
        val picked = if (last.length <= maxChars) last else last.takeLast(maxChars)
        return picked.trim()
    }

    /**
     * 这道题有没有引用原文。不要求逐字复制整段兜底片段：
     * 模型常把「但视频只给了一条数据」写成「只给了一条数据」，
     * 只要有 ≥[minChars] 个连续字能在原文里找到，就算引用了。
     */
    fun quotesSource(question: String, source: String, minChars: Int = 4): Boolean {
        val q = normalize(question)
        val s = normalize(source)
        if (q.length < minChars || s.length < minChars) return false
        var best = 0
        for (i in q.indices) {
            var len = best + 1
            while (i + len <= q.length) {
                if (s.contains(q.substring(i, i + len))) {
                    best = len
                    len += 1
                } else {
                    break
                }
            }
        }
        return best >= minChars
    }

    /** 引用屏幕原词的兜底问句。挑不出词时返回空串，调用方就不要发。 */
    fun fallbackQuestion(source: String, maxChars: Int = 12): String {
        val span = quotableSpan(source, maxChars)
        if (span.isEmpty()) return ""
        return "就用原文这句：「$span」——它说的是什么？"
    }

    private fun label(option: String): String {
        Regex("""[A-Da-dＡ-Ｄ]""").find(option)?.let {
            return when (it.value) {
                "Ａ" -> "A"
                "Ｂ" -> "B"
                "Ｃ" -> "C"
                "Ｄ" -> "D"
                else -> it.value.uppercase()
            }
        }
        return option.trim().take(8)
    }

    private fun mentions(reason: String, other: String): Boolean {
        val l = label(other)
        if (l.length == 1 && l[0] in 'A'..'D') {
            if (reason.uppercase().contains(l)) return true
            val ordinal = when (l) {
                "A" -> "一"
                "B" -> "二"
                "C" -> "三"
                else -> "四"
            }
            return reason.contains("第${ordinal}项") ||
                reason.contains("第${ordinal}个") ||
                reason.contains("${ordinal}项")
        }
        return other.isNotBlank() && reason.contains(other.take(8))
    }

    private fun no(problem: FinalReviewProblem, hint: String) =
        FinalReviewVerdict(ok = false, problem = problem, hint = hint)
}
