package com.yishou.app.gate

import com.yishou.app.judge.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GateFlowTest {
    private val q = LockedQ("test", "问题", listOf("甲", "乙"), listOf(Point("P1", "甲乙丙丁"), Point("P2", "其他要点")), 1, "参考")
    private val accept = object : Judge { override suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace) = Verdict(mapOf("P1" to answer), false, true, "通过") }
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
}
