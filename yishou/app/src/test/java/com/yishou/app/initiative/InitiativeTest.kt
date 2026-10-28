package com.yishou.app.initiative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InitiativeTest {
    private val now = 1_760_000_000_000L
    private val min = 60_000L

    private fun sig(
        hour: Int = 10,
        rounds: Int = 0,
        lastRoundAt: Long = 0,
        lastPushAt: Long = 0,
        count: Int = 0,
        streak: Int = 0,
        windowActive: Boolean = false,
        stoppedAt: Long = 0,
        stuck: String = "",
    ) = Signal(
        now = now,
        hour = hour,
        minute = 30,
        roundsToday = rounds,
        goal = 6,
        lastRoundAt = lastRoundAt,
        lastPushAt = lastPushAt,
        pushCountToday = count,
        ignoredStreak = streak,
        windowActive = windowActive,
        windowStoppedAt = stoppedAt,
        stuck = stuck,
    )

    private val cfg = InitiativeConfig(gapMinutes = 60, floorMinutes = 30, dayStartHour = 8, dayEndHour = 22, patience = 3)

    @Test
    fun `今天没走一手就该叫`() {
        val p = Initiative.decide(cfg, sig())
        assertNotNull(p)
        assertTrue(p!!.title.contains("还没应一手"))
    }

    @Test
    fun `夜里不叫`() {
        assertNull(Initiative.decide(cfg, sig(hour = 23)))
        assertNull(Initiative.decide(cfg, sig(hour = 7)))
    }

    @Test
    fun `窗口里不叫`() {
        assertNull(Initiative.decide(cfg, sig(windowActive = true)))
    }

    @Test
    fun `刚自己关了窗口不追`() {
        assertNull(Initiative.decide(cfg, sig(stoppedAt = now - 10 * min)))
    }

    @Test
    fun `刚走完一手先让他缓一下`() {
        assertNull(Initiative.decide(cfg, sig(rounds = 3, lastRoundAt = now - 10 * min)))
    }

    @Test
    fun `在走但没达标 间隔翻倍`() {
        assertEquals(120, Initiative.intervalMin(cfg, sig(rounds = 2)))
    }

    @Test
    fun `达标了只留一次`() {
        assertEquals(180, Initiative.intervalMin(cfg, sig(rounds = 6)))
        assertNull(Initiative.decide(cfg, sig(rounds = 6, count = 1)))
    }

    @Test
    fun `被忽略会退避`() {
        assertTrue(Initiative.intervalMin(cfg, sig(streak = 2)) > Initiative.intervalMin(cfg, sig(streak = 1)))
    }

    @Test
    fun `忍到上限当天收手`() {
        assertNull(Initiative.decide(cfg, sig(streak = 3)))
    }

    @Test
    fun `关掉就完全安静`() {
        assertNull(Initiative.decide(cfg.copy(enabled = false), sig()))
    }

    @Test
    fun `断点会被带进提醒里`() {
        val p = Initiative.decide(cfg, sig(stuck = "第三个图推不出来"))
        assertNotNull(p)
        assertTrue(p!!.text.contains("第三个图推不出来"))
    }

    @Test
    fun `短间隔重发仍能结算忽略并延后下一次提醒`() {
        val short = cfg.copy(gapMinutes = 30)
        val sent = Initiative.State("2026-10-18").pushed(now).pushed(now + 30 * min)
        assertEquals(now, sent.pendingAt)
        assertEquals(2, sent.count)
        val checkedAt = now + 45 * min + 1
        val settled = sent.settle(0, checkedAt, 45 * min)
        assertEquals(1, settled.streak)
        assertEquals(0L, settled.pendingAt)
        assertEquals(settled, settled.settle(0, checkedAt + min, 45 * min))
        val signal = sig().copy(
            now = checkedAt,
            lastPushAt = settled.lastAt,
            pushCountToday = settled.count,
            ignoredStreak = settled.streak,
        )
        assertNull(Initiative.decide(short, signal))
        val nextAt = now + 75 * min + 1
        val next = Initiative.decide(short, signal.copy(now = nextAt))
        assertNotNull(next)
        assertTrue(next!!.firm)
        assertEquals(nextAt, settled.pushed(nextAt).pendingAt)
    }

    @Test
    fun `走了一手会清除忽略并开始下一轮等待`() {
        val sent = Initiative.State("2026-10-18", streak = 2).pushed(now).pushed(now + 30 * min)
        val replied = sent.settle(now + 31 * min, now + 32 * min, 45 * min)
        assertEquals(0, replied.streak)
        assertEquals(0L, replied.pendingAt)
        assertEquals(now + 60 * min, replied.pushed(now + 60 * min).pendingAt)
    }

    @Test
    fun `当天忍到上限后次日仍会主动叫一手`() {
        val stopped = Initiative.State("2026-10-18", now, 3, 3, now)
        assertEquals(stopped, stopped.onDay("2026-10-18"))
        assertNull(Initiative.decide(cfg, sig(count = stopped.count, streak = stopped.streak)))
        val nextDay = stopped.onDay("2026-10-19")
        assertEquals(Initiative.State("2026-10-19"), nextDay)
        assertNotNull(Initiative.decide(cfg, sig().copy(
            now = now + 86_400_000L,
            lastPushAt = nextDay.lastAt,
            pushCountToday = nextDay.count,
            ignoredStreak = nextDay.streak,
        )))
    }
}
