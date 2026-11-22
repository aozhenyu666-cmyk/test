package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowFeedTest {
    @Test
    fun tracksAppAndMinutes() {
        NowFeed.reset()
        NowFeed.onApp("app.study", 1_000L)
        val oneMinute = NowFeed.snapshot(61_000L)
        assertEquals("app.study", oneMinute.app)
        assertEquals(1, oneMinute.appMinutes)
        assertTrue(oneMinute.screenOn)
    }

    @Test
    fun switchingAppRestartsTheClock() {
        NowFeed.reset()
        NowFeed.onApp("app.study", 1_000L)
        NowFeed.onApp("app.wechat", 70_000L)
        val snapshot = NowFeed.snapshot(80_000L)
        assertEquals("app.wechat", snapshot.app)
        assertEquals(0, snapshot.appMinutes)
        assertEquals(70_000L, snapshot.since)
    }

    @Test
    fun screenOffClearsTheForegroundApp() {
        NowFeed.reset()
        NowFeed.onApp("app.bilibili", 1_000L)
        NowFeed.onScreen(false, 2_000L)
        val off = NowFeed.snapshot(3_000L)
        assertNull(off.app)
        assertFalse(off.screenOn)
        assertEquals(0, off.appMinutes)

        NowFeed.onScreen(true, 4_000L)
        val on = NowFeed.snapshot(5_000L)
        assertNull(on.app)
        assertTrue(on.screenOn)
    }

    @Test
    fun ignoresSystemUiAndIme() {
        NowFeed.reset()
        NowFeed.onApp("com.android.settings", 1_000L)
        NowFeed.onApp("com.android.systemui", 2_000L)
        NowFeed.onApp("com.google.android.inputmethod.latin", 3_000L)
        NowFeed.onApp("com.android.permissioncontroller", 4_000L)

        val snapshot = NowFeed.snapshot(61_000L)
        assertEquals("com.android.settings", snapshot.app)
        assertEquals(1_000L, snapshot.since)
        assertEquals(1, snapshot.appMinutes)
    }

    @Test
    fun chargingIsTracked() {
        NowFeed.reset()
        NowFeed.onCharging(true, 1_000L)
        assertTrue(NowFeed.snapshot(2_000L).charging)
        NowFeed.onCharging(false, 3_000L)
        assertFalse(NowFeed.snapshot(4_000L).charging)
    }

    @Test
    fun todayAddsUpEachAppAcrossSwitchesAndScreenOff() {
        NowFeed.reset()
        NowFeed.onApp("tv.danmaku.bili", 0L)
        NowFeed.onApp("com.tencent.mm", 10 * 60_000L)
        NowFeed.onApp("tv.danmaku.bili", 15 * 60_000L)
        NowFeed.onScreen(false, 20 * 60_000L)
        NowFeed.onScreen(true, 60 * 60_000L)
        NowFeed.onApp("com.xingin.xhs", 60 * 60_000L)

        val top = NowFeed.today(63 * 60_000L, day = 1L)
        assertEquals(listOf("tv.danmaku.bili" to 15, "com.tencent.mm" to 5, "com.xingin.xhs" to 3), top)
    }

    @Test
    fun aNewDayStartsFromZeroButKeepsTheRunningApp() {
        NowFeed.reset()
        val t0 = 1_000L
        NowFeed.onApp("tv.danmaku.bili", t0)
        assertEquals(listOf("tv.danmaku.bili" to 30), NowFeed.today(t0 + 30 * 60_000L, day = 1L))
        val next = NowFeed.today(t0 + 40 * 60_000L, day = 2L)
        assertTrue(next.isEmpty())
        assertEquals(listOf("tv.danmaku.bili" to 5), NowFeed.today(t0 + 45 * 60_000L, day = 2L))
        assertEquals("dwell is not cut by midnight", 45, NowFeed.snapshot(t0 + 45 * 60_000L).appMinutes)
    }
}
