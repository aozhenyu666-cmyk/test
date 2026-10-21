package com.moling.core

import com.moling.core.atlas.Atlas
import com.moling.core.atlas.AtlasMap
import com.moling.core.atlas.Node
import com.moling.core.atlas.NodeKind
import com.moling.core.atlas.Topic
import com.moling.core.autopilot.Autopilot
import com.moling.core.autopilot.AutopilotSignal
import com.moling.core.flow.Flow
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkStatus
import com.moling.core.live.Activity
import com.moling.core.live.Can
import com.moling.core.live.HabitBook
import com.moling.core.live.Habits
import com.moling.core.live.Modality
import com.moling.core.live.Quizzes
import com.moling.core.live.Reaction
import com.moling.core.live.Trigger
import com.moling.core.mind.Ask
import com.moling.core.mind.BEIJING
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource
import com.moling.core.mind.MindStore
import com.moling.core.usage.Session
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 自动驾驶识别、反免疫账本、捉虫题、体系图沙盘，以及它们接进大脑。 */
class AutopilotTest {
    private fun at(h: Int, mi: Int = 0, s: Int = 0) = LocalDateTime.of(2026, 10, 21, h, mi, s).atZone(BEIJING).toInstant().toEpochMilli()
    private fun ses(pkg: String, cat: String, a: Long, b: Long) = Session(pkg, pkg, cat, a, b)

    @Test fun hoppingBetweenApps() {
        val now = at(15, 5)
        // 5 分钟里在 4 个应用之间切了 7 次
        val pk = listOf("wx", "bili", "xianyu", "launcher")
        val s = (0 until 8).map { i -> ses(pk[i % 4], if (pk[i % 4] == "bili") "ENTERTAINMENT" else "OTHER", at(15) + i * 35_000L, at(15) + i * 35_000L + 30_000L) }
        val r = Autopilot.read(s, now)
        assertTrue(r.reasons.first().contains("切了 7 次"))
        assertEquals(35, r.score)
    }

    @Test fun reopeningAndLongRunAddUp() {
        val now = at(15, 15)
        val s = listOf(
            ses("aweme", "ENTERTAINMENT", at(15, 1), at(15, 3)),
            ses("launcher", "OTHER", at(15, 3), at(15, 4)),
            ses("aweme", "ENTERTAINMENT", at(15, 4), at(15, 6)),
            ses("launcher", "OTHER", at(15, 6), at(15, 7)),
            ses("aweme", "ENTERTAINMENT", at(15, 7), at(15, 9)),
            ses("wx", "OTHER", at(15, 9), at(15, 10)),
            ses("aweme", "ENTERTAINMENT", at(15, 10), at(15, 15)),
        )
        val r = Autopilot.read(s, now)
        assertTrue(r.reasons.any { it.contains("回到「aweme」4 次") })
        assertTrue(r.score >= 30)
        val long = Autopilot.read(listOf(ses("aweme", "ENTERTAINMENT", at(14, 20), at(15, 15))), now)
        assertTrue(long.on.not() && long.score == 35)
    }

    @Test fun quietUseIsNotAutopilot() {
        val now = at(15, 30)
        val r = Autopilot.read(listOf(ses("notion.id", "WORK", at(14, 30), at(15, 30))), now)
        assertEquals(0, r.score)
    }

    @Test fun habitsRestTiredModalities() {
        var b = HabitBook()
        val can = Can(overlay = true, whisper = true, quiz = true)
        b = Habits.shown(b, Modality.PET, at(10))
        b = Habits.react(b, Modality.PET, Reaction.DISMISSED_FAST, 800, at(10))
        assertFalse(Habits.cooling(b, Modality.PET, at(11)))
        b = Habits.shown(b, Modality.PET, at(12))
        b = Habits.react(b, Modality.PET, Reaction.IGNORED, 0, at(12))
        assertTrue("连着两次被秒划 / 不理，歇 48 小时", Habits.cooling(b, Modality.PET, at(13)))
        (0 until 50).forEach { i -> assertNotEquals(Modality.PET, Habits.pick(b, can, at(13), i / 50.0)) }
        assertEquals("什么都不能用时就是底部卡片", Modality.PEEK, Habits.pick(HabitBook(), Can(), at(13), 0.7))
        assertTrue(Habits.summary(b, at(13)).single().contains("歇着"))
    }

