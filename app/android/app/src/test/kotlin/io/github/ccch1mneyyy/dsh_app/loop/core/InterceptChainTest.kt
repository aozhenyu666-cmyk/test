package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 4, task 3: the entertainment-app intercept chain, end to end in pure logic.
 *
 *   verified foreground → InterceptRules.decide → InterceptGuard (one open interception)
 *   → the page is provably shown → 训练通过 / 授权规则 / 白名单 / 暂停 / 紧急退出
 *   → one receipt in 行为拦截账, never twice for the same eventId
 *
 * The blocking page itself (a new Activity, opened with the same decor-attached/shown/laid-out/
 * focused proof ProactivePageActivity uses) and the accessibility reading that feeds it cannot run
 * on the JVM; what the kernel promises — who is intercepted, who is let through, what is recorded —
 * is asserted here.
 */
class InterceptChainTest {
    private var wall = 1_793_340_000_000L
    private var seq = 0
    private val zone = 8 * 3_600_000L
    private val minute = 60_000L
    private var day = 20_000L

    private val bili = "tv.danmaku.bili"
    private val douyin = "com.ss.android.ugc.aweme"

    private fun minuteOfDay(w: Long) = (((w + zone) % 86_400_000L) / minute).toInt()

    private fun book(store: SnapshotStore = MemorySnapshotStore()) = InterceptLedgerBook(store).also { it.recover() }

    private fun guard(
        book: InterceptLedgerBook = book(),
        store: SnapshotStore = MemorySnapshotStore(),
    ) = InterceptGuard(store, book, { wall }) { "evt-" + (++seq) }.also { it.recover(wall) }

    private fun InterceptGuard.armed(cfg: InterceptConfig = InterceptConfig(enabled = true)) = also {
        it.setConfig(cfg, wall)
    }

    private fun env(minuteOfDayValue: Int = 12 * 60) =
        InterceptEnv(screenOn = true, unlocked = true, dshBusy = false, minuteOfDay = minuteOfDayValue, dayKey = day)

    /**
     * Solve the 训练题 the guard minted for [eventId].
     *
     * Round 4's page passed on a countdown; from the hardening round a training pass is only
     * granted when the drill says every candidate was pressed in the required order. Tests that are
     * about the chain *after* training call this instead of pretending the page can grant itself.
     */
    private fun solveDrill(g: InterceptGuard, eventId: String) {
        val drill = g.drill() ?: error("no drill for the open interception")
        val order = if (drill.ascending) drill.candidates.sorted() else drill.candidates.sortedDescending()
        for (v in order) assertTrue("a correct press is accepted", g.tapDrill(eventId, v))
        assertTrue("the drill is solved", g.drill()!!.solved)
    }

    // ---- 0. one interception at a time, whatever the app -------------------------------------

    @Test
    fun openingAnotherTargetWhileOneIsOpenIsNotASecondInterception() {
        val ledger = book()
        val g = guard(ledger)
        g.setConfig(InterceptConfig(enabled = true), wall)
        val first = g.onForeground(bili, env(), wall) as InterceptDecision.Block

        // The person opens Douyin while the B站 page is still up. A second page must not open, the
        // cooldown and today's count must not be spent, and — the real defect this pins — the first
        // event must not lose its eventId and end up with no receipt at all.
        val second = g.onForeground(douyin, env(), wall + 1_000L)
        assertTrue("no second page", second is InterceptDecision.Allow)
        assertEquals(InterceptRules.REASON_ANOTHER_OPEN, (second as InterceptDecision.Allow).reason)
        assertEquals("the first interception is still the open one", first.eventId, g.openEventId())
        assertEquals("nothing extra was counted", 1, g.state.countToday)

        g.onPageShown(first.eventId, wall + 2_000L)
        solveDrill(g, first.eventId)
        assertTrue(g.onReleased(first.eventId, InterceptGuard.RELEASE_TRAINING, wall + 3_000L))
        assertEquals("exactly one receipt, and it is the first event", first.eventId, ledger.state.receipts.single().eventId)
    }

    @Test
    fun theSameAppStillOpenIsNotASecondInterception() {
        val g = guard()
        g.setConfig(InterceptConfig(enabled = true), wall)
        val first = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        val again = g.onForeground(bili, env(), wall + 1_000L)
        assertEquals(
            InterceptDecision.Allow(bili, InterceptRules.REASON_ALREADY_OPEN),
            again,
        )
        assertEquals(first.eventId, g.openEventId())
    }

