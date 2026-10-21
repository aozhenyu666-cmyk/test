package com.moling.core

import com.moling.core.flow.Flow
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkResult
import com.moling.core.link.ZkStatus
import com.moling.core.mind.Actor
import com.moling.core.mind.Ask
import com.moling.core.mind.BEIJING
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource
import com.moling.core.mind.MindStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 第三批要用的大脑能力：总控结局回传、体系图操作、定时判断、前台类别。 */
class BrainMoreTest {
    private fun at(h: Int, mi: Int = 0, d: Int = 20) = LocalDateTime.of(2026, 10, d, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    /** 像总控一样：收下给编号，结局由测试设定。 */
    private class Zk : ZkPort {
        var reachable = true
        val results = mutableMapOf<String, ZkResult>()
        var n = 0
        var status = ZkStatus(blocked = listOf("com.ss.android.ugc.aweme"))
        override fun status() = if (reachable) status else null
        override fun send(command: ZkCommand): ZkReceipt? {
            if (!reachable) return null
            val id = "m${n++}"
            results[id] = ZkResult("PENDING", "等你确认")
            return ZkReceipt(true, "收下了", 0, id)
        }
        override fun result(id: String) = if (reachable) results[id] else null
        /** 假装总控的授权书什么都授权了。 */
        var granted = false
        var autoRequests = 0
        override fun sendAuto(command: ZkCommand): ZkReceipt? {
            autoRequests++
            return send(command)?.let { if (granted) it.copy(auto = true, message = "授权内，60 秒后执行") else it }
        }
    }

    private val phone = FactsSource { Facts(now = it, minute = Flow.minuteOf(it)) }

    private fun brain(zk: Zk, reply: String? = null, store: MemStore = MemStore()) = Brain(phone, reply?.let { r -> Ask { _, _ -> r } }, store, MemStore(), zk)

    /** 挣到了管家：用满 7 天、接受率和猜测都高。 */
    private fun stewardStore(): MemStore = MemStore().apply {
        json = kotlinx.serialization.json.Json.encodeToString(
            BrainState.serializer(),
            BrainState(trust = com.moling.core.power.TrustStats(accepted = 9, proposed = 10, guessRight = 8, guessJudged = 10, days = 7)),
        )
    }

    @Test fun learnsWhatZongkongDidWithIt() = runTest {
        val zk = Zk()
        val b = brain(zk, "好。\n【动作】专注｜25｜写申论")
        val p = b.chat("开始", at(10)).proposals.single()
        b.accept(p.id, at(10, 1))
        assertEquals(listOf("m0"), b.state().proposals.single().zkIds)
        // 还在总控里等确认：不出结局
        assertTrue(b.tick(at(10, 2)).none { it.kind == "zk_result" })
        zk.results["m0"] = ZkResult("DONE", "开始「写申论」，专注锁 25 分钟")
        val n = b.tick(at(10, 3)).single { it.kind == "zk_result" }
        assertTrue(n.text.startsWith("总控执行了"))
        assertEquals("总控执行了", b.state().proposals.single().zkOutcome)
        // 有了结局就不再问
        assertTrue(b.tick(at(10, 4)).none { it.kind == "zk_result" })
    }

    @Test fun bundleReportsTheFirstThingThatDidNotHappen() = runTest {
        val zk = Zk().apply { status = status.copy(mainTitle = "申论第二题", mainPlannedAt = at(15)) }
        val b = brain(zk)
        b.planDay(at(9))
        val p = b.confirmFlow(at(9, 1))!!
        b.accept(p.id, at(9, 2))
        val ids = b.state().proposals.single().zkIds
        assertTrue(ids.size >= 2)
        ids.forEach { zk.results[it] = ZkResult("DONE", "") }
        zk.results[ids.last()] = ZkResult("DECLINED", "")
        b.tick(at(9, 3))
        assertEquals("你在总控里点了不用", b.state().proposals.single().zkOutcome)
    }

    @Test fun unreachableZongkongLeavesOutcomeUnknown() = runTest {
        val zk = Zk()
        val b = brain(zk, "好。\n【动作】专注｜25｜写申论")
        b.accept(b.chat("开始", at(10)).proposals.single().id, at(10, 1))
        zk.reachable = false
        b.tick(at(10, 2))
        assertEquals("", b.state().proposals.single().zkOutcome)
    }

    @Test fun atlasVerifyRemoveAndAskGap() = runTest {
        val b = brain(Zk(), "记下了。\n【动作】记下｜申论｜开头先亮观点")
        b.accept(b.chat("记一下", at(10)).proposals.single().id, at(10, 1))
        val node = b.state().atlas.nodes.single()
        assertFalse(b.verifyNode("nope", "x", at(10, 2)))
        assertTrue(b.verifyNode(node.id, "写了 3 篇都更顺", at(10, 2)))
        assertEquals("高", b.state().atlas.nodes.single().confidence)
        val ask = b.askGap("开头亮观点之后怎么接论据？", Actor.CHATGPT, at(10, 3))
        assertNotNull(ask)
        assertEquals(Act.Summon(Actor.CHATGPT, "开头亮观点之后怎么接论据？"), ask!!.act)
        b.removeNode(node.id)
        assertTrue(b.state().atlas.nodes.isEmpty())
    }

    @Test fun synthesisNeedsTopicNodesAndModel() = runTest {
        // 同一段回复既能当对话（带一条“记下”）又能当归纳结果，省得写两个假模型
        val reply = "记下了。\n【主干】申论靠观点带结构\n【分支】开头亮观点\n【关键链】观点 → 论据 → 对策\n【最大缺口】论据从哪来\n【动作】记下｜申论｜开头先亮观点"
        val b = brain(Zk(), reply)
        assertNull("没有主题时不归纳", b.synthesize(at(10)))
        b.setTopic("申论", at(10))
        assertNull("主题里没有论断时不归纳", b.synthesize(at(10)))
        b.accept(b.chat("记一下", at(10)).proposals.single().id, at(10, 1))
        val text = b.synthesize(at(10, 2))
        assertNotNull(text)
        assertTrue(text!!.startsWith("主干：申论靠观点带结构"))
        assertEquals(text, b.state().synthesis[b.state().topicId])
        assertNull("模型不可用时返回 null", brain(Zk()).synthesize(at(10)))
    }

    @Test fun timingHelpers() = runTest {
        val b = brain(Zk())
        assertFalse(b.needsPlan(at(5, 59)))
        assertTrue(b.needsPlan(at(8)))
        b.planDay(at(8))
        assertFalse(b.needsPlan(at(9)))
        assertFalse(b.needsNight(at(22)))
        assertTrue(b.needsNight(at(23, 30)))
        b.night(at(23, 30))
        assertFalse(b.needsNight(at(23, 50)))
        assertFalse("凌晨 1 点还是昨天那一夜", b.needsNight(at(1, 0, d = 21)))
        assertTrue(b.needsNight(at(23, 40, d = 21)))
    }

    @Test fun plannerHandsPlansStraightToZongkong() = runTest {
        val zk = Zk()
        val b = brain(zk, "好，三点开始。\n【动作】主线｜写申论｜15:00")
        assertEquals(com.moling.core.power.Tier.PLANNER, b.tier())
        val p = b.chat("下午三点写申论", at(10)).proposals.single()
        assertTrue(p.auto)
        assertEquals(ProposalState.SENT, p.state)
        assertEquals(1, zk.autoRequests)
        assertEquals("总控收下了", p.receipt)
    }

    @Test fun stewardTightensByGrantAndVetoCountsAsNo() = runTest {
        val zk = Zk().apply { granted = true; status = status.copy(mainTitle = "申论第二题", mainPlannedAt = at(10)) }
        val b = brain(zk, store = stewardStore())
        assertEquals(com.moling.core.power.Tier.STEWARD, b.tier())
        b.planDay(at(9))
        b.confirmFlow(at(9, 1))
        b.tick(at(10, 15), "ENTERTAINMENT")
        b.tick(at(10, 20), "ENTERTAINMENT")
        val ask = b.tick(at(10, 25), "ENTERTAINMENT").single { it.kind == "drift" }
        assertEquals("提议这一级管家也要先问", ProposalState.PENDING, b.state().proposals.single { it.id == ask.proposalId }.state)
        val n = b.tick(at(10, 30), "ENTERTAINMENT").single { it.kind == "drift" }
        assertEquals("AUTO", n.rung)
        assertEquals("上一级没回应的提议作废", ProposalState.EXPIRED, b.state().proposals.single { it.id == ask.proposalId }.state)
        val focus = b.state().proposals.single { it.id == n.proposalId }
        assertTrue("管家按授权书直接收紧", focus.auto)
        assertEquals(ProposalState.SENT, focus.state)
        assertEquals("授权内：总控倒计时执行", focus.receipt)
        // 他在总控的倒计时里点了取消
        val before = b.state().decisions.size
        zk.results[focus.zkIds.single()] = ZkResult("VETOED", "")
        focus.let { f -> b.state().proposals.filter { it.id != f.id }.flatMap { it.zkIds }.forEach { zk.results[it] = ZkResult("DONE", "") } }
        b.tick(at(10, 31), "ENTERTAINMENT")
        assertEquals("你在总控里取消了", b.state().proposals.single { it.id == focus.id }.zkOutcome)
        assertTrue(b.state().decisions.drop(before).any { !it.accepted })
    }

    @Test fun newCommandsAreValid() {
        assertTrue(com.moling.core.link.ZkWire.valid(ZkCommand.Commit("写申论", at(22), "看行动记录", "")))
        assertFalse(com.moling.core.link.ZkWire.valid(ZkCommand.Commit("写申论", 0, "", "")))
        assertTrue(com.moling.core.link.ZkWire.valid(ZkCommand.Tighten("strict", "90", "")))
        assertTrue(com.moling.core.link.ZkWire.valid(ZkCommand.Tighten("cut_play", "30", "")))
        assertEquals("true", com.moling.core.link.ZkWire.toMapAuto(ZkCommand.Tighten("strict", "90", ""))["auto"])
    }

    @Test fun foregroundCategory() {
        val s = ZkStatus(blocked = listOf("com.ss.android.ugc.aweme"))
        assertEquals("ENTERTAINMENT", BrainFacts.category("com.ss.android.ugc.aweme", s))
        assertEquals("ACTOR", BrainFacts.category("com.openai.chatgpt", s))
        assertEquals("WORK", BrainFacts.category("com.zongkong.app", s))
        assertEquals("OTHER", BrainFacts.category("com.android.chrome", s))
        assertEquals("OTHER", BrainFacts.category("com.ss.android.ugc.aweme", null))
        assertEquals("", BrainFacts.category("", s))
    }
}
