package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpportunityTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ctx(
        trainingActive: Boolean = false,
        focusActive: Boolean = false,
        allowInterrupt: Boolean = true,
        materialFresh: Boolean = true,
        alreadyHandled: Boolean = false,
        recentIneffective: Int = 0,
        materialAssistAllowed: Boolean = true,
        coolingDown: Boolean = false,
        cooldownLeftMillis: Long = 0,
        materialHasBody: Boolean = true,
    ) = OpportunityContext(
        now = 1_000,
        trainingActive = trainingActive,
        focusActive = focusActive,
        allowInterrupt = allowInterrupt,
        materialFresh = materialFresh,
        alreadyHandled = alreadyHandled,
        recentIneffective = recentIneffective,
        materialAssistAllowed = materialAssistAllowed,
        coolingDown = coolingDown,
        cooldownLeftMillis = cooldownLeftMillis,
        materialHasBody = materialHasBody,
    )

    private fun opp(id: String = "o1", sessionId: String = "s1") = Opportunity(
        opportunityId = id,
        source = OpportunitySource.MATERIAL_IMPORTED,
        sessionId = sessionId,
        whyNow = "材料已导入",
    )

    private fun store() = OpportunityStore(java.io.File(tmp.root, "opp.json"))

    @Test
    fun proceedsWithAuthorizationAndFreshMaterial() {
        assertTrue(OpportunityArbiter().decide(ctx()) is OpportunityDecision.Proceed)
    }

    @Test
    fun noAuthorizationMeansSuppressNotFabricate() {
        val d = OpportunityArbiter().decide(ctx(materialAssistAllowed = false))
        assertTrue((d as OpportunityDecision.Suppress).reason.contains("授权"))
    }

    @Test
    fun staleMaterialIsSuppressed() {
        val d = OpportunityArbiter().decide(ctx(materialFresh = false))
        assertTrue((d as OpportunityDecision.Suppress).reason.contains("过期"))
    }

    @Test
    fun linkOnlyMaterialWithoutBodyIsSuppressed() {
        val d = OpportunityArbiter().decide(ctx(materialHasBody = false))
        assertTrue((d as OpportunityDecision.Suppress).reason.contains("没有正文"))
    }

    @Test
    fun existingTrainingTakesPriorityOverNewQuestion() {
        val d = OpportunityArbiter().decide(ctx(trainingActive = true))
        assertTrue((d as OpportunityDecision.Suppress).reason.contains("接续"))
    }

    @Test
    fun duplicateEventIsSuppressed() {
        val d = OpportunityArbiter().decide(ctx(alreadyHandled = true))
        assertTrue((d as OpportunityDecision.Suppress).reason.contains("已处理"))
    }

    @Test
    fun quietOrProtectedContextSuppresses() {
        val d = OpportunityArbiter().decide(ctx(allowInterrupt = false))
        assertTrue((d as OpportunityDecision.Suppress).reason.contains("打扰"))
    }

    @Test
    fun cooldownSuppressesWithReadableReason() {
        val d = OpportunityArbiter().decide(ctx(coolingDown = true, cooldownLeftMillis = 5 * 60_000))
        assertTrue(d is OpportunityDecision.Suppress)
        assertTrue((d as OpportunityDecision.Suppress).reason.contains("安静"))
    }

    @Test
    fun sameEventRecordedOnlyOnce() {
        val s = store()
        assertEquals(RecordResult.Recorded, s.record("key-1", opp("o1")))
        assertEquals(RecordResult.Duplicate, s.record("key-1", opp("o2")))
        assertEquals(1, s.load().opportunities.size)
    }

    @Test
    fun storageFailureIsVisibleNotSilent() {
        val blocker = java.io.File(tmp.root, "blocker")
        blocker.writeText("x")
        val s = OpportunityStore(java.io.File(blocker, "opp.json"))
        val r = s.record("k", opp())
        assertTrue(r is RecordResult.StorageFailed)
        assertNotNull(s.lastError)
    }

    @Test
    fun corruptFileReportedNotCrash() {
        val file = java.io.File(tmp.root, "opp.json")
        file.writeText("{ bad")
        val s = OpportunityStore(file)
        s.load()
        assertNotNull(s.lastError)
    }

    @Test
    fun deliveryPersistsAndFailedIsNotShown() {
        val f = java.io.File(tmp.root, "opp.json")
        val s = OpportunityStore(f)
        s.record("k", opp("o1", "s1"))
        assertTrue(s.markDelivery("o1", DeliveryState.SHOWN))
        s.record("k2", opp("o2", "s2"))
        assertTrue(s.markDelivery("o2", DeliveryState.FAILED))
        val reloaded = OpportunityStore(f).load()
        assertEquals(DeliveryState.SHOWN, reloaded.opportunities.first { it.opportunityId == "o1" }.delivery)
        assertEquals(DeliveryState.FAILED, reloaded.opportunities.first { it.opportunityId == "o2" }.delivery)
    }

    @Test
    fun settleBySessionTargetsCorrectOpportunityNotLastOne() {
        val f = java.io.File(tmp.root, "opp.json")
        val s = OpportunityStore(f)
        s.record("k1", opp("o-session", "sess-A"))
        // 之后插入一条抑制记录（无 sessionId）——它就是“列表最后一条”。
        s.record("k2", Opportunity(
            opportunityId = "o-suppressed",
            source = OpportunitySource.MATERIAL_IMPORTED,
            delivery = DeliveryState.SUPPRESSED,
            suppressionReason = "当时不适合打扰",
        ))
        assertTrue(s.settleBySession("sess-A", ResolutionState.VERIFIED_PROGRESS, 5_000))
        val snap = s.load()
        assertEquals(ResolutionState.VERIFIED_PROGRESS,
            snap.opportunities.first { it.opportunityId == "o-session" }.resolution)
        assertEquals(ResolutionState.UNANSWERED,
            snap.opportunities.first { it.opportunityId == "o-suppressed" }.resolution)
    }

    @Test
    fun findSessionReturnsBoundOpportunity() {
        val s = store()
        s.record("k", opp("o1", "sess-B"))
        assertEquals("o1", s.findBySession("sess-B")?.opportunityId)
        assertNull(s.findBySession("nope"))
    }

    @Test
    fun repeatedSettlementDoesNotDoubleCount() {
        val s = store()
        s.record("k", opp("o1", "sess-C"))
        assertTrue(s.settleBySession("sess-C", ResolutionState.NO_PROGRESS, 1_000))
        assertEquals(1, s.load().recentIneffective)
        assertFalse(s.settleBySession("sess-C", ResolutionState.NO_PROGRESS, 2_000))
        assertEquals(1, s.load().recentIneffective)
    }

    @Test
    fun progressResetsIneffectiveCount() {
        val s = store()
        s.record("k1", opp("o1", "s1"))
        s.settleBySession("s1", ResolutionState.NO_PROGRESS, 1_000)
        assertEquals(1, s.load().recentIneffective)
        s.record("k2", opp("o2", "s2"))
        s.settleBySession("s2", ResolutionState.VERIFIED_PROGRESS, 2_000)
        assertEquals(0, s.load().recentIneffective)
    }

    @Test
    fun threeIneffectiveSetFiniteCooldownThenRecover() {
        val s = store()
        var t = 1_000L
        repeat(3) { i ->
            s.record("k$i", opp("o$i", "s$i"))
            s.settleBySession("s$i", ResolutionState.NO_PROGRESS, t)
            t += 1_000
        }
        assertEquals(3, s.load().recentIneffective)
        assertTrue(s.cooling(t))
        assertTrue(s.cooldownLeft(t) > 0)
        val after = t + OpportunityStore.COOLDOWN_MILLIS + 1
        assertFalse(s.cooling(after))
        assertEquals(0L, s.cooldownLeft(after))
    }
}
