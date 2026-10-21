package com.moling.core

import com.moling.core.flow.Busy
import com.moling.core.flow.ChannelKind
import com.moling.core.flow.Flow
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkStatus
import com.moling.core.mind.Ask
import com.moling.core.mind.BEIJING
import com.moling.core.mind.Conclude
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource
import com.moling.core.mind.MindStore
import com.moling.core.power.Tier
import com.moling.core.predict.DayStat
import com.moling.core.predict.Metric
import com.moling.core.review.About
import com.moling.core.review.Councils
import com.moling.core.review.Reviews
import com.moling.core.rhythm.Rhythm
import com.moling.core.rhythm.ScreenEvent
import com.moling.core.talk.Modes
import com.moling.core.talk.TalkMode
import com.moling.core.watch.Claims
import com.moling.core.watch.Ladders
import com.moling.core.watch.Rung
import com.moling.core.watch.Wake
import com.moling.core.watch.Wakes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** v4 C4–C7：阶梯、说法对证据、唤醒、了解、周报、参谋、模式、作息、日历。 */
class V4Test {
    private fun at(h: Int, mi: Int = 0, d: Int = 20) = LocalDateTime.of(2026, 10, d, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class Zk(var status: ZkStatus = ZkStatus()) : ZkPort {
        val sent = mutableListOf<ZkCommand>()
        override fun status() = status
        override fun send(command: ZkCommand): ZkReceipt { sent += command; return ZkReceipt(true, "收下了", 0) }
    }

    /** 按顺序回放的假模型；用完了返回 null（模型不可用）。 */
    private class Replies(vararg r: String) : Ask {
        val q = ArrayDeque(r.toList())
        val prompts = mutableListOf<String>()
        override suspend fun ask(system: String, user: String): String? { prompts += user; return q.removeFirstOrNull() }
    }

    private val phone = FactsSource { Facts(now = it, minute = Flow.minuteOf(it)) }
    private fun brain(zk: Zk = Zk(), ask: Ask? = null) = Brain(phone, ask, MemStore(), MemStore(), zk)

    // ---------------------------------------------------------------- 阶梯

    @Test fun ladderClimbsWaitsAndStopsWhenAnswered() {
        val k = "d:main-0"
        var (l, r) = Ladders.step(null, k, at(10), Tier.STEWARD)
        assertEquals(Rung.NOTICE, r)
        Ladders.step(l, k, at(10, 3), Tier.STEWARD).let { (l2, r2) -> l = l2; assertNull(r2) }
        Ladders.step(l, k, at(10, 5), Tier.STEWARD).let { (l2, r2) -> l = l2; assertEquals(Rung.WINDOW, r2) }
        Ladders.step(l!!.copy(answered = true), k, at(10, 20), Tier.STEWARD).let { (_, r2) -> assertNull("回应过就停", r2) }
        // 回去做事 10 分钟又偏离：接着走，不从头提醒
        val idle = Ladders.step(l, null, at(10, 6), Tier.STEWARD).first
        assertEquals(Rung.PROPOSE, Ladders.step(idle, k, at(10, 16), Tier.STEWARD).second)
        // 隔了 15 分钟以上：从头来
        assertEquals(Rung.NOTICE, Ladders.step(idle, k, at(10, 30), Tier.STEWARD).second)
    }

    // ---------------------------------------------------------------- 说法对证据

    @Test fun claimsParseChineseAndCheckAgainstZongkong() {
        val c = Claims.parse("今天写了四十分钟申论", "他", at(20))!!
        assertEquals(Metric.WORK, c.metric)
        assertEquals(40, c.minutes)
        assertEquals(30, Claims.parse("就刷了半小时抖音", "他", at(20))!!.minutes)
        assertEquals(60, Claims.parse("搞了快一个小时", "他", at(20))!!.minutes)
        assertNull(Claims.parse("今天天气不错", "他", at(20)))
        assertEquals("对得上", Claims.check(c, ZkStatus(workMinutesToday = 45)))
        assertTrue(Claims.check(c, ZkStatus(workMinutesToday = 10)).contains("10 分钟"))
        val play = Claims.parse("只玩了 20 分钟", "他", at(20))!!
        assertTrue(Claims.check(play, ZkStatus(playMinutesToday = 70)).contains("70"))
        assertEquals("看不到", Claims.check(play, null))
    }

    @Test fun chatPutsMismatchedNumbersInThePrompt() = runTest {
        val ask = Replies("好，那我们看看差在哪。")
        val b = brain(Zk(ZkStatus(workMinutesToday = 12)), ask)
        b.chat("我今天做了 60 分钟了", at(20))
        assertTrue(ask.prompts.single().contains("总控记的今天做事一共 12 分钟"))
        assertTrue(b.state().claims.single().verdict.contains("12"))
    }

    // ---------------------------------------------------------------- 唤醒

    @Test fun wakesFromChatAndDailyOnes() = runTest {
        val b = brain(ask = Replies("好，十分钟后我来问。\n【动作】提醒我｜10 分钟后｜问他第二题写了没"))
        val r = b.chat("我先歇十分钟", at(20))
        assertTrue("唤醒不需要他点好", r.proposals.isEmpty())
        assertEquals(at(20, 10), b.state().wakes.single().at)
        assertTrue(b.tick(at(20, 9)).none { it.kind == "wake" })
        assertEquals("问他第二题写了没", b.tick(at(20, 10)).single { it.kind == "wake" }.text)
        assertTrue(b.state().wakes.isEmpty())
        val (due, keep) = Wakes.due(listOf(Wake("w", at(8), "晨报", daily = true)), at(9))
        assertEquals(1, due.size)
        assertEquals(at(8, d = 21), keep.single().at)
        assertTrue("错过的标上原定时间", Wakes.text(due.single(), at(9)).contains("原定 08:00"))
    }

    // ---------------------------------------------------------------- 了解

    @Test fun aboutUpdatesNeedHisYes() = runTest {
        val doc = "# 我对你的了解\n\n## 你在乎的\n- 申论\n\n## 你的规律\n- 下午三点后容易卡住\n\n## 管用的打法\n- 先聊 10 分钟再写\n\n## 我还不确定的\n"
        // 夜里导演也会调模型写日记：按提示词分开回答
        val ask = Ask { _, user -> if (user.contains("我对他的了解")) "【改了什么】记下了下午容易卡\n【文档】\n$doc" else "今天的日记。" }
        val zk = Zk(ZkStatus(workMinutesToday = 30, mainTitle = "申论"))
        val b = brain(zk, ask)
        b.night(at(23, 30))
        assertEquals("没点好之前不算数", "", b.state().about)
        val p = b.pending(at(23, 31)).single { it.act is Act.About }
        assertTrue(b.accept(p.id, at(23, 32)).ok)
        assertEquals(doc.trim(), b.state().about)
        assertEquals(listOf("申论", "下午三点后容易卡住", "先聊 10 分钟再写"), About.bullets(b.state().about))
        b.editAbout("# 我对你的了解\n- 我自己改的", at(23, 40))
        assertEquals("你", b.state().aboutVersions.last().by)
        assertNull("不改就不提", About.parse("【不改】", doc))
    }

    @Test fun aboutGoesIntoChatPrompt() = runTest {
        val ask = Replies("嗯。")
        val b = brain(ask = ask)
        b.editAbout("- 晚上十点后效率低", at(9))
        b.chat("今晚还写吗", at(21))
        assertTrue(ask.prompts.single().contains("晚上十点后效率低"))
    }

    // ---------------------------------------------------------------- 周报

    @Test fun weekReportNumbersAndLocalText() {
        val days = listOf(
            DayStat("2026-10-19", 60, 120, true, 0.0, mainScore = 1.0, drifts = 2, nudges = 4),
            DayStat("2026-10-20", 90, 60, true, 0.0, mainScore = 0.5, drifts = 3, nudges = 6),
            DayStat("2026-10-12", 30, 300, true, 0.0, mainScore = 1.0, drifts = 1, nudges = 2),
        )
        val r = Reviews.week("2026-10-19", days, listOf(true, false, true, true), 1, emptyList())
        assertEquals(2, r.days)
        assertEquals(0.75, r.mainScore, 1e-9)
        assertEquals(5, r.drifts)
        assertEquals(180, r.workMinutes)
        assertEquals(0.75, r.acceptRate, 1e-9)
        assertEquals(10 / 3.0, r.nudgesPerHour, 1e-9)
        val last = Reviews.week("2026-10-12", days, emptyList(), 0, emptyList())
        assertTrue(Reviews.localText(r, last).contains("做事 3 小时"))
        assertEquals("2026-10-19", Reviews.monday("2026-10-25"))
    }

    @Test fun sundayNightWritesAWeekReport() = runTest {
        val b = brain(Zk(ZkStatus(workMinutesToday = 90, playMinutesToday = 30, mainTitle = "申论", mainStarted = true)))
        val text = b.night(at(23, 30, d = 25)) // 2026-10-25 是周日
        assertTrue(text.contains("周报"))
        val w = b.state().weeks.single()
        assertEquals("2026-10-19", w.week)
        assertEquals(0.5, w.mainScore, 1e-9)
    }

    // ---------------------------------------------------------------- 参谋、反驳、疯想

    @Test fun councilNeedsAllFourVoices() = runTest {
        val full = "【教练】先写一段\n【军师】第二题不是本周关键\n【对手】他可能只是累了\n【朋友】今天歇一歇\n【合起来】先写 15 分钟，写不动就收工"
        assertNotNull(Councils.parse(full))
        assertNull(Councils.parse(full.replace("【对手】他可能只是累了\n", "")))
        val b = brain(ask = Replies(full))
        assertEquals("先写 15 分钟，写不动就收工", b.council("要不要放弃第二题", at(20))!!.together)
        assertTrue(b.state().log.any { it.contains("对手：他可能只是累了") })
        assertNull(brain().council("x", at(20)))
    }

    @Test fun devilAndWild() = runTest {
        val b = brain(ask = Replies("嗯。", "1. 你默认了申论只能先写开头。", "【疯想】1. 倒着写 ｜ 可以试\n【疯想】2. 只写论据 ｜ 假设\n【小实验】只写三条论据｜20"))
        b.chat("我觉得第二题就该先写开头", at(20))
        val d = b.devil(at(20, 1))
        assertTrue(d.text.contains("默认"))
        val w = b.wild("申论怎么写快", at(20, 2))
        assertTrue(w.text.contains("小实验：只写三条论据"))
        val add = (w.proposals.single().act as Act.Zk).commands.single() as ZkCommand.AddAction
        assertEquals(20 * 60_000L, add.end - add.start)
        assertTrue(add.start >= at(20, 2))
    }

    // ---------------------------------------------------------------- 模式

    @Test fun modesGuessAndStick() = runTest {
        assertEquals(TalkMode.COMPANY, Modes.guess("陪我聊聊天", 0, false, null))
        assertEquals(TalkMode.REST, Modes.guess("好累啊", 0, false, null))
        assertEquals(TalkMode.START, Modes.guess("嗯", 0, false, null))
        assertEquals(TalkMode.START, Modes.guess("在干嘛", 20, false, null))
        assertEquals(TalkMode.WORK, Modes.guess("第二题怎么写", 0, true, null))
        assertEquals(2, Modes.score("依据呢？\n【分】2"))
        val ask = Replies("依据是哪一句？\n【分】1", "嗯。")
        val b = brain(ask = ask)
        val r = b.chat("我觉得申论就是背模板", at(20), TalkMode.TRAIN)
        assertEquals(TalkMode.TRAIN, r.mode)
        assertFalse("分数不显示给他", r.text.contains("【分】"))
        assertEquals("他选的模式一小时内有效", TalkMode.TRAIN, b.chat("那怎么办", at(20, 30)).mode)
        assertTrue(ask.prompts.last().contains("训练"))
    }

    // ---------------------------------------------------------------- 作息和日历

    @Test fun sleepFromScreenEvents() {
        val ev = listOf(
            ScreenEvent(at(23, 40, d = 19), false),
            ScreenEvent(at(3, 0), true), ScreenEvent(at(3, 1), false), // 半夜看了一眼
            ScreenEvent(at(7, 30), true),
        )
        val n = Rhythm.lastNight(ev, "2026-10-20")!!
        assertEquals(at(23, 40, d = 19), n.sleepAt)
        assertEquals(at(7, 30), n.wakeAt)
        assertEquals(1, n.wakeUps)
        assertNull("没有 3 小时以上的熄屏不算", Rhythm.lastNight(listOf(ScreenEvent(at(1), false), ScreenEvent(at(2), true)), "2026-10-20"))
        assertTrue(Rhythm.advice(n.copy(wakeAt = at(4))).contains("4.3"))
    }

    @Test fun shortSleepCapsMainAndBusyIsAvoided() = runTest {
        val zk = Zk(ZkStatus(mainTitle = "申论第二题", mainPlannedAt = at(14)))
        val b = brain(zk)
        b.sleep(listOf(ScreenEvent(at(2), false), ScreenEvent(at(7), true)), at(8))
        val plan = b.planDay(at(8), busy = listOf(Busy(at(14), at(16), "课")))
        val main = plan.channels.single { it.kind == ChannelKind.MAIN }
        assertEquals("睡了 5 小时：主河道不超过 45 分钟", 45, main.minutes)
        assertTrue("主河道挪到课后", main.startMin >= 16 * 60)
        plan.channels.filter { it.kind != ChannelKind.FREE && it.kind != ChannelKind.POOL }.forEach {
            assertFalse("${it.title} 不和课重叠", it.startMin < 16 * 60 && it.endMin > 14 * 60)
        }
    }

    @Test fun closingLooksLikeClosing() {
        assertTrue(Conclude.looksLikeClosing("收线｜开头先亮观点｜写第二段｜21:00"))
        assertTrue(Conclude.looksLikeClosing("今天聊出了一个结论：先写论据"))
        assertTrue(Conclude.looksLikeClosing("那就这样，晚上 9 点开始写第二题的开头部分吧"))
        assertFalse(Conclude.looksLikeClosing("好的"))
        assertFalse(Conclude.looksLikeClosing("https://example.com/xyz"))
    }
}
