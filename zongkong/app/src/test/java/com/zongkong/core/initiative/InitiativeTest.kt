package com.zongkong.core.initiative

import com.zongkong.core.Config
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InitiativeTest {
    private val now = 1_760_000_000_000L
    private val min = 60_000L

    private fun sig(
        minute: Int = 10 * 60,
        quiet: Boolean = false,
        paused: Boolean = false,
        hqActive: Boolean = false,
        focusing: Boolean = false,
        lastContactAt: Long = now - 60 * min,
        lastPushAt: Long = 0,
        count: Int = 0,
        streak: Int = 0,
        backlog: Int = 1,
        intensity: Int = 1,
    ) = Signal(
        now = now,
        minute = minute,
        activeStart = 8 * 60,
        activeEnd = 23 * 60,
        quiet = quiet,
        paused = paused,
        hqActive = hqActive,
        focusing = focusing,
        lastContactAt = lastContactAt,
        lastPushAt = lastPushAt,
        pushCountToday = count,
        ignoredStreak = streak,
        backlog = backlog,
        intensity = intensity,
    )

    private val cfg = InitiativeConfig(captureEveryMin = 20, captureFloorMin = 10, capturePerDay = 6, capturePatience = 3)

    @Test
    fun `第一次该拉就拉`() {
        val p = Initiative.decide(cfg, sig())
        assertNotNull(p)
        assertEquals(Kind.CAPTURE, p!!.kind)
    }

    @Test
    fun `睡觉时段不打扰`() {
        assertNull(Initiative.decide(cfg, sig(minute = 2 * 60, quiet = true)))
    }

    @Test
    fun `休假不打扰`() {
        assertNull(Initiative.decide(cfg, sig(paused = true)))
    }

    @Test
    fun `总控时间里不另外拉`() {
        assertNull(Initiative.decide(cfg, sig(hqActive = true)))
    }

    @Test
    fun `安静档一次都不拉`() {
        assertNull(Initiative.decide(cfg, sig(intensity = 0)))
    }

    @Test
    fun `活动时段之外不拉`() {
        assertNull(Initiative.decide(cfg, sig(minute = 7 * 60)))
        assertNull(Initiative.decide(cfg, sig(minute = 23 * 60 + 30)))
    }

    @Test
    fun `刚说完话不抢话`() {
        assertNull(Initiative.decide(cfg, sig(lastContactAt = now - 3 * min)))
    }

    @Test
    fun `两次之间有冷却`() {
        assertNull(Initiative.decide(cfg, sig(lastPushAt = now - 5 * min)))
    }

    @Test
    fun `到了间隔才拉`() {
        assertNull(Initiative.decide(cfg, sig(lastPushAt = now - 15 * min)))
        assertNotNull(Initiative.decide(cfg, sig(lastPushAt = now - 21 * min)))
    }

    @Test
    fun `收集箱空着就少催`() {
        assertEquals(30, Initiative.intervalMin(cfg, sig(backlog = 0)))
    }

    @Test
    fun `积压三条就多催`() {
        assertEquals(14, Initiative.intervalMin(cfg, sig(backlog = 3)))
    }

    @Test
    fun `任何档位都不低于十分钟`() {
        assertEquals(10, Initiative.intervalMin(cfg, sig(backlog = 3, intensity = 3)))
        assertTrue(Initiative.intervalMin(cfg, sig(backlog = 0, intensity = 3)) >= Initiative.MIN_GAP_MIN)
    }

    @Test
    fun `主动程度越高间隔越短`() {
        val a = Initiative.intervalMin(cfg, sig(intensity = 1))
        val b = Initiative.intervalMin(cfg, sig(intensity = 2))
        val c = Initiative.intervalMin(cfg, sig(intensity = 3))
        assertTrue(a > b)
        assertTrue(b > c)
    }

    @Test
    fun `被忽略会退避`() {
        val once = Initiative.intervalMin(cfg, sig(streak = 1))
        val twice = Initiative.intervalMin(cfg, sig(streak = 2))
        assertTrue(twice > once)
    }

    @Test
    fun `忍到上限当天收手`() {
        assertNull(Initiative.decide(cfg, sig(streak = cfg.capturePatience)))
    }

    @Test
    fun `每天有上限`() {
        assertNull(Initiative.decide(cfg, sig(count = cfg.capturePerDay)))
    }

    @Test
    fun `专注时不打断`() {
        assertNull(Initiative.decide(cfg, sig(focusing = true, backlog = 1)))
    }

    @Test
    fun `专注但收集箱压到三条时仍然提醒`() {
        assertNotNull(Initiative.decide(cfg, sig(focusing = true, backlog = 3)))
    }

    @Test
    fun `被忽略过一次语气变硬`() {
        val p = Initiative.decide(cfg, sig(streak = 1, lastPushAt = now - 60 * min))
        assertNotNull(p)
        assertTrue(p!!.firm)
    }

    @Test
    fun `关掉总开关就完全安静`() {
        assertNull(Initiative.decide(cfg.copy(enabled = false), sig()))
        assertNull(Initiative.nextAlarmAt(cfg.copy(enabled = false), sig()))
    }

    @Test
    fun `下一次闹钟总在现在之后`() {
        val at = Initiative.nextAlarmAt(cfg, sig(lastPushAt = now - 5 * min))
        assertNotNull(at)
        assertTrue(at!! > now)
    }

    @Test
    fun `连续提醒仍按第一次未回应计时并触发退避`() {
        val sent = Initiative.State("2026-10-18").pushed(now).pushed(now + 20 * min)
        assertEquals(now, sent.pendingAt)
        assertEquals(2, sent.count)
        val checkedAt = now + 30 * min + 1
        val settled = sent.settle(0, checkedAt, 30 * min)
        assertEquals(1, settled.streak)
        assertEquals(0L, settled.pendingAt)
        assertEquals(settled, settled.settle(0, checkedAt + min, 30 * min))
        val signal = sig().copy(
            now = checkedAt,
            lastContactAt = 0,
            lastPushAt = settled.lastAt,
            pushCountToday = settled.count,
            ignoredStreak = settled.streak,
        )
        assertNull(Initiative.decide(cfg, signal))
        val nextAt = now + 50 * min + 1
        val next = Initiative.decide(cfg, signal.copy(now = nextAt))
        assertNotNull(next)
        assertTrue(next!!.firm)
        assertEquals(nextAt, settled.pushed(nextAt).pendingAt)
    }

    @Test
    fun `重发之后回应也能清除忽略并开始下一轮`() {
        val sent = Initiative.State("2026-10-18", streak = 2).pushed(now).pushed(now + 20 * min)
        val replied = sent.settle(now + 21 * min, now + 22 * min, 30 * min)
        assertEquals(0, replied.streak)
        assertEquals(0L, replied.pendingAt)
        assertEquals(now + 60 * min, replied.pushed(now + 60 * min).pendingAt)
    }

    @Test
    fun `当天忽略上限不会让次日永久停催`() {
        val stopped = Initiative.State("2026-10-18", now, 6, 3, now)
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

    @Test
    fun `旧配置保留主动性默认值且新配置能往返保存`() {
        val old = Json.decodeFromString<Config>("{}")
        assertEquals(InitiativeConfig(), old.initiative)
        val changed = old.copy(initiative = cfg.copy(enabled = false, captureEveryMin = 40))
        assertEquals(changed, Json.decodeFromString<Config>(Json.encodeToString(changed)))
    }
}
