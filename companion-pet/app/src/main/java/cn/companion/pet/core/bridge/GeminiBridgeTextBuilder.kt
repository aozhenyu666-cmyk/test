package cn.companion.pet.core.bridge

import cn.companion.pet.core.text.UnicodeText

/**
 * Gemini 桥接文本拼装。
 *
 * 只负责拼文字：不写剪贴板、不查包名、不发 Intent、不碰麦克风、不记调用次数。
 * 不定义全局场景枚举，场景由上层用 [BridgeContext.isLearning] 传入，避免与状态机冲突。
 */
object GeminiBridgeTextBuilder {

    /** “原文节选”最多保留的码点数。 */
    const val MAX_EXCERPT_CHARS: Int = 600

    private const val NON_LEARNING_CONTEXT = "当前不是学习场景，不提供屏幕内容"
    private const val DEFAULT_USER_INPUT = "我还没想好怎么说，你先接着它的问题聊"
    private const val TAIL_INSTRUCTION =
        "请像熟人一样直接接话，不要客套，不要复述以上信息，不要提任务或计划。" +
            "如果我在看学习内容，带我一起拆解；如果我在走神，帮我把念头说清楚。"

    /**
     * 按固定模板拼出交给 Gemini 的一段文字。
     *
     * 换行只用 `\n`，字段顺序与中文标点固定，不加标题、不加 Markdown 围栏，
     * 结果末尾不额外加换行。所有输入先去首尾空白；用户输入的内部文字和换行原样保留。
     */
    fun buildText(context: BridgeContext): String {
        val timeText = context.timeText.trim()
        val trajectorySummary = context.trajectorySummary.trim()
        val contextSummary = if (context.isLearning) {
            context.contextSummary.trim()
        } else {
            NON_LEARNING_CONTEXT
        }
        val excerpt = if (context.isLearning) {
            UnicodeText.truncate(context.excerpt.trim(), MAX_EXCERPT_CHARS)
        } else {
            ""
        }
        val hookText = context.hookText.trim()
        val userInput = context.userInput.trim().ifEmpty { DEFAULT_USER_INPUT }

        return listOf(
            "[来自我的桌宠 | $timeText]",
            "最近轨迹：$trajectorySummary",
            "我在看的内容：$contextSummary",
            "原文节选：$excerpt",
            "桌宠刚才问我：「$hookText」",
            "我想说：$userInput",
            "——",
            TAIL_INSTRUCTION
        ).joinToString("\n")
    }

    /**
     * Live 共看的开场白。hook 为空时只留前半句。
     */
    fun buildLiveOpening(hookText: String): String {
        val hook = hookText.trim()
        return if (hook.isEmpty()) {
            "我在看这个，帮我一起想想"
        } else {
            "我在看这个，帮我一起想想 $hook"
        }
    }
}
