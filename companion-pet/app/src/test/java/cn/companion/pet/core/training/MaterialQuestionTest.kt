package cn.companion.pet.core.training

import cn.companion.pet.core.companion.MaterialContentType
import cn.companion.pet.core.companion.SharedMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 材料绑定与出题：必须来自真实正文，不能是固定样例题。 */
class MaterialQuestionTest {

    /** 足够长的正文，包含范围词与因果词；长度超过 BoundMaterial.MIN_BODY_CHARS。 */
    private val SAMPLE_BODY =
        "我们这次只邀请了二十位自愿参加的同学做了一周的训练，训练结束后测验正确率提高了大约一成。" +
        "所以任何人使用这个训练方式都会提高自己的正确率，这个方法对所有人都有效，可以放心推广到全部班级。"

    private fun material(
        id: String = "m1",
        title: String = "一份材料",
        body: String = SAMPLE_BODY,
        type: MaterialContentType = MaterialContentType.EXCERPT,
    ) = SharedMaterial(
        id = id,
        sourceEventId = "evt-" + id,
        url = "https://example.com/" + id,
        title = title,
        body = body,
        note = "",
        contentType = type,
        source = "test",
        receivedAt = 1_000,
        expiresAt = 9_999_999_999,
    )

    @Test
    fun linkOnlyHasNoReadableBody() {
        val m = BoundMaterial.of(material(type = MaterialContentType.LINK_ONLY, body = ""))
        assertFalse(m.hasReadableBody)
        assertTrue(m.evidenceNote.contains("没有正文"))
        assertNull(MaterialQuestionBuilder.build(m))
    }

    @Test
    fun shortBodyIsNotEnoughForComprehensionQuestion() {
        val m = BoundMaterial.of(material(body = "太短了。"))
        assertFalse(m.hasReadableBody)
        assertNull(MaterialQuestionBuilder.build(m))
    }

    @Test
    fun questionComesFromRealBodyWithCue() {
        val m = BoundMaterial.of(material())
        assertTrue(m.hasReadableBody)
        val plan = MaterialQuestionBuilder.build(m)
        assertNotNull(plan)
        // 引用的句子必须真的出现在正文里。
        assertTrue(SAMPLE_BODY.contains(plan!!.span.text))
        assertTrue(plan.span.cue.isNotBlank())
        assertTrue(plan.rubricPoints.isNotEmpty())
        assertTrue(plan.nextStep.isNotBlank())
    }

    @Test
    fun questionIdBindsMaterialSoDifferentMaterialsNeverShare() {
        val a = BoundMaterial.of(material(id = "mA", title = "A"))
        val b = BoundMaterial.of(material(id = "mB", title = "B",
            body = "这份报告只调查了三家城市公司，结论却是所有行业都受益。因此所有人都该照着做，全部照搬即可。"))
        val pa = MaterialQuestionBuilder.build(a)!!
        val pb = MaterialQuestionBuilder.build(b)!!
        assertNotEquals(pa.questionId, pb.questionId)
        assertEquals("mA", pa.materialId)
        assertEquals("mB", pb.materialId)
    }

    @Test
    fun sameMaterialEditedGetsNewVersionAndQuestionId() {
        val v1 = BoundMaterial.of(material())
        val v2 = BoundMaterial.of(material(body = SAMPLE_BODY + "后来又补了一批样本，但结论没有改。"))
        assertNotEquals(v1.version, v2.version)
        val p1 = MaterialQuestionBuilder.build(v1)!!
        val p2 = MaterialQuestionBuilder.build(v2)!!
        assertNotEquals(p1.questionId, p2.questionId)
    }

    @Test
    fun bodyWithoutAnyCueReturnsNoQuestion() {
        // 没有任何范围词/因果词的正文：宁可不问，也不编题。
        val plain = "今天天气很好，我去公园里散步，看到路边的花开得正好，回来以后心情一直不错，晚上还做了饭。"
        val m = BoundMaterial.of(material(body = plain))
        assertTrue(m.hasReadableBody)
        assertNull(MaterialQuestionBuilder.build(m))
    }

    @Test
    fun assembledQuestionUsesRealSentences() {
        val m = BoundMaterial.of(material())
        val plan = MaterialQuestionBuilder.build(m)!!
        val q = MaterialTraining.toQuestion(plan)
        assertFalse(q.builtInSample)
        assertEquals(m.materialId, q.materialId)
        assertEquals(m.version, q.materialVersion)
        q.sentences.forEach { assertTrue(SAMPLE_BODY.contains(it)) }
        assertTrue(q.rubricPoints.isNotEmpty())
        assertTrue(q.nextStep.isNotBlank())
    }

    @Test
    fun locatePassesOnCorrectSentenceAndFailsOnVague() {
        val m = BoundMaterial.of(material())
        val plan = MaterialQuestionBuilder.build(m)!!
        val q = MaterialTraining.toQuestion(plan)
        val c = TrainingController(TrainingSession(id = "s1", materialId = m.materialId,
            materialVersion = m.version, createdAt = 0, updatedAt = 0))
        c.bind(q)
        c.present(1_000)

        c.submit("懂了", now = 2_000)
        assertEquals(Verdict.NEED_CLARIFICATION,
            MaterialTraining.evaluateLocate(plan, c.current, emptyList(), "懂了")!!.verdict)

        val correctIdx = plan.allSpans.indexOfFirst { it.index == plan.span.index }
        c.submit("", listOf(correctIdx), now = 3_000)
        assertEquals(Verdict.PASS,
            MaterialTraining.evaluateLocate(plan, c.current, listOf(correctIdx), "")!!.verdict)
    }

    @Test
    fun connectRequiresConcreteReasonNotVague() {
        val m = BoundMaterial.of(material())
        val plan = MaterialQuestionBuilder.build(m)!!
        val q = MaterialTraining.toQuestion(plan)
        val c = TrainingController(TrainingSession(id = "s2", materialId = m.materialId, createdAt = 0, updatedAt = 0))
        c.bind(q)
        c.present(1_000)
        c.submit("有道理", now = 2_000)
        assertEquals(Verdict.NEED_CLARIFICATION,
            MaterialTraining.evaluateConnect(plan, c.current, "有道理")!!.verdict)
        c.submit("样本只是自愿参加的同学，不能代表所有人", now = 3_000)
        assertEquals(Verdict.PASS,
            MaterialTraining.evaluateConnect(plan, c.current, "样本只是自愿参加的同学，不能代表所有人")!!.verdict)
    }
}
