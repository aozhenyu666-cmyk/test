package cn.companion.pet.core.training

import cn.companion.pet.core.companion.MaterialContentType
import cn.companion.pet.core.companion.SharedMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反敷衍回归样例（R2.1 §7.1）。
 *
 * **能力边界声明**：
 * - 本地判分只能验形式，判不了语义正确性（见 MaterialTraining 注释）。
 * - 因此这里验证的是“形式缺陷能被拦住”，不是“正确性被判定”。
 * - 语义正确的最终确认需要模型结构化建议 + 人工复核；本轮不宣称已具备。
 * - 这些标签由开发者按可枚举规则标注，**不是**独立人工审核的黄金集。
 */
class GoldenSetTest {

    private val BODY =
        "这份报告只采访了十二位自由职业者，说他们用某个记账软件之后每月结余变多了。" +
        "所以任何人使用这个软件都能改善财务状况，所有人都应该立刻换成它，全部家庭都适用。"

    private fun plan(): MaterialQuestionPlan {
        val m = SharedMaterial(
            id = "golden", sourceEventId = "evt", url = "https://example.com/g",
            title = "记账软件报告", body = BODY, note = "",
            contentType = MaterialContentType.EXCERPT, source = "test",
            receivedAt = 1_000, expiresAt = 9_999_999_999,
        )
        return MaterialQuestionBuilder.build(BoundMaterial.of(m))!!
    }

    private fun session(): TrainingController {
        val p = plan()
        val c = TrainingController(TrainingSession(id = "s", materialId = p.materialId,
            materialVersion = p.materialVersion, createdAt = 0, updatedAt = 0))
        c.bind(MaterialTraining.toQuestion(p))
        c.present(1_000)
        return c
    }

    private fun locate(pickCorrect: Boolean, transcript: String = ""): Verdict {
        val p = plan()
        val c = session()
        val idx = if (pickCorrect) listOf(p.allSpans.indexOfFirst { it.index == p.span.index }) else emptyList()
        c.submit(transcript, idx, now = 2_000)
        return MaterialTraining.evaluateLocate(p, c.current, idx, transcript)!!.verdict
    }

    private fun connect(transcript: String): EvaluationAdvice {
        val p = plan()
        val c = session()
        c.submit(transcript, now = 2_000)
        return MaterialTraining.evaluateConnect(p, c.current, transcript)!!
    }

    // ---------- 类别 1：空泛态度（必须拦住） ----------

    @Test
    fun category1_vagueAttitudeIsRejectedOnBothSlots() {
        listOf("懂了", "有道理", "知道了", "明白了", "好的", "嗯").forEach {
            assertEquals("空泛「" + it + "」LOCATE 不应通过", Verdict.NEED_CLARIFICATION, locate(false, it))
            assertEquals("空泛「" + it + "」CONNECT 不应通过", Verdict.NEED_CLARIFICATION, connect(it).verdict)
        }
    }

    // ---------- 类别 2：复述题目（必须拦住） ----------

    @Test
    fun category2_restatingQuestionIsRejected() {
        listOf(
            "材料里有一句把结论推到了很大的范围词",
            "我需要指出哪一句范围词",
        ).forEach {
            assertEquals("复述「" + it + "」应被拦", Verdict.NEED_CLARIFICATION, connect(it).verdict)
        }
    }

    // ---------- 类别 3：明显残缺（必须要求补完） ----------

    @Test
    fun category3_truncatedInputAsksToFinish() {
        listOf("样本不", "只有十二", "因为", "不能代").forEach {
            assertEquals("残缺「" + it + "」应要求补完", Verdict.NEED_CLARIFICATION, connect(it).verdict)
        }
    }

    // ---------- 类别 4：点对句子（可靠通过） ----------

    @Test
    fun category4_correctSpanSelectionPasses() {
        // 点选是可枚举的，所以这一槽的本地判定可靠。
        assertEquals(Verdict.PASS, locate(pickCorrect = true))
        assertEquals(Verdict.PASS, locate(pickCorrect = false, transcript = "任何人"))
    }

    // ---------- 类别 5：形式合规但语义未经核验（必须留痕） ----------

