package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityHealthTest {

    private fun judge() = HealthChallengeJudge(timeoutMillis = 3_000)

    @Test
    fun healthyReceiptFromExecutionPathPasses() {
        val j = judge()
        val c = j.issue("c1", "n1", "a11y_tap", 1_000)
        val r = HealthReceipt("c1", "n1", stage = "main_dispatcher", ok = true, completedAt = 1_400)
        val v = j.judge(c, r, now = 1_400)
        assertTrue(v is ChallengeVerdict.Healthy)
        assertEquals(400L, (v as ChallengeVerdict.Healthy).latencyMillis)
    }

    @Test
    fun wrongNonceIsRejected() {
        // nonce 不符 = 可能是伪造或陈旧响应。
        val j = judge()
        val c = j.issue("c1", "n1", "a11y_tap", 1_000)
        val r = HealthReceipt("c1", "WRONG", stage = "main_dispatcher", ok = true, completedAt = 1_400)
        assertTrue(j.judge(c, r, 1_400) is ChallengeVerdict.Rejected)
    }

    @Test
    fun binderEchoIsNotAHealthProof() {
        // 入口直接回显不能证明执行队列活着。
        val j = judge()
        val c = j.issue("c1", "n1", "a11y_tap", 1_000)
        val echo = HealthReceipt("c1", "n1", stage = "binder_echo", ok = true, completedAt = 1_100)
        val v = j.judge(c, echo, 1_100)
        assertTrue(v is ChallengeVerdict.Rejected)
        assertTrue((v as ChallengeVerdict.Rejected).reason.contains("入口回显"))
    }

    @Test
    fun emptyStageIsRejected() {
        val j = judge()
        val c = j.issue("c1", "n1", "a11y_tap", 1_000)
        val r = HealthReceipt("c1", "n1", stage = "", ok = true, completedAt = 1_100)
        assertTrue(j.judge(c, r, 1_100) is ChallengeVerdict.Rejected)
    }

    @Test
    fun lateResponseIsVoidedByIssuerClock() {
        // 用发起端时钟判断，不听回执自报时间。
        val j = judge()
        val c = j.issue("c1", "n1", "a11y_tap", 1_000)
        val r = HealthReceipt("c1", "n1", stage = "main_dispatcher", ok = true, completedAt = 1_200)
        val v = j.judge(c, r, now = 9_999)
        assertTrue(v is ChallengeVerdict.TimedOut)
    }

    @Test
    fun missingReceiptTimesOut() {
        val j = judge()
        val c = j.issue("c1", "n1", "a11y_tap", 1_000)
        assertTrue(j.judge(c, null, 5_000) is ChallengeVerdict.TimedOut)
    }

    @Test
    fun failedExecutionIsRejected() {
        val j = judge()
        val c = j.issue("c1", "n1", "a11y_tap", 1_000)
        val r = HealthReceipt("c1", "n1", stage = "main_dispatcher", ok = false, completedAt = 1_200)
        assertTrue(j.judge(c, r, 1_200) is ChallengeVerdict.Rejected)
    }

    @Test
    fun permissionOnButExecutionStuckIsDegradedNotOk() {
        // 核心：权限开着不等于健康。
        val reports = CapabilitySummary.summarize(
            permission = true, serviceConnected = true,
            executionPathHealthy = false, lastAction = ActionReceiptState.UNKNOWN,
        )
        val exec = reports.first { it.layer == HealthLayer.EXECUTION_PATH }
        assertEquals(HealthState.DEGRADED, exec.state)
        assertTrue(exec.detail.contains("没响应"))
    }

    @Test
    fun apiSuccessWithoutObservationIsUnknown() {
        val reports = CapabilitySummary.summarize(
            permission = true, serviceConnected = true,
            executionPathHealthy = true,
            lastAction = ActionReceiptState.API_REPORTED_SUCCESS,
        )
        val last = reports.first { it.layer == HealthLayer.LAST_ACTION }
        assertEquals(HealthState.UNKNOWN, last.state)
        assertTrue(last.detail.contains("没观察到效果"))
    }

    @Test
    fun cannotClaimReliableWithoutObservedEffect() {
        // 只 API 报成功，不能宣称监管可靠。
        assertFalse(CapabilitySummary.canClaimReliable(
            permission = true, serviceConnected = true, executionPathHealthy = true,
            lastAction = ActionReceiptState.API_REPORTED_SUCCESS,
        ))
        assertFalse(CapabilitySummary.canClaimReliable(
            permission = true, serviceConnected = true, executionPathHealthy = false,
            lastAction = ActionReceiptState.OBSERVED_CONFIRMED,
        ))
        assertTrue(CapabilitySummary.canClaimReliable(
            permission = true, serviceConnected = true, executionPathHealthy = true,
            lastAction = ActionReceiptState.OBSERVED_CONFIRMED,
        ))
    }

    @Test
    fun noServiceMeansUnavailable() {
        val reports = CapabilitySummary.summarize(
            permission = false, serviceConnected = false,
            executionPathHealthy = false, lastAction = null,
        )
        reports.forEach {
            // BINDER 未使用这条通道时是 UNKNOWN（不适用），不算不可用。
            if (it.layer != HealthLayer.LAST_ACTION && it.layer != HealthLayer.BINDER) {
                assertEquals(HealthState.UNAVAILABLE, it.state)
            }
            if (it.layer == HealthLayer.BINDER) {
                assertEquals(HealthState.UNKNOWN, it.state)
                assertTrue(it.detail.contains("不适用"))
            }
        }
    }
}
