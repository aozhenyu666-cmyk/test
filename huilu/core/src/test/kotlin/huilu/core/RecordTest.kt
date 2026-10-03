package huilu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordTest {
    @Test
    fun everyEventSurvivesTheLogRoundTrip() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("学习课程", "要交作业", 30, Expectation(EnvMode.IN_APPS, setOf("course")), strict = true)
        repeat(4) {
            sim.phone.switchTo("launcher")
            sim.advance(0.25)
            sim.phone.switchTo("bili")
            sim.advance(2.25)
            val c = sim.s.pending!!
            sim.engine.note(c.id, "刷到一个视频", Via.NOTIFICATION)
            sim.engine.answer(c.id, AnswerKind.RECOMMIT, text = "回去", via = Via.SCREEN)
        }
        sim.prompts.forEach { sim.engine.interventionResult(it.intervention.id, ActResult.VERIFIED, "ok") }
        sim.advance(1.0)
        val rest = sim.s.pending ?: run { sim.engine.checkNow(); sim.s.pending!! }
        sim.engine.answer(rest.id, AnswerKind.REST, minutes = 3)
        sim.advance(3.5)
        sim.engine.answer(sim.s.pending!!.id, AnswerKind.SHRINK, newText = "只做一题", minutes = 4)
        sim.engine.answer(sim.s.pending?.id ?: "", AnswerKind.MUTE)
        sim.engine.stop(Outcome.ABANDONED)

        val types = sim.log.events.map { it::class }.toSet()
        assertTrue("覆盖足够多的事件类型: $types", types.size >= 14)
        for (e in sim.log.events) {
            val line = Codec.encode(e)
            assertEquals(line, e, Codec.decode(line))
        }
    }

    @Test
    fun lockEventsRoundTrip() {
        for (e in listOf(Event.Locked(5, setOf("b", "a")), Event.Unlocked(6, setOf("a")))) {
            assertEquals(e, Codec.decode(Codec.encode(e)))
        }
        val s = Situation.replay(listOf(Event.Locked(5, setOf("a", "b")), Event.Unlocked(6, setOf("a"))))
        assertEquals(setOf("b"), s.locked)
    }

    @Test
    fun corruptOrUnknownLinesAreSkipped() {
        assertNull(Codec.decode("{\"t\":\"from_the_future\",\"at\":1}"))
        assertNull(Codec.decode("{\"t\":\"action_sta"))
    }

    @Test
    fun llmOutputIsConfinedToTheOfferedChoices() {
        val allowed = listOf(AnswerKind.RECOMMIT, AnswerKind.REST, AnswerKind.SHRINK)
        val ok = LlmJudge.parse("好的：\n{\"assessment\":\"在查资料时顺手点开了视频\",\"kind\":\"shrink\",\"minutes\":3,\"new_text\":\"只看完第一节\",\"reply\":\"先只看第一节。\"}", allowed)!!
        assertEquals(AnswerKind.SHRINK, ok.kind)
        assertEquals(3, ok.minutes)
        assertEquals("只看完第一节", ok.new_text())
        val outside = LlmJudge.parse("{\"kind\":\"DONE\",\"reply\":\"x\"}", allowed)!!
        assertNull("模型不能选择这次检查没给出的选项", outside.kind)
        assertNull(LlmJudge.parse("我觉得你应该继续", allowed))
        val weird = LlmJudge.parse("{\"kind\":null,\"minutes\":999,\"reply\":\"你现在卡在哪一步？\"}", allowed)!!
        assertNull(weird.kind)
        assertNull(weird.minutes)
    }

    private fun LlmJudge.Interpretation.new_text() = newText

    @Test
    fun insightsSummariseWhereAndWhenDriftHappens() {
        val sim = Sim()
        val course = Expectation(EnvMode.IN_APPS, setOf("course"))
        repeat(3) { i ->
            sim.phone.switchTo("course")
            sim.engine.start("学习课程", "", 10, course, strict = false)
            sim.advance(1.0 + i)
            sim.phone.switchTo("bili")
            sim.advance(2.25)
            sim.engine.answer(sim.s.pending!!.id, AnswerKind.RECOMMIT)
            sim.phone.switchTo("course")
            sim.engine.stop(Outcome.DONE)
            sim.advance(1.0)
        }
        val ins = Insights.of(Timeline.build(sim.log.events))
        assertEquals(3, ins.episodes)
        assertEquals(3, ins.driftedEpisodes)
        assertEquals(listOf(1, 2, 3), ins.onsetMinutes)
        assertEquals(2, ins.medianOnset)
        assertEquals("bili", ins.topDriftApps.first().first)
        assertEquals(Triple("学习课程", 3, 3), ins.byAction.single())
        assertEquals(3, ins.levels[Level.NOTIFY]!!.requested)
        // 每 15 秒 tick 一次，阈值 2 分钟：最晚 2 分 15 秒内重新出现
        assertEquals(3, ins.driftLatencies.size)
        assertTrue(ins.driftLatencies.all { it in 120_000L..135_000L })
    }
}
