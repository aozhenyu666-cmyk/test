package cn.companion.pet.core.training

import cn.companion.pet.core.companion.MaterialContentType
import cn.companion.pet.core.companion.SharedMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 端到端（纯逻辑层）：机会发现 → 出题 → 核验 → 结算 → 冷却/恢复。
 *
 * 这些用例走真实代码路径，不是只向仲裁器手动传 materialFresh=true。
 */
class MaterialOpportunityIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val BODY_A =
        "这份报告只采访了十二位自由职业者，说他们用某个记账软件之后每月结余变多了。" +
        "所以任何人使用这个软件都能改善财务状况，所有人都应该立刻换成它，全部家庭都适用。"

    private val BODY_B =
        "我们在一所小学的两个班里试了新的课间操，只有这两班的孩子参加了测试。" +
        "因此所有学校都应该推广这套课间操，每一个学生都会因此更健康，全部年级一律照做。"

    private fun material(id: String, title: String, body: String) = SharedMaterial(
        id = id,
        sourceEventId = "evt-" + id,
        url = "https://example.com/" + id,
        title = title,
        body = body,
        note = "",
        contentType = MaterialContentType.EXCERPT,
        source = "test",
        receivedAt = 1_000,
        expiresAt = 9_999_999_999,
    )

    private fun store() = OpportunityStore(java.io.File(tmp.root, "opp.json"))

    private fun ok() = OpportunityArbiter().decide(
        OpportunityContext(
            now = 1_000, trainingActive = false, focusActive = false,
            allowInterrupt = true, materialFresh = true, alreadyHandled = false,
            materialAssistAllowed = true, materialHasBody = true,
        ),
    )

    /** 走真实链路：材料 → 绑定 → 出题。 */
    private fun planFor(m: SharedMaterial): MaterialQuestionPlan? =
        MaterialQuestionBuilder.build(BoundMaterial.of(m))

    @Test
    fun twoDifferentMaterialsNeverCrossContaminate() {
        val a = material("mA", "A 记账", BODY_A)
        val b = material("mB", "B 课间操", BODY_B)
        val pa = planFor(a)!!
        val pb = planFor(b)!!
        assertNotEquals(pa.questionId, pb.questionId)
        assertNotEquals(pa.materialId, pb.materialId)
        // 引用句必须各自来自自己的正文，不能互相串。
        assertTrue(BODY_A.contains(pa.span.text))
        assertTrue(BODY_B.contains(pb.span.text))
        assertFalse(BODY_A.contains(pb.span.text))
        assertFalse(BODY_B.contains(pa.span.text))
    }

    @Test
    fun eachMaterialGetsItsOwnOpportunityAndSession() {
        val s = store()
        s.record("evt-mA", Opportunity(
            opportunityId = "opp-A", source = OpportunitySource.MATERIAL_IMPORTED,
            sourceEventId = "evt-mA", sessionId = "sess-A",
            materialRef = "mA", materialVersion = 1, delivery = DeliveryState.SHOWN,
        ))
        s.record("evt-mB", Opportunity(
            opportunityId = "opp-B", source = OpportunitySource.MATERIAL_IMPORTED,
            sourceEventId = "evt-mB", sessionId = "sess-B",
            materialRef = "mB", materialVersion = 1, delivery = DeliveryState.SHOWN,
        ))
        assertEquals("opp-A", s.findBySession("sess-A")?.opportunityId)
        assertEquals("opp-B", s.findBySession("sess-B")?.opportunityId)
        // 结算 A 不影响 B。
        assertTrue(s.settleBySession("sess-A", ResolutionState.VERIFIED_PROGRESS, 2_000))
        assertEquals(ResolutionState.UNANSWERED, s.findBySession("sess-B")!!.resolution)
    }

    @Test
    fun realTrainingCompletionSettlesTheRightOpportunity() {
        val m = material("mA", "A 记账", BODY_A)
        val plan = planFor(m)!!
        val q = MaterialTraining.toQuestion(plan)
        val s = store()
        s.record("evt-mA", Opportunity(
            opportunityId = "opp-A", source = OpportunitySource.MATERIAL_IMPORTED,
            sessionId = "sess-A", materialRef = "mA", delivery = DeliveryState.SHOWN,
        ))
        // 插入一条抑制记录，确保结算不是“取最后一条”。
        s.record("evt-noise", Opportunity(
            opportunityId = "opp-noise", source = OpportunitySource.MATERIAL_IMPORTED,
            delivery = DeliveryState.SUPPRESSED, suppressionReason = "安静时段",
        ))

        val bound = BoundMaterial.of(m)
        val c = TrainingController(TrainingSession(id = "sess-A", materialId = m.id,
            materialVersion = bound.version, opportunityId = "opp-A", createdAt = 0, updatedAt = 0))
        c.bind(q)
        c.present(1_000)
        // 走两个槽位，真的完成。
        val locateIdx = plan.allSpans.indexOfFirst { it.index == plan.span.index }
        c.submit("", listOf(locateIdx), now = 2_000)
        val a1 = MaterialTraining.evaluateLocate(plan, c.current, listOf(locateIdx), "")!!
        c.acceptAdvice(a1, 3_000)
        assertEquals(1, c.current.currentSlot)
        c.submit("样本只有十二个自由职业者，不能代表所有人", now = 4_000)
        val a2 = MaterialTraining.evaluateConnect(plan, c.current, "样本只有十二个自由职业者，不能代表所有人")!!
        val done = c.acceptAdvice(a2, 5_000)
        assertTrue(done is TrainingOutcome.Completed)
        // 完成 → 用 sessionId 结算。
        c.current.opportunityId.let { id ->
            assertTrue(s.settleBySession(c.current.id, ResolutionState.VERIFIED_PROGRESS, 6_000))
            assertEquals("opp-A", id)
        }
        assertEquals(ResolutionState.VERIFIED_PROGRESS, s.findBySession("sess-A")!!.resolution)
        assertEquals(ResolutionState.UNANSWERED, s.load().opportunities.first { it.opportunityId == "opp-noise" }.resolution)
    }

    @Test
    fun duplicateCallbackDoesNotDoubleCount() {
        val s = store()
        s.record("k", Opportunity(opportunityId = "opp-A",
            source = OpportunitySource.MATERIAL_IMPORTED, sessionId = "sess-A"))
        assertTrue(s.settleBySession("sess-A", ResolutionState.NO_PROGRESS, 1_000))
        assertEquals(1, s.load().recentIneffective)
        // 完成、中止、到期都走同一结算；重复调用不重复加。
        assertFalse(s.settleBySession("sess-A", ResolutionState.NO_PROGRESS, 2_000))
        assertFalse(s.settleBySession("sess-A", ResolutionState.VERIFIED_PROGRESS, 3_000))
        assertEquals(1, s.load().recentIneffective)
    }

    @Test
    fun blockedDisplayIsNotCountedAsShown() {
        val s = store()
        s.record("k", Opportunity(opportunityId = "opp-A",
            source = OpportunitySource.MATERIAL_IMPORTED, sessionId = "sess-A",
            delivery = DeliveryState.PROPOSED))
        // 展示失败：记 FAILED，并结算为无推进。
        assertTrue(s.markDelivery("opp-A", DeliveryState.FAILED))
        s.markResolution("opp-A", ResolutionState.NO_PROGRESS, 2_000)
        val opp = s.load().opportunities.single()
        assertEquals(DeliveryState.FAILED, opp.delivery)
        assertNotEquals(DeliveryState.SHOWN, opp.delivery)
        assertEquals(1, s.load().recentIneffective)
    }

    @Test
    fun threeIneffectiveThenCooldownThenRecover() {
        val s = store()
        var t = 1_000L
        repeat(3) { i ->
            s.record("k$i", Opportunity(opportunityId = "opp-$i",
                source = OpportunitySource.MATERIAL_IMPORTED, sessionId = "sess-$i"))
            s.settleBySession("sess-$i", ResolutionState.NO_PROGRESS, t)
            t += 1_000
        }
        // 冷却中：仲裁明确抑制并给出可读原因。
        val snap = s.load()
        val decision = OpportunityArbiter().decide(
            OpportunityContext(
                now = t, trainingActive = false, focusActive = false,
                allowInterrupt = true, materialFresh = true, alreadyHandled = false,
                recentIneffective = snap.recentIneffective, materialAssistAllowed = true,
                coolingDown = s.cooling(t), cooldownLeftMillis = s.cooldownLeft(t),
                materialHasBody = true,
            ),
        )
        assertTrue(decision is OpportunityDecision.Suppress)

        // 冷却到期后恢复可发。
        val after = t + OpportunityStore.COOLDOWN_MILLIS + 1
        val recovered = OpportunityArbiter().decide(
            OpportunityContext(
                now = after, trainingActive = false, focusActive = false,
                allowInterrupt = true, materialFresh = true, alreadyHandled = false,
                recentIneffective = snap.recentIneffective, materialAssistAllowed = true,
                coolingDown = s.cooling(after), cooldownLeftMillis = s.cooldownLeft(after),
                materialHasBody = true,
            ),
        )
        // 冷却解除后不再因冷却抑制（仍可能因无效计数降频，这是兜底）。
        assertFalse(s.cooling(after))
        if (recovered is OpportunityDecision.Suppress) {
            assertFalse(recovered.reason.contains("先安静"))
        }
    }

    @Test
    fun answeringCorrectlyAdvancesToSecondStep() {
        val m = material("mA", "A 记账", BODY_A)
        val plan = planFor(m)!!
        val q = MaterialTraining.toQuestion(plan)
        val bound = BoundMaterial.of(m)
        val c = TrainingController(TrainingSession(id = "sess-A", materialId = m.id,
            materialVersion = bound.version, createdAt = 0, updatedAt = 0))
        c.bind(q)
        c.present(1_000)
        val locateIdx = plan.allSpans.indexOfFirst { it.index == plan.span.index }
        c.submit("", listOf(locateIdx), now = 2_000)
        val advice = MaterialTraining.evaluateLocate(plan, c.current, listOf(locateIdx), "")!!
        val out = c.acceptAdvice(advice, 3_000)
        // 回答正确 → 进入相关第二步，并且下一步带着依据要求。
        assertTrue(out is TrainingOutcome.NextSlot)
        assertEquals(SlotKind.CONNECT, c.current.currentSlotKind)
        assertTrue((out as TrainingOutcome.NextSlot).ask.contains("范围") || out.ask.contains("样本")
            || out.ask.contains("依据") || out.ask.contains("为什么"))
    }

    @Test
    fun vagueAnswerDoesNotAdvance() {
        val m = material("mA", "A 记账", BODY_A)
        val plan = planFor(m)!!
        val q = MaterialTraining.toQuestion(plan)
        val c = TrainingController(TrainingSession(id = "sess-A", materialId = m.id, createdAt = 0, updatedAt = 0))
        c.bind(q)
        c.present(1_000)
        c.submit("有道理", now = 2_000)
        val advice = MaterialTraining.evaluateLocate(plan, c.current, emptyList(), "有道理")!!
        val out = c.acceptAdvice(advice, 3_000)
        assertTrue(out is TrainingOutcome.Waiting)
        assertEquals(0, c.current.currentSlot)
        // 没推进：拒绝理由必须给出具体缺口，不能只回一句“再想想”。
        assertTrue(c.current.attempts.lastOrNull()?.missingStep?.isNotBlank() == true)
    }
}