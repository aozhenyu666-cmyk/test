package com.moling.core

import com.moling.core.flow.ChannelKind
import com.moling.core.flow.Flow
import com.moling.core.guard.GuardSample
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkStatus
import com.moling.core.mind.Actor
import com.moling.core.mind.Ask
import com.moling.core.mind.BEIJING
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource
import com.moling.core.mind.MindStore
import com.moling.core.power.Power
import com.moling.core.power.Tier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BrainTest {
    private fun at(h: Int, mi: Int = 0, d: Int = 20) = LocalDateTime.of(2026, 10, d, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class FakeZk(var status: ZkStatus = ZkStatus()) : ZkPort {
        var reachable = true
        val sent = mutableListOf<ZkCommand>()
        override fun status() = if (reachable) status else null
        override fun send(command: ZkCommand): ZkReceipt? {
            if (!reachable) return null
            sent += command
            return ZkReceipt(true, "已收下，等你在总控里确认", 0)
        }
    }

    private val phone = FactsSource { Facts(now = it, minute = Flow.minuteOf(it)) }

    private fun brain(zk: FakeZk = FakeZk(), reply: String? = null, store: MemStore = MemStore(), mind: MemStore = MemStore()) =
        Brain(phone, reply?.let { r -> Ask { _, _ -> r } }, store, mind, zk)

    @Test fun chatOnlyProposesUntilAccepted() = runTest {
        val zk = FakeZk()
        val b = brain(zk, "好，三点开始。\n【动作】主线｜写申论｜15:00")
        val r = b.chat("下午三点写申论", at(10))
        assertEquals("好，三点开始。", r.text)
        assertEquals(1, r.proposals.size)
        assertTrue(zk.sent.isEmpty())
        val out = b.accept(r.proposals[0].id, at(10, 1))
        assertTrue(out.ok)
        val cmd = zk.sent.single() as ZkCommand.SetMain
        assertEquals("写申论", cmd.title)
        assertEquals(at(15), cmd.at)
        assertEquals(ProposalState.SENT, b.state().proposals.single().state)
        assertEquals(1, b.state().decisions.count { it.accepted })
    }

    @Test fun declineSendsNothing() = runTest {
        val zk = FakeZk()
        val b = brain(zk, "行。\n【动作】总控时间｜30")
        val p = b.chat("我想进总控时间", at(10)).proposals.single()
        b.decline(p.id, at(10, 1))
        assertTrue(zk.sent.isEmpty())
        assertEquals(ProposalState.DECLINED, b.state().proposals.single().state)
        assertFalse(b.accept(p.id, at(10, 2)).ok)
        assertTrue(zk.sent.isEmpty())
    }

    @Test fun unreachableZongkongFailsThenRetrySends() = runTest {
        val zk = FakeZk().apply { reachable = false }
        val b = brain(zk, "好。\n【动作】专注｜25｜写申论")
        val p = b.chat("开始写", at(10)).proposals.single()
        val first = b.accept(p.id, at(10, 1))
        assertFalse(first.ok)
        assertEquals(ProposalState.FAILED, b.state().proposals.single().state)
        zk.reachable = true
        assertTrue(b.retry(p.id, at(10, 5)).ok)
        assertEquals(ProposalState.SENT, b.state().proposals.single().state)
        assertEquals(1, zk.sent.size)
    }

    @Test fun modelDownGivesLocalReplyWithoutActions() = runTest {
        val r = brain().chat("嗯", at(10))
        assertFalse(r.byModel)
        assertTrue(r.text.contains("空"))
        assertTrue(r.proposals.isEmpty())
    }

    @Test fun flowIsDraftUntilConfirmedAndHandedOffOnlyAfterAccept() = runTest {
        val zk = FakeZk(ZkStatus(mainTitle = "申论第二题", mainPlannedAt = at(15)))
        val b = brain(zk)
        val plan = b.planDay(at(9))
        val main = plan.channels.single { it.kind == ChannelKind.MAIN }
        assertEquals("申论第二题", main.title)
        assertFalse(plan.confirmed)
        val p = b.confirmFlow(at(9, 5))
        assertNotNull(p)
        assertTrue(b.state().flow!!.confirmed)
        assertTrue(zk.sent.isEmpty())
        val cmds = (p!!.act as Act.Zk).commands
        assertTrue(cmds.first() is ZkCommand.SetMain)
        assertTrue(cmds.any { it is ZkCommand.AddAction })
        assertTrue(b.accept(p.id, at(9, 6)).ok)
        assertEquals(cmds, zk.sent)
    }

    @Test fun driftNudgesOnceAndOnlyProposesFocus() = runTest {
        val zk = FakeZk(ZkStatus(mainTitle = "申论第二题", mainPlannedAt = at(10)))
        val b = brain(zk)
        b.planDay(at(9))
        b.confirmFlow(at(9, 1))
        val n = b.tick(at(10, 15), "ENTERTAINMENT").single { it.kind == "drift" }
        assertTrue(n.text.contains("申论第二题"))
        val focus = b.state().proposals.single { it.id == n.proposalId }
        assertEquals(Power.TIGHTEN, focus.power)
        assertTrue((focus.act as Act.Zk).commands.single() is ZkCommand.StartFocus)
        assertTrue(b.tick(at(10, 20), "ENTERTAINMENT").none { it.kind == "drift" })
        assertTrue(zk.sent.isEmpty())
    }

    @Test fun advisorOnlyWarnsOnDrift() = runTest {
        val b = brain(FakeZk(ZkStatus(mainTitle = "申论第二题", mainPlannedAt = at(10))))
        b.setUserMax(Tier.ADVISOR)
        b.planDay(at(9))
        b.confirmFlow(at(9, 1))
        val n = b.tick(at(10, 15), "ENTERTAINMENT").single { it.kind == "drift" }
        assertEquals("", n.proposalId)
    }

    @Test fun ironRulesFilterSuggestions() = runTest {
        val b = brain()
        assertNull(b.suggest(ZkCommand.Tighten("block_app", "com.android.phone", "别打电话"), at(10)))
        assertNull(b.suggest(ZkCommand.EnterHq(600, "太长"), at(10)))
        assertNull("总控没有这个开关", b.suggest(ZkCommand.Tighten("cut_play", "30", "今天刷多了"), at(10)))
        assertNull(b.suggest(ZkCommand.RequestLoosen("pause_all", "1", "想歇一天"), at(10)))
        val ok = b.suggest(ZkCommand.Tighten("block_app", "com.ss.android.ugc.aweme", "今天刷多了"), at(10))
        assertNotNull(ok)
        assertEquals(ProposalState.PENDING, ok!!.state)
        // 同一句话不重复提
        assertEquals(ok.id, b.suggest(ZkCommand.Tighten("block_app", "com.ss.android.ugc.aweme", "今天刷多了"), at(10, 1))!!.id)
        assertNotNull(b.suggest(ZkCommand.RequestLoosen("unblock_app", "tv.danmaku.bili", "周末想看"), at(10)))
    }

    @Test fun neverRepairsOrFreezesZongkong() = runTest {
        val zk = FakeZk()
        val b = brain(zk)
        fun s(t: Long) = GuardSample(t, zkReachable = true, secureSettingsOn = false, heartbeatAt = t, userDisabled = false, screenOn = true, canRepair = true)
        b.tick(at(10), guard = s(at(10)))
        val n = b.tick(at(10, 5), guard = s(at(10, 5))).single { it.kind == "guard" }
        assertTrue(n.text.contains("不去修"))
        assertFalse(b.state().guard.frozen)
        assertTrue(b.state().guard.repairs.isEmpty())
        assertTrue(zk.sent.isEmpty())
    }

    @Test fun stateSurvivesRestart() = runTest {
        val store = MemStore()
        val mind = MemStore()
        val zk = FakeZk()
        val p = brain(zk, "好。\n【动作】行动｜写申论｜15:00｜45", store, mind).chat("三点写 45 分钟", at(10)).proposals.single()
        val again = brain(zk, null, store, mind)
        val restored = again.pending(at(10, 1)).single()
        assertEquals(p, restored)
        assertTrue(again.accept(p.id, at(10, 1)).ok)
        val cmd = zk.sent.single() as ZkCommand.AddAction
        assertEquals(at(15, 45), cmd.end)
        assertEquals(2, again.state().talk.size)
    }

    @Test fun proposalsExpire() = runTest {
        val zk = FakeZk()
        val b = brain(zk, "好。\n【动作】专注｜25｜写申论")
        val p = b.chat("开始", at(10)).proposals.single()
        b.tick(at(13, 1))
        assertEquals(ProposalState.EXPIRED, b.state().proposals.single().state)
        assertFalse(b.accept(p.id, at(13, 2)).ok)
        assertTrue(zk.sent.isEmpty())
    }

    @Test fun summonReturnsOpening() = runTest {
        val b = brain(reply = "去和 Gemini 理一下。\n【动作】去聊｜gemini｜申论怎么开头")
        val p = b.chat("申论开头总写不好", at(10)).proposals.single()
        val out = b.accept(p.id, at(10, 1))
        assertTrue(out.ok)
        assertEquals(Actor.GEMINI_LIVE, out.opening!!.actor)
    }

    @Test fun atlasNoteGoesIntoTopic() = runTest {
        val b = brain(reply = "记下了。\n【动作】记下｜申论｜开头先亮观点再给依据")
        val p = b.chat("我发现开头先亮观点好写", at(10)).proposals.single()
        assertTrue(b.accept(p.id, at(10, 1)).ok)
        val s = b.state()
        val topic = s.atlas.topics.single { it.name == "申论" }
        assertEquals(topic.id, s.topicId)
        assertEquals("开头先亮观点再给依据", s.atlas.nodes.single { it.topicId == topic.id }.claim)
    }

    @Test fun rerouteFromChatAfterAccept() = runTest {
        val zk = FakeZk(ZkStatus(mainTitle = "申论第二题", mainPlannedAt = at(10)))
        val b = brain(zk, "那先歇会儿。\n【动作】改道｜累了")
        b.planDay(at(9))
        b.confirmFlow(at(9, 1))
        val p = b.chat("好累", at(10, 5)).proposals.single()
        val out = b.accept(p.id, at(10, 6))
        assertTrue(out.ok)
        assertTrue(out.message.contains("总控里排的行动还是旧的"))
        assertTrue(b.state().flow!!.channels.any { it.kind == ChannelKind.POOL && it.why == "累了" })
    }

    @Test fun nightRecordsDayOnceAndCountsDecisions() = runTest {
        val zk = FakeZk(ZkStatus(playMinutesToday = 50, workMinutesToday = 90, mainStarted = true))
        val b = brain(zk, "好。\n【动作】专注｜25｜写申论")
        val p1 = b.chat("开始", at(10)).proposals.single()
        b.accept(p1.id, at(10, 1))
        b.suggest(ZkCommand.Tighten("block_app", "tv.danmaku.bili", "刷多了"), at(11))!!.let { b.decline(it.id, at(11, 1)) }
        assertTrue(b.night(at(23, 30)).isNotBlank())
        val s = b.state()
        assertEquals(90, s.days.single().work)
        assertEquals(2, s.trust.proposed)
        assertEquals(1, s.trust.accepted)
        assertEquals(1, s.trust.days)
        assertEquals("", b.night(at(23, 45)))
    }
}