    @Test
    fun anUnrelatedAppIsIgnoredEvenWhileAnInterceptionIsOpen() {
        val g = guard()
        g.setConfig(InterceptConfig(enabled = true), wall)
        g.onForeground(bili, env(), wall)
        // Regression: the "one at a time" short-circuit used to answer Allow for *any* package,
        // claiming a decision about an app this feature never configured. A non-target is Ignore.
        assertTrue(g.onForeground("com.tencent.mm", env(), wall + 1_000L) is InterceptDecision.Ignore)
        assertTrue(g.onForeground(null, env(), wall + 2_000L) is InterceptDecision.Ignore)
    }

    // ---- 1. what is a target ---------------------------------------------------------------

    @Test fun onlyConfiguredPackagesAreIntercepted() {
        val g = guard().armed()
        assertTrue("a configured target blocks", g.onForeground(bili, env(), wall) is InterceptDecision.Block)
        assertTrue("an unrelated app is none of our business", g.onForeground("com.tencent.mm", env(), wall) is InterceptDecision.Ignore)
        assertTrue("null is nothing to do", g.onForeground(null, env(), wall) is InterceptDecision.Ignore)
    }

    @Test fun theTargetListIsEditableAndNothingElseIsHardCoded() {
        val g = guard().armed(InterceptConfig(enabled = true, targets = listOf(InterceptTarget("com.example.game", "游戏"))))
        assertTrue(g.onForeground("com.example.game", env(), wall) is InterceptDecision.Block)
        assertTrue("removing a target stops it immediately", g.onForeground(bili, env(), wall) is InterceptDecision.Ignore)
        assertEquals(listOf("com.example.game"), g.config.targets.map { it.packageName })
    }

    @Test fun aDisabledFeatureLetsEverythingThrough() {
        val g = guard().armed(InterceptConfig(enabled = false))
        assertEquals(InterceptDecision.Allow(bili, InterceptRules.REASON_DISABLED), g.onForeground(bili, env(), wall))
    }

    // ---- 2. the full block → release → receipt chain ----------------------------------------

    @Test fun aBlockIsShownThenReleasedByTrainingAndRecordedOnce() {
        val ledger = book()
        val g = guard(ledger)
        g.setConfig(InterceptConfig(enabled = true), wall)
        val d = g.onForeground(bili, env(), wall)
        assertTrue("a rule hit opens the blocking page", d is InterceptDecision.Block)
        val block = d as InterceptDecision.Block
        assertEquals(bili, block.target.packageName)
        assertEquals("哔哩哔哩", block.target.label)
        assertNotNull("the eventId is minted by the producer", block.eventId)
        assertEquals(1, ledger.state.triggerCount)
        assertEquals("triggered is not yet shown", 0, ledger.state.blockedCount)

        assertTrue("the page proved it was on screen", g.onPageShown(block.eventId, wall + 500L))
        assertEquals(1, ledger.state.blockedCount)
        solveDrill(g, block.eventId)
        assertTrue(g.onReleased(block.eventId, InterceptGuard.RELEASE_TRAINING, wall + 61_000L))

        val r = ledger.state.receipts.single()
        assertEquals(block.eventId, r.eventId)
        assertEquals(bili, r.packageName)
        assertEquals(InterceptOutcome.RELEASED, r.outcome)
        assertEquals("training", r.releasedBy)
        assertEquals("a release expires, it is not a standing permission", wall + 61_000L + g.config.releaseMs, r.releasedUntilWall)
        assertEquals(1, ledger.state.userReleasedCount)
        assertNull("nothing is open any more", g.openEventId())
    }

    @Test fun aReleaseLetsTheSameAppThroughUntilItExpires() {
        val g = guard()
        g.setConfig(InterceptConfig(enabled = true, releaseMs = 10 * minute), wall)
        val block = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        g.onPageShown(block.eventId, wall)
        // A release now has to be earned: the drill the guard minted must be solved first.
        solveDrill(g, block.eventId)
        g.onReleased(block.eventId, InterceptGuard.RELEASE_TRAINING, wall)

        wall += 5 * minute
        assertEquals(
            InterceptDecision.Allow(bili, InterceptRules.REASON_RELEASED),
            g.onForeground(bili, env(), wall),
        )
        wall += 10 * minute // the release ran out
        day--
        assertTrue("the passage is temporary, as promised", g.onForeground(bili, env(), wall) is InterceptDecision.Block)
    }

