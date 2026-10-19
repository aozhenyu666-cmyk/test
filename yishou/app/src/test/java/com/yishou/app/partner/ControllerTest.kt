package com.yishou.app.partner

import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.voice.ApiReply
import com.yishou.app.voice.RequestTag
import com.yishou.app.voice.StreamingTextModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeMouth : Mouth {
    val said = mutableListOf<String>()
    var cancelled = 0
    override suspend fun say(text: String) {
        try {
            delay(500)
            said += text
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled++
            throw e
        }
    }
}

private class FakeTalker(var reply: String = "先别急。你觉得关键是哪一句？", var fail: Boolean = false) : StreamingTextModel {
    val systems = mutableListOf<String>()
    val users = mutableListOf<String>()
    override suspend fun stream(tag: RequestTag, system: String, history: List<Pair<String, String>>, user: String, onDelta: suspend (String) -> Unit): ApiReply<String> {
        systems += system
        users += user
        if (fail) return ApiReply(tag, LlmResult.Err(LlmError.Timeout))
        reply.chunked(4).forEach {
            delay(100)
            onDelta(it)
        }
        return ApiReply(tag, LlmResult.Ok(reply))
    }
}

private class FakeDirector : Director {
    var calls = 0
    override suspend fun card(input: DirectorInput): LlmResult<DirectorCard> {
        calls++
        return LlmResult.Ok(DirectorCard("追问条件", 4, input.plan.gear, "是成本变了，还是客人变了？", input.elapsedMin >= input.plan.minutes))
    }
}

class ControllerTest {

    private val plan = PartnerPlan(Material.ARTICLE, "县城咖啡店", minutes = 10, gear = Gear.ACCOMPANY)

    private fun TestScope.make(
        talker: FakeTalker = FakeTalker(),
        director: FakeDirector = FakeDirector(),
        mouth: FakeMouth = FakeMouth(),
        lastNext: String? = null,
        closed: MutableList<Triple<String, String, Int>> = mutableListOf(),
    ) = PartnerController(
        plan, talker, director, mouth, backgroundScope, clock = { testScheduler.currentTime },
        lastNext = lastNext, onClosed = { a, b, c -> closed += Triple(a, b, c) },
    )

    @Test
    fun opensAndAsksAboutLastStep() = runTest {
        val mouth = FakeMouth()
        val c = make(mouth = mouth, lastNext = "做两道资料分析")
        c.start()
        advanceTimeBy(1_000)
        assertTrue(mouth.said.single().contains("做两道资料分析"))
        assertEquals(Stage.PROBE, c.state.value.stage)
    }

    @Test
    fun replySpeaksSentencesWithDirectorCardHidden() = runTest {
        val talker = FakeTalker()
        val mouth = FakeMouth()
        val c = make(talker = talker, mouth = mouth)
        c.start()
        advanceTimeBy(1_000)
        c.heard("我觉得是客人不一样，好了")
        advanceTimeBy(5_000)
        assertEquals("我觉得是客人不一样", talker.users.single())
        assertTrue(talker.systems.single().contains("【内部导演卡】"))
        assertEquals(listOf("先别急。", "你觉得关键是哪一句？"), mouth.said.drop(1))
        assertFalse(mouth.said.any { "导演卡" in it })
        val lines = c.state.value.lines
        assertEquals(Turn("伙伴", "先别急。你觉得关键是哪一句？"), lines.last())
        assertEquals(Stage.PROBE, c.state.value.stage)
    }

    @Test
    fun unansweredLadderThenGiveUp() = runTest {
        val mouth = FakeMouth()
        val c = make(mouth = mouth)
        c.start()
        advanceTimeBy(1_000)
        c.heard("是客人不一样")
        advanceTimeBy(5_000)
        val before = mouth.said.size
        advanceTimeBy(21_000) // 第一次没接：缩短重问
        assertEquals(before + 1, mouth.said.size)
        assertTrue(mouth.said.last().endsWith("？"))
        advanceTimeBy(21_000) // 第二次：二选一
        assertTrue("还是" in mouth.said.last())
        advanceTimeBy(21_000) // 第三次：记一笔，不再说话
        assertEquals(before + 2, mouth.said.size)
        assertEquals(1, c.state.value.missed)
        assertEquals(Stage.ACCOMPANY, c.state.value.stage)
    }

