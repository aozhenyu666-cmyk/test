package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 整合轮新增：核验失败不卡死、不重复提交、恢复态能继续、支架按题配、前台判定认“起点应用/桌面”、坏文件留底。 */
class TrainingUnifyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun controller(q: TrainingQuestion = BuiltInQuestions.SAMPLES.first()): TrainingController {
        val c = TrainingController(TrainingSession(id = "s1", createdAt = 0, updatedAt = 0))
        c.bind(q)
        c.present(0)
        return c
    }

    @Test
    fun evaluationFailedReturnsToWaitingInput() {
        val c = controller()
        c.submit("范围词是任何人", now = 1_000)
        assertEquals(TrainingState.EVALUATING, c.current.state)
        val out = c.evaluationFailed(2_000)
        assertTrue(out is TrainingOutcome.Waiting)
        assertEquals(TrainingState.WAITING_INPUT, c.current.state)
        assertEquals("", c.current.pendingRequestId)
        // 回到等待后支架能用（原实现卡在 EVALUATING，“我不会”没反应）
        assertTrue(c.scaffold(3_000) is TrainingOutcome.Scaffold)
    }

    @Test
    fun secondSubmitWhileEvaluatingIsRejected() {
        val c = controller()
        c.submit("一", now = 1_000)
        val req = c.current.pendingRequestId
        assertTrue(c.submit("二", now = 1_500) is TrainingOutcome.Rejected)
        assertEquals(req, c.current.pendingRequestId)
        assertEquals(1, c.current.attempts.size)
    }

    @Test
    fun resumeWorksFromRecovery() {
        val c = controller()
        c.recover(1_000)
        val out = c.resume(2_000)
        assertTrue(out is TrainingOutcome.Waiting)
        assertEquals(TrainingState.WAITING_INPUT, c.current.state)
    }

    @Test
    fun scaffoldUsesQuestionOptionsOrGenericOnes() {
        val q = TrainingQuestion(id = "x", stem = "甲说A，所以B。", sentences = listOf("甲说A", "所以B"))
        val c = controller(q)
        c.submit("所以B", now = 1_000)
        c.acceptAdvice(EvaluationAdvice(c.current.id, c.current.revision, "x", SlotKind.LOCATE, c.current.pendingRequestId, Verdict.PASS), 2_000)
        val s = c.scaffold(3_000) as TrainingOutcome.Scaffold
        // 通用选项不应带出别的题的内容
        assertFalse(s.options.any { it.contains("20") || it.contains("自愿") })

        val sample = controller(BuiltInQuestions.SAMPLES.first { it.id == "sample-cause-01" })
        sample.submit("所以是这个软件减少了迟到", now = 1_000)
        sample.acceptAdvice(EvaluationAdvice(sample.current.id, sample.current.revision, "sample-cause-01", SlotKind.LOCATE, sample.current.pendingRequestId, Verdict.PASS), 2_000)
        val s2 = sample.scaffold(3_000) as TrainingOutcome.Scaffold
        assertTrue(s2.options.any { it.contains("别的变化") })
    }

    @Test
    fun bridgeTreatsBaseAppAndHomeAsNotLeaving() {
        val b = TrainingFocusBridge()
        val session = controller().current
        assertFalse(b.decide(session, "com.reader", "me", basePackage = "com.reader").shouldBlock)
        assertFalse(b.decide(session, "com.launcher", "me", homePackages = setOf("com.launcher")).shouldBlock)
        assertTrue(b.decide(session, "com.video", "me", basePackage = "com.reader", homePackages = setOf("com.launcher")).shouldBlock)
    }

    @Test
    fun storeGapUsesSameClockAsSession() {
        val c = controller()
        val wall = 1_700_000_000_000L
        val s = c.current.copy(updatedAt = wall)
        val store = TrainingStore(tmp.root)
        store.save(s, BuiltInQuestions.SAMPLES.first())
        val r = TrainingStore(tmp.root).restore(wall + 5 * 60_000) as TrainingStore.Restore.Resume
        assertEquals(5 * 60_000L, r.gapMillis)
    }

    @Test
    fun corruptTrainingFileIsBackedUp() {
        val f = File(tmp.root, "training/session.json")
        f.parentFile.mkdirs()
        f.writeText("nope")
        assertTrue(TrainingStore(tmp.root).restore(1_000) is TrainingStore.Restore.None)
        assertTrue(f.parentFile.listFiles()!!.any { it.name.startsWith("session.json.corrupt-") })
    }
}
