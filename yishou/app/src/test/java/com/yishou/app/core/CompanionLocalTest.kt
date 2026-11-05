package com.yishou.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionLocalTest {

    @Test
    fun `nudge quotes the stuck point first`() {
        val q = CompanionLocal.nudge(
            CompanionContext(taskTitle = "行测", stuck = "总在两个都像的选项里选错"),
            "哔哩哔哩",
            35,
        )
        assertTrue(q.contains("总在两个都像的选项里选错"))
        assertTrue(q.contains("35 分钟"))
        assertTrue(q.contains("休息"))
    }

    @Test
    fun `nudge falls back to the task title`() {
        val q = CompanionLocal.nudge(CompanionContext(taskTitle = "投三份简历"), "抖音", 20)
        assertTrue(q.contains("投三份简历"))
        assertTrue(q.contains("20 分钟"))
    }

    @Test
    fun `nudge without context still asks a question`() {
        val q = CompanionLocal.nudge(CompanionContext(), "微博", 12)
        assertTrue(q.contains("微博"))
        assertTrue(q.contains("休息"))
    }

    @Test
    fun `short choice answers pass`() {
        assertTrue(CompanionLocal.checkAnswer("休息").pass)
        assertTrue(CompanionLocal.checkAnswer("现在回去").pass)
        assertTrue(CompanionLocal.checkAnswer("定个时间").pass)
    }

    @Test
    fun `longer explanation passes`() {
        assertTrue(CompanionLocal.checkAnswer("看完这个就去写简历").pass)
    }

    @Test
    fun `filler does not pass`() {
        assertFalse(CompanionLocal.checkAnswer("嗯").pass)
        assertFalse(CompanionLocal.checkAnswer("随便").pass)
    }

    @Test
    fun `first failure can reask and second cannot`() {
        assertTrue(CompanionLocal.checkAnswer("嗯", attempt = 0).reask)
        assertFalse(CompanionLocal.checkAnswer("嗯", attempt = 1).reask)
    }

    @Test
    fun `empty answer is rejected`() {
        val v = CompanionLocal.checkAnswer("")
        assertFalse(v.pass)
        assertEquals(JudgementProblem.EMPTY, v.problem)
    }
}
