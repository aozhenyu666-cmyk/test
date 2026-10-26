package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionPolicyTest {

    private fun policy() = ExtensionPolicy(maxTimes = 2, perExtensionMillis = 90_000, maxTotalMillis = 180_000)

    @Test
    fun firstExtensionIsGrantedWithClearDeadline() {
        val p = policy()
        val (result, state) = p.request(ExtensionState(), now = 1_000)
        assertTrue(result is ExtensionResult.Granted)
        val g = result as ExtensionResult.Granted
        assertEquals(91_000L, g.until)
        assertEquals(1, state.usedTimes)
        assertEquals(90_000L, state.usedMillis)
    }

    @Test
    fun secondExtensionStillGranted() {
        val p = policy()
        val (_, s1) = p.request(ExtensionState(), 1_000)
        val (r2, s2) = p.request(s1, 2_000)
        assertTrue(r2 is ExtensionResult.Granted)
        assertEquals(2, s2.usedTimes)
        assertEquals(180_000L, s2.usedMillis)
    }

    @Test
    fun thirdExtensionIsDeniedWithActionableReason() {
        val p = policy()
        val (_, s1) = p.request(ExtensionState(), 1_000)
        val (_, s2) = p.request(s1, 2_000)
        val (r3, s3) = p.request(s2, 3_000)
        assertTrue(r3 is ExtensionResult.Denied)
        assertTrue((r3 as ExtensionResult.Denied).reason.contains("延长"))
        // 被拒绝时状态不再变化。
        assertEquals(2, s3.usedTimes)
    }

    @Test
    fun totalTimeCapAlsoStopsInfiniteExtension() {
        // 次数多但累计上限小的策略：累计上限先触发。
        val p = ExtensionPolicy(maxTimes = 10, perExtensionMillis = 90_000, maxTotalMillis = 180_000)
        val (_, s1) = p.request(ExtensionState(), 1_000)
        val (_, s2) = p.request(s1, 2_000)
        val (r3, _) = p.request(s2, 3_000)
        assertTrue(r3 is ExtensionResult.Denied)
        assertTrue((r3 as ExtensionResult.Denied).reason.contains("上限"))
    }

    @Test
    fun technicalBlockDoesNotConsumeQuota() {
        // 关键：麦克风/网络坏了不能算用户拖延。
        val p = policy()
        val (r, state) = p.request(ExtensionState(), now = 1_000, technicalBlock = true)
        assertEquals(ExtensionResult.Technical, r)
        assertEquals(0, state.usedTimes)
        assertEquals(0L, state.usedMillis)
    }

    @Test
    fun resetGivesFreshQuotaForNewQuestion() {
        val p = policy()
        val (_, s1) = p.request(ExtensionState(), 1_000)
        val (_, s2) = p.request(s1, 2_000)
        val fresh = p.reset()
        assertEquals(0, fresh.usedTimes)
        val (r, _) = p.request(fresh, 3_000)
        assertTrue(r is ExtensionResult.Granted)
    }

    @Test
    fun grantedReportsRemainingBudget() {
        val p = policy()
        val (r, _) = p.request(ExtensionState(), 1_000)
        val g = r as ExtensionResult.Granted
        assertEquals(1, g.remainingTimes)
        assertEquals(90_000L, g.remainingMillis)
    }

    @Test
    fun recordingDurationIsBounded() {
        val d = InputDurationPolicy(maxRecordingMillis = 120_000)
        assertFalse(d.exceeded(0, 119_999))
        assertTrue(d.exceeded(0, 120_000))
        assertEquals(0L, d.remaining(0, 130_000))
        assertTrue(d.remaining(0, 60_000) == 60_000L)
    }
}
