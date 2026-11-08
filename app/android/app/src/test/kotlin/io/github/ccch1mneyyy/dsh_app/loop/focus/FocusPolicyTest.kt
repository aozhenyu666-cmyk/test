package io.github.ccch1mneyyy.dsh_app.loop.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPolicyTest {
    private val own = "io.github.ccch1mneyyy.dsh_app"
    private val ime = setOf("com.google.android.inputmethod.latin")
    private val t0 = 1_800_000_000_000L

    private fun active(
        allowed: Set<String> = setOf("com.example.task"),
        pausedUntil: Long = 0L,
        endAt: Long = 0L,
        lastBlockAt: Long = 0L,
        lastBlockPkg: String = "",
    ) = FocusState(
        active = true,
        task = "第 3 题",
        taskPackage = "com.example.task",
        allowed = allowed,
        startedAt = t0 - 600_000,
        pausedUntil = pausedUntil,
        endAt = endAt,
        lastBlockAt = lastBlockAt,
        lastBlockPkg = lastBlockPkg,
    )

    @Test fun inactiveNeverBlocks() {
        assertEquals(FocusDecision.None, FocusPolicy.evaluate(FocusState(), "com.bilibili", t0, t0 - 60_000, own, ime))
    }

    @Test fun theTaskAppIsAllowed() {
        assertEquals(FocusDecision.None, FocusPolicy.evaluate(active(), "com.example.task", t0, t0 - 60_000, own, ime))
    }

    @Test fun dshPhoneAndImeAreAlwaysAllowed() {
        val s = active()
        assertEquals(FocusDecision.None, FocusPolicy.evaluate(s, own, t0, t0 - 60_000, own, ime))
        assertEquals(FocusDecision.None, FocusPolicy.evaluate(s, "com.android.dialer", t0, t0 - 60_000, own, ime))
        assertEquals(FocusDecision.None, FocusPolicy.evaluate(s, "com.android.systemui", t0, t0 - 60_000, own, ime))
        assertEquals(FocusDecision.None, FocusPolicy.evaluate(s, "com.google.android.inputmethod.latin", t0, t0 - 60_000, own, ime))
    }

    @Test fun aFreshDeviationWaitsForTheGraceWindow() {
        val s = active()
        assertEquals(FocusDecision.None, FocusPolicy.evaluate(s, "com.bilibili", t0, t0, own, ime))
    }

    @Test fun aPersistentDeviationBlocks() {
        val s = active()
        val d = FocusPolicy.evaluate(s, "com.bilibili", t0, t0 - FocusPolicy.GRACE_MS - 1_000, own, ime)
        assertTrue(d is FocusDecision.Block)
        assertEquals("com.bilibili", (d as FocusDecision.Block).packageName)
        assertEquals(6L, d.secondsOff)
    }

    @Test fun pausedOrExpiredNeverBlocks() {
        val paused = active(pausedUntil = t0 + 60_000)
        assertEquals(FocusDecision.None, FocusPolicy.evaluate(paused, "com.bilibili", t0, t0 - 60_000, own, ime))
        val ended = active(endAt = t0 - 1)
        assertEquals(FocusDecision.None, FocusPolicy.evaluate(ended, "com.bilibili", t0, t0 - 60_000, own, ime))
    }

    @Test fun theSameAppIsNotReBlockedInsideTheCooldown() {
        val s = active(lastBlockAt = t0 - 10_000, lastBlockPkg = "com.bilibili")
        assertEquals(FocusDecision.None, FocusPolicy.evaluate(s, "com.bilibili", t0, t0 - 60_000, own, ime))
    }

    @Test fun anotherAppIsStillBlockedInsideTheCooldown() {
        val s = active(lastBlockAt = t0 - 10_000, lastBlockPkg = "com.bilibili")
        val d = FocusPolicy.evaluate(s, "com.douyin", t0, t0 - 60_000, own, ime)
        assertTrue(d is FocusDecision.Block)
    }

    @Test fun stateSurvivesARoundTrip() {
        val s = active(pausedUntil = t0, endAt = t0 + 3_600_000).copy(deviations = 4, lastBlockPkg = "com.bilibili", lastBlockAt = t0)
        val again = FocusState.fromMap(s.toMap())
        assertEquals(s, again)
    }
}
