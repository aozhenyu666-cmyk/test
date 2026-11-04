package com.dshdriver.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateTest {
    private val profile = Profile(goals = listOf("12 月省考行测"), habits = listOf("抓到一半就下结论"))

    @Test fun questionPriority() {
        val agenda = Agenda(newId = { "A1" })
        agenda.add("missing", "第3题·黑点数量：缺「递增」的依据", 10, "第3题·黑点数量", missing = "递增")
        val q1 = Gate.pick(agenda, "选D", "第3题", profile)
        assertEquals("agenda", q1.source)
        assertTrue(q1.text.contains("第3题·黑点数量") && q1.text.contains("递增"))
        assertEquals("A1", q1.agendaId)

        val q2 = Gate.pick(Agenda(), "第3题选D", "第3题", profile)
        assertEquals("conclusion", q2.source)
        assertTrue(q2.text.contains("第3题选D"))

        assertEquals("goal", Gate.pick(null, null, null, profile).source)
        assertEquals("default", Gate.pick(null, null, null, Profile()).source)
    }

    @Test fun onlyGroundedAnswersPass() {
        val q = Gate.Question("缺「递增」的依据是什么？", "第3题", "递增", null, "agenda")
        assertFalse(Gate.judge("嗯", q).pass)
        assertFalse(Gate.judge("A", q).pass, "点选不算")
        assertFalse(Gate.judge("不知道", q).pass)
        assertFalse(Gate.judge("感觉是", q).pass, "太短")
        assertTrue(Gate.judge("因为题干第二句说了数量", q).pass)
        assertTrue(Gate.judge("黑点每格递增", q).pass, "命中要的那个词")
        assertTrue(Gate.judge("我觉得这一题的思路应该先看整体再看局部特征", q).pass, "够长的实在话")
    }

    @Test fun stricterMinimumLength() {
        val q = Gate.Question("说说你的依据", null, null, null, "default")
        val plain = "这是一句刚好十五个字的普通回答内容"
        assertTrue(Gate.judge(plain, q, 15).pass, "17 个字，15 字门槛应放行")
        assertFalse(Gate.judge(plain, q, 30).pass, "17 个字，30 字门槛应拒绝")
        assertTrue(Gate.judge("因为题干第二句说了数量", q, 50).pass, "有明确依据可豁免字数门槛")
    }

    @Test fun llmQuestionMustAskForReasoning() {
        assertTrue(Gate.acceptsLlm(Draft("one_word", "依据是哪个词？", "evidence")))
        assertFalse(Gate.acceptsLlm(Draft("ab", "选哪个？", "choice", Options("a", "b"))))
        assertFalse(Gate.acceptsLlm(null))
    }
}
