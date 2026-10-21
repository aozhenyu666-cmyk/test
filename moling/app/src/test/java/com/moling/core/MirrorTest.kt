package com.moling.core

import com.moling.core.flow.Flow
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkStatus
import com.moling.core.mind.Ask
import com.moling.core.mind.BEIJING
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource
import com.moling.core.mind.MindStore
import com.moling.core.mirror.InboxItem
import com.moling.core.mirror.Mirror
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 贯通：黑板、收件箱、想问 GPT 的、深想模型。 */
class MirrorTest {
    private fun at(h: Int, mi: Int = 0, d: Int = 21) = LocalDateTime.of(2026, 10, d, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class Zk : ZkPort {
        override fun status() = ZkStatus(workMinutesToday = 30, playMinutesToday = 70)
        override fun send(command: ZkCommand) = ZkReceipt(true, "收下了", 0)
    }

    private class Replies(vararg r: String) : Ask {
        val q = ArrayDeque(r.toList())
        val prompts = mutableListOf<String>()
        override suspend fun ask(system: String, user: String): String? { prompts += user; return q.removeFirstOrNull() }
    }

    private val phone = FactsSource { Facts(now = it, minute = Flow.minuteOf(it)) }
    private fun brain(ask: Ask? = null, deep: Ask? = null) = Brain(phone, ask, MemStore(), MemStore(), Zk(), deep = deep)

    @Test fun pageIdFromLinks() {
        val id = "1a2b3c4d5e6f40718293a4b5c6d7e8f9"
        val want = "1a2b3c4d-5e6f-4071-8293-a4b5c6d7e8f9"
        assertEquals(want, Mirror.pageId("https://www.notion.so/Moling-$id"))
        assertEquals(want, Mirror.pageId("https://www.notion.so/me/Bee-$id?pvs=4"))
        assertEquals(want, Mirror.pageId(want))
        assertNull(Mirror.pageId("https://www.notion.so/"))
    }

    @Test fun boardShowsWhatSheKnowsAndAsks() = runTest {
        val b = brain()
        b.askGpt("他上周跟你说的申论提纲定下来没有？", at(9))
        b.editAbout("# 我对你的了解\n- 晚上容易刷短视频", at(9))
        val board = b.board(at(10))
        val text = board.joinToString("\n") { it.title + "\n" + it.lines.joinToString("\n") }
        assertTrue(text.contains("Q1：他上周跟你说的申论提纲定下来没有？"))
        assertTrue(text.contains("晚上容易刷短视频"))
        assertTrue(text.contains("《给墨灵的收件箱》"))
        assertEquals("时间变了、内容没变，指纹不变", Mirror.hash(board), Mirror.hash(b.board(at(11))))
    }

    @Test fun handoffForAnyAi() = runTest {
        val b = brain()
        b.askGpt("他周末的复习计划定了吗？", at(9))
        val h = b.handoff(at(10))
        assertTrue(h.startsWith("你好。下面是墨灵"))
        assertTrue(h.contains("## 墨灵想问你的"))
        assertTrue(h.contains("- Q1：他周末的复习计划定了吗？"))
        assertFalse("给 Notion 的说明不放进交接包", h.contains("给 GPT / Notion AI 的说明"))
    }

    @Test fun absorbAnswersQuestionsAndProposesAboutChange() = runTest {
        val deep = Replies(
            "【回答】Q1｜提纲定了：三段式，周四前写完第一篇\n" +
                "【论断】n1｜判断｜申论卡在开头，不是卡在材料｜中｜看下次写开头用了多久\n" +
                "【了解】写东西卡住时先列三点就能开始\n" +
                "【提醒】周四前要交第一篇",
        )
        val b = brain(Replies(), deep)
        b.askGpt("申论提纲定了吗？", at(9))
        val items = listOf(InboxItem("blk-1", "Q1：定了，三段式……", "GPT"))
        val r = b.absorb(items, at(10))
        assertEquals(listOf("Q1"), r.answered)
        assertEquals(1, r.added)
        assertTrue(r.aboutProposal)
        assertEquals(listOf("周四前要交第一篇"), r.notes)
        assertTrue(b.state().questions.single().answer.startsWith("提纲定了"))
        assertTrue("了解没直接改，要他点好", b.state().about.isBlank())
        assertTrue(b.state().proposals.any { it.act is Act.About })
        // 同一条不再收
        assertEquals(0, b.absorb(items, at(11)).added)
        assertEquals(1, deep.prompts.size)
    }

    @Test fun modelDownMeansTryAgainLater() = runTest {
        val b = brain()
        val items = listOf(InboxItem("blk-1", "一段笔记", "Notion"))
        b.absorb(items, at(10))
        assertTrue(b.state().mirrorSeen.isEmpty())
    }

    @Test fun deepFallsBackToNormalModel() = runTest {
        val normal = Replies("【问题】他跟你说过想换方向吗？｜这几天主线没开始")
        val dead = object : Ask { override suspend fun ask(system: String, user: String): String? = null }
        val b = brain(normal, dead)
        val made = b.prepareQuestions(at(23, 40))
        assertEquals(1, made.size)
        assertEquals("她", made.single().by)
        assertTrue(b.board(at(23, 41)).any { s -> s.lines.any { it.contains("想换方向") } })
    }

    @Test fun sheCanDecideToAskGptInChat() = runTest {
        val b = brain(Replies("这个我也拿不准。\n【动作】问GPT｜他之前跟你定的复习顺序是什么？"))
        b.chat("我忘了之前跟 GPT 定的复习顺序", at(15))
        assertEquals("他之前跟你定的复习顺序是什么？", b.state().questions.single().text)
        assertFalse(b.state().talk.last().text.contains("【动作】"))
    }
}
