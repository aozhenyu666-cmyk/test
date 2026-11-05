package com.yishou.app.core

/**
 * 伴读模式的判断标准：看屏之后，必须给出一个判断，而不是复述或感受。
 *
 * 通过条件（两条都要）：
 *  1. 有立场：同意 / 不同意 / 觉得 / 认为 / 说明 / 意味着 / 问题是；
 *  2. 有理由：因为 / 所以 / 说明 / 意味着 / 导致 / 反例 / 比如 / 例如 / 证据 / 原因是。
 *
 * 只说“好看”“哈哈”“有道理”不算；只复述屏幕内容不算；只给立场不给理由也不算。
 * 纯函数，不碰 Android，可单测。
 */
enum class JudgementProblem {
    EMPTY,
    /** 只有口头禅、笑、感叹。 */
    FILLER,
    TOO_SHORT,
    /** 没有立场。 */
    NO_STANCE,
    /** 有立场，没有理由或反例。 */
    NO_REASON,
}

data class JudgementCheck(
    val pass: Boolean,
    val problem: JudgementProblem? = null,
    val hint: String = "",
    /** 还能再答一次；false = 记一次没做到。 */
    val reask: Boolean = false,
)

object JudgementRules {
    const val MIN_CHARS = 10

    private val fillers = listOf(
        "哈哈", "哈哈哈", "笑死", "好看", "有意思", "有道理", "确实", "真的",
        "嗯", "啊", "哦", "呃", "唉", "卧槽", "牛逼", "绝了",
        "不知道", "说不好", "随便", "算了",
    )

    private val stance = listOf(
        "同意", "不同意", "赞成", "反对", "觉得", "认为", "判断",
        "说明", "意味着", "问题是", "关键在于", "我猜", "看起来",
        "站不住", "不成立", "错在", "问题出在",
    )

    private val reason = listOf(
        "因为", "所以", "说明", "意味着", "导致", "反例", "比如", "例如",
        "证据", "原因是", "依据", "理由是", "可见", "由此",
    )

    fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }

    fun substance(text: String): Int {
        var s = normalize(text)
        for (f in fillers) s = s.replace(normalize(f), "")
        return s.length
    }

    fun check(answer: String, attempt: Int = 0): JudgementCheck {
        val raw = normalize(answer)
        if (raw.isEmpty()) return fail(JudgementProblem.EMPTY, "先给一句判断。", attempt)

        val sub = substance(answer)
        if (sub < 3) return fail(JudgementProblem.FILLER, "这句只是反应，不是判断。", attempt)
        if (sub < MIN_CHARS) {
            return fail(JudgementProblem.TOO_SHORT, "还差 ${MIN_CHARS - sub} 个字：判断 + 一个理由。", attempt)
        }
        if (stance.none { it in raw }) {
            return fail(
                JudgementProblem.NO_STANCE,
                "先站队：同意 / 不同意 / 这说明什么。",
                attempt,
            )
        }
        if (reason.none { it in raw }) {
            return fail(
                JudgementProblem.NO_REASON,
                "判断后面加一个理由或反例（因为… / 反例是…）。",
                attempt,
            )
        }
        return JudgementCheck(pass = true, hint = "记下了。")
    }

    private fun fail(problem: JudgementProblem, hint: String, attempt: Int) =
        JudgementCheck(pass = false, problem = problem, hint = hint, reask = attempt <= 0)

    /**
     * 出题用的系统提示。硬规则写死在这里，避免模型自由发挥成聊天。
     */
    const val SYSTEM = """
你是伴读。只做一件事：针对屏幕上正在播放的内容，问一个必须做判断的问题。

硬规则：
- 只问一句，不超过 30 字；
- 必须引用屏幕上的原词，把它放在「」里；
- 问判断，不问感受，不问事实：同意还是不同意 / 这说明什么 / 哪一句是证据 / 哪个说法站不住；
- 不问“好看吗”“有趣吗”“讲了什么”；
- 不解释、不鼓励、不说教、不补充背景；
- 只输出这一句问题，不要任何前缀。
"""

    /** 给模型的用户消息：屏幕内容 + 当前应用。 */
    fun userMessage(screenText: String, appLabel: String?): String = buildString {
        if (!appLabel.isNullOrBlank()) appendLine("正在用的应用：$appLabel")
        appendLine("屏幕上的文字：")
        appendLine(screenText.take(800))
        append("出一句判断题。")
    }
}