    @Test
    fun speakingAloudPausesTheWait() = runTest {
        val mouth = FakeMouth()
        val c = make(mouth = mouth)
        c.start()
        advanceTimeBy(1_000)
        c.heard("是客人不一样")
        advanceTimeBy(5_000)
        val before = mouth.said.size
        repeat(4) {
            advanceTimeBy(10_000)
            c.activity()
        }
        assertEquals(before, mouth.said.size)
        assertEquals(0, c.state.value.missed)
    }

    @Test
    fun interruptStopsVoiceAndDropsLateReply() = runTest {
        val talker = FakeTalker(reply = "第一句很长很长很长。第二句也很长很长。第三句还是很长。")
        val mouth = FakeMouth()
        val c = make(talker = talker, mouth = mouth)
        c.start()
        advanceTimeBy(1_000)
        c.heard("说说看")
        advanceTimeBy(900) // 第一句刚开始念
        c.interrupt()
        advanceTimeBy(10_000)
        assertTrue(mouth.cancelled >= 1)
        assertFalse(mouth.said.any { "第二句" in it || "第三句" in it })
        assertEquals(Phase.LISTENING, c.state.value.phase)
    }

    @Test
    fun timeUpClosesAndSaves() = runTest {
        val closed = mutableListOf<Triple<String, String, Int>>()
        val mouth = FakeMouth()
        val c = make(mouth = mouth, closed = closed)
        c.start()
        advanceTimeBy(10 * 60_000L + 2_000)
        assertEquals(Stage.CLOSE, c.state.value.stage)
        assertTrue(mouth.said.last().contains("聊出了什么"))
        c.heard("挺好")
        advanceTimeBy(2_000)
        assertTrue(mouth.said.last().contains("再具体一点"))
        c.heard("新区活下来是因为客群本来就喝咖啡")
        advanceTimeBy(2_000)
        assertTrue(mouth.said.last().contains("下一步"))
        c.heard("明天做两道削弱题")
        advanceTimeBy(2_000)
        // 十分钟一句没说：中途主动问的那一问没接，记了一笔
        assertEquals(Triple("新区活下来是因为客群本来就喝咖啡", "明天做两道削弱题", 1), closed.single())
        assertTrue(mouth.said.last().contains("1 个问题没接上"))
        assertEquals(Stage.ENDED, c.state.value.stage)
    }

    @Test
    fun spokenCommands() = runTest {
        val talker = FakeTalker()
        val mouth = FakeMouth()
        val c = make(talker = talker, mouth = mouth)
        c.start()
        advanceTimeBy(1_000)
        c.heard("逼紧一点")
        advanceTimeBy(2_000)
        assertEquals(Gear.STRICT, c.state.value.gear)
        c.heard("今天到这")
        advanceTimeBy(2_000)
        assertEquals(Stage.CLOSE, c.state.value.stage)
        assertTrue(talker.users.isEmpty())
        // 说的是长句、或者在讨论，不当口令
        val c2 = make(talker = talker)
        c2.start()
        advanceTimeBy(1_000)
        c2.heard("作者为什么说今天到这里就够了")
        advanceTimeBy(5_000)
        assertEquals(1, talker.users.size)
    }

    @Test
    fun directorEveryFewTurns() = runTest {
        val director = FakeDirector()
        val c = make(director = director)
        c.start()
        runCurrent()
        assertEquals(1, director.calls)
        repeat(3) {
            c.heard("第 $it 句回答，内容够长")
            advanceTimeBy(6_000)
        }
        assertEquals(2, director.calls)
        advanceTimeBy(95_000)
        assertTrue(director.calls >= 3)
    }

    @Test
    fun modelFailureFallsBackToHook() = runTest {
        val talker = FakeTalker(fail = true)
        val mouth = FakeMouth()
        val c = make(talker = talker, mouth = mouth)
        c.start()
        advanceTimeBy(1_000)
        c.heard("是客人不一样")
        advanceTimeBy(3_000)
        assertEquals("是成本变了，还是客人变了？", mouth.said.last())
        assertEquals(Stage.PROBE, c.state.value.stage)
        assertTrue(c.state.value.error != null)
    }

    @Test
    fun idleProbeAndQuiet() = runTest {
        val mouth = FakeMouth()
        val c = make(mouth = mouth)
        c.start()
        advanceTimeBy(1_000)
        val before = mouth.said.size
        advanceTimeBy(241_000)
        assertEquals(before + 1, mouth.said.size)
        c.heard("安静")
        advanceTimeBy(2_000)
        val after = mouth.said.size
        advanceTimeBy(600_000 - 250_000)
        assertEquals(after, mouth.said.size)
    }
}
