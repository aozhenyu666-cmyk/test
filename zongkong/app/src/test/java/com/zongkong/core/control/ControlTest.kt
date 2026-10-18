package com.zongkong.core.control

import com.zongkong.core.*
import com.zongkong.core.boss.Dossier
import com.zongkong.core.hq.*
import com.zongkong.core.plan.DayReport
import com.zongkong.core.plan.PlanBook
import com.zongkong.core.work.Work
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class ControlTest {
    private val now = at(12)
    private val date = DayClock.dateKey(now, ZONE)
    private val cfg = TakeoverConfig(enabled = true)
    private val episode = TakeoverEpisode(now, now + 30 * 60_000L, 20, "回应主线进度")
    private fun book() = ControlBook(episode = episode)
    private fun deferred(kind: DeferralKind = DeferralKind.GATE, target: String = "g", base: ControlBook = ControlBook()) =
        Control.defer(base, date, kind, target, "日终清单", now, "休息一下", gate()).getOrThrow()

    @Test fun samplingStaysInRangeAndCannotBeResetByAnotherPrompt() {
        repeat(100) { seed ->
            val b = Control.offer(ControlBook(), cfg, now, "第一次", Random(seed))
            val e = b.episode!!
            assertTrue(e.dueAt in (now + 30 * 60_000L)..(now + 60 * 60_000L))
            assertTrue(e.minutes in 15..30)
            assertEquals(b, Control.offer(b, cfg, now + 60_000, "重发", Random(seed + 1)))
        }
    }

    @Test fun restartPreservesDeadlineQuotaAndOriginalGate() {
        val b = deferred(base = book())
        val restored = Json.decodeFromString<ControlBook>(Json.encodeToString(b))
        assertEquals(b, restored)
        assertEquals(0, Control.passesLeft(restored, date))
        assertEquals(gate(), restored.deferrals.single().gate)
        assertEquals(b, Control.offer(restored, cfg, now + 60_000, "重启后又提醒", Random(8)))
    }

    @Test fun graceDoesNotErasePendingTakeoverAndDoesNotResampleDuration() {
        val b = Control.defer(book(), date, DeferralKind.GATE, "g", "任务", now + 20 * 60_000, "", gate()).getOrThrow()
        assertEquals(now + 50 * 60_000, b.episode!!.dueAt)
        assertEquals(episode.minutes, b.episode!!.minutes)
        assertEquals(ControlDecision.NONE, Control.decide(b, cfg, ControlSignal(now + 40 * 60_000, 0)))
        assertEquals(ControlDecision.WARN, Control.decide(b, cfg, ControlSignal(now + 50 * 60_000, 0)))
    }

    @Test fun noticeMustBeDeliveredBeforeEnforcingEvenWhenAlarmWasLate() {
        val late = episode.dueAt + 10 * 60_000
        assertEquals(ControlDecision.NONE, Control.decide(book(), cfg, ControlSignal(now, 0)))
        assertEquals(ControlDecision.WARN, Control.decide(book(), cfg, ControlSignal(late, 0)))
        val warned = book().copy(episode = episode.copy(warnedAt = late))
        assertEquals(ControlDecision.NONE, Control.decide(warned, cfg, ControlSignal(late + 59_999, 0)))
        assertEquals(ControlDecision.START, Control.decide(warned, cfg, ControlSignal(late + 60_000, 0)))
    }

    @Test fun fiveMinuteNoticeDoesNotShortenTheWait() {
        val warning = episode.dueAt - 5 * 60_000
        assertEquals(ControlDecision.NONE, Control.decide(book(), cfg, ControlSignal(warning - 1, 0)))
        assertEquals(ControlDecision.WARN, Control.decide(book(), cfg, ControlSignal(warning, 0)))
        val b = book().copy(episode = episode.copy(warnedAt = warning))
        assertEquals(ControlDecision.NONE, Control.decide(b, cfg, ControlSignal(episode.dueAt - 1, 0)))
        assertEquals(ControlDecision.START, Control.decide(b, cfg, ControlSignal(episode.dueAt, 0)))
    }

    @Test fun actualResponseQuietHoursPauseAndOtherSessionCancelTheWait() {
        val signal = ControlSignal(episode.dueAt, 0)
        listOf(signal.copy(lastContact = now + 1), signal.copy(quiet = true), signal.copy(paused = true),
            signal.copy(active = false), signal.copy(hqActive = true)).forEach {
            assertEquals(ControlDecision.CLEAR, Control.decide(book(), cfg, it))
        }
    }

    @Test fun unhealthyAccessibilityNeverStartsTakeover() {
        val b = book().copy(episode = episode.copy(warnedAt = now))
        val s = ControlSignal(episode.dueAt, 0, guardHealthy = false)
        assertEquals(ControlDecision.UNAVAILABLE, Control.decide(b, cfg, s))
        val notified = b.copy(episode = b.episode!!.copy(unavailableNotified = true))
        assertEquals(ControlDecision.NONE, Control.decide(notified, cfg, s))
        assertEquals(ControlDecision.START, Control.decide(notified, cfg, s.copy(guardHealthy = true)))
    }

    @Test fun missingNotificationsNeverStartsTakeover() {
        val b = book().copy(episode = episode.copy(warnedAt = now))
        assertEquals(ControlDecision.NONE, Control.decide(b, cfg, ControlSignal(episode.dueAt, 0, canNotify = false)))
    }

    @Test fun dailyCapAndCooldownClearPendingEscalation() {
        assertEquals(ControlDecision.CLEAR, Control.decide(book(), cfg, ControlSignal(now, 0, roundsToday = 2)))
        assertEquals(ControlDecision.CLEAR, Control.decide(book(), cfg, ControlSignal(now, 0, cooldownUntil = now + 1)))
    }

    @Test fun disabledOrInvalidConfigurationNeverOffersTakeover() {
        assertNull(Control.offer(ControlBook(), cfg.copy(enabled = false), now, "", Random(1)).episode)
        assertNull(Control.offer(ControlBook(), cfg.copy(waitMin = 0), now, "", Random(1)).episode)
        assertEquals(ControlDecision.CLEAR, Control.decide(book(), cfg.copy(enabled = false), ControlSignal(now, 0)))
    }

    @Test fun onlyOneSharedPassAndResolvingDoesNotRefundIt() {
        val b = deferred()
        for (kind in DeferralKind.entries) assertTrue(Control.defer(b, date, kind, "other", "其他任务", now, "").isFailure)
        val resolved = Control.resolve(b, setOf(b.deferrals.single().id), now + 1)
        assertEquals(0, Control.passesLeft(resolved, date))
        assertNull(Control.graceUntil(resolved, now + 1))
        assertEquals(1, Control.passesLeft(resolved, "2026-10-10"))
    }

    @Test fun graceAffectsOnlyChosenGateAndExpiresAtTheExactBoundary() {
        val c = config(gate(), gate("other"))
        val b = deferred()
        val log = DayLog(date)
        val status = Engine.evaluate(c, log, now, ZONE, control = b)
        assertFalse(status.gates.first { it.gate.id == "g" }.blocking)
        assertFalse(status.gates.first { it.gate.id == "g" }.done)
        assertTrue(status.gates.first { it.gate.id == "other" }.blocking)
        assertTrue(status.strict)
        val expired = Engine.evaluate(c, log, b.deferrals.single().until, ZONE, control = b)
        assertTrue(expired.gates.all { it.blocking })
        assertEquals(0, expired.doneCount)
    }

    @Test fun deferralDoesNotRemoveQuotaFocusOrAbsenceRestrictions() {
        val c = config(gate(), quota = 10)
        val log = DayLog(date, playSeconds = mapOf("app" to 600L))
        val status = Engine.evaluate(c, log, now, ZONE, FocusLock("学习", now + 60_000),
            absence = Hq.Absence.STRICT, control = deferred())
        assertTrue(status.reasons.any { it is Reason.Quota })
        assertTrue(status.reasons.any { it is Reason.Focus })
        assertTrue(status.reasons.any { it is Reason.Absent })
        assertFalse(status.reasons.any { it is Reason.GateDue })
        assertTrue(status.strict)
    }

    @Test fun originalDateSubmissionCannotPassTodaysSameNamedGate() {
        val oldDate = LocalDate.parse(date)
        val tomorrow = at(12, date = oldDate.plusDays(1))
        val c = config(gate(days = setOf(oldDate.dayOfWeek.value)))
        val old = Engine.evaluate(c, DayLog(date), tomorrow, ZONE, dateOverride = date)
        assertEquals(GatePhase.OVERDUE, old.gates.single().phase)
        val passed = DayOps.submit(DayLog(date), old.gates.single(), "迟交的真实内容", passVerdict(), tomorrow)
        assertTrue(passed.passed("g")!!.late)
        val today = Engine.evaluate(config(gate()), DayLog(oldDate.plusDays(1).toString()), tomorrow, ZONE)
        assertFalse(today.gates.single().done)
    }

    @Test fun hqGracePausesOnlyTheSessionAndPreservesRemainingTime() {
        val s = Hq.start("h", HqTrigger.UNANSWERED, HqConfig(), now - 60_000, 20)
        val until = now + Control.GRACE_MINUTES * 60_000L
        val paused = Hq.suspend(s, now, until)
        assertEquals(s.endsAt + 30 * 60_000, paused.endsAt)
        assertEquals(s.stepStartedAt + 30 * 60_000, paused.stepStartedAt)
        assertFalse(Hq.canAdvance(paused, now, true))
        assertFalse(Engine.evaluate(config(), DayLog(date), now, ZONE, session = paused).strict)
        assertTrue(Engine.evaluate(config(), DayLog(date), until, ZONE, session = paused).strict)
        assertFalse(paused.early)
        assertTrue(paused.active)
    }

    @Test fun resumingEarlyCountsOnlyActualPauseAndCannotExtendAgain() {
        val s = Hq.start("h", HqTrigger.SCHEDULED, HqConfig(), now, 30)
        val paused = Hq.suspend(s, now + 60_000, now + 31 * 60_000)
        assertEquals(paused, Hq.suspend(paused, now + 120_000, now + 60 * 60_000))
        val resumed = Hq.resume(paused, now + 11 * 60_000)
        assertEquals(s.endsAt + 10 * 60_000, resumed.endsAt)
        assertEquals(s.stepStartedAt + 10 * 60_000, resumed.stepStartedAt)
        assertEquals(0L, resumed.suspendedUntil)
        assertEquals(resumed, Hq.resume(resumed, now + 12 * 60_000))
    }

    @Test fun forcedSessionCanSealBeforeMaximumSoTimerCannotAlwaysBeatCompletion() {
        val s = Hq.start("h", HqTrigger.UNANSWERED, HqConfig(), now, 30)
            .copy(step = HqStep.SEAL.ordinal, stepStartedAt = now + 20 * 60_000)
        assertFalse(Hq.canFinish(s, now + 20 * 60_000 + 19_999))
        assertTrue(Hq.canFinish(s, now + 20 * 60_000 + 20_000))
        assertFalse(Hq.canFinish(s.copy(trigger = HqTrigger.SCHEDULED), now + 21 * 60_000))
    }

    @Test fun forcedSessionCannotKeepBlockingPastItsMaximumEvenBeforeCleanupRuns() {
        val s = Hq.start("h", HqTrigger.UNANSWERED, HqConfig(), now, 20)
        assertTrue(Engine.evaluate(config(), DayLog(date), s.endsAt - 1, ZONE, session = s).strict)
        assertFalse(Engine.evaluate(config(), DayLog(date), s.endsAt, ZONE, session = s).strict)
        assertFalse(Engine.evaluate(config(), DayLog(date), s.endsAt + 60_000, ZONE, session = s).strict)
        // 只结束这轮接管，其他关卡照常生效。
        assertTrue(Engine.evaluate(config(gate()), DayLog(date), s.endsAt, ZONE, session = s).strict)
    }

    @Test fun unfinishedForcedRoundDoesNotSilentlyReplaceTheNightlyReview() {
        val start = at(20)
        val s = Hq.start("h", HqTrigger.UNANSWERED, HqConfig(), start, 20)
        val ended = Hq.exitEarly(s, s.endsAt, "本轮到时，仍有未完成项")
        assertFalse(Hq.doneToday(HqConfig(), listOf(ended), at(21, 36), ZONE))
        assertEquals(Hq.Due.OVERDUE, Hq.due(HqConfig(), listOf(ended), at(21, 36), ZONE))
        val completed = Hq.finish(s, s.endsAt, "已完成所有环节")
        assertTrue(Hq.doneToday(HqConfig(), listOf(completed), at(21, 36), ZONE))
    }

    @Test fun summaryIncludesAllGatesAndUnresolvedPastDayDeferrals() {
        val tomorrow = at(22, date = LocalDate.parse(date).plusDays(1))
        val g = gate("hq_review").copy(title = "每晚总结报告")
        val r = DayReport.build(Work(), PlanBook(), DayLog("2026-10-10"), emptyList(), Dossier(), null,
            tomorrow, ZONE, config = config(g), control = deferred())
        assertFalse(r.empty)
        assertEquals("每晚总结报告", r.gates.single().first)
        val text = DayReport.text(r)
        assertTrue(text, text.contains("每晚总结报告"))
        assertTrue(text, text.contains("2026-10-09 · 日终清单"))
        assertTrue(text, text.contains("待补交"))
        assertFalse(text, text.contains("已补交"))
    }

    @Test fun enablingAndTighteningAreImmediateButWeakeningWaits24Hours() {
        val enabled = Policy.setTakeover(config(), cfg, now).config
        assertEquals(cfg, enabled.takeover)
        val tightened = Policy.setTakeover(enabled, cfg.copy(waitMax = 45), now).config
        assertEquals(45, tightened.takeover.waitMax)
        val pending = Policy.setTakeover(tightened, cfg.copy(enabled = false), now).config
        assertTrue(pending.takeover.enabled)
        assertTrue(pending.pending.single().op is ChangeOp.Takeover)
        assertTrue(Policy.applyDue(pending, now + Policy.DELAY_MILLIS - 1).takeover.enabled)
        assertFalse(Policy.applyDue(pending, now + Policy.DELAY_MILLIS).takeover.enabled)
        assertEquals(enabled, Policy.setTakeover(enabled, cfg.copy(durationMax = 100), now).config)
    }

    @Test fun oldSavedSettingsAndSessionsLoadWithSafeDefaults() {
        assertFalse(Json.decodeFromString<Config>("{}").takeover.enabled)
        val session = Json.decodeFromString<HqSession>("""{"id":"h","trigger":"MANUAL","startedAt":1,"minutes":30}""")
        assertEquals(0L, session.suspendedUntil)
        assertEquals(1 + 30 * 60_000L, session.endsAt)
    }
}
