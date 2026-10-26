package cn.companion.pet.core.training

import cn.companion.pet.core.companion.MaterialContentType
import cn.companion.pet.core.companion.SharedMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语义确认：本地只验形式，语义交给模型；确认失败不冒充通过。
 */
class SemanticConfirmTest {

    private val BODY =
        "这份报告只采访了十二位自由职业者，说他们用某个记账软件之后每月结余变多了。" +
        "所以任何人使用这个软件都能改善财务状况，所有人都应该立刻换成它，全部家庭都适用。"

    private fun plan(): MaterialQuestionPlan {
        val m = SharedMaterial(
            id = "sem", sourceEventId = "evt", url = "https://example.com/s",
            title = "记账", body = BODY, note = "",
            contentType = MaterialContentType.EXCERPT, source = "test",
            receivedAt = 1_000, expiresAt = 9_999_999_999,
        )
        return MaterialQuestionBuilder.build(BoundMaterial.of(m))!!
    }

    private fun session(): TrainingSession =
        TrainingSession(id = "s1", questionId = "q1", createdAt = 0, updatedAt = 0)

    @Test
    fun supportsProducesRealPassWithoutUncertainty() {
        // 只有确认 SUPPORTS 才产生可采纳的 PASS。
        val advice = SemanticConfirmBridge.toAdvice(
            session(), plan(), SemanticVerdict.Supports("指向样本缺口"),
        )
        assertEquals(Verdict.PASS, advice.verdict)
        assertEquals("", advice.uncertainty)
        assertEquals("SEMANTIC_CONFIRMED", advice.evaluationSource)
        assertTrue(advice.evidenceRefs.isNotEmpty())
    }

    @Test
    fun notSupportsRequiresClarification() {
        val advice = SemanticConfirmBridge.toAdvice(
            session(), plan(), SemanticVerdict.NotSupports("方向不对"),
        )
        assertEquals(Verdict.NEED_CLARIFICATION, advice.verdict)
        assertTrue(advice.missingStep.isNotBlank())
    }

    @Test
    fun unclearRequiresClarification() {
        val advice = SemanticConfirmBridge.toAdvice(
            session(), plan(), SemanticVerdict.Unclear("太笼统"),
        )
        assertEquals(Verdict.NEED_CLARIFICATION, advice.verdict)
    }

    @Test
    fun notConfirmedRetriesInsteadOfPassing() {
        // 没 Key / 超时：不判通过也不判失败。
        val advice = SemanticConfirmBridge.toAdvice(
            session(), plan(), SemanticVerdict.NotConfirmed("模型用不了"),
        )
        assertEquals(Verdict.RETRY, advice.verdict)
        assertTrue(advice.uncertainty.isNotBlank())
        assertEquals("SEMANTIC_UNAVAILABLE", advice.evaluationSource)
    }

    @Test
    fun parseHandlesValidAndInvalidShape() {
        assertEquals("指向缺口",
            (SemanticConfirmPrompt.parse("{\"verdict\":\"SUPPORTS\",\"why\":\"指向缺口\"}") as SemanticVerdict.Supports).why)
        assertTrue(SemanticConfirmPrompt.parse("{\"verdict\":\"SUPPORTS\",\"why\":\"x\"}") is SemanticVerdict.Supports)
        // 缺字段 / 非法值 → null，调用方走 NotConfirmed。
        assertEquals(null, SemanticConfirmPrompt.parse("{\"why\":\"没有 verdict\"}"))
        assertEquals(null, SemanticConfirmPrompt.parse("not json"))
        assertEquals(null, SemanticConfirmPrompt.parse("{\"verdict\":\"MAYBE\"}"))
    }

    @Test
    fun promptCarriesRubricAndMaterialOnlyAsData() {
        val p = plan()
        val msgs = SemanticConfirmPrompt.build("人设", p, "样本太小")
        val system = msgs.first().content
        // 评分依据必须进提示词。
        assertTrue(p.rubricPoints.any { system.contains(it) })
        // 材料必须被包在数据标签里，不能被当成指令。
        assertTrue(system.contains(SemanticConfirmPrompt.OPEN))
        assertTrue(msgs.last().content.contains(p.span.text))
    }
}
