package cn.companion.pet.core.continuity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CL-S3 接线：每一步只按自己的标准核验；三步走完才结束；断点能恢复到第几步。 */
class StrictPerStepCriteriaTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun machine() = StrictSessionMachine(
        StrictSessionState.new(
            now = 0, minutes = 10, topic = "读一段", materialRef = "screen",
            criteria = listOf("说清这一段的核心主张"),
            upcomingSteps = listOf("从原文里找一句话，支撑你刚才的判断。", "这段话有没有没覆盖到的情况？举一个。"),
            upcomingCriteria = listOf("给出一句真实存在于材料中的原文依据", "举出一个材料没覆盖的具体情况"),
        ),
    )

    private fun pass(m: StrictSessionMachine, at: Long): StrictOutcome {
        m.submit("答", at)
        return m.acceptEvaluation(Evaluation(m.state.sessionId, m.state.stepId, "a", m.state.revision, Verdict.PASS, feedback = "ok"), at + 1)
    }

    @Test
    fun eachStepUsesItsOwnCriterion() {
        val m = machine()
        m.ask("这段最关键的一点是什么？", emptyList(), 1)
        assertEquals(listOf("说清这一段的核心主张"), m.state.criteria) // 第一步不拿“原文依据”卡人
        assertTrue(pass(m, 10) is StrictOutcome.NextStep)
        assertEquals(listOf("给出一句真实存在于材料中的原文依据"), m.state.criteria)
        assertEquals(2, m.state.stepIndex)
        assertTrue(pass(m, 20) is StrictOutcome.NextStep)
        assertEquals(listOf("举出一个材料没覆盖的具体情况"), m.state.criteria)
        val last = pass(m, 30)
        assertTrue(last is StrictOutcome.Accepted)
        assertEquals(Phase.COMPLETED, m.state.phase)
        assertEquals(3, m.state.passedIndependently)
    }

    @Test
    fun midRoundRestartKeepsStepAndRemainingSteps() {
        val m = machine()
        m.ask("这段最关键的一点是什么？", emptyList(), 1)
        pass(m, 10)
        val store = StrictSessionStore(tmp.root)
        store.save(m.state)
        val r = StrictSessionStore(tmp.root).restore(100) as StrictSessionStore.Restore.Resume
        assertEquals(2, r.state.stepIndex)
        assertEquals(1, r.state.upcomingSteps.size)
        assertEquals(listOf("给出一句真实存在于材料中的原文依据"), r.state.criteria)
        assertEquals(listOf("举出一个材料没覆盖的具体情况"), r.state.upcomingCriteria)
    }
}
