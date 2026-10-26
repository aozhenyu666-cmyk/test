package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** S4 端到端（纯逻辑）：出题 → 本地判分 → 结算 → 单次发券 → 休息 → 到点续轮 / 硬截止结束。 */
class BreakCycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = BreakCycleEngine()
    private val min = 60_000L

    private fun cycle(now: Long = 0, required: Int = 5, deadline: Long = 60 * min) =
        engine.start("c1", now, required, 2 * min, deadline)

    /** 用真实控制器和本地判分走完一题；[useChoices] 表示先要支架再点选项（答案被说破）。 */
    private fun solve(q: TrainingQuestion, id: String, now: Long, useChoices: Boolean = false, firstWrong: Boolean = false): TrainingSession {
        val item = OfflineBank.item(q)!!
        val c = TrainingController(TrainingSession(id = id, createdAt = now, updatedAt = now))
        c.bind(q)
        c.present(now)
        for (slot in listOf(SlotKind.LOCATE, SlotKind.CONNECT)) {
            if (firstWrong && slot == SlotKind.LOCATE) {
                c.submit("不知道", now = now)
                c.acceptAdvice(OfflineTraining.evaluate(q, c.current)!!, now)
            }
            val answer = if (slot == SlotKind.LOCATE) item.locateCorrect else item.connectCorrect
            if (useChoices) {
                c.scaffold(now)
                c.submit(answer, modality = "choice", assistance = AssistanceLevel.RECOGNIZED_WITH_CHOICES, now = now)
            } else c.submit(answer, now = now)
            c.acceptAdvice(OfflineTraining.evaluate(q, c.current)!!, now)
        }
        assertEquals(TrainingState.COMPLETED, c.current.state)
        return c.current
    }

    @Test
    fun bankHasFiveFamiliesEachWithATransferSibling() {
        assertTrue(OfflineBank.families.size >= 5)
        for (fam in OfflineBank.families) assertTrue(fam, OfflineBank.ITEMS.count { it.family == fam } >= 2)
        // 每题的判据都引用题干原文，否则 acceptAdvice 会拒收。
        for (item in OfflineBank.ITEMS) {
            for (e in item.locateEvidence + item.connectEvidence) assertTrue(item.question.id + ":" + e, item.question.stem.contains(e))
            assertTrue(item.question.id, item.accepts(SlotKind.LOCATE, item.locateCorrect))
            assertTrue(item.question.id, item.accepts(SlotKind.CONNECT, item.connectCorrect))
            assertTrue(item.question.id, item.locateCorrect in item.locateOptions)
            assertTrue(item.question.id, item.connectCorrect in item.connectOptions)
            // 错误选项（常见误解）不能被接受。
            for (o in item.connectOptions - item.connectCorrect) assertFalse(item.question.id + ":" + o, item.accepts(SlotKind.CONNECT, o))
            for (o in item.locateOptions - item.locateCorrect) assertFalse(item.question.id + ":" + o, item.accepts(SlotKind.LOCATE, o))
            // 空泛态度不能通过。
            for (v in listOf("懂了", "有道理", "对", "")) assertFalse(item.accepts(SlotKind.CONNECT, v))
        }
    }

    @Test
    fun fiveIndependentPassesIssueExactlyOneGrant() {
        var s = cycle()
        var grants = 0
        for (i in 1..5) {
            val q = engine.nextQuestion(s)!!
            s = engine.markSeen(s, q.id)
            val r = engine.settle(s, q, solve(q, "s$i", i * min), i * min)
            when (r) {
                is SettleResult.Credited -> { s = r.state; assertEquals(PassKind.FIRST_TRY_INDEPENDENT, r.kind) }
                is SettleResult.GrantReady -> { s = r.state; grants++ }
                else -> throw AssertionError("unexpected $r")
            }
        }
        assertEquals(1, grants)
        assertEquals(CycleState.ON_BREAK, s.state)
        assertEquals(5 * min + 2 * min, s.restUntil)
        // 五题来自五个不同类别。
        assertEquals(5, s.set.credits.map { OfflineBank.byId(it.questionId)!!.family }.toSet().size)
    }

    @Test
    fun replayedCompletionIsIgnored() {
        var s = cycle()
        val q = engine.nextQuestion(s)!!
        val done = solve(q, "s1", 1)
        s = (engine.settle(s, q, done, 2) as SettleResult.Credited).state
        assertTrue(engine.settle(s, q, done, 3) is SettleResult.Ignored)
        // 换个会话号再答同一题，也不重复计。
        val again = engine.settle(s, q, solve(q, "s2", 4), 5)
        assertTrue(again is SettleResult.NotCounted)
        assertEquals(1, (again as SettleResult.NotCounted).state.set.credits.size)
    }

    @Test
    fun revealedAnswerNeedsUnseenTransferQuestion() {
        var s = cycle()
        val first = engine.nextQuestion(s)!!
        s = engine.markSeen(s, first.id)
        val r = engine.settle(s, first, solve(first, "s1", 1, useChoices = true), 2)
        assertTrue(r is SettleResult.NeedsTransfer)
        s = (r as SettleResult.NeedsTransfer).state
        assertEquals(0, s.set.credits.size)
        // 下一题必须是同类、没见过的迁移题。
        val transfer = engine.nextQuestion(s)!!
        assertNotEquals(first.id, transfer.id)
        assertEquals(OfflineBank.item(first)!!.family, OfflineBank.item(transfer)!!.family)
        s = engine.markSeen(s, transfer.id)
        val t = engine.settle(s, transfer, solve(transfer, "s2", 3), 4) as SettleResult.Credited
        assertEquals(PassKind.TRANSFER_PASS, t.kind)
        assertTrue(t.state.pendingTransferFamilies.isEmpty())
    }

    @Test
    fun promptedButNotRevealedCountsAsPromptedPass() {
        var s = cycle()
        val q = engine.nextQuestion(s)!!
        val r = engine.settle(s, q, solve(q, "s1", 1, firstWrong = true), 2) as SettleResult.Credited
        assertEquals(PassKind.PROMPTED_PASS, r.kind)
        s = r.state
        assertEquals(0.0, QuestionSetSettlement().independentRate(s.set), 0.0001)
    }

    @Test
    fun modelJudgedQuestionDoesNotEarnCredit() {
        val s = cycle()
        val q = BuiltInQuestions.SAMPLES[1] // 只能靠模型判的题
        val c = TrainingController(TrainingSession(id = "m1"))
        c.bind(q); c.present(1)
        for (slot in q.requiredSlots.take(2)) {
            c.submit("答案", now = 2)
            c.acceptAdvice(EvaluationAdvice(c.current.id, c.current.revision, q.id, slot, c.current.pendingRequestId,
                Verdict.PASS, evidenceRefs = listOf(q.sentences.first()), evaluationSource = "MODEL"), 3)
        }
        val r = engine.settle(s, q, c.current, 4)
        assertTrue(r is SettleResult.NotCounted)
        assertEquals(0, (r as SettleResult.NotCounted).state.set.credits.size)
    }

    @Test
    fun breakEndsAtHardDeadlineAndNeverExtends() {
        var s = cycle(required = 1, deadline = 10 * min)
        val q = engine.nextQuestion(s)!!
        val r = engine.settle(s, q, solve(q, "s1", 9 * min), 9 * min) as SettleResult.GrantReady
        // 休息 2 分钟会超过硬截止：截到硬截止。
        assertEquals(10 * min, r.restUntil)
        s = r.state
        val ended = engine.tick(s, 10 * min) { "c2" }!!
        assertEquals(CycleState.ENDED, ended.state)
        assertEquals("DEADLINE_DURING_BREAK", ended.endReason)
        assertNull(engine.tick(ended, 11 * min) { "c3" })
    }

    @Test
    fun breakOverStartsNewCycleWithSameDeadline() {
        var s = cycle(required = 1)
        val q = engine.nextQuestion(s)!!
        s = (engine.settle(s, q, solve(q, "s1", 1 * min), 1 * min) as SettleResult.GrantReady).state
        assertNull(engine.tick(s, 2 * min) { "c2" }) // 休息中
        val next = engine.tick(s, 3 * min) { "c2" }!!
        assertEquals(CycleState.COLLECTING, next.state)
        assertEquals("c2", next.cycleId)
        assertEquals(s.hardDeadline, next.hardDeadline)
        assertEquals(0, next.set.credits.size)
        // 新一轮不能再出上一轮做过的题。
        assertNotEquals(q.id, engine.nextQuestion(next)!!.id)
    }

    @Test
    fun settleAfterDeadlineIsIgnored() {
        val s = cycle(deadline = 5 * min)
        val q = engine.nextQuestion(s)!!
        assertTrue(engine.settle(s, q, solve(q, "s1", 6 * min), 6 * min) is SettleResult.Ignored)
    }

    @Test
    fun grantSurvivesRestartWithoutRestartingTimer() {
        val store = RestGrantStore(tmp.root)
        val g1 = store.grantUntil("c1", "cycle-c1", emptySet(), expiresAt = 10 * min, now = 8 * min)
        assertNotNull(g1)
        // 重启后同一事件再领：拿到原许可，到期时间不变。
        val g2 = RestGrantStore(tmp.root).grantUntil("c1", "cycle-c1", emptySet(), expiresAt = 20 * min, now = 9 * min)
        assertEquals(g1, g2)
        assertNull(RestGrantStore(tmp.root).active(10 * min))
    }

    @Test
    fun cycleStateRoundTrips() {
        var s = cycle()
        val q = engine.nextQuestion(s)!!
        s = (engine.settle(engine.markSeen(s, q.id), q, solve(q, "s1", 1), 2) as SettleResult.Credited).state
        assertTrue(BreakCycleStore(tmp.root).save(s))
        assertEquals(s, BreakCycleStore(tmp.root).load())
    }

    @Test
    fun exhaustedBankReturnsNullInsteadOfRepeating() {
        var s = cycle()
        for (item in OfflineBank.ITEMS) s = engine.markSeen(s, item.question.id)
        assertNull(engine.nextQuestion(s))
    }
}