    // ---- 3. the three exits the task requires -----------------------------------------------

    @Test fun theEmergencyExitClosesTheBlockAndIsRecordedAsAnExit() {
        val ledger = book()
        val g = guard(ledger)
        g.setConfig(InterceptConfig(enabled = true), wall)
        val block = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        g.onPageShown(block.eventId, wall)
        assertTrue(g.onExited(block.eventId, wall + 1_000L))
        val r = ledger.state.receipts.single()
        assertEquals(InterceptOutcome.EXITED, r.outcome)
        assertEquals("emergency_exit", r.reason)
        assertEquals(1, ledger.state.userExitedCount)
        assertEquals("an exit is not a release", 0, ledger.state.userReleasedCount)
    }

    @Test fun pausingLetsEveryTargetThroughForTheWindow() {
        val ledger = book()
        val g = guard(ledger)
        g.setConfig(InterceptConfig(enabled = true), wall)
        val block = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        g.onPageShown(block.eventId, wall)
        assertTrue(g.onPaused(block.eventId, 30 * minute, wall + 1_000L))
        assertEquals(InterceptOutcome.PAUSED, ledger.state.receipts.single().outcome)
        assertEquals(1, ledger.state.pausedCount)

        wall += 5 * minute
        assertEquals(InterceptDecision.Allow(douyin, InterceptRules.REASON_PAUSED), g.onForeground(douyin, env(), wall))
        wall += 60 * minute
        assertTrue("the pause ran out", g.onForeground(douyin, env(), wall) is InterceptDecision.Block)
    }

    @Test fun theWhitelistIsAStandingDecisionNotATimer() {
        val g = guard()
        g.setConfig(InterceptConfig(enabled = true, whitelist = setOf(douyin)), wall)
        assertEquals(InterceptDecision.Allow(douyin, InterceptRules.REASON_WHITELIST), g.onForeground(douyin, env(), wall))
        // Added from the blocking page, it keeps holding after every timer would have expired.
        val block = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        g.onPageShown(block.eventId, wall)
        g.onReleased(block.eventId, InterceptGuard.RELEASE_WHITELIST, wall)
        assertTrue(bili in g.config.whitelist)
        wall += 30 * 86_400_000L
        day += 30_000L
        assertEquals(InterceptDecision.Allow(bili, InterceptRules.REASON_WHITELIST), g.onForeground(bili, env(), wall))
    }

    @Test fun anAuthorizationWindowLetsTheTargetThroughWithoutTraining() {
        val g = guard()
        // 12:00–13:00 local is a window the person authorized.
        g.setConfig(InterceptConfig(enabled = true, authorizedWindows = listOf(12 * 60..13 * 60)), wall)
        assertEquals(
            InterceptDecision.Allow(bili, InterceptRules.REASON_AUTHORIZED_WINDOW),
            g.onForeground(bili, env(12 * 60 + 30), wall),
        )
        assertTrue("outside the window the rule applies again", g.onForeground(bili, env(14 * 60), wall) is InterceptDecision.Block)
    }

    // ---- 4. it never fights DSH or a locked phone --------------------------------------------

    @Test fun dshBusyOrALockedPhoneIsNeverCoveredByABlockPage() {
        val g = guard().armed()
        assertEquals(
            InterceptDecision.Allow(bili, InterceptRules.REASON_DSH_BUSY),
            g.onForeground(bili, env().copy(dshBusy = true), wall),
        )
        assertEquals(
            InterceptDecision.Allow(bili, InterceptRules.REASON_LOCKED),
            g.onForeground(bili, env().copy(unlocked = false), wall),
        )
        assertEquals(
            InterceptDecision.Allow(bili, InterceptRules.REASON_SCREEN_OFF),
            g.onForeground(bili, env().copy(screenOn = false), wall),
        )
        assertEquals(0, g.receipts().size)
    }

    // ---- 5. no storms: cooldown, daily cap, one at a time ------------------------------------

