package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInQuestionsTest {

    @Test
    fun hasEnoughDistinctQuestionsForFiveSet() {
        assertTrue("至少要有 5 道不同题才能演示五题换休息", BuiltInQuestions.SAMPLES.size >= 5)
        assertEquals("题号必须唯一", BuiltInQuestions.SAMPLES.size,
            BuiltInQuestions.SAMPLES.map { it.id }.toSet().size)
    }

    @Test
    fun everyQuestionCarriesRubricAndNextStep() {
        BuiltInQuestions.SAMPLES.forEach { q ->
            assertTrue(q.id + " 缺评分依据", q.rubricPoints.isNotEmpty())
            assertTrue(q.id + " 缺下一步", q.nextStep.isNotBlank())
            assertTrue(q.id + " 缺句子", q.sentences.size >= 2)
            assertTrue(q.id + " 必须标注内置示例", q.builtInSample)
        }
    }

    @Test
    fun pickSkipsAnswered() {
        val first = BuiltInQuestions.SAMPLES.first().id
        val next = BuiltInQuestions.pick(setOf(first))
        assertTrue("应跳过已答过的题", next.id != first)
    }

    @Test
    fun pickManyReturnsDistinct() {
        val batch = BuiltInQuestions.pickMany(emptySet(), 5)
        assertEquals(5, batch.size)
        assertEquals(5, batch.map { it.id }.toSet().size)
    }
}
