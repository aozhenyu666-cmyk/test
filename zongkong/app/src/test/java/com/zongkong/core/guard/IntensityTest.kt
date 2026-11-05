package com.zongkong.core.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntensityTest {

    @Test
    fun `empty answer is rejected`() {
        val v = IntensityRules.check("", IntensityLevel.L2)
        assertFalse(v.pass)
        assertEquals(AnswerProblem.EMPTY, v.problem)
    }

    @Test
    fun `pure filler is rejected`() {
        val v = IntensityRules.check("嗯嗯，我就看看，求你了", IntensityLevel.L1)
        assertFalse(v.pass)
        assertEquals(AnswerProblem.FILLER, v.problem)
    }

    @Test
    fun `emotion without action is rejected`() {
        val v = IntensityRules.check(
            "我今天心情不好真的不想学习我也不知道怎么办反正就是没状态",
            IntensityLevel.L1,
        )
        assertFalse(v.pass)
        assertTrue(v.problem == AnswerProblem.NO_NEXT_ACTION || v.problem == AnswerProblem.FILLER)
    }

    @Test
    fun `a concrete answer passes at L1`() {
        val v = IntensityRules.check("先打开粉笔做 5 道言语题，15 分钟做完就停", IntensityLevel.L1)
        assertTrue(v.hint, v.pass)
    }

    @Test
    fun `L2 needs both duration and a stop condition`() {
        val onlyTime = IntensityRules.check("先打开粉笔做言语第 3 套前 5 题，15 分钟", IntensityLevel.L2)
        assertFalse(onlyTime.pass)
        assertEquals(AnswerProblem.NO_NEXT_ACTION, onlyTime.problem)

        val both = IntensityRules.check("先打开粉笔做 5 道言语题，15 分钟做完就停", IntensityLevel.L2)
        assertTrue(both.pass)
    }

    @Test
    fun `higher level requires more substance`() {
        val short = "先打开粉笔做题，15 分钟做完就停"
        assertTrue(IntensityRules.check(short, IntensityLevel.L1).pass)
        assertFalse(IntensityRules.check(short, IntensityLevel.L2).pass)
    }

    @Test
    fun `first failure can reask, second one counts as a miss`() {
        val first = IntensityRules.check("就看看", IntensityLevel.L2, attempt = 0)
        assertTrue(first.reask)
        val second = IntensityRules.check("就看看", IntensityLevel.L2, attempt = 1)
        assertFalse(second.reask)
    }

    @Test
    fun `misses raise the level for the day`() {
        assertEquals(IntensityLevel.L2, IntensityRules.levelFor(misses = 0, passes = 0))
        assertEquals(IntensityLevel.L2, IntensityRules.levelFor(misses = 1, passes = 0))
        assertEquals(IntensityLevel.L3, IntensityRules.levelFor(misses = 2, passes = 0))
    }

    @Test
    fun `three passes relax to L1`() {
        assertEquals(IntensityLevel.L1, IntensityRules.levelFor(misses = 0, passes = 3))
    }

    @Test
    fun `release minutes shrink as the level rises`() {
        assertEquals(2, IntensityRules.releaseMinutes(IntensityLevel.L1))
        assertEquals(1, IntensityRules.releaseMinutes(IntensityLevel.L2))
        assertEquals(1, IntensityRules.releaseMinutes(IntensityLevel.L3))
    }

    @Test
    fun `no response escalates on a fixed clock`() {
        assertEquals(NoResponse.WAIT, IntensityRules.onNoResponse(10))
        assertEquals(NoResponse.NUDGE, IntensityRules.onNoResponse(IntensityRules.NUDGE_SECONDS))
        assertEquals(NoResponse.NUDGE, IntensityRules.onNoResponse(IntensityRules.CLOSE_SECONDS - 1))
        assertEquals(NoResponse.CLOSE_AND_MISS, IntensityRules.onNoResponse(IntensityRules.CLOSE_SECONDS))
    }
}
