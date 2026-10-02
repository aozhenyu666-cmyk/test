package com.behaviordept.app

import com.behaviordept.app.data.Rule
import com.behaviordept.app.guard.RuleLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleLogicTest {
    private val day = RuleLogic.COOLING_MS
    private val rule = Rule(id = 1, name = "娱乐", packages = "a,b", dailyLimitMin = 30, effectiveAt = 0)

    @Test
    fun tighteningIsImmediate() {
        val r = RuleLogic.edit(rule, "娱乐", 20, listOf("a", "b", "c"), 100)
        assertEquals(20, r.dailyLimitMin)
        assertEquals(listOf("a", "b", "c"), r.packageList)
        assertFalse(r.hasPending)
    }

    @Test
    fun looseningWaits24Hours() {
        val r = RuleLogic.edit(rule, "娱乐", 45, listOf("a"), 100)
        assertEquals(30, r.dailyLimitMin)
        assertEquals(listOf("a", "b"), r.packageList)
        assertTrue(r.hasPending)
        assertEquals(100 + day, r.pendingEffectiveAt)
        // 冷静期没到，不变
        assertEquals(r, RuleLogic.applyDue(r, 100 + day - 1))
        val applied = RuleLogic.applyDue(r, 100 + day)!!
        assertEquals(45, applied.dailyLimitMin)
        assertEquals(listOf("a"), applied.packageList)
        assertFalse(applied.hasPending)
    }

    @Test
    fun mixedChangeAppliesTightPartNow() {
        // 加一个 App（收紧）同时提高上限（放宽）：App 立刻加上，上限等 24 小时
        val r = RuleLogic.edit(rule, "娱乐", 40, listOf("a", "b", "c"), 0)
        assertEquals(listOf("a", "b", "c"), r.packageList)
        assertEquals(30, r.dailyLimitMin)
        assertEquals(40, r.pendingLimitMin)
    }

    @Test
    fun deleteAndCancel() {
        val r = RuleLogic.requestDelete(rule, 0)
        assertTrue(r.pendingDelete)
        assertNull(RuleLogic.applyDue(r, day))
        val back = RuleLogic.cancelPending(r)
        assertFalse(back.hasPending)
        assertEquals(back, RuleLogic.applyDue(back, 10 * day))
    }

    @Test
    fun minutesSumOverPackages() {
        assertEquals(25, RuleLogic.minutesFor(rule, mapOf("a" to 10, "b" to 15, "c" to 99)))
        assertEquals(2, RuleLogic.daysKept(listOf(10, 30, 31), 30))
    }
}
