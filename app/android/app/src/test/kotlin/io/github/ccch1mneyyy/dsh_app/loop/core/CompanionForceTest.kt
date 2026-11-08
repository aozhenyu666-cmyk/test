package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 大肥鱼说"锁"：不看拦截自己的开关和额度，但守住白名单、已经挣到的放行、一次只锁一个。 */
class CompanionForceTest {
    private var wall = 1_800_000_000_000L
    private var seq = 0
    private val day = 20_000L
    private val game = "com.tencent.tmgp.sgame"

    private fun guard(cfg: InterceptConfig = InterceptConfig()) =
        InterceptGuard(MemorySnapshotStore(), InterceptLedgerBook(MemorySnapshotStore()).also { it.recover() }, { wall }) { "evt-" + (++seq) }
            .also { it.recover(wall); it.setConfig(cfg, wall) }

    private fun env(screenOn: Boolean = true, dshBusy: Boolean = false) =
        InterceptEnv(screenOn = screenOn, unlocked = true, dshBusy = dshBusy, minuteOfDay = 21 * 60, dayKey = day)

    @Test fun locksAnAppTheFeatureNeverListedEvenWhenTheFeatureIsOff() {
        val g = guard(InterceptConfig(enabled = false, dailyMax = 1))
        val d = g.force(game, "王者荣耀", env(), wall)
        assertTrue(d is InterceptDecision.Block)
        d as InterceptDecision.Block
        assertEquals(game, d.target.packageName)
        assertEquals("王者荣耀", d.target.label)
        assertEquals(InterceptGuard.REASON_COMPANION, d.reason)
        assertEquals(d.eventId, g.openEventId())
    }

    @Test fun oneLockAtATime() {
        val g = guard()
        g.force(game, null, env(), wall)
        val again = g.force(game, null, env(), wall + 1_000)
        assertEquals(InterceptDecision.Allow(game, InterceptRules.REASON_ALREADY_OPEN), again)
    }

    @Test fun theWhitelistAndAnEarnedReleaseStillHold() {
        assertEquals(
            InterceptDecision.Allow(game, InterceptRules.REASON_WHITELIST),
            guard(InterceptConfig(whitelist = setOf(game))).force(game, null, env(), wall),
        )
        val g = guard()
        val block = g.force(game, null, env(), wall) as InterceptDecision.Block
        val drill = g.drill()!!
        for (v in if (drill.ascending) drill.candidates.sorted() else drill.candidates.sortedDescending()) g.tapDrill(block.eventId, v)
        wall += 2 * 60_000
        assertTrue(g.onReleased(block.eventId, InterceptGuard.RELEASE_TRAINING, wall))
        assertEquals(InterceptDecision.Allow(game, InterceptRules.REASON_RELEASED), g.force(game, null, env(), wall + 60_000))
        assertTrue("the release runs out, the lock comes back", g.force(game, null, env(), wall + 11 * 60_000) is InterceptDecision.Block)
    }

    @Test fun neverCoversADarkScreenOrDshAtWork() {
        val g = guard()
        assertEquals(InterceptDecision.Allow(game, InterceptRules.REASON_SCREEN_OFF), g.force(game, null, env(screenOn = false), wall))
        assertEquals(InterceptDecision.Allow(game, InterceptRules.REASON_DSH_BUSY), g.force(game, null, env(dshBusy = true), wall))
    }
}