    @Test
    fun category5_formOkButSemanticUnverifiedIsMarked() {
        // 注意：本地判不了语义对错。这里既包含真实正确的理由，也包含方向错误的理由，
        // 两者都会被判形式合规——这正是必须由模型/人工复核的原因。
        val correct = connect("样本只是十二位自愿参加者，不能代表所有人")
        assertEquals(Verdict.PASS, correct.verdict)
        assertEquals("LOCAL_FORM_ONLY", correct.evaluationSource)
        assertTrue("语义未经核验必须留痕", correct.uncertainty.isNotBlank())

        val wrongDirection = connect("因为自由职业者不喜欢软件")
        // 形式合规 → 本地放行，但同样标记未核验，不能据此发券。
        assertEquals(Verdict.PASS, wrongDirection.verdict)
        assertEquals("LOCAL_FORM_ONLY", wrongDirection.evaluationSource)
        assertTrue(wrongDirection.uncertainty.isNotBlank())
    }

    // ---------- 类别 6：提示后 vs 迁移，分开记账 ----------

    @Test
    fun category6_promptedPassRecordedSeparately() {
        val s = QuestionSetSettlement(requiredCredits = 5)
        val st1 = (s.record(SetState(), "q1", 1, PassKind.FIRST_TRY_INDEPENDENT, false, 1_000)
            as CreditResult.Counted).state
        val st2 = (s.record(st1, "q2", 1, PassKind.PROMPTED_PASS, false, 2_000)
            as CreditResult.Counted).state
        val stats = s.stats(st2)
        assertEquals(1, stats[PassKind.FIRST_TRY_INDEPENDENT])
        assertEquals(1, stats[PassKind.PROMPTED_PASS])
        assertEquals(0.5, s.independentRate(st2), 0.001)
    }

    @Test
    fun category6_revealedAnswerRequiresTransfer() {
        val s = QuestionSetSettlement()
        val r = s.record(SetState(), "q1", 1, PassKind.PROMPTED_PASS, answerRevealed = true, now = 1_000)
        assertTrue(r is CreditResult.NeedsTransfer)
        val r2 = s.record(SetState(), "q1t", 1, PassKind.TRANSFER_PASS, false, 2_000)
        assertTrue(r2 is CreditResult.Counted)
    }

    // ---------- 类别 7：题目问题走澄清，不判失败 ----------

    @Test
    fun category7_questionIssueGoesToRecovery() {
        val c = session()
        c.submit("这题前提没说清，样本来源也没交代", now = 2_000)
        val advice = EvaluationAdvice(
            sessionId = c.current.id, revision = c.current.revision,
            questionId = c.current.questionId, slot = SlotKind.LOCATE,
            requestId = c.current.pendingRequestId,
            verdict = Verdict.QUESTION_ISSUE,
            missingStep = "题目前提待澄清",
        )
        val out = c.acceptAdvice(advice, 3_000)
        assertTrue(out is TrainingOutcome.Recovery)
        assertEquals(0, c.current.attempts.count { it.verdict == Verdict.PASS })
    }

    // ---------- 类别 8：同题重复不得重复计数 ----------

    @Test
    fun category8_sameQuestionNeverDoubleCounts() {
        val s = QuestionSetSettlement(requiredCredits = 5)
        var state = SetState()
        repeat(10) { i ->
            when (val r = s.record(state, "same-q", 1, PassKind.FIRST_TRY_INDEPENDENT, false, i.toLong())) {
                is CreditResult.Counted -> state = r.state
                is CreditResult.Duplicate -> Unit
                is CreditResult.NeedsTransfer -> Unit
            }
        }
        assertEquals(1, state.credits.size)
        assertTrue(!s.eligible(state))
    }

    // ---------- 门槛：只有 5 道不同题才发券 ----------

    @Test
    fun hardGate_needsFiveDistinctCredits() {
        val s = QuestionSetSettlement(requiredCredits = 5)
        var state = SetState()
        repeat(4) { i ->
            state = (s.record(state, "q$i", 1, PassKind.FIRST_TRY_INDEPENDENT, false, i.toLong())
                as CreditResult.Counted).state
        }
        assertTrue("4 题不够", !s.eligible(state))
        state = (s.record(state, "q4", 1, PassKind.FIRST_TRY_INDEPENDENT, false, 9_000)
            as CreditResult.Counted).state
        assertTrue("5 道不同题才够", s.eligible(state))
    }
}