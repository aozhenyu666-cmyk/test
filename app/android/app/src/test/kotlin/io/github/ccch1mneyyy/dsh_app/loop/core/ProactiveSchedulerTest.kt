package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** T6. The local zone is UTC+8 here; the scheduler only sees it through minuteOfDay / dayOf. */
class ProactiveSchedulerTest {
    private val zone = 8 * 3_600_000L
    private val min = 60_000L
    private val hour = 60 * min
    /** 2026-10-30 10:00 local. */
    private var wall = 1_793_325_600_000L - zone + 10 * hour - (1_793_325_600_000L % 86_400_000L)
    private var elapsed = 5 * hour
    private var boot = 1
    private val store = MemorySnapshotStore()
    private var seq = 0
    private fun now() = Clocks(wall, elapsed, boot)
    private fun advance(ms: Long) { wall += ms; elapsed += ms }
    private fun minuteOfDay(w: Long) = (((w + zone) % 86_400_000L) / min).toInt()
    private fun dayOf(w: Long) = Math.floorDiv(w + zone, 86_400_000L)

    private val cfg = SchedulerConfig(
        enabled = true, minIntervalMs = 30 * min, maxIntervalMs = 90 * min, quietStartMinute = 23 * 60, quietEndMinute = 8 * 60,
        dailyMax = 4, userActiveDeferMs = 20 * min, ignoredBackoff = 2.0, maxBackoffFactor = 8.0, pauseAfterIgnored = 3,
    )

    private fun scheduler(seed: Int = 7, s: SnapshotStore = store) =
        ProactiveScheduler(s, ::minuteOfDay, ::dayOf, Random(seed), { "sched-${++seq}" })

    private fun armed(seed: Int = 7): ProactiveScheduler = scheduler(seed).also { it.recover(now()); it.setConfig(cfg, now()) }

    /** Advance to the due time and return the attempt. */
    private fun ProactiveScheduler.fireNext(): EventRequest {
        val w = evaluate(now())
        if (w is ProactiveScheduler.Decision.Wait) advance(w.untilWall - wall)
        val d = evaluate(now())
        assertTrue("expected Fire, got $d", d is ProactiveScheduler.Decision.Fire)
        return (d as ProactiveScheduler.Decision.Fire).request.also { onSubmitted(it.eventId, now()) }
    }

    private fun gap(s: ProactiveScheduler) = s.state.nextDueElapsed - elapsed

    @Test fun firstGapIsRandomWithinTheConfiguredRangeAndTheDecisionIsDeterministic() {
        val gaps = (1..20).map { seed -> scheduler(seed, MemorySnapshotStore()).also { it.recover(now()); it.setConfig(cfg, now()) }.let { gap(it) } }
        assertTrue(gaps.all { it in 30 * min..90 * min })
        assertTrue("random, not constant", gaps.toSet().size > 10)
        val s = armed()
        val a = s.evaluate(now())
        assertEquals(a, s.evaluate(now()))
        assertEquals("not_due", (a as ProactiveScheduler.Decision.Wait).reason)
    }

    @Test fun anAttemptIsAProactiveEventForTheCoordinator() {
        val r = armed().fireNext()
        assertEquals("SCHEDULER", r.source)
        assertEquals("scheduler:random", r.dedupeKey)
        assertTrue(r.needsAgent)
        assertNull("no canned text: without the agent it fails rather than fakes", r.fallbackText)
    }

    @Test fun pendingAttemptBlocksAnotherAndOnlyItsReceiptCounts() {
        val s = armed()
        val r = s.fireNext()
        advance(10 * hour)
        assertEquals("pending", (s.evaluate(now()) as ProactiveScheduler.Decision.Wait).reason)
        assertFalse("someone else's receipt", s.onOutcome("other", Engagement.RESPONDED, now()))
        assertTrue(s.onOutcome(r.eventId, Engagement.RESPONDED, now()))
        assertFalse("duplicate receipt", s.onOutcome(r.eventId, Engagement.RESPONDED, now()))
        assertEquals(1, s.state.countToday)
    }

    /** P2: a 409 "not this time" ends the attempt without counting it or rushing the next one. */
    @Test fun aSkippedAttemptIsOverWithoutCountingOrBackoff() {
        val s = armed()
        val r = s.fireNext()
        val due = s.state.nextDueElapsed
        s.onSkipped(r.eventId, "agent-busy", now())
        assertNull("no longer pending", s.state.pendingEventId)
        assertEquals("not delivered, so not counted", 0, s.state.countToday)
        assertEquals(0, s.state.notDeliveredStreak)
        assertEquals(0, s.state.ignoredStreak)
        assertEquals("skipped:agent-busy", s.state.lastReason)
        assertEquals("no retrySoon: the companion's next /plan moves the alarm", due, s.state.nextDueElapsed)
    }

