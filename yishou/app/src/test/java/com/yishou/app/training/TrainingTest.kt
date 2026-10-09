package com.yishou.app.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrainingTest {
    private val today = LocalDate.of(2026, 10, 10)
    private fun d(back: Long) = today.minusDays(back)

    @Test
    fun firstDayUsesBase() {
        val p = Training.plan(mapOf(today to 4), today, setSize = 3, base = 2)
        assertEquals(2, p.targetSets)
        assertEquals(1, p.doneSets)
        assertEquals(1, p.inSet)
        assertEquals(0, p.streak)
        assertEquals(7, p.days.size)
        assertEquals(today, p.days.last().date)
    }

    @Test
    fun twoHitDaysAddASet() {
        val p = Training.plan(mapOf(d(2) to 6, d(1) to 7, today to 0), today, 3, 2)
        assertEquals(3, p.targetSets)
        assertEquals(2, p.streak)
        assertTrue(p.note.contains("加一组"))
    }

    @Test
    fun twoMissDaysRemoveASetButNotBelowOne() {
        val p = Training.plan(mapOf(d(3) to 1, d(2) to 0), today, 3, 2)
        // d(3) 和 d(2) 没达标 → 减到 1；d(1) 没用（0）也算没达标
        assertEquals(1, p.targetSets)
        val q = Training.plan(mapOf(d(5) to 0), today, 3, 1)
        assertEquals(1, q.targetSets)
    }

    @Test
    fun todayHitCountsInStreak() {
        val p = Training.plan(mapOf(d(1) to 6, today to 6), today, 3, 2)
        assertTrue(p.hitToday)
        assertEquals(2, p.streak)
    }

    @Test
    fun capsAtMax() {
        val days = (1L..40L).associate { d(it) to 100 }
        assertEquals(Training.MAX_SETS, Training.plan(days, today, 3, 2).targetSets)
    }
}
