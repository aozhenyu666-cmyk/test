package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestGrantTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun practicePolicyGrantsOnce() {
        val s = RestGrantStore(tmp.root)
        val g = s.grant(FocusPolicyType.PRACTICE_FOR_BREAK, "s1", "e1", setOf("com.douyin"), 600_000, 1_000)
        assertNotNull(g)
        assertEquals(601_000, g!!.expiresAt)
    }

    @Test
    fun sameEventIsIdempotent() {
        val s = RestGrantStore(tmp.root)
        val a = s.grant(FocusPolicyType.PRACTICE_FOR_BREAK, "s1", "e1", setOf("com.douyin"), 600_000, 1_000)
        val b = s.grant(FocusPolicyType.PRACTICE_FOR_BREAK, "s1", "e1", setOf("com.douyin"), 600_000, 5_000)
        assertEquals(a!!.expiresAt, b!!.expiresAt)
    }

    @Test
    fun activeGrantIsNotStacked() {
        val s = RestGrantStore(tmp.root)
        s.grant(FocusPolicyType.PRACTICE_FOR_BREAK, "s1", "e1", setOf("a"), 600_000, 1_000)
        val second = s.grant(FocusPolicyType.PRACTICE_FOR_BREAK, "s1", "e2", setOf("b"), 600_000, 2_000)
        assertEquals("e1", second!!.eventId)
    }

    @Test
    fun fixedFocusNeverGrants() {
        val s = RestGrantStore(tmp.root)
        assertNull(s.grant(FocusPolicyType.FIXED_FOCUS, "s1", "e1", setOf("com.douyin"), 600_000, 1_000))
    }

    @Test
    fun expiredGrantIsRevokedAndInactive() {
        val s = RestGrantStore(tmp.root)
        s.grant(FocusPolicyType.PRACTICE_FOR_BREAK, "s1", "e1", setOf("a"), 60_000, 1_000)
        assertNull(s.active(62_000))
        assertTrue(s.load()!!.revoked)
    }

    @Test
    fun grantSurvivesRestartWithoutDuplicating() {
        val s = RestGrantStore(tmp.root)
        s.grant(FocusPolicyType.PRACTICE_FOR_BREAK, "s1", "e1", setOf("a"), 600_000, 1_000)
        val fresh = RestGrantStore(tmp.root)
        val again = fresh.grant(FocusPolicyType.PRACTICE_FOR_BREAK, "s1", "e1", setOf("a"), 600_000, 2_000)
        assertEquals(601_000, again!!.expiresAt)
    }

    @Test
    fun revokeMakesInactive() {
        val s = RestGrantStore(tmp.root)
        s.grant(FocusPolicyType.PRACTICE_FOR_BREAK, "s1", "e1", setOf("a"), 600_000, 1_000)
        assertTrue(s.revoke())
        assertNull(s.active(2_000))
    }
}