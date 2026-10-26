package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingControllerTest {

    private fun controller(mode: InteractionMode = InteractionMode.COACH): TrainingController {
        val q = BuiltInQuestions.SAMPLES.first()
        val c = TrainingController(
            TrainingSession(id = "s1", interactionMode = mode, createdAt = 0, updatedAt = 0),
        )
        c.bind(q)
        return c
    }

    private fun advice(
        c: TrainingController,
        verdict: Verdict,
        slot: SlotKind? = null,
        missing: String = "",
        next: String = "",
        assistance: AssistanceLevel = AssistanceLevel.INDEPENDENT,
        sessionId: String? = null,
        questionId: String? = null,
        requestId: String? = null,
    ) = EvaluationAdvice(
        sessionId = sessionId ?: c.current.id,
        revision = c.current.revision,
        questionId = questionId ?: c.current.questionId,
        slot = slot ?: c.current.currentSlotKind!!,
        requestId = requestId ?: c.current.pendingRequestId,
        verdict = verdict,
        evidenceRefs = listOf("任何人"),
        missingStep = missing,
        nextPrompt = next,
        assistanceLevel = assistance,
    )

    @Test
    fun presentAsksFirstSlotOnly() {
        val c = controller()
        val out = c.present(1_000)
        assertTrue(out is TrainingOutcome.Present)
        assertEquals(SlotKind.LOCATE, c.current.currentSlotKind)
        assertEquals(TrainingState.WAITING_INPUT, c.current.state)
    }

    @Test
    fun emptyInputIsIgnored() {
        val c = controller()
        c.present(1_000)
        assertEquals(TrainingOutcome.None, c.submit(now = 2_000))
        assertEquals(TrainingState.WAITING_INPUT, c.current.state)
    }

    @Test
    fun passAdvancesToNextSlotAndKeepsEarlierAttempt() {
        val c = controller()
        c.present(1_000)
        c.submit("范围词是任何人", now = 2_000)
        val out = c.acceptAdvice(advice(c, Verdict.PASS), 3_000)
        assertTrue(out is TrainingOutcome.NextSlot)
        assertEquals(1, c.current.currentSlot)
        assertEquals(SlotKind.CONNECT, c.current.currentSlotKind)
        assertEquals(1, c.current.attempts.size)
        assertEquals(Verdict.PASS, c.current.attempts[0].verdict)
    }

    @Test
    fun allSlotsDoneCompletes() {
        val c = controller()
        c.present(1_000)
        c.submit("a", now = 2_000)
        c.acceptAdvice(advice(c, Verdict.PASS), 3_000)
        c.submit("b", now = 4_000)
        val out = c.acceptAdvice(advice(c, Verdict.PASS), 5_000)
        assertTrue(out is TrainingOutcome.Completed)
        assertEquals(TrainingState.COMPLETED, c.current.state)
    }

    @Test
    fun retryKeepsSameSlotAndAsksSpecificGap() {
        val c = controller()
        c.present(1_000)
        c.submit("过度推论", now = 2_000)
        val out = c.acceptAdvice(advice(c, Verdict.NEED_CLARIFICATION, missing = "从哪句到哪句"), 3_000)
        assertTrue(out is TrainingOutcome.Waiting)
        assertTrue((out as TrainingOutcome.Waiting).ask.contains("从哪句到哪句"))
        assertEquals(0, c.current.currentSlot)
        assertEquals(TrainingState.WAITING_INPUT, c.current.state)
    }

    @Test
    fun staleRequestIdIsRejected() {
        val c = controller()
        c.present(1_000)
        c.submit("答案", now = 2_000)
        val bad = advice(c, Verdict.PASS, requestId = "req-old")
        assertTrue(c.acceptAdvice(bad, 3_000) is TrainingOutcome.Rejected)
        assertEquals(TrainingState.EVALUATING, c.current.state)
    }

    @Test
    fun wrongSlotOrQuestionIsRejected() {
        val c = controller()
        c.present(1_000)
        c.submit("答案", now = 2_000)
        assertTrue(c.acceptAdvice(advice(c, Verdict.PASS, slot = SlotKind.EXAMINE), 3_000) is TrainingOutcome.Rejected)
        assertTrue(c.acceptAdvice(advice(c, Verdict.PASS, questionId = "other"), 3_000) is TrainingOutcome.Rejected)
        assertTrue(c.acceptAdvice(advice(c, Verdict.PASS, sessionId = "other"), 3_000) is TrainingOutcome.Rejected)
    }

    @Test
    fun promptedPassIsNotIndependent() {
        val c = controller()
        c.present(1_000)
        c.submit("a", modality = "choice", assistance = AssistanceLevel.RECOGNIZED_WITH_CHOICES, now = 2_000)
        c.acceptAdvice(advice(c, Verdict.PASS, assistance = AssistanceLevel.RECOGNIZED_WITH_CHOICES), 3_000)
        c.submit("b", now = 4_000)
        c.acceptAdvice(advice(c, Verdict.PASS), 5_000)
        val summary = c.summary()
        assertTrue(summary.contains("独立通过 1 步"))
        assertTrue(summary.contains("提示后通过 1 步"))
    }

    @Test
    fun scaffoldShrinksQuestionWithoutClosing() {
        val c = controller()
        c.present(1_000)
        val out = c.scaffold(30_000)
        assertTrue(out is TrainingOutcome.Scaffold)
        assertEquals(TrainingState.SCAFFOLDED, c.current.state)
        assertTrue((out as TrainingOutcome.Scaffold).options.isNotEmpty())
        assertTrue(c.current.active)
    }

    @Test
    fun recoveryPausesButKeepsSession() {
        val c = controller()
        c.present(1_000)
        val out = c.recover(40_000)
        assertTrue(out is TrainingOutcome.Recovery)
        assertEquals(TrainingState.RECOVERY, c.current.state)
        assertTrue(c.current.active)
        assertEquals(SlotKind.LOCATE, c.current.currentSlotKind)
    }

    @Test
    fun questionIssueGoesToRecoveryNotFailure() {
        val c = controller()
        c.present(1_000)
        c.submit("题目前提有问题", now = 2_000)
        val out = c.acceptAdvice(advice(c, Verdict.QUESTION_ISSUE), 3_000)
        assertTrue(out is TrainingOutcome.Recovery)
        assertEquals(0, c.current.attempts.count { it.verdict == Verdict.PASS })
    }

    @Test
    fun modeSwitchKeepsTaskAndRevisionAdvances() {
        val c = controller(InteractionMode.COACH)
        c.present(1_000)
        val before = c.current.revision
        c.switchMode(InteractionMode.WARM, 2_000)
        assertEquals(InteractionMode.WARM, c.current.interactionMode)
        assertNotEquals(before, c.current.revision)
        assertEquals(TrainingState.WAITING_INPUT, c.current.state)
        assertEquals(0, c.current.currentSlot)
    }

    @Test
    fun interruptThenResumeKeepsSlot() {
        val c = controller()
        c.present(1_000)
        c.interrupt(2_000)
        assertEquals(TrainingState.INTERRUPTED, c.current.state)
        val out = c.resume(3_000)
        assertTrue(out is TrainingOutcome.Waiting)
        assertEquals(TrainingState.WAITING_INPUT, c.current.state)
        assertEquals(SlotKind.LOCATE, c.current.currentSlotKind)
    }

    @Test
    fun endedSessionRejectsNewAdvice() {
        val c = controller()
        c.present(1_000)
        c.submit("a", now = 2_000)
        val snapshot = advice(c, Verdict.PASS)
        c.end(3_000)
        assertTrue(c.acceptAdvice(snapshot, 4_000) is TrainingOutcome.Rejected)
        assertEquals(TrainingState.ENDED, c.current.state)
    }
}