    @Test fun oneInterceptionAtATimeAndNoStormAfterAnExit() {
        val ledger = book()
        val g = guard(ledger)
        g.setConfig(InterceptConfig(enabled = true, cooldownMs = 2 * minute), wall)
        val block = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        assertEquals(
            InterceptDecision.Allow(bili, "already_open"),
            g.onForeground(bili, env(), wall),
        )
        g.onPageShown(block.eventId, wall)
        g.onExited(block.eventId, wall + 1_000L)
        // The same app, seconds later: the cooldown holds, so no second page.
        assertEquals(
            InterceptDecision.Allow(bili, InterceptRules.REASON_COOLDOWN),
            g.onForeground(bili, env(), wall + 30_000L),
        )
        assertEquals(1, ledger.state.triggerCount)
        wall += 5 * minute
        assertTrue(g.onForeground(bili, env(), wall) is InterceptDecision.Block)
    }

    @Test fun theDailyCapStopsTheInterceptionsForThatDay() {
        val g = guard()
        g.setConfig(InterceptConfig(enabled = true, dailyMax = 1, cooldownMs = 0), wall)
        val block = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        g.onPageShown(block.eventId, wall)
        g.onReleased(block.eventId, InterceptGuard.RELEASE_RULE, wall)
        wall += minute
        assertEquals(
            InterceptDecision.Allow(douyin, InterceptRules.REASON_DAILY_MAX),
            g.onForeground(douyin, env(), wall),
        )
        day += 1
        assertTrue("a new local day gets a new budget", g.onForeground(douyin, env(), wall) is InterceptDecision.Block)
    }

    @Test fun aPageThatNeverComesUpIsNotRecordedAsABlock() {
        val ledger = book()
        val g = guard(ledger)
        g.setConfig(InterceptConfig(enabled = true), wall)
        val block = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        assertTrue(g.onUnavailable(block.eventId, "start_failed", wall + 500L))
        assertEquals(1, ledger.state.triggerCount)
        assertEquals(0, ledger.state.blockedCount)
        assertEquals(InterceptOutcome.NOT_SHOWN, ledger.state.receipts.single().outcome)
        assertNull("the slot is free again at once", g.openEventId())
    }

    // ---- 6. staleness and duplicates ---------------------------------------------------------

    @Test fun aReportForAnotherOrClosedInterceptionChangesNothing() {
        val ledger = book()
        val g = guard(ledger)
        g.setConfig(InterceptConfig(enabled = true), wall)
        val block = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        assertFalse("a forged eventId", g.onPageShown("forged", wall))
        assertFalse(g.onReleased("forged", InterceptGuard.RELEASE_TRAINING, wall))
        assertFalse(g.onExited("forged", wall))
        g.onPageShown(block.eventId, wall)
        assertTrue(g.onExited(block.eventId, wall + 1_000L))
        // The page reports again after closing: refused, nothing recounted.
        assertFalse(g.onExited(block.eventId, wall + 2_000L))
        assertFalse(g.onPageShown(block.eventId, wall + 2_000L))
        assertEquals(1, ledger.state.triggerCount)
        assertEquals(1, ledger.state.blockedCount)
        assertEquals(1, ledger.state.userExitedCount)
        assertEquals(1, ledger.state.receipts.size)
    }

    @Test fun oneEventIdProducesExactlyOneReceiptEvenIfEveryButtonIsPressed() {
        val ledger = book()
        val g = guard(ledger)
        g.setConfig(InterceptConfig(enabled = true), wall)
        val block = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        g.onPageShown(block.eventId, wall)
        solveDrill(g, block.eventId)
        assertTrue(g.onReleased(block.eventId, InterceptGuard.RELEASE_TRAINING, wall))
        assertFalse(g.onExited(block.eventId, wall))
        assertFalse(g.onPaused(block.eventId, minute, wall))
        assertFalse(g.onUnavailable(block.eventId, "x", wall))
        assertEquals(1, ledger.state.receipts.size)
        assertEquals(InterceptOutcome.RELEASED, ledger.state.receipts.single().outcome)
    }

    // ---- 7. crash recovery -------------------------------------------------------------------

    @Test fun anInterceptionOnScreenWhenTheProcessDiedIsRecordedOnRecovery() {
        val ledgerStore = MemorySnapshotStore()
        val guardStore = MemorySnapshotStore()
        val ledger = book(ledgerStore)
        val first = guard(ledger, guardStore)
        first.setConfig(InterceptConfig(enabled = true), wall)
        val block = first.onForeground(bili, env(), wall) as InterceptDecision.Block
        first.onPageShown(block.eventId, wall)

        wall += 5 * minute
        val secondLedger = book(ledgerStore)
        val second = guard(secondLedger, guardStore)
        assertEquals("the open interception did not survive, the receipt did", null, second.openEventId())
        val r = secondLedger.state.receipts.single()
        assertEquals(block.eventId, r.eventId)
        assertEquals(InterceptOutcome.BLOCKED, r.outcome)
        assertEquals("process_restart", r.reason)
        assertEquals(1, secondLedger.state.triggerCount)
        assertEquals(1, secondLedger.state.blockedCount)
        // A new interception can start again, and the counters keep counting.
        assertTrue(second.onForeground(douyin, env(), wall) is InterceptDecision.Block)
        assertEquals(2, secondLedger.state.triggerCount)
    }

