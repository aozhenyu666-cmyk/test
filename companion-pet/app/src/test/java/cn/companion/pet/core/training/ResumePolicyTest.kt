package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 主动断点恢复：自己回来问，但有冷却、有上限、重启不重置。 */
class ResumePolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val p = ResumePolicy(cooldownMillis = 10 * 60_000L, maxPerSession = 3)
    private val min = 60_000L

    private fun ctx(now: Long, active: Boolean = true, bubble: Boolean = false, busy: Boolean = false,
                    quiet: Boolean = false, paused: Boolean = false) =
        ResumeContext(now, active, quiet, paused, busy, bubble)

    private fun controller(at: Long = 0): TrainingController =
        TrainingController(TrainingSession(id = "s", createdAt = at, updatedAt = at)).also {
            it.bind(BuiltInQuestions.SAMPLES.first()); it.present(at)
        }

    @Test
    fun comesBackAfterCooldownWithSameQuestion() {
        val c = controller()
        assertTrue(p.decide(c.current, ctx(5 * min)) is ResumeDecision.Hold)
        val d = p.decide(c.current, ctx(11 * min))
        assertTrue(d is ResumeDecision.Resume)
        assertEquals("sample-scope-01", c.current.questionId) // 不换题
    }

    @Test
    fun respectsPresenceQuietPausedBusyAndVisibleBubble() {
        val s = controller().current
        val now = 30 * min
        assertTrue(p.decide(s, ctx(now, active = false)) is ResumeDecision.Hold)
        assertTrue(p.decide(s, ctx(now, quiet = true)) is ResumeDecision.Hold)
        assertTrue(p.decide(s, ctx(now, paused = true)) is ResumeDecision.Hold)
        assertTrue(p.decide(s, ctx(now, busy = true)) is ResumeDecision.Hold)
        assertTrue(p.decide(s, ctx(now, bubble = true)) is ResumeDecision.Hold)
    }

    @Test
    fun stopsChasingAfterLimitAndLimitSurvivesRestart() {
        val c = controller()
        var now = 0L
        repeat(3) {
            now += 11 * min
            val d = p.decide(c.current, ctx(now))
            assertTrue("round $it", d is ResumeDecision.Resume)
            c.recordProactiveResume(now)
        }
        // 第 3 次的提示里要说明这是最后一次。
        assertTrue(TrainingStore(tmp.root).save(c.current, BuiltInQuestions.SAMPLES.first()))
        val restored = (TrainingStore(tmp.root).restore(now) as TrainingStore.Restore.Resume).session
        assertEquals(3, restored.proactiveResumes)
        val after = p.decide(restored, ctx(now + 60 * min))
        assertTrue(after is ResumeDecision.Hold)
        assertTrue((after as ResumeDecision.Hold).reason.contains("不再追问"))
    }

    @Test
    fun lastResumeSaysItIsTheLast() {
        val c = controller()
        c.recordProactiveResume(1); c.recordProactiveResume(2)
        val d = p.decide(c.current, ctx(20 * min)) as ResumeDecision.Resume
        assertTrue(d.lead.contains("最后一次"))
    }

    @Test
    fun recoveryStateGetsWelcomeBackLead() {
        val c = controller()
        c.recover(1)
        val d = p.decide(c.current, ctx(20 * min)) as ResumeDecision.Resume
        assertTrue(d.lead.contains("回来"))
    }

    @Test
    fun finishedOrEvaluatingIsNotChased() {
        val c = controller()
        c.submit("任何人", now = 1)
        assertTrue(p.decide(c.current, ctx(30 * min)) is ResumeDecision.Hold) // 核验中
        c.end(2)
        assertTrue(p.decide(c.current, ctx(60 * min)) is ResumeDecision.Hold)
    }
}
