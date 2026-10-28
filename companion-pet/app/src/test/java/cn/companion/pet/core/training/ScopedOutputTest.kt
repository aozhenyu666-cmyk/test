package cn.companion.pet.core.training

import org.junit.Assert.*
import org.junit.Test

class ScopedOutputTest {
    @Test fun ambientMessagesCannotImpersonateTheActiveQuestion() {
        val arbiter = OutputArbiter()
        arbiter.acquire(OutputPriority.TRAINING, "question-a")
        assertFalse(ScopedOutputPolicy.allows("question-a", "", OutputPriority.AMBIENT, arbiter))
        assertFalse(ScopedOutputPolicy.allows("question-a", "question-b", OutputPriority.TRAINING, arbiter))
        assertTrue(ScopedOutputPolicy.allows("question-a", "question-a", OutputPriority.TRAINING, arbiter))
    }
    @Test fun strictThinkingIsProtectedEvenBeforeAnArbiterLockIsAcquired() {
        val arbiter = OutputArbiter()
        assertFalse(ScopedOutputPolicy.allows("strict-current", "agent", OutputPriority.AMBIENT, arbiter))
        assertTrue(ScopedOutputPolicy.allows("strict-current", "strict-current", OutputPriority.TRAINING, arbiter))
    }
    @Test fun priorityCannotBypassTheCurrentSessionOwner() {
        assertFalse(ScopedOutputPolicy.allows("question", "old-notice", OutputPriority.URGENT, OutputArbiter()))
    }
    @Test fun oldExpiryCannotCloseANewerReplyOrInvokeItsOldActions() {
        val lifetime = OutputLifetime()
        var closes = 0
        val oldNotice = lifetime.replace()
        val expire = { if (lifetime.owns(oldNotice)) closes++ }
        val newReply = lifetime.replace()
        expire()
        assertEquals(0, closes)
        assertTrue(lifetime.owns(newReply))
    }
    @Test fun hidingOrUpdatingTheBubbleInvalidatesPreviouslyScheduledCallbacks() {
        val lifetime = OutputLifetime()
        val first = lifetime.replace()
        lifetime.invalidate()
        assertFalse(lifetime.owns(first))
        val second = lifetime.replace()
        lifetime.replace()
        assertFalse(lifetime.owns(second))
    }
}
