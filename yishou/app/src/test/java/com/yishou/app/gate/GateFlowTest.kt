package com.yishou.app.gate

import com.yishou.app.judge.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GateFlowTest {
    private val q = LockedQ(id = "test", prompt = "问题", options = listOf("甲", "乙"), answer = 0,
        points = listOf(Point("P1", "甲乙丙丁", "往甲那边想"), Point("P2", "其他要点")), need = 1, reference = "参考")
    private val accept = object : Judge { override suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace, seen: List<String>) = Verdict(mapOf("P1" to answer), false, true, "通过") }
    private val offline = object : Judge { override suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace, seen: List<String>) = Verdict(emptyMap(), false, false, "不在", unavailable = true) }
    @Test fun optionsRequireSubmittedPoints() = runTest {
        val flow = GateFlow(q) { 5_000L }; flow.shown()
        assertFalse(flow.canSelect())
        flow.edit("自己的说法")
        assertFalse(flow.canSelect())
        flow.submit(accept)
        assertTrue(flow.canSelect())
    }
    @Test fun failedPointsFreezeAndStopAfterThreeAttempts() = runTest {
        var now = 0L
        val flow = GateFlow(q) { now }; flow.shown(); now = 2_900
        flow.edit("没有命中")
        flow.submit(FakeJudge())
        assertFalse(flow.canSelect()); assertEquals(15_000L, flow.remainingFreeze)
        flow.submit(accept)
        assertEquals(1, flow.attempts)
        repeat(2) { now += 15_000; flow.submit(FakeJudge()) }
        assertTrue(flow.exhausted); assertFalse(flow.canSelect())
    }

    @Test fun missShowsHintNotCriterionAndHintGoesToSeen() = runTest {
        var now = 0L
        var seenLast: List<String> = emptyList()
        val miss = object : Judge { override suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace, seen: List<String>): Verdict { seenLast = seen; return Verdict(mapOf("P1" to null, "P2" to null), false, false, "还差 1 个要点") } }
        val flow = GateFlow(q) { now }; flow.shown(); now = 10_000
        flow.edit("我自己的一段想法")
        flow.submit(miss)
        assertEquals(listOf("往甲那边想"), flow.missedHints())
        now += 20_000
        flow.submit(miss)
        assertEquals(listOf("往甲那边想"), seenLast)
    }
    @Test fun judgeOfflineDoesNotCountOrPass() = runTest {
        var now = 0L
        val flow = GateFlow(q) { now }; flow.shown(); now = 10_000
        flow.edit("我自己的一段想法")
        flow.submit(offline)
        assertEquals(0, flow.attempts); assertTrue(flow.unavailable); assertFalse(flow.canSelect())
    }
    @Test fun passedSlowShortAnswerStillShowsOptions() = runTest {
        var now = 0L
        val flow = GateFlow(q) { now }; flow.shown(); now = 50_000
        flow.edit("甲乙丙丁")
        flow.submit(accept)
        assertTrue(flow.canSelect()); assertFalse(flow.fragment)
    }
}