    @Test fun quietHoursPushAttemptsToTheMorning() {        val s = armed()
        advance(13 * hour) // 23:00
        assertEquals("quiet", (s.evaluate(now()) as ProactiveScheduler.Decision.Wait).reason)
        val until = (s.evaluate(now()) as ProactiveScheduler.Decision.Wait).untilWall
        assertEquals(8 * 60, minuteOfDay(until))
        // A plan that would land at night is moved past the quiet end.
        val r = s.fireNext()
        assertTrue(minuteOfDay(wall) >= 8 * 60)
        s.onOutcome(r.eventId, Engagement.RESPONDED, now())
    }

    @Test fun dailyMaxCountsDeliveriesNotAttempts() {
        val s = armed()
        repeat(2) { s.onOutcome(s.fireNext().eventId, Engagement.NOT_DELIVERED, now()) }
        assertEquals("undelivered attempts do not count", 0, s.state.countToday)
        var guard = 0
        while (s.state.countToday < 4 && guard++ < 20) {
            val w = s.evaluate(now())
            if (w is ProactiveScheduler.Decision.Wait && w.reason == "quiet") { advance(w.untilWall - wall); continue }
            s.onOutcome(s.fireNext().eventId, Engagement.UNCONFIRMED, now())
        }
        val d = s.evaluate(now())
        if (minuteOfDay(wall) < 23 * 60 && dayOf(wall) == s.state.dayKey) {
            assertEquals("daily_max", (d as ProactiveScheduler.Decision.Wait).reason)
            assertEquals(8 * 60, minuteOfDay(d.untilWall))
        }
    }

    @Test fun userActivityResetsTheStreakAndKeepsOutOfTheWay() {
        val s = armed()
        val r = s.fireNext()
        s.onOutcome(r.eventId, Engagement.IGNORED_SEEN, now())
        assertEquals(1, s.state.ignoredStreak)
        advance(5 * hour)
        s.onUserActivity(now())
        assertEquals(0, s.state.ignoredStreak)
        assertTrue(gap(s) >= 20 * min)
        assertEquals("not_due", (s.evaluate(now()) as ProactiveScheduler.Decision.Wait).reason)
        advance(gap(s))
        assertTrue(s.evaluate(now()) is ProactiveScheduler.Decision.Fire)
    }

    // 9. Two confirmed deliveries in a row with no answer
    @Test fun ignoredMessagesWidenTheGapAndThenPause() {
        val s = armed(seed = 3)
        s.onOutcome(s.fireNext().eventId, Engagement.IGNORED_SEEN, now())
        s.onOutcome(s.fireNext().eventId, Engagement.IGNORED_SEEN, now())
        assertEquals(2, s.state.ignoredStreak)
        assertTrue("×4 after two ignored: ${gap(s) / min} min", gap(s) >= 4 * 30 * min)
        assertTrue(gap(s) <= 4 * 90 * min + 8 * hour + 30 * min)
        s.onOutcome(s.fireNext().eventId, Engagement.IGNORED_SEEN, now())
        assertTrue(s.state.pausedForIgnored)
        advance(3 * 86_400_000L)
        assertEquals("paused_ignored", (s.evaluate(now()) as ProactiveScheduler.Decision.Wait).reason)
        s.onUserActivity(now())
        assertFalse(s.state.pausedForIgnored)
    }

    // 8. A notification posted but never confirmed seen
    @Test fun unconfirmedDeliveryIsNeitherPenalisedNorRewarded() {
        val s = armed()
        s.onOutcome(s.fireNext().eventId, Engagement.IGNORED_SEEN, now())
        s.onOutcome(s.fireNext().eventId, Engagement.UNCONFIRMED, now())
        assertEquals(1, s.state.ignoredStreak)
        assertEquals(2, s.state.countToday)
    }

    @Test fun undeliveredAttemptsRetrySoonButNotForever() {
        val s = armed()
        s.onOutcome(s.fireNext().eventId, Engagement.NOT_DELIVERED, now())
        assertTrue(gap(s) in 10 * min..15 * min)
        s.onOutcome(s.fireNext().eventId, Engagement.NOT_DELIVERED, now())
        s.onOutcome(s.fireNext().eventId, Engagement.NOT_DELIVERED, now())
        assertTrue("back to the normal gap after repeated failures", gap(s) >= 30 * min)
    }

