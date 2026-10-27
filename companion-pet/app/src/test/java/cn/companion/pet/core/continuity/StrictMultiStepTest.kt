package cn.companion.pet.core.continuity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CL-S3 多步接续 + CL-S4 按步帮助等级 + 剩余时间不被交互刷新。 */
class StrictMultiStepTest {

    private fun machine(steps: List<String> = listOf("第二步问题", "第三步问题")) =
        StrictSessionMachine(
            StrictSessionState.new(
                now = 0, minutes = 10, topic = "读一段", materialRef = "screen",
                criteria = listOf("说清判断", "给一句依据"), upcomingSteps = steps,
            ),
        )

    private fun eval(
        m: StrictSessionMachine,
        verdict: Verdict,
        usedHelp: Boolean = false,
        missing: List<String> = listOf("缺依据"),
    ) = Evaluation(
        sessionId = m.state.sessionId,
        stepId = m.state.stepId,
        attemptId = "a",
        requestRevision = m.state.revision,
        verdict = verdict,
        criteriaMissing = missing,
        feedback = "ok",
        usedHelpThisStep = usedHelp,
    )

    @Test
    fun passWithNextStepContinuesInsteadOfCompleting() {
        val m = machine()
        m.ask("第一步问题", emptyList(), 1_000)
        m.submit("答一", 2_000)
        val out = m.acceptEvaluation(eval(m, Verdict.PASS), 3_000)
        assertTrue(out is StrictOutcome.NextStep)
        assertEquals(2, m.state.stepIndex)
        assertEquals("第二步问题", m.state.question)
        assertEquals(Phase.WAITING, m.state.phase)
        assertTrue(m.state.active)
    }

    @Test
    fun lastStepPassCompletes() {
        val m = machine(steps = listOf("第二步问题"))
        m.ask("第一步", emptyList(), 1_000)
        m.submit("a", 2_000)
        assertTrue(m.acceptEvaluation(eval(m, Verdict.PASS), 3_000) is StrictOutcome.NextStep)
        assertEquals(2, m.state.stepIndex)
        m.submit("b", 4_000)
        val out = m.acceptEvaluation(eval(m, Verdict.PASS), 5_000)
        assertTrue(out is StrictOutcome.Accepted)
        assertEquals(Phase.COMPLETED, m.state.phase)
    }

    @Test
    fun stepIdAdvancesSoStaleEvaluationIsRejected() {
        val m = machine()
        m.ask("第一步", emptyList(), 1_000)
        m.submit("a", 2_000)
        val stale = eval(m, Verdict.PASS)
        m.acceptEvaluation(stale, 3_000)
        assertNotEquals("step-1", m.state.stepId)
        m.submit("b", 4_000)
        assertTrue(m.acceptEvaluation(stale, 5_000) is StrictOutcome.Rejected)
    }

    @Test
    fun helpThisStepCountsAsPromptedNotIndependent() {
        val m = machine()
        m.ask("第一步", emptyList(), 1_000)
        m.needsSupport(1_500)
        m.submit("a", 2_000)
        m.acceptEvaluation(eval(m, Verdict.PASS), 3_000)
        m.submit("b", 4_000)
        m.acceptEvaluation(eval(m, Verdict.PASS), 5_000)
        assertEquals(1, m.state.passedAfterHelp)
        assertEquals(1, m.state.passedIndependently)
    }

    @Test
    fun secondTryOnSameStepCountsAsPrompted() {
        val m = machine(steps = emptyList())
        m.ask("唯一一步", emptyList(), 1_000)
        m.submit("第一次", 2_000)
        m.acceptEvaluation(eval(m, Verdict.NEED_EVIDENCE), 3_000)
        m.submit("第二次", 4_000)
        m.acceptEvaluation(eval(m, Verdict.PASS), 5_000)
        assertEquals(1, m.state.passedAfterHelp)
        assertEquals(0, m.state.passedIndependently)
    }

    @Test
    fun remainingTimeUsesNowNotUpdatedAt() {
        val m = machine()
        m.ask("问", emptyList(), 60_000)
        assertEquals(540_000L, m.state.remainingMillis(60_000))
        m.submit("答", 120_000)
        assertEquals(480_000L, m.state.remainingMillis(120_000))
        m.acceptEvaluation(eval(m, Verdict.NEED_EVIDENCE), 180_000)
        assertEquals(420_000L, m.state.remainingMillis(180_000))
    }

    @Test
    fun technicalFailureKeepsStepAndAnswer() {
        val m = machine()
        m.ask("第一步", emptyList(), 1_000)
        m.submit("我的回答", 2_000)
        val out = m.evaluationUnavailable("网络断了", 3_000)
        assertTrue(out is StrictOutcome.Ask)
        assertEquals(Phase.WAITING, m.state.phase)
        assertEquals("我的回答", m.state.lastAnswer)
        assertEquals(1, m.state.stepIndex)
        assertTrue(m.state.active)
    }

    @Test
    fun questionIssueStopsWithoutJudgingUser() {
        val m = machine()
        m.ask("第一步", emptyList(), 1_000)
        m.submit("题目前提有问题", 2_000)
        val out = m.acceptEvaluation(eval(m, Verdict.QUESTION_ISSUE), 3_000)
        assertTrue(out is StrictOutcome.Support)
        assertEquals(0, m.state.passedIndependently)
        assertEquals(0, m.state.passedAfterHelp)
    }
}
