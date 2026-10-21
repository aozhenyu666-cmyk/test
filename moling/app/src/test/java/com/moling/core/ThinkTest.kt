package com.moling.core

import com.moling.core.flow.Flow
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkStatus
import com.moling.core.live.Activity
import com.moling.core.live.Trigger
import com.moling.core.mind.Ask
import com.moling.core.mind.BEIJING
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource
import com.moling.core.mind.MindStore
import com.moling.core.think.Recall
import com.moling.core.think.Think
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 她自己的思考：什么时候想、想出什么、到点验证、想好的话怎么说出去、对话里怎么翻知识。 */
class ThinkTest {
    private fun at(h: Int, mi: Int = 0, d: Int = 21) = LocalDateTime.of(2026, 10, d, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class Zk : ZkPort {
        override fun status() = ZkStatus(workMinutesToday = 20, playMinutesToday = 95, mainTitle = "申论第一篇")
        override fun send(command: ZkCommand) = ZkReceipt(true, "收下了", 0)
    }

    /** 按提示词里的关键字回话（导演夜里也会调模型，按顺序排容易错位）。 */
    private class Router(val rules: List<Pair<String, String>>) : Ask {
        val prompts = mutableListOf<String>()
        override suspend fun ask(system: String, user: String): String? {
            prompts += user
            return rules.firstOrNull { user.contains(it.first) }?.second
        }
    }

    private val phone = FactsSource { Facts(now = it, minute = Flow.minuteOf(it)) }
    private fun brain(ask: Ask?) = Brain(phone, ask, MemStore(), MemStore(), Zk())

    @Test fun whenToThink() {
        assertTrue(Think.due(0, at(9), 0))
        assertFalse("夜里不想", Think.due(0, at(2), 0))
        assertFalse(Think.due(at(9), at(10), 0))
        assertTrue("他说了不少，45 分钟就再想", Think.due(at(9), at(9, 50), 3))
        assertTrue(Think.due(at(9), at(11, 30), 0))
    }

    @Test fun parseThoughts() {
        val now = at(14)
        val r = Think.parse(
            "【判断】下午主线没开始，是卡在开头｜做事 20 分钟、娱乐 95 分钟｜中｜看 16 点前有没有开始｜16:00 前验证\n" +
                "【判断】晚上可能会熬夜｜x｜低｜无｜明天\n" +
                "【放下】T2｜错了｜昨晚其实睡够了\n" +
                "【打算】出声｜要不先只写开头三句？",
            Flow.dayStart(Brain.calendarDay(now)), now,
        )
        assertEquals(2, r.made.size)
        assertEquals(at(16), r.made[0].checkAt)
        assertEquals(now + 24 * 3_600_000L, r.made[1].checkAt)
        assertEquals("错了" to "昨晚其实睡够了", r.settled["T2"])
        assertEquals(listOf("要不先只写开头三句？"), r.say)
    }

    @Test fun recallFindsRelatedKnowledge() {
        val items = listOf("申论开头总是卡住", "周末想去爬山", "写申论先列三点")
        assertEquals(setOf("申论开头总是卡住", "写申论先列三点"), Recall.relevant("申论又卡在开头了", items).toSet())
        assertTrue(Recall.relevant("今天天气", items).isEmpty())
    }

    @Test fun sheThinksVerifiesAndQueuesWhatToSay() = runTest {
        val ask = Router(
            listOf(
                "自己的思考时间" to "【判断】主线卡在开头｜做事少、娱乐多｜中｜16 点前看有没有开始｜16:00 前验证\n【打算】出声｜要不先只写开头三句？\n【动作】问GPT｜他跟你定的申论框架是哪三段？",
            ),
        )
        val b = brain(ask)
        val r = b.think(at(14))!!
        assertEquals("T1", r.made.single().id)
        assertEquals(1, b.state().sayQueue.size)
        assertEquals("他跟你定的申论框架是哪三段？", b.state().questions.single().text)
        assertFalse(b.needsThink(at(14, 30)))
        assertTrue(ask.prompts.last().contains("做事 20 分钟"))

    }

    @Test fun wrongThoughtBecomesMisjudgmentAndSayIsUsed() = runTest {
        val ask = Router(
            listOf(
                "自己的思考时间" to "【判断】晚上会刷到很晚｜昨晚 1 点才睡｜中｜看 23 点后有没有刷｜明天\n【打算】出声｜今晚 23:30 前放下手机好不好？",
                "你被叫醒的原因" to "【出声】是\n【表情】担心\n【说】今晚 23:30 前放下手机好不好？\n【拦】否",
            ),
        )
        val b = brain(ask)
        b.think(at(10))
        b.judgeThought("T1", right = false, now = at(11))
        val douyin = Activity("com.ss.android.ugc.aweme", "抖音", "ENTERTAINMENT", 45)
        b.live(Trigger.LONG_SCROLL, douyin, at(15), roll = 0.9)
        val livePrompt = ask.prompts.last()
        assertTrue(livePrompt.contains("你之前想好要对他说的】今晚 23:30 前放下手机好不好？"))
        assertTrue(livePrompt.contains("我推断「晚上会刷到很晚」，错了"))
        assertTrue("说出去了就从待说里拿掉", b.state().sayQueue.isEmpty())
    }

    @Test fun chatRecallsKnowledgeAndThoughts() = runTest {
        val ask = Router(
            listOf(
                "自己的思考时间" to "【判断】申论卡在开头而不是材料｜他说过两次｜中｜下次写看开头用了多久｜明天",
                "【这次的模式】" to "那就先写开头三句。",
            ),
        )
        val b = brain(ask)
        b.ingest("申论开头总是卡住，先列三点再写就顺了", "笔记", "申论", at(9))
        b.think(at(10))
        b.chat("申论又卡在开头了", at(11))
        val p = ask.prompts.last()
        assertTrue(p.contains("你的知识里和这句话相关的"))
        assertTrue(p.contains("你自己推的判断"))
        assertTrue(p.contains("申论卡在开头而不是材料"))
    }

    @Test fun sayNowPutsHerLineIntoTheChat() = runTest {
        val b = brain(Router(listOf("自己的思考时间" to "【打算】出声｜下午那篇先写开头三句？")))
        b.think(at(14))
        assertEquals("下午那篇先写开头三句？", b.sayNow(at(15)))
        assertEquals("墨灵", b.state().talk.last().who)
        assertTrue(b.state().sayQueue.isEmpty())
        assertNull(b.sayNow(at(16)))
    }

    @Test fun modelDownMeansNoThinkRecorded() = runTest {
        val b = brain(null)
        assertNull(b.think(at(10)))
        assertTrue(b.needsThink(at(10)))
    }
}
