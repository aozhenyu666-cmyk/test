package cn.companion.pet.core.continuity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 整合轮新增：持续闭环、按步统计、支架不写死、模型不可用不卡死、坏文件留底、恢复不卡核验。 */
class StrictLoopTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun machine(minutes: Int = 10) =
        StrictSessionMachine(StrictSessionState.new(0, minutes, "素材", "screen", StrictCriteria.forQuestion("这段最关键的是什么？", true)))

    private fun eval(m: StrictSessionMachine, verdict: Verdict, next: String = "", missing: List<String> = emptyList()) = Evaluation(
        sessionId = m.state.sessionId, stepId = m.state.stepId, attemptId = "a", requestRevision = m.state.revision,
        verdict = verdict, criteriaMissing = missing, feedback = "", nextPrompt = next,
    )

    @Test
    fun passWithoutNextPromptCompletes() {
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        m.submit("答", 2_000)
        m.acceptEvaluation(eval(m, Verdict.PASS), 3_000)
        assertEquals(Phase.COMPLETED, m.state.phase)
        assertFalse(m.state.active)
    }

    @Test
    fun passAfterExpiryCompletesEvenWithNextPrompt() {
        val m = machine(minutes = 1)
        m.ask("问一", emptyList(), 1_000)
        m.submit("答", 2_000)
        m.acceptEvaluation(eval(m, Verdict.PASS, next = "再问"), 61_000)
        assertEquals(Phase.COMPLETED, m.state.phase)
    }

    @Test
    fun helpedCountIsPerStepNotPerSession() {
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        m.submit("不对", 2_000)
        m.acceptEvaluation(eval(m, Verdict.NEED_EVIDENCE, next = "依据呢"), 3_000)
        m.submit("对了", 4_000)
        m.acceptEvaluation(eval(m, Verdict.PASS, next = "问二"), 5_000)
        assertEquals(1, m.state.passedAfterHelp)
        // 第二步一次就过：算独立通过（原实现按整回合 attempts>1 一律记成“提示后”）。
        m.submit("一次过", 6_000)
        m.acceptEvaluation(eval(m, Verdict.PASS, next = "问三"), 7_000)
        assertEquals(1, m.state.passedIndependently)
        assertEquals(3, m.state.stepIndex)
        assertEquals("step-3", m.state.stepId)
    }

    @Test
    fun supportMarksStepHelped() {
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        m.needsSupport(2_000)
        m.submit("答", 3_000)
        m.acceptEvaluation(eval(m, Verdict.PASS), 4_000)
        assertEquals(1, m.state.passedAfterHelp)
        assertEquals(0, m.state.passedIndependently)
    }

    @Test
    fun supportTextIsNotHardcodedToOneQuestionType() {
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        val plain = m.needsSupport(2_000) as StrictOutcome.Support
        assertFalse(plain.text.contains("转折"))
        m.submit("答", 3_000)
        m.acceptEvaluation(eval(m, Verdict.NEED_EVIDENCE, missing = listOf("一句原文依据")), 4_000)
        val withGap = m.needsSupport(5_000) as StrictOutcome.Support
        assertTrue(withGap.text.contains("一句原文依据"))
    }

    @Test
    fun criteriaFollowTheQuestion() {
        val c = StrictCriteria.forQuestion("为什么作者不同意？", hasMaterial = false)
        assertTrue(c[0].contains("为什么作者不同意"))
        assertFalse(c.joinToString().contains("转折"))
        assertTrue(c[1].contains("没保存"))
    }

    @Test
    fun evaluationUnavailableReturnsToWaiting() {
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        m.submit("答", 2_000)
        val rev = m.state.revision
        m.evaluationUnavailable(3_000)
        assertEquals(Phase.WAITING, m.state.phase)
        assertEquals(Health.DEGRADED, m.state.health)
        assertEquals(0, m.state.passedIndependently + m.state.passedAfterHelp)
        // 迟到的那次结果作废
        val late = Evaluation(m.state.sessionId, m.state.stepId, "a", rev, Verdict.PASS)
        assertTrue(m.acceptEvaluation(late, 4_000) is StrictOutcome.Rejected)
    }

    @Test
    fun restoreDuringEvaluatingGoesBackToWaiting() {
        val store = StrictSessionStore(tmp.root)
        val m = machine()
        m.ask("问一", emptyList(), 1_000)
        m.submit("答", 2_000)
        store.save(m.state)
        val r = StrictSessionStore(tmp.root).restore(3_000) as StrictSessionStore.Restore.Resume
        assertEquals(Phase.WAITING, r.state.phase)
        assertEquals("答", r.state.lastAnswer)
        assertTrue(r.state.revision > m.state.revision)
    }

    @Test
    fun corruptFileIsBackedUpNotSilentlyLost() {
        val f = File(tmp.root, "continuity/strict.json")
        f.parentFile.mkdirs()
        f.writeText("{broken")
        val store = StrictSessionStore(tmp.root)
        assertTrue(store.restore(1_000) is StrictSessionStore.Restore.None)
        assertTrue(store.lastError!!.contains("读不了"))
        assertFalse(f.exists())
        assertTrue(f.parentFile.listFiles()!!.any { it.name.startsWith("strict.json.corrupt-") })
    }
}
