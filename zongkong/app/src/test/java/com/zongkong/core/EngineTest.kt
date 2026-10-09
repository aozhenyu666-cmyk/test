package com.zongkong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    @Test
    fun afterDeadlineGateOnlyBlocksOnceOverdue() {
        val c = config(gate(deadline = 10 * 60))
        val before = Engine.evaluate(c, DayLog("2026-10-09"), at(9), ZONE)
        assertFalse(before.strict)
        assertEquals(GatePhase.OPEN, before.gates[0].phase)

        val dueSoon = Engine.evaluate(c, DayLog("2026-10-09"), at(9, 45), ZONE)
        assertEquals(GatePhase.DUE_SOON, dueSoon.gates[0].phase)
        assertFalse(dueSoon.strict)

        val after = Engine.evaluate(c, DayLog("2026-10-09"), at(10, 1), ZONE)
        assertTrue(after.strict)
        assertEquals(GatePhase.OVERDUE, after.gates[0].phase)
        assertTrue(after.reasons.single() is Reason.GateDue)
    }

    @Test
    fun fromOpenGateBlocksUntilDone() {
        val c = config(gate(open = 6 * 60, deadline = 10 * 60, block = BlockMode.FROM_OPEN))
        assertFalse(Engine.evaluate(c, DayLog("2026-10-09"), at(5), ZONE).strict)
        assertTrue(Engine.evaluate(c, DayLog("2026-10-09"), at(7), ZONE).strict)

        val done = DayLog("2026-10-09", submissions = listOf(Submission("g", at(7), "部署", passVerdict())))
        val s = Engine.evaluate(c, done, at(7, 5), ZONE)
        assertFalse(s.strict)
        assertEquals(GatePhase.DONE, s.gates[0].phase)
    }

    @Test
    fun failedSubmissionDoesNotCount() {
        val c = config(gate(deadline = 10 * 60))
        val day = DayLog("2026-10-09", submissions = listOf(Submission("g", at(9), "x", Verdict(false, "AI", "不行"))))
        val s = Engine.evaluate(c, day, at(11), ZONE)
        assertTrue(s.strict)
        assertEquals(1, s.gates[0].attempts)
    }

    @Test
    fun lateSubmissionClearsStrictAndIsMarkedLate() {
        val c = config(gate(deadline = 10 * 60))
        val st = Engine.evaluate(c, DayLog("2026-10-09"), at(11), ZONE)
        val day = DayOps.submit(DayLog("2026-10-09"), st.gates[0], "补交的内容", passVerdict(), at(11))
        val s = Engine.evaluate(c, day, at(11, 1), ZONE)
        assertFalse(s.strict)
        assertEquals(GatePhase.DONE_LATE, s.gates[0].phase)
        assertEquals("迟交通过", day.outbox.single().result)
    }

    @Test
    fun gateOnlyAppliesOnItsDays() {
        // 2026-10-09 是周五（5）
        val c = config(gate(deadline = 10 * 60, days = setOf(7)))
        val s = Engine.evaluate(c, DayLog("2026-10-09"), at(12), ZONE)
        assertTrue(s.gates.isEmpty())
        assertFalse(s.strict)
    }

    @Test
    fun lateNightBelongsToPreviousDay() {
        // 截止 23:00 的关卡，凌晨 1 点仍然是“昨天”的超时关卡
        val c = config(gate(deadline = 23 * 60))
        val oneAm = at(1, 0, java.time.LocalDate.of(2026, 10, 10))
        val s = Engine.evaluate(c, DayLog("2026-10-09"), oneAm, ZONE)
        assertEquals("2026-10-09", s.date)
        assertTrue(s.strict)
        // 凌晨 4 点换日后，新的一天关卡还没到点
        val s2 = Engine.evaluate(c, DayLog("2026-10-10"), at(4, 30, java.time.LocalDate.of(2026, 10, 10)), ZONE)
        assertEquals("2026-10-10", s2.date)
        assertFalse(s2.strict)
    }

    @Test
    fun deadlineAfterMidnightCountsAsSameNight() {
        val c = config(gate(open = 22 * 60, deadline = 60)) // 22:00 – 次日 01:00
        val s = Engine.evaluate(c, DayLog("2026-10-09"), at(23, 30), ZONE)
        assertFalse(s.strict)
        assertEquals(GatePhase.OPEN, s.gates[0].phase)
        val late = Engine.evaluate(c, DayLog("2026-10-09"), at(1, 30, java.time.LocalDate.of(2026, 10, 10)), ZONE)
        assertTrue(late.strict)
    }

    @Test
    fun silenceTriggersAfterHoursWithoutReport() {
        val c = config(silenceHours = 3).copy(activeStart = 8 * 60, activeEnd = 23 * 60)
        assertFalse(Engine.evaluate(c, DayLog("2026-10-09"), at(10, 59), ZONE).strict)
        val s = Engine.evaluate(c, DayLog("2026-10-09"), at(11, 0), ZONE)
        assertTrue(s.strict)
        assertTrue(s.reasons.single() is Reason.Silence)

        val reported = DayOps.checkin(DayLog("2026-10-09"), "在做行测", at(10, 30))
        val s2 = Engine.evaluate(c, reported, at(12), ZONE)
        assertFalse(s2.strict)
        assertEquals(at(13, 30), s2.silenceDueAt)
    }

    @Test
    fun silenceIgnoredOutsideActiveHours() {
        val c = config(silenceHours = 1).copy(activeStart = 8 * 60, activeEnd = 23 * 60)
        val s = Engine.evaluate(c, DayLog("2026-10-09"), at(23, 30), ZONE)
        assertFalse(s.strict)
        assertNull(s.silenceDueAt)
    }

    @Test
    fun quotaExceededIsStrict() {
        val c = config(quota = 60)
        val day = DayOps.addPlay(DayLog("2026-10-09"), "tv.danmaku.bili", 59 * 60)
        assertFalse(Engine.evaluate(c, day, at(12), ZONE).strict)
        val more = DayOps.addPlay(day, "com.ss.android.ugc.aweme", 60)
        val s = Engine.evaluate(c, more, at(12), ZONE)
        assertTrue(s.strict)
        assertTrue(s.reasons.single() is Reason.Quota)
    }

    @Test
    fun emergencyPassSuspendsStrictForItsDuration() {
        val c = config(gate(deadline = 10 * 60)).copy(emergencyPerDay = 1, emergencyMinutes = 10)
        val day = DayLog("2026-10-09")
        val st = Engine.evaluate(c, day, at(11), ZONE)
        val (tooShort, msg) = DayOps.emergency(day, c, st, "要用", at(11))
        assertNull(tooShort)
        assertTrue(msg.contains("20"))

        val (granted, _) = DayOps.emergency(day, c, st, "导师在 B 站发了明天要用的课程录屏，必须现在下载看一下", at(11))
        val during = Engine.evaluate(c, granted!!, at(11, 5), ZONE)
        assertFalse(during.strict)
        assertEquals(0, during.emergencyLeft)
        assertTrue(Engine.evaluate(c, granted, at(11, 11), ZONE).strict)

        val (again, msg2) = DayOps.emergency(granted, c, Engine.evaluate(c, granted, at(11, 11), ZONE), "导师在 B 站发了明天要用的课程录屏，必须现在下载看一下", at(11, 11))
        assertNull(again)
        assertTrue(msg2.contains("用完"))
    }

    @Test
    fun pausedNeverStrict() {
        val c = config(gate(deadline = 10 * 60)).copy(pausedUntil = at(23))
        val s = Engine.evaluate(c, DayLog("2026-10-09"), at(11), ZONE)
        assertFalse(s.strict)
        assertTrue(s.paused)
    }

    @Test
    fun defaultGatesEvaluate() {
        val s = Engine.evaluate(Config(), DayLog("2026-10-09"), at(8), ZONE)
        // 周五：没有周部署
        assertEquals(5, s.gates.size)
        assertTrue(s.strict) // 晨间部署开放即拦截
        assertEquals("plan_morning", (s.reasons.first() as Reason.GateDue).status.gate.id)
        assertTrue(Engine.summary(s).contains("晨间部署"))
    }
}
