package com.yishou.app.core

/**
 * 伴读出题前能读到的“此刻上下文”。
 *
 * 只做一件事：把当前任务、卡点、上一轮回答、最近的判断拼成一段给模型的材料，
 * 让问题贴着用户正在做的事，而不是泛泛地问屏幕。
 */
data class CompanionContext(
    val taskTitle: String? = null,
    val goal: String? = null,
    /** 断点里“卡在哪”。 */
    val stuck: String? = null,
    /** 断点里“下一次该问什么”。 */
    val nextQuestion: String? = null,
    /** 上一轮他说的话。 */
    val lastAnswer: String? = null,
    /** 最近几条他自己确认过的判断。 */
    val recentConclusions: List<String> = emptyList(),
) {
    /** 有没有值得一提的上下文；没有就走纯屏幕题。 */
    fun hasMaterial(): Boolean =
        !taskTitle.isNullOrBlank() || !stuck.isNullOrBlank() ||
            !nextQuestion.isNullOrBlank() || recentConclusions.isNotEmpty()
}

object CompanionPrompt {
    /** 给模型的用户消息：上下文 + 屏幕文字。 */
    fun user(ctx: CompanionContext, screenText: String, appLabel: String?): String = buildString {
        if (!appLabel.isNullOrBlank()) appendLine("正在用的应用：$appLabel")
        if (!ctx.taskTitle.isNullOrBlank()) appendLine("当前任务：${ctx.taskTitle}")
        if (!ctx.goal.isNullOrBlank()) appendLine("目标：${ctx.goal.take(80)}")
        if (!ctx.stuck.isNullOrBlank()) appendLine("他卡在：${ctx.stuck.take(120)}")
        if (!ctx.nextQuestion.isNullOrBlank()) appendLine("上次留下的问题：${ctx.nextQuestion.take(120)}")
        if (!ctx.lastAnswer.isNullOrBlank()) appendLine("上一轮回答：${ctx.lastAnswer.take(120)}")
        if (ctx.recentConclusions.isNotEmpty()) {
            appendLine("他最近的判断：")
            ctx.recentConclusions.take(3).forEach { appendLine("- ${it.take(80)}") }
        }
        appendLine("屏幕上的文字：")
        appendLine(screenText.take(800))
        appendLine("要求：如果能贴着“卡点”问，就先问卡点；屏幕只是素材。不要重复他最近的判断。")
        append("只输出一句判断题。")
    }

    /** 兜底句也尽量贴上下文：有卡点就问卡点，没有才问屏幕原词。 */
    fun fallback(ctx: CompanionContext, screenText: String): String {
        val stuck = ctx.stuck?.trim().orEmpty()
        if (stuck.length >= 4) return "你说卡在「${stuck.take(30)}」——具体卡在哪一步？"
        return Companion.fallbackQuestion(screenText)
    }
}
