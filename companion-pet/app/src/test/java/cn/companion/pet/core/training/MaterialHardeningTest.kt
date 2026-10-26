package cn.companion.pet.core.training

import cn.companion.pet.core.companion.MaterialContentType
import cn.companion.pet.core.companion.SharedMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** S6 加固：出题不歧义、不泄答案、重启后本地判分不丢。 */
class MaterialHardeningTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bound(body: String) = BoundMaterial.of(SharedMaterial(
        id = "m1", sourceEventId = "evt-m1", url = null, title = "t", body = body, note = "",
        contentType = MaterialContentType.EXCERPT, source = "test", receivedAt = 1, expiresAt = Long.MAX_VALUE,
    ))

    private val BODY = "我们在一所小学的两个班里试了新的课间操，参加的孩子大多觉得精神更好了。" +
        "老师们记录了一个月的出勤情况，变化并不大。" +
        "因此所有学校都应该推广这套课间操，全部年级一律照做。" +
        "家长们周末也一起参与了几次活动。"

    @Test
    fun loneDuIsNotACue() {
        // 只有日常的“都”，没有下结论：不出题。
        val plain = "我们每天早上都去公园跑步，中午都在食堂吃饭，晚上一起看书，周末再去爬山放松一下心情。"
        assertNull(MaterialQuestionBuilder.build(bound(plain)))
    }

    @Test
    fun strongScopeBeatsWeakCue() {
        val plan = MaterialQuestionBuilder.build(bound(BODY))!!
        assertTrue(plan.span.text.contains("所有学校"))
        assertEquals("所有", plan.span.cue)
    }

    @Test
    fun distractorsHaveNoCueSoAnswerIsUnique() {
        val plan = MaterialQuestionBuilder.build(bound(BODY))!!
        val others = plan.allSpans.filter { it.index != plan.span.index }
        assertTrue(others.isNotEmpty())
        others.forEach { assertFalse(it.text, it.text.contains("所有") || it.text.contains("因此") || it.text.contains("都")) }
        // 原文顺序：正确句不是总排第一。
        assertEquals(plan.allSpans.sortedBy { it.index }, plan.allSpans)
        assertTrue(plan.allSpans.first().index != plan.span.index)
    }

    @Test
    fun stemListsAllCandidatesInsteadOfTheAnswer() {
        val plan = MaterialQuestionBuilder.build(bound(BODY))!!
        val q = MaterialTraining.toQuestion(plan)
        plan.allSpans.forEach { assertTrue(q.stem.contains(it.text)) }
        // 题干第一段只有问题本身，没有单独标出答案。
        assertFalse(q.stem.substringBefore("材料：").contains(plan.span.text))
    }

    @Test
    fun singleCharCueNeedsShortAnswer() {
        val body = "这次只调查了三个人的看法，他们觉得新功能挺好用的，而且上手也快。" +
            "说明大家都会喜欢这个新功能，可以直接上线了。"
        val plan = MaterialQuestionBuilder.build(bound(body))!!
        assertEquals("都", plan.span.cue)
        val q = MaterialTraining.toQuestion(plan)
        val c = TrainingController(TrainingSession(id = "s", materialId = plan.materialId, materialVersion = plan.materialVersion))
        c.bind(q); c.present(1)
        c.submit("我都不知道", now = 2)
        assertEquals(Verdict.NEED_CLARIFICATION, MaterialTraining.evaluateLocate(plan, c.current, emptyList(), "我都不知道")!!.verdict)
        c.acceptAdvice(MaterialTraining.evaluateLocate(plan, c.current, emptyList(), "我都不知道")!!, 3)
        c.submit("都", now = 4)
        assertEquals(Verdict.PASS, MaterialTraining.evaluateLocate(plan, c.current, emptyList(), "都")!!.verdict)
    }

    @Test
    fun planIsRebuiltFromSavedQuestionAfterRestart() {
        val plan = MaterialQuestionBuilder.build(bound(BODY))!!
        val q = MaterialTraining.toQuestion(plan)
        val c = TrainingController(TrainingSession(id = "s1", materialId = plan.materialId, materialVersion = plan.materialVersion))
        c.bind(q); c.present(1)
        assertTrue(TrainingStore(tmp.root).save(c.current, q))
        val r = TrainingStore(tmp.root).restore(2) as TrainingStore.Restore.Resume
        val restored = MaterialTraining.restorePlan(r.question)
        assertNotNull(restored)
        assertEquals(plan.span.text, restored!!.span.text)
        assertEquals(plan.span.cue, restored.span.cue)
        assertEquals(plan.questionId, restored.questionId)
        // 恢复后的计划照样能本地判第一步：点选正确句即通过。
        val c2 = TrainingController(r.session).also { it.bind(r.question) }
        val idx = restored.allSpans.indexOfFirst { it.index == restored.span.index }
        c2.submit("", listOf(idx), now = 3)
        val advice = MaterialTraining.evaluateLocate(restored, c2.current, listOf(idx), "")!!
        assertEquals(Verdict.PASS, advice.verdict)
        assertTrue(c2.acceptAdvice(advice, 4) is TrainingOutcome.NextSlot)
    }

    @Test
    fun builtInQuestionsAreNotRestoredAsMaterial() {
        assertNull(MaterialTraining.restorePlan(BuiltInQuestions.SAMPLES.first()))
    }
}
