package com.yishou.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionContextTest {

    @Test
    fun `user message carries task stuck and recent judgements`() {
        val ctx = CompanionContext(
            taskTitle = "行测 · 言语理解",
            goal = "每套错不超过 4 个",
            stuck = "总在两个都像的选项里选错",
            lastAnswer = "我觉得 B 更宏观",
            recentConclusions = listOf("排除 A 要先回原文找依据"),
        )
        val msg = CompanionPrompt.user(ctx, "这段文字主要说明什么", "YouTube")
        assertTrue(msg.contains("行测 · 言语理解"))
        assertTrue(msg.contains("总在两个都像的选项里选错"))
        assertTrue(msg.contains("排除 A 要先回原文找依据"))
        assertTrue(msg.contains("屏幕上的文字"))
        assertTrue(msg.contains("只输出一句判断题"))
    }

    @Test
    fun `fallback asks about the stuck point first`() {
        val ctx = CompanionContext(stuck = "资料分析总算错单位")
        val q = CompanionPrompt.fallback(ctx, "随便一段屏幕文字")
        assertTrue(q.contains("资料分析总算错单位"))
        assertTrue(q.contains("卡在哪一步"))
    }

    @Test
    fun `fallback uses screen text when there is no stuck point`() {
        val ctx = CompanionContext()
        val q = CompanionPrompt.fallback(ctx, "这个视频说短视频让人注意力下降")
        assertTrue(q.contains("「"))
        assertTrue(q.contains("同意吗"))
    }

    @Test
    fun `empty context has no material`() {
        assertFalse(CompanionContext().hasMaterial())
        assertTrue(CompanionContext(taskTitle = "x").hasMaterial())
        assertTrue(CompanionContext(recentConclusions = listOf("y")).hasMaterial())
    }

    @Test
    fun `missing fields do not print empty labels`() {
        val msg = CompanionPrompt.user(CompanionContext(taskTitle = "只填了任务"), "屏幕", null)
        assertFalse(msg.contains("正在用的应用"))
        assertFalse(msg.contains("他卡在"))
        assertFalse(msg.contains("上一轮回答"))
        assertTrue(msg.contains("当前任务：只填了任务"))
    }
}
