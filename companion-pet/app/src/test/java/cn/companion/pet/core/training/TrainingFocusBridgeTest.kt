package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingFocusBridgeTest {

    private val own = "cn.companion.pet"
    private fun active() = TrainingSession(
        id = "s",
        requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT),
        currentSlot = 0,
        state = TrainingState.WAITING_INPUT,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun noSessionNeverBlocks() {
        val b = TrainingFocusBridge()
        val d = b.decide(null, "com.douyin", own)
        assertEquals(FocusVerdict.NOT_APPLICABLE, d.verdict)
        assertFalse(d.shouldBlock)
    }

    @Test
    fun finishedSessionNeverBlocks() {
        val b = TrainingFocusBridge()
        val done = active().copy(state = TrainingState.COMPLETED)
        assertFalse(b.decide(done, "com.douyin", own).shouldBlock)
    }

    @Test
    fun insideTrainingIsFine() {
        val b = TrainingFocusBridge()
        val d = b.decide(active(), own, own)
        assertEquals(FocusVerdict.IN_TRAINING, d.verdict)
        assertFalse(d.shouldBlock)
    }

    @Test
    fun allowedReadingIsNotBlocked() {
        val b = TrainingFocusBridge(allowedReadingPackages = setOf("com.browser"))
        val d = b.decide(active(), "com.browser", own)
        assertEquals(FocusVerdict.ALLOWED_READING, d.verdict)
        assertFalse(d.shouldBlock)
    }

    @Test
    fun leavingToEntertainmentBlocks() {
        val b = TrainingFocusBridge()
        val d = b.decide(active(), "com.douyin", own)
        assertEquals(FocusVerdict.BLOCK_BACK_TO_TRAINING, d.verdict)
        assertTrue(d.shouldBlock)
    }

    @Test
    fun unknownForegroundDoesNotBlock() {
        val b = TrainingFocusBridge()
        assertFalse(b.decide(active(), null, own).shouldBlock)
        assertFalse(b.decide(active(), "", own).shouldBlock)
    }

    @Test
    fun focusAllowedPackageIsRespected() {
        val b = TrainingFocusBridge()
        val d = b.decide(active(), "com.notes", own, allowedFocusPackages = setOf("com.notes"))
        assertFalse(d.shouldBlock)
    }
}