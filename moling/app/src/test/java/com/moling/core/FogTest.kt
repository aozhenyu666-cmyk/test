package com.moling.core

import com.moling.core.flow.Flow
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkStatus
import com.moling.core.live.Activity
import com.moling.core.live.Habits
import com.moling.core.live.Live
import com.moling.core.live.Modality
import com.moling.core.live.Reaction
import com.moling.core.live.Trigger
import com.moling.core.mind.Ask
import com.moling.core.mind.BEIJING
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource
import com.moling.core.mind.MindStore
import com.moling.core.talk.Talk
import com.moling.core.think.Think
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 破雾：刷懵了的时候只要他点，不要他打字；她自己的盲盒 / 小剧场 / 赌。 */
class FogTest {
    private fun at(h: Int, mi: Int = 0) = LocalDateTime.of(2026, 10, 21, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class Zk : ZkPort {
        override fun status() = ZkStatus()
        override fun send(command: ZkCommand) = ZkReceipt(true, "收下了", 0)
    }

    private class Router(val rules: List<Pair<String, String>>) : Ask {
        val prompts = mutableListOf<String>()
        override suspend fun ask(system: String, user: String): String? { prompts += user; return rules.firstOrNull { user.contains(it.first) }?.second }
    }

    private val phone = FactsSource { Facts(now = it, minute = Flow.minuteOf(it)) }
    private fun brain(ask: Ask?) = Brain(phone, ask, MemStore(), MemStore(), Zk())
    private val douyin = Activity("com.ss.android.ugc.aweme", "抖音", "ENTERTAINMENT", 45)

    @Test fun choicesParse() {
        assertEquals(listOf("确实在扯", "有点道理", "纯发呆"), Live.choices("【说】嗯\n【选】【确实在扯】｜有点道理|纯发呆｜第四个"))
        val r = Talk.parse("你在发呆吧。\n【选】是｜不是", 0)
        assertEquals("你在发呆吧。", r.text)
        assertEquals(listOf("是", "不是"), r.choices)
    }

    @Test fun fogOpensWithChoicesNotQuestions() = runTest {
        val ask = Router(listOf("你被叫醒的原因" to "【出声】是\n【表情】好奇\n【说】第五个做饭视频了，你猜下一个是红烧肉还是糖醋排骨？\n【拦】否\n【选】红烧肉｜糖醋排骨｜都不想吃"))
        val b = brain(ask)
        val t = b.live(Trigger.LONG_SCROLL, douyin, at(15), roll = 0.9)!!
        assertTrue(t.fog)
        assertEquals(listOf("红烧肉", "糖醋排骨", "都不想吃", Live.EXIT), t.choices)
        val p = ask.prompts.last()
        assertTrue(p.contains("【破雾】"))
        assertTrue(p.contains("不问为什么 / 卡在哪"))
    }

    @Test fun withoutModelFogNeverAsksWhereStuck() = runTest {
        val b = brain(null)
        val t = b.live(Trigger.AUTOPILOT, douyin, at(15), roll = 0.9)!!
        assertFalse(t.line.contains("卡在哪"))
        assertEquals(listOf("有点懵", "还行", "别管我", Live.EXIT), t.choices)
    }

    @Test fun earlyScrollAtWorkIsFogToo() = runTest {
        // v13：该做事时刚刷 6 分钟，也不审“卡在哪”，走点选
        val ask = Router(listOf("你被叫醒的原因" to "【出声】是\n【表情】好奇\n【说】B 站又把你吸进去了？"))
        val b = brain(ask)
        val t = b.live(Trigger.SCROLL_AT_WORK, douyin.copy(minutes = 6), at(10), roll = 0.9)!!
        assertTrue(t.fog)
        assertTrue(ask.prompts.last().contains("【破雾】"))
        assertEquals("模型漏了【选】也有按钮，最后一个是退出", Live.EXIT, t.choices.last())
        assertTrue(t.choices.size >= 2)
        val offline = brain(null).live(Trigger.SCROLL_AT_WORK, douyin.copy(minutes = 6), at(10), roll = 0.9)!!
        assertFalse(offline.line.contains("卡在哪"))
        assertEquals(Live.EXIT, offline.choices.last())
    }

    @Test fun everyEntertainmentEntryIsFog() {
        Live.ENTERTAINMENT.forEach { assertTrue(it.name, Live.fog(it, 0)) }
        assertFalse(Live.fog(Trigger.MORNING, 0))
        assertFalse(Live.fog(Trigger.DONE, 5))
        assertFalse(Live.fog(Trigger.SPAR, 5))
        assertTrue("老被划掉也走破雾", Live.fog(Trigger.UNLOCK, 2))
        Live.ENTERTAINMENT.forEach { tr ->
            assertFalse(tr.name, Live.local(tr, douyin, true, "写报告").line.let { it.contains("卡在哪") || it.contains("还要多久") })
        }
        assertEquals(listOf("a", "b", "c", Live.EXIT), Live.fogChoices(listOf("a", "b", "c", "d"), listOf("x")))
        assertEquals(listOf("x", Live.EXIT), Live.fogChoices(emptyList(), listOf("x")))
    }

    @Test fun exitChoiceEndsFogAndReleasesGate() = runTest {
        val b = brain(Router(listOf("你被叫醒的原因" to "【出声】是\n【表情】气鼓鼓\n【说】停一下？\n【拦】是\n【选】好｜不")))
        val t = b.live(Trigger.SCROLL_IN_BED, douyin.copy(minutes = 30), at(23, 40), roll = 0.9)!!
        assertTrue(t.gate)
        val r = b.tap(Live.EXIT, at(23, 41))
        assertTrue("点退出第一下就放", r.release)
        assertTrue(r.choices.isEmpty())
        assertNull(b.state().gate)
    }

    @Test fun dontCareMeAlsoEntersQuietPeriod() = runTest {
        val b = brain(null)
        b.live(Trigger.AUTOPILOT, Activity("launcher", "桌面", "OTHER", 0), at(15), roll = 0.9)
        val r = b.tap("别管我", at(15, 1), modality = Modality.PEEK)
        assertTrue("“别管我”和“先不聊了”一样进安静期", Habits.isQuiet(b.state().habits, at(15, 2)))
        assertNull("不再拦着", b.state().gate)
        assertTrue("不再给选项", r.choices.isEmpty())
        // 只给这一次出现记 STOPPED；不是“她回复了”。
        assertEquals(Reaction.STOPPED, b.state().habits.marks.last().reaction)
    }

    @Test fun fogExitWithoutGateIsNotAReleaseAndClearsIgnored() = runTest {
        val b = brain(null)
        // 先攒一次冷落
        b.react(Modality.PEEK, Reaction.IGNORED, 0, at(14))
        assertEquals(1, b.state().spirit.ignored)
        b.live(Trigger.AUTOPILOT, Activity("launcher", "桌面", "OTHER", 0), at(15), roll = 0.9)
        val r = b.tap(Live.EXIT, at(15, 1), modality = Modality.PEEK)
        assertFalse("不拦人的破雾点退出，不该说放行", r.release)
        assertEquals("他点了就不算冷落", 0, b.state().spirit.ignored)
        assertTrue(Habits.isQuiet(b.state().habits, at(15, 2)))
        assertFalse("退出的话不说一个不存在的恢复按钮", r.line.contains("点「恢复」"))
    }

    @Test fun fogUsesHisMaterialAndNeverInventsScreenContent() = runTest {
        val ask = Router(listOf("你被叫醒的原因" to "【出声】是\n【表情】好奇\n【说】考你一个：申论开头先亮观点还是先铺垫？\n【选】亮观点｜先铺垫"))
        val b = brain(ask)
        b.live(Trigger.LONG_SCROLL, douyin, at(15), roll = 0.9, material = listOf("申论：申论开头最容易卡"))
        val p = ask.prompts.last()
        assertTrue(p.contains("申论开头最容易卡"))
        assertTrue(p.contains("你看不到他屏幕上的内容"))
    }

    @Test fun tollIsCappedPerDay() = runTest {
        val b = brain(null)
        val desk = Activity("launcher", "桌面", "OTHER", 0)
        val times = listOf(at(10), at(12), at(14))
        times.forEach { t ->
            assertEquals(Trigger.TOLL, b.liveTrigger(desk, t, cfg = com.moling.core.live.LiveConfig(spar = true), leftPlayMin = 20))
            assertTrue("这一回收了", b.live(Trigger.TOLL, desk, t, roll = 0.9) != null)
        }
        assertTrue("第四回不收", b.liveTrigger(desk, at(16), cfg = com.moling.core.live.LiveConfig(spar = true), leftPlayMin = 20) != Trigger.TOLL)
    }

    @Test fun threeTapsBringHimOut() = runTest {
        val b = brain(null)
        // 在桌面和几个应用之间横跳（不是娱乐应用，不会拦）
        b.live(Trigger.AUTOPILOT, Activity("launcher", "桌面", "OTHER", 0), at(15), roll = 0.9)
        val t1 = b.tap("有点懵", at(15, 1))
        assertTrue(t1.choices.isNotEmpty())
        val t2 = b.tap("全忘了", at(15, 2))
        assertTrue("第二下变成两分钟的小事", t2.choices.contains("起来喝口水"))
        val t3 = b.tap("起来喝口水", at(15, 3))
        assertTrue(t3.choices.isEmpty())
        assertEquals(0, b.state().spirit.ignored)
        assertEquals("（点了）起来喝口水", b.state().talk.dropLast(1).last().text)
    }

    @Test fun fogGateLetsGoAfterTwoTaps() = runTest {
        val ask = Router(listOf("你被叫醒的原因" to "【出声】是\n【表情】气鼓鼓\n【说】停！先猜：刚才那个 UP 主几句话里在煽情？\n【拦】是\n【选】一句｜三句｜全是"))
        val b = brain(ask)
        val t = b.live(Trigger.SCROLL_IN_BED, douyin.copy(minutes = 30), at(23, 40), roll = 0.9)!!
        assertTrue(t.gate)
        assertTrue(b.state().gate!!.fog)
        assertFalse(b.tap("三句", at(23, 41)).release)
        assertTrue("点两下就放", b.tap("起来喝口水", at(23, 42)).release)
        assertNull(b.state().gate)
    }

    @Test fun fogGateAcceptsShortTypedReply() = runTest {
        val b = brain(Router(listOf("你被叫醒的原因" to "【出声】是\n【表情】好奇\n【说】刷懵了没？\n【拦】是\n【选】懵了｜没有")))
        b.live(Trigger.AUTOPILOT, douyin, at(15), roll = 0.9)
        assertTrue("破雾时回个“嗯”也行", b.gateReply("嗯", at(15, 1)).release)
    }

    @Test fun herPlayIsServedWithoutAModelCall() = runTest {
        val ask = Router(
            listOf(
                "自己的思考时间" to "【盲盒】写申论只要先列三点，开头就一定不会卡｜“一定”太绝对，列了三点也可能卡在第一句\n" +
                    "【小剧场】对手：他今天又要摆了｜军师：先写开头一句就算赢｜朋友：别逼他，他昨天睡得少\n" +
                    "【赌】我赌你今晚 12 点前放不下手机，把握 70%",
            ),
        )
        val b = brain(ask)
        val r = b.think(at(14))!!
        assertEquals(listOf("blindbox", "drama", "bet"), r.play.map { it.kind })
        val before = ask.prompts.size
        val t = b.live(Trigger.SAY, Activity("launcher", "桌面", "OTHER", 0), at(15), roll = 0.9)!!
        assertEquals("盲盒直接拿出来，不调模型", before, ask.prompts.size)
        assertTrue(t.line.contains("有没有毛病"))
        assertEquals(listOf("有漏洞", "说得对"), t.choices)
        val t2 = b.tap("有漏洞", at(15, 1))
        assertTrue(t2.line.startsWith("对，漏洞在：“一定”太绝对"))
    }

    @Test fun thinkPromptOffersPlay() {
        val p = Think.prompt("墨灵", "你", "现在", "", "", emptyList(), "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(p.contains("【盲盒】") && p.contains("【小剧场】") && p.contains("【赌】"))
    }
}
