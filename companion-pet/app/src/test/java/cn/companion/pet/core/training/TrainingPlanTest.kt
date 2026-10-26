package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** S1 / A18 / A25：到点主动出题的本地决策与诚实记账。 */
class TrainingPlanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val p = TrainingPlanner()
    private val day = LocalDate.of(2026, 10, 26)
    private fun at(h: Int, m: Int, s: Int = 0) = day.atTime(h, m, s).atZone(zone).toInstant().toEpochMilli()
    private val times = listOf(LocalTime.of(20, 30))

    private fun ctx(now: Long, active: Boolean = true, overlay: Boolean = true, quiet: Boolean = false,
                    paused: Boolean = false, training: Boolean = false, busy: String? = null) =
        PlanContext(now, paused, quiet, active, overlay, training, busy, since = at(0, 0))

    private fun decide(ledger: PlanLedger, c: PlanContext) = p.decide(ledger, p.slots(c.now, times, zone), c)

    @Test
    fun parsesTimesLeniently() {
        assertEquals(listOf(LocalTime.of(8, 30), LocalTime.of(20, 0)), p.parseTimes("20:00，08:30, 25:99, abc, 20:00"))
    }

    @Test
    fun startsAtDueTimeAndRecordsDelay() {
        val d = decide(PlanLedger(), ctx(at(20, 30, 40)))
        assertTrue(d is PlanDecision.Start)
        assertEquals(40_000, (d as PlanDecision.Start).delayMillis)
        assertTrue(decide(PlanLedger(), ctx(at(20, 29))) is PlanDecision.None) // 还没到点
    }

    @Test
    fun handledSlotIsNotStartedTwice() {
        val now = at(20, 31)
        val d = decide(PlanLedger(), ctx(now)) as PlanDecision.Start
        val ledger = p.record(PlanLedger(), PlanRecord(d.slot.slotId, d.slot.dueAt, now, d.delayMillis, PlanOutcome.SHOWN))
        assertTrue(decide(ledger, ctx(now + 10_000)) is PlanDecision.None)
        // 重启后从落盘账本读回，也不重复。
        val store = PlanLedgerStore(tmp.root)
        assertTrue(store.save(ledger))
        assertTrue(decide(PlanLedgerStore(tmp.root).load(), ctx(now + 60_000)) is PlanDecision.None)
    }

    @Test
    fun resumesExistingTrainingInsteadOfNewQuestion() {
        val d = decide(PlanLedger(), ctx(at(20, 30), training = true)) as PlanDecision.Start
        assertTrue(d.resumeExisting)
    }

    @Test
    fun screenOffNotifiesOnceThenShowsWhenUserReturns() {
        val now = at(20, 31)
        val d = decide(PlanLedger(), ctx(now, active = false))
        assertTrue(d is PlanDecision.Notify)
        val n = d as PlanDecision.Notify
        // 通知只记 NOTIFIED（不等于看见），也不是终态。
        val ledger = p.record(PlanLedger(), PlanRecord(n.slot.slotId, n.slot.dueAt, now, n.delayMillis, PlanOutcome.NOTIFIED))
        assertTrue(decide(ledger, ctx(now + 60_000, active = false)) is PlanDecision.None) // 不重复通知
        val back = decide(ledger, ctx(at(20, 45)))
        assertTrue(back is PlanDecision.Start)
        assertEquals(15 * 60_000L, (back as PlanDecision.Start).delayMillis)
    }

    @Test
    fun beyondGraceIsRecordedMissedWithReason() {
        val now = at(20, 31)
        val n = decide(PlanLedger(), ctx(now, active = false)) as PlanDecision.Notify
        val ledger = p.record(PlanLedger(), PlanRecord(n.slot.slotId, n.slot.dueAt, now, n.delayMillis, PlanOutcome.NOTIFIED))
        val late = decide(ledger, ctx(at(21, 5)))
        assertTrue(late is PlanDecision.Record)
        val r = (late as PlanDecision.Record).record
        assertEquals(PlanOutcome.MISSED, r.outcome)
        assertTrue(r.reason.contains("通知"))
    }

    @Test
    fun processDeadAtDueTimeIsMissedNotReplayed() {
        // 服务晚了一小时才起来：不补出旧题，记 MISSED。
        val r = decide(PlanLedger(), ctx(at(21, 40))) as PlanDecision.Record
        assertEquals(PlanOutcome.MISSED, r.record.outcome)
        assertTrue(r.record.delayMillis >= 60 * 60_000L)
    }

    @Test
    fun timesBeforePlanWasEnabledAreIgnored() {
        val c = ctx(at(21, 40)).copy(since = at(21, 0))
        assertTrue(decide(PlanLedger(), c) is PlanDecision.None)
    }

    @Test
    fun quietAndPausedAreSkipped() {
        assertEquals(PlanOutcome.SKIPPED, (decide(PlanLedger(), ctx(at(20, 30), quiet = true)) as PlanDecision.Record).record.outcome)
        assertEquals(PlanOutcome.SKIPPED, (decide(PlanLedger(), ctx(at(20, 30), paused = true)) as PlanDecision.Record).record.outcome)
    }

    @Test
    fun busySessionDefersWithoutSpamming() {
        val now = at(20, 30)
        val d = decide(PlanLedger(), ctx(now, busy = "严格陪想进行中")) as PlanDecision.Record
        assertEquals(PlanOutcome.DEFERRED, d.record.outcome)
        val ledger = p.record(PlanLedger(), d.record)
        assertTrue(decide(ledger, ctx(now + 10_000, busy = "严格陪想进行中")) is PlanDecision.None)
        // 忙完了但还在退避期：不立刻弹；退避过后再开题。
        assertTrue(decide(ledger, ctx(now + 30_000)) is PlanDecision.None)
        assertTrue(decide(ledger, ctx(now + TrainingPlanner.RETRY_MILLIS + 1)) is PlanDecision.Start)
    }

    @Test
    fun twoPendingSlotsAreMergedNotDoubled() {
        val two = listOf(LocalTime.of(20, 0), LocalTime.of(20, 20))
        val now = at(20, 21)
        val d = p.decide(PlanLedger(), p.slots(now, two, zone), ctx(now))
        assertTrue(d is PlanDecision.Record)
        val r = (d as PlanDecision.Record).record
        assertEquals(PlanOutcome.SKIPPED, r.outcome)
        assertTrue(r.slotId.endsWith("20:00"))
        val next = p.decide(p.record(PlanLedger(), r), p.slots(now, two, zone), ctx(now))
        assertTrue(next is PlanDecision.Start)
        assertTrue((next as PlanDecision.Start).slot.slotId.endsWith("20:20"))
    }

    @Test
    fun nextDueRollsOverMidnight() {
        assertEquals(at(20, 30), p.nextDue(at(10, 0), times, zone))
        val tomorrow = day.plusDays(1).atTime(20, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(tomorrow, p.nextDue(at(21, 0), times, zone))
        assertEquals(null, p.nextDue(at(21, 0), emptyList(), zone))
    }

    @Test
    fun describeIsHonestAboutNotifications() {
        val ledger = p.record(PlanLedger(), PlanRecord("2026-10-26@20:30", at(20, 30), at(20, 31), 60_000, PlanOutcome.NOTIFIED, "屏幕关着"))
        val line = p.describe(ledger, zone).single()
        assertNotNull(line)
        assertTrue(line.contains("不代表看见"))
    }
}
