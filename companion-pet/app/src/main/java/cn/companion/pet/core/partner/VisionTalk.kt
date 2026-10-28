package cn.companion.pet.core.partner

import cn.companion.pet.core.vision.VisionSummary

/**
 * 看图的伙伴用法：带着一个**具体问题**去看，把结果变成“可以聊的点”，而不是只显示“页面主题”。
 *
 * 之前的问题（源码核对）：提示词固定为“描述页面主题、可见元素…”，调用时不带用户想问什么，
 * 界面最后只展示了 subject。这里不改 [cn.companion.pet.core.vision.VisionAnalyzer] 的输出格式，
 * 只换掉传进去的 prompt，并用完整结果生成可聊的锚点。
 */
object VisionTalk {

    /**
     * 给视觉模型的提问。[userQuestion] 是他亲口问的（手动看图）；为空表示它主动看一眼。
     * [context] 是它此刻知道的背景（正在聊的话题线标题、他在用的应用），帮助视觉模型挑重点。
     */
    fun prompt(userQuestion: String, context: String = ""): String = buildString {
        if (userQuestion.isNotBlank()) {
            append("他问的是：").append(userQuestion.take(200)).append("\n请围绕这个问题看图。\n")
        } else {
            append("你是他的朋友，想就他屏幕上正在看的东西和他聊一句。\n")
        }
        if (context.isNotBlank()) append("背景：").append(context.take(200)).append('\n')
        append("elements 里写**具体、可以照抄的细节**：画面上的原文句子、人物在做的动作、数字、标题，")
        append("不要写“一个按钮”“一些文字”这种泛称。")
        append("next_step 里写一个你觉得最值得和他聊的具体点（为什么有意思），不要写操作建议。")
        append("看不清的写进 uncertain，不要猜。")
    }

    /** 能不能拿来主动开口：要有具体元素，且模型自己不是低置信。 */
    fun usable(s: VisionSummary): Boolean =
        s.confidence.lowercase() != "low" && s.elements.any { it.trim().length >= 2 && !isVague(it) }

    /** 变成主动开口的候选；不可用返回 null（宁可不说，也不拿模糊画面硬聊）。 */
    fun toCandidate(id: String, s: VisionSummary, observedAt: Long, importance: Int = 1): TopicCandidate? {
        if (!usable(s)) return null
        val concrete = s.elements.filter { it.trim().length >= 2 && !isVague(it) }.take(4)
        val anchor = buildString {
            if (s.subject.isNotBlank()) append("画面：").append(s.subject.take(60)).append('\n')
            concrete.forEach { append("· ").append(it.take(80)).append('\n') }
            if (s.nextStep.isNotBlank()) append("值得聊：").append(s.nextStep.take(80))
        }.trim()
        return TopicCandidate(id, TopicSource.SCREEN_IMAGE, anchor, importance, observedAt)
    }

    /** 手动看图后给他看的完整结果：主题 + 具体元素 + 看不清的部分，不再只剩一句主题。 */
    fun displayText(s: VisionSummary): String = buildString {
        if (s.subject.isNotBlank()) append(s.subject.take(80))
        val concrete = s.elements.filter { it.isNotBlank() }.take(5)
        if (concrete.isNotEmpty()) append("\n看到：").append(concrete.joinToString("；") { it.take(60) })
        if (s.nextStep.isNotBlank()) append("\n我想聊的：").append(s.nextStep.take(80))
        if (s.uncertain.isNotEmpty()) append("\n看不清：").append(s.uncertain.take(3).joinToString("；") { it.take(40) })
    }.trim()

    private val VAGUE = listOf("按钮", "一些文字", "图标", "界面", "文字内容", "若干", "一些元素", "图片")
    private fun isVague(e: String): Boolean {
        val t = e.trim()
        return t.length <= 4 && VAGUE.any { t.contains(it) }
    }
}
