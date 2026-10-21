package com.moling.core

import com.moling.core.flow.Flow
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkStatus
import com.moling.core.live.Activity
import com.moling.core.live.Eagerness
import com.moling.core.live.GateRules
import com.moling.core.live.LiveConfig
import com.moling.core.live.Mood
import com.moling.core.live.Spirit
import com.moling.core.live.Spirits
import com.moling.core.live.Trigger
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

/** 活的墨灵：精力、叫醒、拦人、放行、急事。 */
class LiveTest {
    private fun at(h: Int, mi: Int = 0, d: Int = 20) = LocalDateTime.of(2026, 10, d, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class Zk(var status: ZkStatus = ZkStatus()) : ZkPort {
        override fun status() = status
        override fun send(command: ZkCommand) = ZkReceipt(true, "收下了", 0)
    }

    private class Replies(vararg r: String) : Ask {
        val q = ArrayDeque(r.toList())
        val prompts = mutableListOf<String>()
        override suspend fun ask(system: String, user: String): String? { prompts += user; return q.removeFirstOrNull() }
    }

    private val phone = FactsSource { Facts(now = it, minute = Flow.minuteOf(it)) }
    private fun brain(ask: Ask? = null, zk: Zk = Zk()) = Brain(phone, ask, MemStore(), MemStore(), zk)
    private val douyin = Activity("com.ss.android.ugc.aweme", "抖音", "ENTERTAINMENT", 25, "在看一个做饭的短视频")

    @Test fun spiritBudgetGapAndLaziness() {
        val cfg = LiveConfig()
        val s = Spirit(day = "2026-10-20")
        assertTrue(Spirits.mayAct(s, Trigger.SCROLL_IN_BED, at(23), cfg, roll = 0.0))
        assertFalse("不重要的事一半时候懒得说", Spirits.mayAct(s, Trigger.LONG_SCROLL, at(23), cfg, roll = 0.2))
        assertTrue(Spirits.mayAct(s, Trigger.LONG_SCROLL, at(23), cfg, roll = 0.8))
        assertFalse("20 分钟内不再出声", Spirits.mayAct(s.copy(lastAt = at(22, 50)), Trigger.SCROLL_IN_BED, at(23), cfg, 0.9))
        assertTrue("她自己定的唤醒不受间隔限制", Spirits.mayAct(s.copy(lastAt = at(22, 50)), Trigger.WAKE, at(23), cfg, 0.9))
        assertFalse("被冷落多了，今天能说的就少", Spirits.mayAct(s.copy(spoke = 4, ignored = 3), Trigger.LONG_SCROLL, at(23), cfg, 0.9))
        assertFalse("被冷落 3 次以上那天不再拦", Spirits.mayGate(s.copy(ignored = 3), cfg))
    }

    @Test fun onlyEntertainmentCanBeGated() {
        assertTrue(GateRules.eligible(douyin))
        assertFalse(GateRules.eligible(douyin.copy(pkg = "com.android.dialer")))
        assertFalse(GateRules.eligible(douyin.copy(pkg = "com.tencent.mm")))
        assertFalse(GateRules.eligible(douyin.copy(category = "WORK")))
        assertFalse(GateRules.eligible(null))
    }

    @Test fun knownPlayAppsCountOnlyWhenZongkongIsAway() {
        assertEquals("ENTERTAINMENT", BrainFacts.category("com.ss.android.ugc.aweme", null))
        assertEquals("OTHER", BrainFacts.category("com.ss.android.ugc.aweme", ZkStatus(blocked = listOf("tv.danmaku.bili"))))
        assertEquals("ENTERTAINMENT", BrainFacts.category("tv.danmaku.bili", ZkStatus(blocked = listOf("tv.danmaku.bili"))))
    }

    @Test fun triggers() {
        val b = brain()
        assertEquals(Trigger.SCROLL_IN_BED, b.liveTrigger(douyin, at(23, 40)))
        assertNull("白天刷 25 分钟还不算久", b.liveTrigger(douyin, at(15)))
        assertEquals(Trigger.LONG_SCROLL, b.liveTrigger(douyin.copy(minutes = 45), at(15)))
        assertNull(b.liveTrigger(douyin.copy(category = "WORK", minutes = 90), at(23, 40)))
    }

    @Test fun sheAlsoComesWhenNotScrolling() = runTest {
        val home = Activity("com.android.launcher", "桌面", "OTHER", 2)
        val b = brain(null, Zk(ZkStatus(mainDone = true, at = at(15))))
        b.tick(at(15))
        assertEquals("主线做完了，来夸一句", Trigger.DONE, b.liveTrigger(home, at(15)))
        b.live(Trigger.DONE, home, at(15), roll = 0.9)
        assertNull("一天只夸一次", b.liveTrigger(home, at(16)))
        val b2 = brain()
        assertEquals("放下手机半小时后刚拿起来", Trigger.UNLOCK, b2.liveTrigger(home, at(15), unlockGapMin = 40))
        assertEquals(Trigger.MORNING, b2.liveTrigger(home, at(8), unlockGapMin = 400))
        assertNull("平常不在闲着时来", b2.liveTrigger(home, at(15)))
        assertEquals("“多”的时候闲着也来", Trigger.IDLE, b2.liveTrigger(home, at(15), cfg = LiveConfig.of(Eagerness.HIGH, true)))
        assertNull("在做事不打扰", b2.liveTrigger(home.copy(category = "WORK"), at(15), unlockGapMin = 40))
        assertNull("深夜不来", b2.liveTrigger(home, at(0, 30, d = 21), unlockGapMin = 40))
        assertEquals(0, LiveConfig.of(Eagerness.LOW, false).gatesPerDay)
    }

    @Test fun shePopsUpGatesAndLetsGoAfterARealAnswer() = runTest {
        val ask = Replies(
            "【出声】是\n【表情】气鼓鼓\n【说】躺着刷做饭视频 25 分钟了！昨天你也说十分钟。几点放下？\n【拦】是",
            "【表情】气鼓鼓\n【说】“马上”不算答案。几点？\n【放行】否",
            "【表情】开心\n【说】好，23:50 我来问。\n【放行】是\n【动作】提醒我｜10 分钟后｜问他放下手机没",
        )
        val b = brain(ask, Zk(ZkStatus(playMinutesToday = 90)))
        val t = b.live(Trigger.SCROLL_IN_BED, douyin, at(23, 40), roll = 0.9)!!
        assertTrue(t.gate)
        assertEquals(Mood.POUT, t.mood)
        assertTrue(ask.prompts.single().contains("在看一个做饭的短视频"))
        assertNotNull(b.state().gate)
        assertNull("拦着的时候不再叫醒她", b.live(Trigger.WAKE, douyin, at(23, 41), roll = 0.9))
        val r1 = b.gateReply("马上", at(23, 41))
        assertFalse(r1.release)
        assertTrue(r1.gate)
        val r2 = b.gateReply("23:50 放下，明早要早起写申论", at(23, 42))
        assertTrue(r2.release)
        assertNull(b.state().gate)
        assertEquals(1, b.state().wakes.size)
        assertEquals(0, b.state().spirit.ignored)
    }

    @Test fun fogGateDoesNotDemandWords() = runTest {
        val b = brain() // 没模型：用本地判断
        assertTrue(b.live(Trigger.SCROLL_IN_BED, douyin, at(23, 40), roll = 0.9)!!.gate)
        assertTrue("夜里躺着刷算刷懵了：回个“嗯”也放", b.gateReply("嗯", at(23, 41)).release)
    }

    @Test fun safetyValveAndEmergency() = runTest {
        val b = brain()
        b.live(Trigger.SCROLL_IN_BED, douyin, at(23, 40), roll = 0.9)
        assertTrue("拦满 5 分钟，他再随便说什么都放", b.gateReply("嗯", at(23, 46)).release)
        b.live(Trigger.SCROLL_IN_BED, douyin, at(0, 10, d = 21), roll = 0.9)
        b.gateEscape("急事", at(0, 11, d = 21))
        assertNull(b.state().gate)
        assertEquals(1, b.state().spirit.ignored)
        assertFalse(b.state().decisions.last().accepted)
    }

    @Test fun sheMayStayQuietAndNeverGatesNonEntertainment() = runTest {
        val quiet = brain(Replies("【出声】否"))
        assertNull(quiet.live(Trigger.SCROLL_IN_BED, douyin, at(23, 40), roll = 0.9))
        assertTrue(quiet.state().log.last().contains("没出声"))
        val b = brain(Replies("【出声】是\n【表情】担心\n【说】看了好久了\n【拦】是"))
        val t = b.live(Trigger.LONG_SCROLL, douyin.copy(pkg = "com.android.settings"), at(15), roll = 0.9)!!
        assertFalse("设置永远不拦", t.gate)
    }

    @Test fun misjudgmentsReachHerPrompt() = runTest {
        val ask = Replies("嗯。", "【出声】是\n【表情】气鼓鼓\n【说】你说做了一小时，总控只记了 10 分钟哦。\n【拦】否")
        val b = brain(ask, Zk(ZkStatus(workMinutesToday = 10)))
        b.chat("今天做了一个小时了", at(20))
        b.live(Trigger.SCROLL_IN_BED, douyin, at(23, 40), roll = 0.9)
        assertTrue(ask.prompts.last().contains("这几天判断上的错"))
        assertTrue(ask.prompts.last().contains("总控记的今天做事一共 10 分钟"))
    }
}
