package com.yishou.app.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactivePolicyTest {

    private val cfg = ProactiveConfig()
    private val now = 1_800_000_000_000L

    @Test
    fun `rest sets a rest window and writes a memory`() {
        val r = ProactivePolicy.onAnswer(cfg, ProactiveState(), "我休息一下", now)
        assertEquals(AnswerAction.REST, r.action)
        assertEquals(ProactiveMode.RESTING, r.state.mode)
        assertEquals(now + cfg.restMinutes * 60_000L, r.state.restUntil)
        assertTrue(r.memory.contains("休息"))
    }

    @Test
    fun `defer sets a defer window`() {
        val r = ProactivePolicy.onAnswer(cfg, ProactiveState(), "定个时间，晚点再说", now)
        assertEquals(AnswerAction.DEFER, r.action)
        assertEquals(ProactiveMode.DEFERRED, r.state.mode)
        assertEquals(now + cfg.deferMinutes * 60_000L, r.state.deferUntil)
    }

    @Test
    fun `back and done are recognized`() {
        assertEquals(AnswerAction.BACK, ProactivePolicy.onAnswer(cfg, ProactiveState(), "现在回去", now).action)
        assertEquals(AnswerAction.DONE, ProactivePolicy.onAnswer(cfg, ProactiveState(), "解决了", now).action)
    }

    @Test
    fun `a normal answer clears rest and defer`() {
        val r = ProactivePolicy.onAnswer(cfg, ProactiveState(), "看了两章", now)
        assertEquals(AnswerAction.ANSWERED, r.action)
        assertEquals(ProactiveMode.ANSWERED, r.state.mode)
        assertEquals(0L, r.state.restUntil)
        assertEquals(0L, r.state.deferUntil)
        assertEquals(now, r.state.lastAnswerAt)
    }

    @Test
    fun `two misses enter backoff and reset the gap`() {
        val one = ProactivePolicy.onMiss(cfg, ProactiveState(), now)
        assertEquals(1, one.unanswered)
        assertEquals(ProactiveMode.ASKING, one.mode)
        val two = ProactivePolicy.onMiss(cfg, one, now + 1)
        assertEquals(2, two.unanswered)
        assertEquals(ProactiveMode.BACKOFF, two.mode)
        assertEquals(now + 1, two.lastAnswerAt)
    }

    @Test
    fun `counters reset on a new hour and day`() {
        val s = ProactiveState(promptsThisHour = 3, promptsToday = 10)
        val same = ProactivePolicy.resetCounters(s, now, 10L, "2026-11-06", 10L, "2026-11-06")
        assertEquals(3, same.promptsThisHour)
        val nextHour = ProactivePolicy.resetCounters(s, now, 11L, "2026-11-06", 10L, "2026-11-06")
        assertEquals(0, nextHour.promptsThisHour)
        assertEquals(10, nextHour.promptsToday)
        val nextDay = ProactivePolicy.resetCounters(s, now, 11L, "2026-11-07", 10L, "2026-11-06")
        assertEquals(0, nextDay.promptsThisHour)
        assertEquals(0, nextDay.promptsToday)
    }
}
