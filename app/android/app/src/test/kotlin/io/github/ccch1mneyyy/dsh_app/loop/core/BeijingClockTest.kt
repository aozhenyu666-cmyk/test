package io.github.ccch1mneyyy.dsh_app.loop.core

import java.time.Instant
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * R7 P0-2：时间基准固定北京时间。
 *
 * 这组测试的关键动作是**把 JVM 默认时区改成纽约**再断言——如果哪天有人把 [BeijingClock] 改回
 * `TimeZone.getDefault()`，这里的断言会立刻红，而不是等到真机上深夜锁晚 8 小时才被发现。
 */
class BeijingClockTest {

    private lateinit var original: TimeZone

    @Before
    fun switchDeviceToNewYork() {
        original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun restoreDevice() {
        TimeZone.setDefault(original)
    }

    /** 2026-11-11T18:00Z = 北京时间 11-12 02:00 = 纽约 11-11 13:00。三个时区三个答案，当探针用。 */
    private val night = Instant.parse("2026-11-11T18:00:00Z").toEpochMilli()

    @Test
    fun the_zone_is_beijing_not_the_device_zone() {
        assertEquals("Asia/Shanghai", BeijingClock.ZONE.id)
        assertEquals(8 * 60 * 60 * 1000, BeijingClock.ZONE.rawOffset)
    }

    @Test
    fun minute_of_day_is_beijing_even_on_a_new_york_device() {
        // 北京时间 02:00。按设备时区会算成 13:00（780），深夜锁整个失效。
        assertEquals(2 * 60, BeijingClock.minuteOfDay(night))
    }

    @Test
    fun day_of_rolls_over_at_beijing_midnight_not_utc_midnight() {
        // 北京的 11-12；UTC 还停在 11-11 18:00。2026 年第 316 天。
        assertEquals(2026L * 1000 + 316, BeijingClock.dayOf(night))
    }

    @Test
    fun start_of_day_is_beijing_midnight() {
        val expected = Instant.parse("2026-11-11T16:00:00Z").toEpochMilli()
        assertEquals(expected, BeijingClock.startOfDay(night))
        // 用它当「今天」的下界：此刻必须落在窗口里
        assertEquals(true, BeijingClock.startOfDay(night) <= night)
    }

    @Test
    fun the_night_lock_window_does_not_shift_by_eight_hours() {
        // 真实用法：深夜锁 2 点开始。设备时区是纽约，判定必须还说「现在是 2 点」。
        val minute = BeijingClock.minuteOfDay(night)
        assertEquals(true, minute >= 2 * 60 && minute < 7 * 60)
    }

    @Test
    fun results_are_identical_whatever_the_device_zone_is() {
        val asNewYork = listOf(
            BeijingClock.minuteOfDay(night),
            BeijingClock.dayOf(night),
            BeijingClock.startOfDay(night),
        )
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val asUtc = listOf(
            BeijingClock.minuteOfDay(night),
            BeijingClock.dayOf(night),
            BeijingClock.startOfDay(night),
        )
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val asTokyo = listOf(
            BeijingClock.minuteOfDay(night),
            BeijingClock.dayOf(night),
            BeijingClock.startOfDay(night),
        )
        assertEquals(asNewYork, asUtc)
        assertEquals(asNewYork, asTokyo)
    }
}
