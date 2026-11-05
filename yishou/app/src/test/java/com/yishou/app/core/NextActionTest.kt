package com.yishou.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextActionTest {

    @Test
    fun `empty is rejected`() {
        val v = NextActionRules.check("  ")
        assertEquals(NextActionProblem.EMPTY, v.problem)
        assertTrue(v.hint.isNotBlank())
    }

    @Test
    fun `big category without scope is rejected`() {
        val v = NextActionRules.check("复习行测")
        assertEquals(NextActionProblem.TOO_BIG, v.problem)
        assertTrue(v.hint.contains("缩到"))
    }

    @Test
    fun `wish without an action is rejected`() {
        val v = NextActionRules.check("认真学习")
        assertEquals(NextActionProblem.TOO_VAGUE, v.problem)
    }

    @Test
    fun `vague time is not a start time`() {
        for (text in listOf(
            "有空的时候背 20 个单词",
            "晚点做 5 道言语题",
            "改天整理错题 10 道",
        )) {
            val v = NextActionRules.check(text)
            assertEquals("应该退回：$text", NextActionProblem.NO_START_TIME, v.problem)
        }
    }

    @Test
    fun `a concrete next action passes`() {
        val v = NextActionRules.check("吃完饭坐到桌前，打开粉笔做言语第 3 套前 5 题，15 分钟")
        assertTrue(v.ok)
        assertNotNull(v.action)
        assertEquals(15, v.action!!.minutes)
        assertEquals("吃完饭", v.action!!.trigger)
    }

    @Test
    fun `clock time is accepted`() {
        val v = NextActionRules.check("晚上 7:30 打开粉笔做 5 题，20 分钟")
        assertTrue(v.ok)
        assertEquals(20, v.action!!.minutes)
        assertEquals("7:30", v.action!!.trigger)
    }

    @Test
    fun `chinese clock time is accepted`() {
        val v = NextActionRules.check("晚上七点读 10 页书，25 分钟")
        assertTrue(v.ok)
        assertEquals("七点", v.action!!.trigger)
    }

    @Test
    fun `duration is required`() {
        val v = NextActionRules.check("7:30 打开粉笔做 5 题")
        assertEquals(NextActionProblem.NO_DURATION, v.problem)
    }

    @Test
    fun `first physical move is required`() {
        val v = NextActionRules.check("7:30 到桌前，15 分钟")
        assertEquals(NextActionProblem.NO_FIRST_MOVE, v.problem)
    }

    @Test
    fun `half an hour is parsed`() {
        assertEquals(30, NextActionRules.minutesOf("读半小时"))
        assertEquals(60, NextActionRules.minutesOf("练一个小时"))
        assertEquals(0, NextActionRules.minutesOf("练一会儿"))
    }

    @Test
    fun `every rejection carries a concrete hint`() {
        val samples = listOf("", "复习行测", "认真学习", "有空的时候背单词", "7:30 打开粉笔", "7:30 到桌前，15 分钟")
        for (s in samples) {
            val v = NextActionRules.check(s)
            if (!v.ok) {
                assertNotNull("缺问题码：$s", v.problem)
                assertTrue("缺提示：$s", v.hint.isNotBlank())
            }
        }
    }

    @Test
    fun `valid verdict has no problem code`() {
        val v = NextActionRules.check("起床后打开粉笔做 10 道资料分析，25 分钟")
        assertTrue(v.ok)
        assertNull(v.problem)
    }
}
