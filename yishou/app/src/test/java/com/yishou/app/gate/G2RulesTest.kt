package com.yishou.app.gate

import com.yishou.app.FakeDao
import com.yishou.app.judge.*
import com.yishou.app.reflex.*
import com.yishou.app.reflex.Main as Route
import com.yishou.app.ui.TurnReview
import com.yishou.app.settings.AppPrefs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class G2RulesTest {
    private val q = LockedQ("q", "只有核实车站到达时间才能定出发时间", listOf("甲", "乙"), 0,
        listOf(Point("P1", "查明到达时间", "找时间边界"), Point("P2", "留足余量", "找缓冲")), 1, "参考路径")
    private val yes = Verdict(mapOf("P1" to "八点到车站才赶得上"), false, true, "过了")

    @Test fun `有断点优先线程 断网走默写 三条用完走等待`() {
        val all = GateInput(true, true, true, true, 0)
        assertEquals(Route.STRAND, GatePlan.pick(all))
        assertEquals(Route.BANK, GatePlan.pick(all.copy(strandReady = false)))
        assertEquals(Route.LOW_ENERGY, GatePlan.pick(all.copy(judgeOk = false)))
        val r = Reflexes.create("只有", "先检查必要条件是否满足", "q", 0)
        var used = emptySet<Long>()
        val refs = (1L..3L).map { r.copy(id = it) }
        repeat(3) {
            val current = Reflexes.pick(refs, 1, used)!!
            assertFalse(Reflexes.recalled(current.action, "不知道"))
            used = used + current.id
        }
        assertNull(Reflexes.pick(refs, 1, used))
        assertEquals(Route.WAIT, GatePlan.pick(all.copy(judgeOk = false, hasReflex = false)))
    }
    @Test fun `只有本轮判过且触发器存好才结束`() {
        assertFalse(GateCompletion.ready(yes, false))
        assertFalse(GateCompletion.ready(yes.copy(unavailable = true), true))
        assertFalse(GateCompletion.ready(yes.copy(passed = false), true))
        assertNotNull(Reflexes.check("只有", "认真读题", q.prompt))
        assertNull(Reflexes.check("只有", "先核对到达车站的最后时刻", q.prompt))
        assertTrue(GateCompletion.ready(yes, true))
        // 拉回过了也不放行
        assertFalse(GateCompletion.ready(yes, true, pullBack = true))
    }
    @Test fun `线程判过才发十五分钟 判卷不在不发也不扣次数`() = runTest {
        val issuer = PassIssuer(FakeDao()) { 100 }
        assertNull(issuer.issueVerified("娱乐", 15, yes.copy(unavailable = true)))
        assertNull(issuer.issueVerified("娱乐", 15, yes.copy(passed = false)))
        assertNull(issuer.issueVerified("娱乐", 30, yes))
        assertEquals(900_100L, issuer.issueVerified("娱乐", 15, yes)!!.endAt)
        var now = 0L
        val f = GateFlow(q) { now }; f.shown(); now = 1_000; f.edit("我会先查车站最晚到达的时间")
        f.submit(object : Judge { override suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace, seen: List<String>) = yes.copy(unavailable = true) })
        assertEquals(0, f.attempts); assertFalse(f.canSelect())
    }
    @Test fun `先发问没有过就一直是问句任务 破冰必须是另一题`() = runTest {
        val initial = BankOpening.first(q, FadeState(wins = 3))
        assertEquals(q.id + "-ask", initial.id)
        var now = 0L; val f = GateFlow(initial) { now }; f.shown(); now = 5_000; f.edit("这是一个错误的发问没有切中要点")
        f.submit(object : Judge { override suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace, seen: List<String>) = yes.copy(passed = false) })
        assertFalse(f.canSelect()); assertEquals(initial, f.question)
        assertNull(BankOpening.ice(q.id, listOf(q), 1))
        assertNotNull(BankOpening.ice(q.id, listOf(q.copy(id = "other")), 1))
        assertEquals(listOf("找时间边界", "找缓冲"), BankOpening.hints(q, FadeState()))
    }
    @Test fun `同日第二次和关闭后都不唤起 设置可以往返`() {
        assertTrue(Morning.shouldWake(MorningConfig(), 8, 100, 99))
        assertFalse(Morning.shouldWake(MorningConfig(), 8, 100, 100))
        assertFalse(Morning.shouldWake(MorningConfig(enabled = false), 8, 100, 99))
        val prefs = AppPrefs(morningEnabled = false, lastWakeDay = 100, recallDay = 100, recallIds = setOf(1, 2))
        assertEquals(prefs, AppPrefs.fromJson(prefs.toJson()))
    }
    @Test fun `复盘持久化不改题目也不丢命中原话`() {
        val r = TurnReview(q, "八点到车站才赶得上", ProbeTrace(0, 1000, 5000), yes)
        assertEquals(r, TurnReview.parse(r.json()))
    }
}
