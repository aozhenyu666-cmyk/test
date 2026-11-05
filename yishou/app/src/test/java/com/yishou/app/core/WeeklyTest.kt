package com.yishou.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyTest {

    private fun week(
        set: Int = 5,
        done: Int = 4,
        focus: Int = 60,
        accuracy: (Int) -> Double? = { if (it <= 3) 0.6 else 0.7 },
        seconds: (Int) -> Double? = { if (it <= 3) 80.0 else 70.0 },
        naked: Double? = null,
    ): List<DayRecord> = (1..7).map { d ->
        DayRecord(
            date = "2026-11-0$d",
            focusMinutes = focus,
            actionsSet = set,
            actionsDone = done,
            rounds = 3,
            effectiveRounds = 3,
            accuracy = accuracy(d),
            secondsPerQuestion = seconds(d),
            nakedScore = if (d == 7) naked else null,
        )
    }

    @Test
    fun `no data means no conclusion`() {
        val r = Weekly.build(emptyList())
        assertEquals(WeeklyVerdict.INSUFFICIENT, r.verdict)
        assertEquals(0, r.days)
        assertNull(r.settleRate)
    }

    @Test
    fun `a week that is landing keeps the current mechanism`() {
        val r = Weekly.build(week())
        assertEquals(WeeklyVerdict.KEEP, r.verdict)
        assertEquals(420, r.focusMinutes)
        assertEquals(0.8, r.settleRate!!, 0.0001)
        assertEquals(0.1, r.accuracyDelta!!, 0.0001)
        assertEquals(-10.0, r.speedDeltaSeconds!!, 0.0001)
    }

    @Test
    fun `low settle rate forces a mechanism change`() {
        val r = Weekly.build(week(set = 10, done = 2))
        assertEquals(WeeklyVerdict.CHANGE_MECHANISM, r.verdict)
        assertEquals(0.2, r.settleRate!!, 0.0001)
    }

    @Test
    fun `naked test without a baseline forces a mechanism change`() {
        val r = Weekly.build(week(naked = 0.5))
        assertEquals(WeeklyVerdict.CHANGE_MECHANISM, r.verdict)
    }

    @Test
    fun `naked test that did not improve forces a mechanism change`() {
        val r = Weekly.build(week(naked = 0.5), previousNaked = 0.5)
        assertEquals(WeeklyVerdict.CHANGE_MECHANISM, r.verdict)
    }

    @Test
    fun `naked test that clearly improved can keep the mechanism`() {
        val r = Weekly.build(week(naked = 0.7), previousNaked = 0.5)
        assertEquals(WeeklyVerdict.KEEP, r.verdict)
    }

    @Test
    fun `too few points do not produce a trend`() {
        val r = Weekly.build(
            listOf(
                DayRecord("2026-11-01", accuracy = 0.5, actionsSet = 1, actionsDone = 1),
                DayRecord("2026-11-02", accuracy = 0.9, actionsSet = 1, actionsDone = 1),
            ),
        )
        assertNull(r.accuracyDelta)
    }

    @Test
    fun `report states facts and never blames the person`() {
        val r = Weekly.build(week())
        assertTrue(r.lines.isNotEmpty())
        val banned = listOf("懒", "又", "必须", "应该", "罚")
        for (line in r.lines) {
            for (word in banned) {
                assertFalse("周报不该出现「$word」：$line", line.contains(word))
            }
        }
        assertTrue(r.lines.any { it.contains("420") })
    }

    @Test
    fun `report always ends with a decision`() {
        val decisions = listOf(
            Weekly.build(emptyList()),
            Weekly.build(week()),
            Weekly.build(week(set = 10, done = 1)),
        )
        for (r in decisions) {
            assertTrue(r.lines.last().startsWith("结论："))
        }
    }
}
