package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A13 / A35：执行路径挑战。时间全是发起端单调时钟。 */
class ExecutionPathMonitorTest {

    private fun receipt(c: HealthChallenge, at: Long, stage: String = "a11y_handler_root_read", ok: Boolean = true, nonce: String = c.nonce) =
        HealthReceipt(c.challengeId, nonce, stage, "inst", ok, at)

    @Test
    fun healthyPathReportsOk() {
        val m = ExecutionPathMonitor()
        assertTrue(m.due(0, enforcing = true))
        val c = m.issue(0, "n1")
        assertTrue(m.onReceipt(receipt(c, 40), 40) is ChallengeVerdict.Healthy)
        assertEquals(HealthState.OK, m.snapshot(41, true).state)
    }

    @Test
    fun stuckQueueTimesOutAndLateReceiptIsDropped() {
        val m = ExecutionPathMonitor()
        val c = m.issue(0, "n1")
        assertNull(m.poll(2_000)) // 还没到截止
        // Binder/服务都在，但执行队列卡住：3 秒没回执就是超时。
        assertTrue(m.poll(3_001) is ChallengeVerdict.TimedOut)
        assertEquals(HealthState.DEGRADED, m.snapshot(3_002, true).state)
        // 卡住的队列后来才把回执吐出来：不能把状态改回健康。
        assertNull(m.onReceipt(receipt(c, 9_000), 9_000))
        assertEquals(HealthState.DEGRADED, m.snapshot(9_001, true).state)
    }

    @Test
    fun receiptAfterDeadlineIsTimeoutNotHealthy() {
        val m = ExecutionPathMonitor()
        val c = m.issue(0, "n1")
        // 还没 poll 到，回执就迟到了：按发起端时钟判超时。
        assertTrue(m.onReceipt(receipt(c, 4_000), 4_000) is ChallengeVerdict.TimedOut)
    }

    @Test
    fun entryEchoAndForgedNonceAreRejected() {
        val m = ExecutionPathMonitor()
        var c = m.issue(0, "n1")
        assertTrue(m.onReceipt(receipt(c, 10, stage = "binder_echo"), 10) is ChallengeVerdict.Rejected)
        assertEquals(HealthState.DEGRADED, m.snapshot(11, true).state)
        c = m.issue(100_000, "n2")
        assertTrue(m.onReceipt(receipt(c, 100_010, nonce = "forged"), 100_010) is ChallengeVerdict.Rejected)
    }

    @Test
    fun failedWindowReadIsNotHealthy() {
        val m = ExecutionPathMonitor()
        val c = m.issue(0, "n1")
        assertTrue(m.onReceipt(receipt(c, 10, ok = false), 10) is ChallengeVerdict.Rejected)
        assertFalse(m.healthy(11, true))
    }

    @Test
    fun receiptForUnknownChallengeIsIgnored() {
        val m = ExecutionPathMonitor()
        m.issue(0, "n1")
        val other = HealthChallenge("hc-other", "n1", "x", issuedAt = 0, deadline = 3_000)
        assertNull(m.onReceipt(receipt(other, 5), 5))
        assertTrue(m.pending != null)
    }

    @Test
    fun serviceMissingIsUnavailable() {
        val m = ExecutionPathMonitor()
        m.issue(0, "n1")
        m.unavailable(0)
        assertEquals(HealthState.UNAVAILABLE, m.snapshot(1, true).state)
    }

    @Test
    fun oldHealthyResultGoesStale() {
        val m = ExecutionPathMonitor()
        val c = m.issue(0, "n1")
        m.onReceipt(receipt(c, 10), 10)
        // 3 个周期没有新挑战：旧的“健康”不再可信。
        assertEquals(HealthState.UNKNOWN, m.snapshot(10 + 3 * 60_000 + 1, true).state)
    }

    @Test
    fun noStackingWhileOutstandingAndIntervalRespected() {
        val m = ExecutionPathMonitor()
        val c = m.issue(0, "n1")
        assertFalse(m.due(120_000, true)) // 未决挑战还在
        m.onReceipt(receipt(c, 10), 10)
        assertFalse(m.due(30_000, true))
        assertTrue(m.due(60_000, true))
        assertFalse(m.due(60_000, false)) // 没约束时 5 分钟一次
    }

    @Test
    fun recoveryAfterFailureResetsCounter() {
        val m = ExecutionPathMonitor()
        m.issue(0, "n1"); m.poll(5_000)
        assertEquals(1, m.snapshot(5_001, true).consecutiveFailures)
        val c = m.issue(70_000, "n2")
        m.onReceipt(receipt(c, 70_020), 70_020)
        assertEquals(0, m.snapshot(70_021, true).consecutiveFailures)
        assertEquals(HealthState.OK, m.snapshot(70_021, true).state)
    }
}
