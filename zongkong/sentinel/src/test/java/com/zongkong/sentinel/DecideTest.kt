package com.zongkong.sentinel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecideTest {
    @Test fun confirmedFaultOverridesFreshLegacyHeartbeatButHonorsRelease() {
        val state = Decide.State(now, setOf(tiktok), strict = true, freeUntil = 0)
        assertTrue(Decide.shouldBlock(state, now, tiktok, true, confirmedFault = true))
        assertFalse(Decide.shouldBlock(state, now, tiktok, true, released = true, confirmedFault = true))
        assertFalse(Decide.shouldBlock(state.copy(freeUntil = now + 1000), now, tiktok, true, confirmedFault = true))
        assertFalse(Decide.shouldBlock(state, now, "com.android.settings", true, confirmedFault = true))
    }
    private val now = 1_800_000_000_000L
    private val tiktok = "com.ss.android.ugc.aweme"

    @Test
    fun aliveMainMeansHandsOff() {
        val s = Decide.State(now - 10_000, setOf(tiktok), strict = true, freeUntil = 0)
        assertTrue(Decide.mainAlive(s, now))
        assertFalse(Decide.shouldBlock(s, now, tiktok, mainInstalled = true))
        assertNull(Decide.downFor(s, now))
    }

    @Test
    fun staleOrOfflineMainBlocksListedAppsOnly() {
        val stale = Decide.State(now - 120_000, setOf(tiktok), strict = false, freeUntil = 0)
        assertTrue(Decide.shouldBlock(stale, now, tiktok, mainInstalled = true))
        assertFalse(Decide.shouldBlock(stale, now, "com.android.settings", mainInstalled = true))
        assertEquals(120_000L, Decide.downFor(stale, now))
        val offline = stale.copy(at = 0)
        assertTrue(Decide.shouldBlock(offline, now, tiktok, mainInstalled = true))
        assertEquals(0L, Decide.downFor(offline, now))
    }

    @Test
    fun vacationAndMissingMainDoNotBlock() {
        val s = Decide.State(0, setOf(tiktok), strict = false, freeUntil = now + 60_000)
        assertFalse(Decide.shouldBlock(s, now, tiktok, mainInstalled = true))
        assertFalse(Decide.shouldBlock(s.copy(freeUntil = 0), now, tiktok, mainInstalled = false))
    }

    @Test
    fun neverConnectedWaitsButEmptyListFallsBackToDefaults() {
        assertFalse(Decide.shouldBlock(null, now, tiktok, mainInstalled = true))
        assertTrue(Decide.shouldBlock(Decide.State(0, emptySet(), false, 0), now, "tv.danmaku.bili", mainInstalled = true))
    }

    @Test
    fun healAtMostEveryTwoMinutes() {
        assertTrue(Decide.shouldHeal(0, now))
        assertFalse(Decide.shouldHeal(now - 60_000, now))
    }
}
