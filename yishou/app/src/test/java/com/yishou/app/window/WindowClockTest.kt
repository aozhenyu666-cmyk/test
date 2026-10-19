package com.yishou.app.window

import com.yishou.app.settings.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WindowClockTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 9)
    private fun t(h: Int, m: Int, d: LocalDate = day) = WindowClock.at(d, h, m, zone)

    private val on = AppPrefs(windowEnabled = true, windowHour = 20, windowMinute = 0, windowMinutes = 45)

    @Test
    fun nextDaily() {
        assertEquals(t(20, 0), WindowClock.nextDaily(t(19, 59), 20, 0, zone))
        assertEquals(t(20, 0, day.plusDays(1)), WindowClock.nextDaily(t(20, 0), 20, 0, zone))
    }

    @Test
    fun scheduledWindowActiveOnlyInside() {
        assertNull(WindowClock.active(t(19, 59), on, zone))
        assertEquals(WindowClock.Span(t(20, 0), t(20, 45)), WindowClock.active(t(20, 0), on, zone))
        assertEquals(WindowClock.Span(t(20, 0), t(20, 45)), WindowClock.active(t(20, 44), on, zone))
        assertNull(WindowClock.active(t(20, 45), on, zone))
        assertNull(WindowClock.active(t(20, 10), on.copy(windowEnabled = false), zone))
    }

    @Test
    fun windowCrossingMidnight() {
        val late = on.copy(windowHour = 23, windowMinute = 30)
        assertEquals(t(23, 30, day.minusDays(1)) + 45 * 60_000L, WindowClock.active(t(0, 10), late, zone)!!.end)
    }

    @Test
    fun manualWindowAndEarlyStop() {
        val start = t(9, 0)
        val p = AppPrefs(manualWindowStart = start, windowMinutes = 30)
        assertEquals(WindowClock.Span(start, start + 30 * 60_000L), WindowClock.active(t(9, 10), p, zone))
        val stopped = p.copy(windowStoppedAt = t(9, 12))
        assertNull(WindowClock.active(t(9, 13), stopped, zone))
        assertEquals(12, WindowClock.windowMinutesOn(day, stopped, zone, t(23, 0)))
    }

    @Test
    fun windowMinutesOnlyCountElapsedPart() {
        assertEquals(10, WindowClock.windowMinutesOn(day, on, zone, t(20, 10)))
        assertEquals(45, WindowClock.windowMinutesOn(day, on, zone, t(23, 0)))
        assertEquals(0, WindowClock.windowMinutesOn(day, on, zone, t(19, 0)))
    }

    @Test
    fun summaryDateHandlesLateRun() {
        val p = AppPrefs(summaryHour = 22, summaryMinute = 30)
        assertEquals(day, WindowClock.summaryDate(t(22, 30), p, zone))
        assertEquals(day, WindowClock.summaryDate(t(21, 0), p, zone))
        assertEquals(day.minusDays(1), WindowClock.summaryDate(t(0, 20), p, zone))
    }
}