    // 7. Clock changes
    @Test fun movingTheWallClockNeitherRushesNorDelaysAnAttemptWithinOneBoot() {
        val s = armed()
        val g = gap(s)
        wall += 6 * hour // set forward
        assertEquals("not_due", (s.evaluate(now()) as ProactiveScheduler.Decision.Wait).reason)
        wall -= 12 * hour // set back
        elapsed += g
        val d = s.evaluate(now())
        assertTrue("due by elapsed time, quiet hours by the new wall time: $d",
            d is ProactiveScheduler.Decision.Fire || (d as ProactiveScheduler.Decision.Wait).reason == "quiet")
    }

    @Test fun settingTheDateBackDoesNotResetTheDailyCount() {
        val s = armed()
        repeat(2) { s.onOutcome(s.fireNext().eventId, Engagement.UNCONFIRMED, now()) }
        wall -= 2 * 86_400_000L
        s.evaluate(now())
        assertEquals(2, s.state.countToday)
    }

    // 6. Process restart and reboot
    @Test fun restartKeepsThePlanAndRebootFallsBackToTheWallClock() {
        val s = armed()
        val due = s.state.nextDueWall
        val restarted = scheduler()
        restarted.recover(now())
        assertEquals(due, restarted.state.nextDueWall)
        assertEquals(s.state, restarted.state)
        // Reboot: elapsed restarts near zero; the wall time still holds.
        boot = 2; elapsed = 60_000
        val afterBoot = scheduler()
        afterBoot.recover(now())
        assertEquals(due, afterBoot.state.nextDueWall)
        advance(due - wall)
        assertTrue(afterBoot.evaluate(now()) is ProactiveScheduler.Decision.Fire || minuteOfDay(wall) < 8 * 60 || minuteOfDay(wall) >= 23 * 60)
    }

    @Test fun rebootWithAJumpedClockOrALongOffPeriodReplans() {
        val s = armed()
        val due = s.state.nextDueWall
        boot = 2; elapsed = 60_000; wall -= 30 * 86_400_000L
        val jumped = scheduler(); jumped.recover(now())
        assertEquals("clock_jump", jumped.state.lastReason)
        assertTrue(jumped.state.nextDueWall < due)
        wall += 60 * 86_400_000L; boot = 3
        val off = scheduler(); off.recover(now())
        assertEquals("missed_while_off", off.state.lastReason)
        assertTrue(off.state.nextDueWall > wall)
    }

    @Test fun aLostPendingAttemptIsReleasedOnRecovery() {
        val s = armed()
        s.fireNext()
        advance(2 * hour)
        val r = scheduler(); r.recover(now())
        assertNull(r.state.pendingEventId)
        assertEquals("pending_lost", r.state.lastReason)
    }

    // 11. Switched off
    @Test fun disabledSchedulerNeverFiresAndForgetsItsPendingAttempt() {
        val s = armed()
        s.fireNext()
        s.setConfig(cfg.copy(enabled = false), now())
        assertNull(s.state.pendingEventId)
        advance(10 * hour)
        assertEquals("disabled", (s.evaluate(now()) as ProactiveScheduler.Decision.Wait).reason)
        assertNull(s.nextWake(now(), WakeMode.EXACT))
    }

    @Test fun wakeModesDegradeSafely() {
        assertEquals(WakeMode.EXACT, WakeMode.of(BackgroundCaps()))
        assertEquals(WakeMode.INEXACT, WakeMode.of(BackgroundCaps(exactAlarms = false)))
        assertEquals(WakeMode.OPPORTUNISTIC, WakeMode.of(BackgroundCaps(backgroundRestricted = true)))
        assertEquals(WakeMode.OPPORTUNISTIC, WakeMode.of(BackgroundCaps(alarmsAvailable = false)))
        val s = armed()
        assertNull("no alarm when the system forbids background work", s.nextWake(now(), WakeMode.OPPORTUNISTIC))
        assertEquals(s.state.nextDueWall, s.nextWake(now(), WakeMode.INEXACT))
    }

    @Test fun configIsNormalizedAndPersisted() {
        val n = SchedulerConfig(enabled = true, minIntervalMs = 1, maxIntervalMs = 0, dailyMax = -3, quietStartMinute = 5000).normalized()
        assertEquals(60_000L, n.minIntervalMs)
        assertEquals(60_000L, n.maxIntervalMs)
        assertEquals(0, n.dailyMax)
        assertEquals(1439, n.quietStartMinute)
        armed()
        assertEquals(cfg, scheduler().also { it.recover(now()) }.config)
    }
}
