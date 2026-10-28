package cn.companion.pet.core.focus

import cn.companion.pet.core.training.ActionReceiptState
import org.junit.Assert.*
import org.junit.Test

class FocusActionEvidenceTest {
    @Test fun evidenceExpiresAndRejectsClockRollback() {
        val evidence = FocusActionEvidence(1000)
        evidence.reset("a")
        val id = evidence.requested("focus_return", 100)
        evidence.reported(id, true, 100, "accepted")
        evidence.observed(id, true, 100, "observed")
        assertEquals(ActionReceiptState.OBSERVED_CONFIRMED, evidence.latest(1000)!!.state)
        assertEquals(ActionReceiptState.UNKNOWN, evidence.latest(1101)!!.state)
        assertFalse(evidence.latest(99)!!.observed)
    }
    @Test fun failedApiCannotBePromotedByALateObservationOrDuplicateAcceptance() {
        val evidence = FocusActionEvidence(); evidence.reset("a")
        val id = evidence.requested("focus_return", 0)
        evidence.reported(id, false, 0, "rejected")
        evidence.reported(id, true, 1, "late")
        evidence.observed(id, true, 2, "late")
        assertEquals(ActionReceiptState.FAILED, evidence.latest(2)!!.state)
    }
    @Test fun oldSessionAndUnknownReceiptsAreIgnored() {
        val evidence = FocusActionEvidence(); evidence.reset("a")
        val old = evidence.requested("return", 0)
        evidence.reset("a")
        val current = evidence.requested("return", 1)
        evidence.observed(old, true, 2, "old")
        evidence.observed("invented", true, 2, "invented")
        assertEquals(current, evidence.latest(2)!!.actionId)
        assertEquals(ActionReceiptState.REQUESTED, evidence.latest(2)!!.state)
    }
}