    @Test fun theConfigAndTheDailyCountSurviveARestart() {
        val guardStore = MemorySnapshotStore()
        val ledgerStore = MemorySnapshotStore()
        val first = guard(book(ledgerStore), guardStore)
        first.setConfig(InterceptConfig(enabled = true, dailyMax = 3, whitelist = setOf("com.example.ok")), wall)
        (first.onForeground(bili, env(), wall) as InterceptDecision.Block).let { first.onExited(it.eventId, wall + 1_000L) }

        val second = guard(book(ledgerStore), guardStore)
        assertEquals(setOf("com.example.ok"), second.config.whitelist)
        assertEquals(3, second.config.dailyMax)
        assertEquals("the day's count came back too", 1, second.state.countToday)
    }

    @Test fun aRestartReplaysNoCountersForAnEventAlreadyRecorded() {
        val ledgerStore = MemorySnapshotStore()
        val ledger = book(ledgerStore)
        val g = guard(ledger, MemorySnapshotStore())
        g.setConfig(InterceptConfig(enabled = true), wall)
        val block = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        g.onPageShown(block.eventId, wall)
        g.onExited(block.eventId, wall)

        val replay = book(ledgerStore)
        assertFalse(replay.onTriggered(block.eventId, wall))
        assertFalse(replay.onBlocked(block.eventId, wall))
        assertFalse(replay.onEnded(InterceptReceipt(
            eventId = block.eventId, packageName = bili, triggeredAt = wall, endedAt = wall,
            outcome = InterceptOutcome.RELEASED,
        )))
        assertEquals(1, replay.state.triggerCount)
        assertEquals(1, replay.state.userExitedCount)
        assertEquals(0, replay.state.userReleasedCount)
    }

    // ---- 8. the config file itself -----------------------------------------------------------

    @Test fun theConfigRoundTripsAndIsNormalized() {
        val cfg = InterceptConfig(
            enabled = true,
            targets = listOf(InterceptTarget("  tv.danmaku.bili  ", " 哔哩哔哩 "), InterceptTarget("", "x"), InterceptTarget("tv.danmaku.bili", "dup")),
            whitelist = setOf("  ", "com.example.ok"),
            dailyMax = -5, releaseMs = -1, trainingMs = 1,
            authorizedWindows = listOf(-1..10, 12 * 60..13 * 60),
        ).normalized()
        assertEquals(listOf("tv.danmaku.bili"), cfg.targets.map { it.packageName })
        assertEquals("哔哩哔哩", cfg.targets.single().label)
        assertEquals(setOf("com.example.ok"), cfg.whitelist)
        assertEquals(0, cfg.dailyMax)
        assertEquals(0L, cfg.releaseMs)
        assertEquals(5_000L, cfg.trainingMs)
        assertEquals(listOf(12 * 60..13 * 60), cfg.authorizedWindows)

        val back = InterceptConfig.fromMap(cfg.toMap())
        assertEquals(cfg, back)
        // An empty target list in the file means "none", not "the defaults".
        assertEquals(emptyList<InterceptTarget>(), InterceptConfig.fromMap(mapOf("targets" to emptyList<Any?>())).targets)
        assertEquals(InterceptConfig.DEFAULT_TARGETS, InterceptConfig.fromMap(emptyMap<String, Any?>()).targets)
    }

    @Test fun removingATargetClosesThePageItWasShowing() {
        val ledger = book()
        val g = guard(ledger)
        g.setConfig(InterceptConfig(enabled = true), wall)
        val block = g.onForeground(bili, env(), wall) as InterceptDecision.Block
        g.onPageShown(block.eventId, wall)
        g.setConfig(InterceptConfig(enabled = true, targets = listOf(InterceptTarget(douyin, "抖音"))), wall)
        assertNull(g.openEventId())
        assertEquals("target_removed", ledger.state.receipts.single().reason)
    }
}
