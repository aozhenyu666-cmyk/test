package com.yishou.app.recall

import com.yishou.app.data.Round
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class EchoTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 9)

    private fun round(id: Long, daysAgo: Long, answer: String, effective: Boolean = true) = Round(
        id = id, taskId = 1, source = "home", triggerPackage = null, coachMove = "问$id", userAnswer = answer,
        effective = effective, moveType = "预测", reason = "", feedback = "", judged = true,
        createdAt = today.minusDays(daysAgo).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
    )

    @Test
    fun stages() {
        assertEquals(null, Echo.stageFor(0))
        assertEquals(1, Echo.stageFor(2))
        assertEquals(3, Echo.stageFor(6))
        assertEquals(7, Echo.stageFor(13))
        assertEquals(null, Echo.stageFor(14))
    }

    @Test
    fun picksLongestPerStageAndSkipsDone() {
        val rounds = listOf(
            round(1, 1, "短"),
            round(2, 1, "长一点的回答"),
            round(3, 3, "三天前"),
            round(4, 7, "一周前", effective = false),
            round(5, 0, "今天的不算"),
            round(6, 8, "八天前"),
        )
        val due = Echo.due(rounds, emptySet(), emptySet(), today, zone)
        assertEquals(listOf(2L to 1, 3L to 3, 6L to 7), due.map { it.round.id to it.stage })

        val after = Echo.due(rounds, setOf(2L to 1), emptySet(), today, zone)
        assertEquals(1L, after.first().round.id)

        val todayDone = Echo.due(rounds, setOf(2L to 1), setOf(1, 3), today, zone)
        assertEquals(listOf(6L), todayDone.map { it.round.id })
        assertTrue(Echo.due(rounds, emptySet(), setOf(1, 3, 7), today, zone).isEmpty())
    }
}
