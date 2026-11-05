package com.yishou.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgementTest {

    @Test
    fun `empty is rejected`() {
        val v = JudgementRules.check("")
        assertFalse(v.pass)
        assertEquals(JudgementProblem.EMPTY, v.problem)
    }

    @Test
    fun `pure reaction is rejected`() {
        val v = JudgementRules.check("哈哈哈哈好看笑死")
        assertFalse(v.pass)
        assertTrue(v.problem == JudgementProblem.FILLER || v.problem == JudgementProblem.TOO_SHORT)
    }

    @Test
    fun `description without a stance is rejected`() {
        val v = JudgementRules.check("这个视频讲的是一个人去旅行然后拍了很多照片")
        assertFalse(v.pass)
        assertEquals(JudgementProblem.NO_STANCE, v.problem)
    }

    @Test
    fun `stance without a reason is rejected`() {
        val v = JudgementRules.check("我不同意这个说法，但是就这样")
        assertFalse(v.pass)
        assertEquals(JudgementProblem.NO_REASON, v.problem)
    }

    @Test
    fun `stance plus reason passes`() {
        val v = JudgementRules.check("我不同意，因为视频把相关说成了因果，证据只有一条")
        assertTrue(v.hint, v.pass)
    }

    @Test
    fun `a counterexample also counts as a reason`() {
        val v = JudgementRules.check("这个结论站不住，反例是同样不刷短视频的人注意力也差")
        assertTrue(v.hint, v.pass)
    }

    @Test
    fun `first failure can reask, second counts`() {
        val first = JudgementRules.check("好看", attempt = 0)
        assertTrue(first.reask)
        val second = JudgementRules.check("好看", attempt = 1)
        assertFalse(second.reask)
    }

    @Test
    fun `short stance is still too short`() {
        val v = JudgementRules.check("我同意")
        assertFalse(v.pass)
        assertEquals(JudgementProblem.TOO_SHORT, v.problem)
    }

    @Test
    fun `prompt rules forbid feelings and lectures`() {
        assertTrue(JudgementRules.SYSTEM.contains("不超过 30 字"))
        assertTrue(JudgementRules.SYSTEM.contains("不问感受"))
        assertTrue(JudgementRules.SYSTEM.contains("只输出这一句"))
    }
}
