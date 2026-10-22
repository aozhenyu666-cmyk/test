package cn.companion.pet.core.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiBridgeTextBuilderTest {

    private val tailInstruction =
        "请像熟人一样直接接话，不要客套，不要复述以上信息，不要提任务或计划。" +
            "如果我在看学习内容，带我一起拆解；如果我在走神，帮我把念头说清楚。"

    private fun learning(
        timeText: String = "21:30",
        trajectorySummary: String = "刚在浏览器和笔记之间来回切了三次",
        contextSummary: String = "看一篇讲 Kotlin 协程的教程",
        excerpt: String = "协程把异步回调写成顺序代码。",
        hookText: String = "这段你之前卡过吧？",
        userInput: String = "对，我还是没搞懂挂起函数"
    ) = BridgeContext(
        timeText = timeText,
        trajectorySummary = trajectorySummary,
        isLearning = true,
        contextSummary = contextSummary,
        excerpt = excerpt,
        hookText = hookText,
        userInput = userInput
    )

    @Test
    fun buildText_learningScenarioMatchesTemplateExactly() {
        val expected = listOf(
            "[来自我的桌宠 | 21:30]",
            "最近轨迹：刚在浏览器和笔记之间来回切了三次",
            "我在看的内容：看一篇讲 Kotlin 协程的教程",
            "原文节选：协程把异步回调写成顺序代码。",
            "桌宠刚才问我：「这段你之前卡过吧？」",
            "我想说：对，我还是没搞懂挂起函数",
            "——",
            tailInstruction
        ).joinToString("\n")
        assertEquals(expected, GeminiBridgeTextBuilder.buildText(learning()))
    }

    @Test
    fun buildText_trimsFieldsButKeepsUserInnerNewlines() {
        val text = GeminiBridgeTextBuilder.buildText(
            learning(
                timeText = "  21:30  ",
                trajectorySummary = "\t刚切了两次\n",
                userInput = "  第一行\n第二行  "
            )
        )
        assertTrue(text.startsWith("[来自我的桌宠 | 21:30]\n"))
        assertTrue(text.contains("\n最近轨迹：刚切了两次\n"))
        assertTrue(text.contains("我想说：第一行\n第二行"))
        assertFalse(text.endsWith("\n"))
    }

    @Test
    fun buildText_emptyUserInputFallsBackToDefaultSentence() {
        val expected = "我想说：" + "我还没想好怎么说，你先接着它的问题聊"
        assertEquals(
            true,
            GeminiBridgeTextBuilder.buildText(learning(userInput = "")).contains(expected)
        )
        assertEquals(
            true,
            GeminiBridgeTextBuilder.buildText(learning(userInput = "   \n ")).contains(expected)
        )
    }

    @Test
    fun buildText_excerptIsCappedAt600CodePoints() {
        val exact = "文".repeat(600)
        val exactText = GeminiBridgeTextBuilder.buildText(learning(excerpt = exact))
        assertTrue(exactText.contains("\n原文节选：$exact\n"))

        val over = "文".repeat(601)
        val overText = GeminiBridgeTextBuilder.buildText(learning(excerpt = over))
        val excerptLine = overText.split("\n").first { it.startsWith("原文节选：") }
        assertEquals("原文节选：".length + 600, excerptLine.length)
    }

    @Test
    fun buildText_excerptCapCountsEmojiAsOneCodePoint() {
        val over = "😀".repeat(601)
        val text = GeminiBridgeTextBuilder.buildText(learning(excerpt = over))
        val excerptLine = text.split("\n").first { it.startsWith("原文节选：") }
        val payload = excerptLine.removePrefix("原文节选：")
        assertEquals(600, payload.codePointCount(0, payload.length))
        assertEquals(1200, payload.length)
    }

    @Test
    fun buildText_learningScenarioWithoutBodyKeepsEmptyFields() {
        val text = GeminiBridgeTextBuilder.buildText(
            learning(contextSummary = "", excerpt = "")
        )
        assertTrue(text.contains("\n我在看的内容：\n"))
        assertTrue(text.contains("\n原文节选：\n"))
    }

    @Test
    fun buildText_nonLearningScenarioIsolatesScreenText() {
        val context = BridgeContext(
            timeText = "22:05",
            trajectorySummary = "在信息流里刷了两分钟",
            isLearning = false,
            contextSummary = "机密摘要：某个不该出现的定制内容",
            excerpt = "机密正文：这段屏幕原文不应进入桥接文本",
            hookText = "你刚才是不是在摸鱼？",
            userInput = "有点"
        )
        val text = GeminiBridgeTextBuilder.buildText(context)

        assertFalse(text.contains("机密摘要"))
        assertFalse(text.contains("机密正文"))
        assertTrue(text.contains("\n我在看的内容：当前不是学习场景，不提供屏幕内容\n"))
        assertTrue(text.contains("\n原文节选：\n"))

        val expected = listOf(
            "[来自我的桌宠 | 22:05]",
            "最近轨迹：在信息流里刷了两分钟",
            "我在看的内容：当前不是学习场景，不提供屏幕内容",
            "原文节选：",
            "桌宠刚才问我：「你刚才是不是在摸鱼？」",
            "我想说：有点",
            "——",
            tailInstruction
        ).joinToString("\n")
        assertEquals(expected, text)
    }

    @Test
    fun buildText_emptyFieldsStayEmpty() {
        val context = BridgeContext(
            timeText = "",
            trajectorySummary = "",
            isLearning = false,
            contextSummary = "",
            excerpt = "",
            hookText = "",
            userInput = ""
        )
        val text = GeminiBridgeTextBuilder.buildText(context)
        assertTrue(text.startsWith("[来自我的桌宠 | ]\n最近轨迹：\n"))
        assertTrue(text.contains("桌宠刚才问我：「」"))
        assertTrue(text.endsWith(tailInstruction))
    }

    @Test
    fun buildLiveOpening_withAndWithoutHook() {
        assertEquals(
            "我在看这个，帮我一起想想 这段你之前卡过吧？",
            GeminiBridgeTextBuilder.buildLiveOpening("这段你之前卡过吧？")
        )
        assertEquals(
            "我在看这个，帮我一起想想",
            GeminiBridgeTextBuilder.buildLiveOpening("")
        )
        assertEquals(
            "我在看这个，帮我一起想想",
            GeminiBridgeTextBuilder.buildLiveOpening("   \n ")
        )
        assertEquals(
            "我在看这个，帮我一起想想 这个图表",
            GeminiBridgeTextBuilder.buildLiveOpening("  这个图表  ")
        )
    }
}