    @Test fun quizzes() {
        val q = Quizzes.parse("【句子】写申论｜先列三点｜再写开头｜最后改结尾\n【错的】2\n【正确】先列两点", "写申论先列两点再写开头")!!
        assertEquals(1, q.wrong)
        val local = Quizzes.local("每天写 3 段，写完再刷视频")!!
        assertTrue(local.parts.joinToString("").contains("7"))
        assertEquals("3", local.fix)
        assertNotNull(Quizzes.local("开头卡住时不会先列提纲，所以写不下去"))
        assertEquals(null, Quizzes.parse("【句子】只有一段\n【错的】1", "x"))
    }

    @Test fun atlasIslands() {
        val a = Atlas(
            topics = listOf(Topic("t1", "申论")),
            nodes = listOf(
                Node("n1", "t1", "开头卡住是因为没列提纲", NodeKind.JUDGMENT, "笔记", verified = "试过三次"),
                Node("n2", "t1", "每天写一段能保持手感", NodeKind.HYPOTHESIS, "笔记"),
            ),
        )
        val i = AtlasMap.islands(a).single()
        assertEquals(1, i.lit)
        assertEquals(0.5f, i.progress)
        assertTrue(AtlasMap.line(i).startsWith("「申论」亮了 1/2"))
    }

    // ---------------------------------------------------------------- 接进大脑

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

    @Test fun autopilotWakesHerWithCuesAndAModality() = runTest {
        val ask = Router(listOf("你被叫醒的原因" to "【出声】是\n【表情】好奇\n【说】你在微信和 B 站之间切了七次了，在找什么？\n【拦】否"))
        val b = Brain(phone, ask, MemStore(), MemStore(), Zk())
        val bili = Activity("tv.danmaku.bili", "哔哩哔哩", "ENTERTAINMENT", 1)
        val sig = AutopilotSignal(70, listOf("5 分钟里在 4 个应用之间切了 7 次"))
        assertEquals(Trigger.AUTOPILOT, b.liveTrigger(bili, at(15), autopilot = sig))
        assertEquals("在桌面横跳也算", Trigger.AUTOPILOT, b.liveTrigger(Activity("launcher", "桌面", "OTHER", 0), at(15), autopilot = sig))
        val t = b.live(Trigger.AUTOPILOT, bili, at(15), roll = 0.9, can = Can(overlay = true), cues = sig.reasons)!!
        assertTrue(ask.prompts.last().contains("【你看到的】5 分钟里在 4 个应用之间切了 7 次"))
        assertTrue(ask.prompts.last().contains("小石子"))
        assertTrue(t.modality in setOf(Modality.PEEK, Modality.PET, Modality.STARE, Modality.COUNCIL))
        assertEquals(t.modality, b.state().habits.marks.last().modality)
        b.react(t.modality, Reaction.REPLIED, 4000, at(15, 1))
        assertEquals(Reaction.REPLIED, b.state().habits.marks.last().reaction)
    }

    @Test fun ignoredOftenSheAsksAGuess() = runTest {
        val ask = Router(listOf("你被叫醒的原因" to "【出声】是\n【表情】好奇\n【说】我猜你今天主线又要拖到明天，对吗？\n【拦】否"))
        val b = Brain(phone, ask, MemStore(), MemStore(), Zk())
        b.react(Modality.PEEK, Reaction.DISMISSED_FAST, 500, at(10))
        b.react(Modality.PEEK, Reaction.IGNORED, 0, at(11))
        b.live(Trigger.UNLOCK, null, at(15), roll = 0.9)
        assertTrue(ask.prompts.last().contains("我猜……，对吗？"))
    }

    @Test fun quizFromHisKnowledge() = runTest {
        val b = Brain(phone, null, MemStore(), MemStore(), Zk())
        b.editAbout("# 我对你的了解\n- 每天写 3 段申论就不会断手感", at(9))
        val q = b.quiz(at(10))!!
        assertTrue(q.parts.joinToString("").contains("7"))
    }
}
