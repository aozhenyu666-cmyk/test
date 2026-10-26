package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    ) = OpportunityContext(
        now = 1_000,
        trainingActive = trainingActive,
        focusActive = focusActive,
        allowInterrupt = allowInterrupt,
        materialFresh = materialFresh,
        alreadyHandled = alreadyHandled,
        recentIneffective = recentIneffective,
        materialAssistAllowed = materialAssistAllowed,
    )

    private fun opp(id: String = "o1") = Opportunity(
        opportunityId = id,
        source = OpportunitySource.MATERIAL_IMPORTED,
        whyNow = "材料已导入",
    )

    @Test
    fun proceedsWithAuthorizationAndFreshMaterial() {
        val d = OpportunityArbiter().decide(ctx())
        assertTrue(d is OpportunityDecision.Proceed)
    }

    @Test
    fun noAuthorizationMeansSuppressNotFabricate() {
        val d = OpportunityArbiter().decide(ctx(materialAssistAllowed = false))
        assertTrue(d is OpportunityDecision.Suppress)
        assertTrue((d as OpportunityDecision.Suppress).reason.contains("授权"))
    }

    @Test
    fun staleMaterialIsSuppressed() {
        val d = OpportunityArbiter().decide(ctx(materialFresh = false))
        assertTrue((d as OpportunityDecision.Suppress).reason.contains("过期"))
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
    fun repeatedIneffectiveReducesInsteadOfEscalating() {
        val d = OpportunityArbiter(ineffectiveLimit = 3).decide(ctx(recentIneffective = 3))
        assertTrue(d is OpportunityDecision.Suppress)
        assertTrue((d as OpportunityDecision.Suppress).reason.contains("降频"))
    }

    @Test
    fun sameEventRecordedOnlyOnce() {
        val store = OpportunityStore(java.io.File(tmp.root, "opp.json"))
        assertTrue(store.record("key-1", opp("o1")))
        assertFalse(store.record("key-1", opp("o2")))
        assertEquals(1, store.load().opportunities.size)
    }

    @Test
    fun deliveryAndResolutionPersist() {
        val f = java.io.File(tmp.root, "opp.json")
        val store = OpportunityStore(f)
        store.record("k", opp("o1"))
        assertTrue(store.markDelivery("o1", DeliveryState.SHOWN))
        assertTrue(store.markResolution("o1", ResolutionState.VERIFIED_PROGRESS))
        val reloaded = OpportunityStore(f).load()
        assertEquals(DeliveryState.SHOWN, reloaded.opportunities.single().delivery)
        assertEquals(ResolutionState.VERIFIED_PROGRESS, reloaded.opportunities.single().resolution)
    }

    @Test
    fun noProgressIncrementsAndProgressResets() {
        val f = java.io.File(tmp.root, "opp.json")
        val store = OpportunityStore(f)
        store.record("k1", opp("o1"))
        store.markResolution("o1", ResolutionState.NO_PROGRESS)
        assertEquals(1, store.load().recentIneffective)
        store.record("k2", opp("o2"))
        store.markResolution("o2", ResolutionState.VERIFIED_PROGRESS)
        assertEquals(0, store.load().recentIneffective)
    }
}