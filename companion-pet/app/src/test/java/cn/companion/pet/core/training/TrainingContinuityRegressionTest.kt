package cn.companion.pet.core.training

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 从服务使用的真实顺序复现：保存 → 新 store → restore → controller.bind → 下一步。 */
class TrainingContinuityRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val q = BuiltInQuestions.SAMPLES.first()
    private fun controller(): TrainingController = TrainingController(TrainingSession("s1")).also {
        it.bind(q); it.present(1_000)
    }
    private fun answer(c: TrainingController, text: String, at: Long = 2_000): TrainingOutcome {
        c.submit(text, now = at)
        return c.acceptAdvice(OfflineTraining.evaluate(q, c.current)!!, at + 1)
    }
    private fun reload(c: TrainingController): TrainingController {
        assertTrue(TrainingStore(tmp.root).save(c.current, q))
        val r = TrainingStore(tmp.root).restore(10_000) as TrainingStore.Restore.Resume
        return TrainingController(r.session).also { it.bind(r.question) }
    }

    @Test fun secondSlotSurvivesFullRestoreAndCompletesOffline() {
        val c = controller()
        assertTrue(answer(c, "任何人") is TrainingOutcome.NextSlot)
        val restored = reload(c)
        assertEquals(SlotKind.CONNECT, restored.current.currentSlotKind)
        assertEquals(Verdict.PASS, restored.current.attempts.single().verdict)
        // 仅否定结论、没有指出样本缺口，仍不能冒充依据。
        assertTrue(answer(restored, "不能保证任何人都提高", 10_500) is TrainingOutcome.Waiting)
        assertTrue(answer(restored, "样本不能代表所有人", 11_000) is TrainingOutcome.Completed)
        assertEquals(2, restored.current.attempts.count { it.verdict == Verdict.PASS })
        assertEquals(listOf("20 名自愿参加训练的人", "任何人使用该训练都会提高"), restored.current.attempts.last().evidenceRefs)
    }

    @Test fun vagueAndLongIrrelevantAnswersDoNotPassButShortEvidenceDoes() {
        for (text in listOf("懂了", "有道理", "我觉得一定要认真思考而且认真学习才能不断进步")) {
            val c = controller()
            assertTrue(answer(c, text) is TrainingOutcome.Waiting)
            assertFalse(c.current.attempts.any { it.verdict == Verdict.PASS })
        }
        assertTrue(answer(controller(), "任何人") is TrainingOutcome.NextSlot)
    }

    @Test fun recoverySurvivesRestartWithoutRefillingReminderBudget() {
        val c = controller()
        c.scaffold(2_000); c.markFinalReminder(3_000); c.recover(4_000)
        val restored = reload(c)
        assertEquals(TrainingState.RECOVERY, restored.current.state)
        assertEquals(2, restored.current.remindersUsed)
        assertEquals("NO_RESPONSE", restored.current.recoveryReason)
        assertEquals("USER_RETURN", restored.current.resumeTrigger)
        val timer = TrainingTimer()
        timer.restore(500, restored.current)
        assertTrue(timer.isRecovery())
        assertFalse(timer.shouldNudge(1_000_000))
        assertTrue(restored.resume(11_000) is TrainingOutcome.Waiting)
        timer.restore(600, restored.current)
        assertEquals(TrainingTimer.Stage.WAITING_AFTER_VOICE, timer.currentStage)
        assertEquals(AssistanceLevel.PROMPTED, restored.current.assistanceLevel)
    }

    @Test fun scaffoldedAnswerCannotBeReclassifiedIndependentByAdvice() {
        val c = controller()
        c.scaffold(2_000)
        c.submit("任何人", now = 3_000)
        val local = OfflineTraining.evaluate(q, c.current)!!
        c.acceptAdvice(local.copy(assistanceLevel = AssistanceLevel.INDEPENDENT), 4_000)
        assertEquals(AssistanceLevel.PROMPTED, c.current.attempts.single().assistanceLevel)
    }

    @Test fun duplicateSubmissionAndAdviceDoNotAdvanceTwice() {
        val c = controller()
        c.submit("任何人", now = 2_000)
        val first = c.current
        assertTrue(c.submit("任何人", now = 2_001) is TrainingOutcome.Rejected)
        assertEquals(first, c.current)
        val advice = OfflineTraining.evaluate(q, first)!!
        c.acceptAdvice(advice, 3_000)
        val after = c.current
        assertTrue(c.acceptAdvice(advice, 4_000) is TrainingOutcome.Rejected)
        assertEquals(after, c.current)
    }

    @Test fun pendingRequestAfterRestoreCannotAcceptOldResult() {
        val c = controller()
        c.submit("任何人", now = 2_000)
        val old = OfflineTraining.evaluate(q, c.current)!!
        val restored = reload(c)
        assertTrue(restored.acceptAdvice(old, 11_000) is TrainingOutcome.Rejected)
        assertEquals(0, restored.current.currentSlot)
        assertEquals("任何人", restored.current.attempts.single().transcript)
        assertEquals("", restored.current.pendingRequestId)
    }

    @Test fun networkFailureRetainsAnswerAndAllowsRetryWhileRejectingOldCallback() {
        val c = controller()
        c.submit("任何人", now = 2_000)
        val old = OfflineTraining.evaluate(q, c.current)!!
        assertTrue(c.technicalBlock("重试", 3_000) is TrainingOutcome.Waiting)
        assertEquals("任何人", c.current.attempts.single().transcript)
        assertTrue(c.acceptAdvice(old, 4_000) is TrainingOutcome.Rejected)
        assertTrue(answer(c, "任何人", 5_000) is TrainingOutcome.NextSlot)
    }

    @Test fun evidenceMissingFabricatedOrUncertainCannotPass() {
        for (kind in 0..2) {
            val c = controller()
            c.submit("任何人", now = 2_000)
            val advice = OfflineTraining.evaluate(q, c.current)!!
            val bad = when (kind) {
                0 -> advice.copy(evidenceRefs = emptyList())
                1 -> advice.copy(evidenceRefs = listOf("不存在的材料"))
                else -> advice.copy(uncertainty = "无法确认")
            }
            assertTrue(c.acceptAdvice(bad, 3_000) is TrainingOutcome.Waiting)
            assertEquals(0, c.current.currentSlot)
            assertFalse(c.current.attempts.any { it.verdict == Verdict.PASS })
        }
    }

    @Test fun localRubricDoesNotGradeChangedMaterialOrVersion() {
        val c = controller(); c.submit("任何人", now = 2_000)
        assertNull(OfflineTraining.evaluate(q.copy(version = 2), c.current))
        assertNull(OfflineTraining.evaluate(q.copy(stem = "新的材料"), c.current))
    }

    @Test fun exitPersistsTombstoneAndLateResultCannotReviveIt() {
        val c = controller(); c.submit("任何人", now = 2_000)
        val old = OfflineTraining.evaluate(q, c.current)!!
        c.end(3_000)
        assertTrue(TrainingStore(tmp.root).save(c.current, q))
        assertTrue(TrainingStore(tmp.root).restore(4_000) is TrainingStore.Restore.Finished)
        assertTrue(c.acceptAdvice(old, 4_000) is TrainingOutcome.Rejected)
    }

    @Test fun pauseAtZeroIsRealPauseAndWallClockDoesNotEnterTimer() {
        val timer = TrainingTimer(firstSilenceMillis = 1_000)
        timer.arm(0); timer.pause(0)
        assertTrue(timer.paused)
        assertFalse(timer.shouldNudge(10_000))
        timer.resume(10_000)
        assertFalse(timer.shouldNudge(10_999))
        assertTrue(timer.shouldNudge(11_000))
    }

    @Test fun malformedModelSchemaIsRejectedBeforeStateMutation() {
        val c = controller(); c.submit("任何人", now = 2_000)
        assertNull(TrainingPrompt.parse("""{"verdict":"PASS"}""", c.current, SlotKind.LOCATE))
        assertNull(TrainingPrompt.parse("""{"verdict":"PASS","evidenceRefs":[123],"missingStep":"","nextPrompt":"","assistanceLevel":"INDEPENDENT","uncertainty":""}""", c.current, SlotKind.LOCATE))
    }
}
