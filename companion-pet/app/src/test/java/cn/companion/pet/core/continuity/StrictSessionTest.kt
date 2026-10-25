package cn.companion.pet.core.continuity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictSessionTest {

    private fun machine(minutes: Int = 10) =
        StrictSessionMachine(StrictSessionState.new(0, minutes, "读一篇文章", "screen", listOf("指出转折后的重点")))

    private fun eval(m: StrictSessionMachine, verdict: Verdict, stepId: String = "step-1", sessionId: String? = null, rev: Int? = null) = Evaluation(
        sessionId = sessionId ?: m.state.sessionId,
        stepId = stepId,
        attemptId = "a1",
        requestRevision = rev ?: m.state.revision,
        verdict = verdict,
        criteriaMissing = listOf("原文依据"),
        feedback = "还差依据",
        nextPrompt = "哪句话支持你？",
    )

    @Test
    fun silenceDoesNotEndOrResetSession() {
        val m = machine()
        m.ask("作者转折后强调了什么？", listOf("指出转折后的重点"), 1_000)
        m.onSilence(SilenceReason.NO_RESPONSE, 30_000)
        assertTrue(m.state.active)
        assertEquals(Phase.WAITING, m.state.phase)
        assertEquals("作者转折后强调了什么？", m.state.question)
    }

    @Test
    fun remindersAreCappedAndQuestionSurvives() {
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        assertTrue(m.remind(31_000) is StrictOutcome.Ask)
        assertTrue(m.remind(61_000) is StrictOutcome.Ask)
        assertEquals(StrictOutcome.None, m.remind(91_000))
        assertEquals(2, m.state.reminders)
        assertEquals("问一", m.state.question)
    }

    @Test
    fun shortButSufficientAnswerPasses() {
        val m = machine()
        m.ask("作者转折后强调了什么？", emptyList(), 1_000)
        m.submit("最后一句", 2_000)
        val out = m.acceptEvaluation(eval(m, Verdict.PASS), 3_000)
        assertTrue(out is StrictOutcome.Accepted)
        // 持续闭环：有下一问且没到期 → 进入下一步，不是一答就结束。
        assertEquals(Phase.WAITING, m.state.phase)
        assertEquals(2, m.state.stepIndex)
        assertEquals("哪句话支持你？", m.state.question)
        assertEquals(1, m.state.passedIndependently)
    }

    @Test
    fun missingEvidenceKeepsWaitingWithoutPassing() {
        val m = machine()
        m.ask("作者转折后强调了什么？", emptyList(), 1_000)
        m.submit("选 C", 2_000)
        val out = m.acceptEvaluation(eval(m, Verdict.NEED_EVIDENCE), 3_000)
        assertTrue(out is StrictOutcome.Accepted && out.verdict == Verdict.NEED_EVIDENCE)
        assertEquals(Phase.WAITING, m.state.phase)
        assertEquals(0, m.state.passedIndependently)
        assertEquals("原文依据", m.state.gap)
    }

    @Test
    fun dontKnowGivesSupportNotFailure() {
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        val out = m.needsSupport(2_000)
        assertTrue(out is StrictOutcome.Support)
        assertEquals(Phase.SUPPORT, m.state.phase)
        assertEquals(SilenceReason.NEED_SUPPORT, m.state.silence)
    }

    @Test
    fun staleEvaluationIsRejected() {
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        m.submit("答", 2_000)
        assertTrue(m.acceptEvaluation(eval(m, Verdict.PASS, rev = m.state.revision - 1), 3_000) is StrictOutcome.Rejected)
        assertTrue(m.acceptEvaluation(eval(m, Verdict.PASS, sessionId = "别的会话"), 3_000) is StrictOutcome.Rejected)
        assertTrue(m.acceptEvaluation(eval(m, Verdict.PASS, stepId = "step-9"), 3_000) is StrictOutcome.Rejected)
        assertEquals(Phase.EVALUATING, m.state.phase)
    }

    @Test
    fun lateEvaluationAfterAbortIsDropped() {
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        m.submit("答", 2_000)
        val snapshot = eval(m, Verdict.PASS)
        m.abort(3_000)
        assertTrue(m.acceptEvaluation(snapshot, 4_000) is StrictOutcome.Rejected)
        assertEquals(Phase.ABORTED, m.state.phase)
        assertEquals(0, m.state.passedIndependently)
    }

    @Test
    fun expiryWithoutAnyAnswerIsNeverEngaged() {
        val m = machine(minutes = 1)
        m.ask("问一", emptyList(), 1_000)
        val out = m.tick(61_000)
        assertTrue(out is StrictOutcome.Finished)
        assertEquals(StrictEndReason.NEVER_ENGAGED, (out as StrictOutcome.Finished).reason)
        assertEquals(Phase.EXPIRED, m.state.phase)
    }

    @Test
    fun expiryWithAnswersCountsAsExpiredNotPassed() {
        val m = machine(minutes = 1)
        m.ask("问一", emptyList(), 1_000)
        m.submit("答", 2_000)
        val out = m.tick(61_000) as StrictOutcome.Finished
        assertEquals(StrictEndReason.EXPIRED, out.reason)
        assertEquals(0, m.state.passedIndependently)
    }

    @Test
    fun helpThenPassIsRecordedSeparately() {
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        m.submit("第一次", 2_000)
        m.acceptEvaluation(eval(m, Verdict.NEED_EVIDENCE), 3_000)
        m.submit("第二次", 4_000)
        m.acceptEvaluation(eval(m, Verdict.PASS), 5_000)
        assertEquals(1, m.state.passedAfterHelp)
        assertEquals(0, m.state.passedIndependently)
    }

    @Test
    fun technicalBlockIsNotUserFailure() {
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        m.submit("答", 2_000)
        val out = m.acceptEvaluation(eval(m, Verdict.INPUT_UNCERTAIN), 3_000)
        assertTrue(out is StrictOutcome.Accepted && out.verdict == Verdict.INPUT_UNCERTAIN)
        assertNotEquals(Phase.COMPLETED, m.state.phase)
        assertEquals(0, m.state.passedIndependently)
    }
}