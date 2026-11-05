package com.yishou.app.act

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StallTest {
    private val base = StallSignals(msSinceAsk = 120_000, opened = true, hour = 15)

    @Test fun selfReportWinsAndIsTrusted() {
        val r = Stall.read(base.copy(tapped = StallKind.TIRED, wentToDistraction = true))!!
        assertEquals(StallKind.TIRED, r.kind)
        assertTrue(r.sure)
        assertEquals(StallMove.REST, Stall.move(r, 0))
    }

    @Test fun tooEarlyIsNotAStall() = assertNull(Stall.read(base.copy(msSinceAsk = 30_000)))

    @Test fun signalsOnlyGiveMaybeAndThenAsk() {
        val stuck = Stall.read(base.copy(typedChars = 20, deletes = 4))!!
        assertEquals(StallKind.STUCK, stuck.kind)
        assertFalse(stuck.sure)
        assertEquals(StallMove.ASK, Stall.move(stuck, 0))
        assertEquals(StallKind.UNSEEN, Stall.read(base.copy(opened = false))!!.kind)
        assertEquals(StallMove.REMIND, Stall.move(Stall.read(base.copy(opened = false))!!, 0))
        assertEquals(StallKind.TIRED, Stall.read(base.copy(hour = 23))!!.kind)
        assertEquals(StallKind.TIRED, Stall.read(base.copy(workedMinutes = 100))!!.kind)
        assertEquals(StallKind.UNKNOWN, Stall.read(base)!!.kind)
    }

    @Test fun repeatedDistractionIsAvoidingAndGetsACommitment() {
        val r = Stall.read(base.copy(wentToDistraction = true, recentDodges = 3))!!
        assertEquals(StallKind.AVOIDING, r.kind)
        assertTrue(r.sure)
        assertEquals(StallMove.COMMIT, Stall.move(r, 0))
    }

    @Test fun neverChaseMoreThanThreeTimes() {
        val r = Stall.read(base.copy(tapped = StallKind.CONFUSED))!!
        assertEquals(StallMove.REPHRASE, Stall.move(r, 0))
        assertEquals(StallMove.COMMIT, Stall.move(r, 2))
    }
}

class NextActionTest {
    @Test fun parsesIfThenSentence() {
        val a = NextActions.parse("吃完晚饭坐到桌前，我就打开粉笔做言语第3套前5题（15分钟）")!!
        assertEquals("吃完晚饭坐到桌前", a.cue)
        assertEquals("打开粉笔做言语第3套前5题", a.action)
        assertEquals(15, a.minutes)
        assertNull(NextActions.check(a))
    }

    @Test fun vagueActionsAreSentBack() {
        assertNotNull(NextActions.check(NextAction("晚上8点", "复习行测", 15)))
        assertNotNull(NextActions.check(NextAction("晚上8点", "认真学习", 15)))
        assertNotNull(NextActions.check(NextAction("有空的时候", "做言语第3套前5题", 15)))
        assertNotNull(NextActions.check(NextAction("晚上8点", "做言语第3套前5题", 90)))
        assertNull(NextActions.check(NextAction("", "给第一家公司投简历并附作品集", 20, at = 1L)))
    }

    @Test fun completionCountsPartialAsHalf() {
        val a = NextAction("8点", "做言语第3套前5题", 15)
        val rs = listOf(
            ActionRecord(a, 10, Outcome.DONE), ActionRecord(a, 20, Outcome.PARTIAL),
            ActionRecord(a, 30, Outcome.MISSED), ActionRecord(a, 40, null),
        )
        assertEquals(0.5, NextActions.completion(rs, 0, 100)!!, 1e-9)
        assertEquals(1, NextActions.due(rs, 40 + 15 * 60_000L).size)
    }
}

class FinalReviewTest {
    private val passage = "研究发现，常喝咖啡的人平均寿命更长。但这些人的收入也普遍更高，医疗条件更好。"
    private val options = listOf("收入高同时带来喝咖啡和长寿", "咖啡含有抗氧化物质", "有人不喝咖啡也长寿", "咖啡价格上涨")

    @Test fun bothStepsDone() {
        val c = FinalCheck(0, "这些人的收入也普遍更高", 2, "有人不喝咖啡也长寿只是个例，削弱不了平均")
        assertTrue(FinalReview.problems(passage, options, c).isEmpty())
    }

    @Test fun evidenceMustBeFromThePassage() {
        val c = FinalCheck(0, "有钱人才长寿", 2, "有人不喝咖啡也长寿只是个例，削弱不了平均")
        assertEquals(1, FinalReview.problems(passage, options, c).size)
    }

    @Test fun runnerUpReasonMustTouchThatOption() {
        assertEquals(1, FinalReview.problems(passage, options, FinalCheck(0, "这些人的收入也普遍更高", 2, "不全面")).size)
        assertEquals(1, FinalReview.problems(passage, options, FinalCheck(0, "这些人的收入也普遍更高", 2, "我觉得这个说法跟题目关系不太大")).size)
        assertEquals(1, FinalReview.problems(passage, options, FinalCheck(0, "这些人的收入也普遍更高", 0, "一样的")).size)
    }

    @Test fun rushIsOnlyRecorded() {
        assertTrue(FinalReview.rushed(3_000, null))
        assertFalse(FinalReview.rushed(3_000, FinalCheck(0, "这些人的收入也普遍更高", 2, "")))
        assertFalse(FinalReview.rushed(20_000, null))
    }
}

class WeeklyTest {
    @Test fun summarizesOnlyRealWorldNumbers() {
        val day = 24 * 60 * 60_000L
        val a = NextAction("8点", "做言语第3套前5题", 15)
        val prev = Weekly.summarize(0, Weekly.WEEK_MS,
            listOf(Attempt("言语", false, 90_000, day, false, true), Attempt("言语", true, 60_000, day, false, true)),
            listOf(ActionRecord(a, day, Outcome.MISSED)), listOf(FocusLog(day, 30)))
        val cur = Weekly.summarize(Weekly.WEEK_MS, 2 * Weekly.WEEK_MS,
            listOf(
                Attempt("言语", true, 70_000, Weekly.WEEK_MS + day, true, false),
                Attempt("言语", true, 80_000, Weekly.WEEK_MS + day, true, false),
                Attempt("言语", false, 20_000, Weekly.WEEK_MS + day, false, true),
            ),
            listOf(ActionRecord(a, Weekly.WEEK_MS + day, Outcome.DONE), ActionRecord(a, Weekly.WEEK_MS + day, Outcome.PARTIAL)),
            listOf(FocusLog(Weekly.WEEK_MS + day, 50), FocusLog(Weekly.WEEK_MS + 2 * day, 40)))
        assertEquals(90, cur.focusMinutes)
        assertEquals(0.75, cur.actionCompletion!!, 1e-9)
        assertEquals(1.0, cur.types[0].reviewedAccuracy!!, 1e-9)
        assertEquals(0.0, cur.types[0].unreviewedAccuracy!!, 1e-9)
        val lines = Weekly.compare(prev, cur)
        assertTrue(lines[0], lines[0].contains("+60"))
        assertTrue(lines.any { it.contains("做了终审 100%") })
        assertTrue(lines.any { it.contains("抢答 1 次") && it.contains("上周 2 次") })
    }
}
