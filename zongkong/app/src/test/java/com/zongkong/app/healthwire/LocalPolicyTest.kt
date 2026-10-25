package com.zongkong.app.healthwire

import com.zongkong.app.health.LockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地规则 + 有限兜底：不猜用户作息、去重、只在哨兵失联且自己健康时兜一次。 */
class LocalPolicyTest {

    private val nowWall = 1_700_000_000_000L

    private fun policy(
        enabled: Boolean = true,
        starts: Long = nowWall - 60_000,
        ends: Long = nowWall + 3_600_000,
        lockAt: Long = 0,
        action: String = LocalPolicy.ACTION_LOCK,
        emergency: String = LocalPolicy.EMERGENCY_NONE,
        revision: Int = 1,
    ) = LocalPolicy(
        policyId = "p-$revision", revision = revision, issuer = "user",
        startsAtWall = starts, endsAtWall = ends, lockAtWall = lockAt,
        failureAction = action, emergencyState = emergency, enabled = enabled,
    )

    @Test
    fun `窗口外的规则不生效`() {
        assertFalse(policy(ends = nowWall - 1).effective(nowWall))
        assertFalse(policy(starts = nowWall + 1).effective(nowWall))
        assertFalse(policy(enabled = false).effective(nowWall))
        assertTrue(policy().effective(nowWall))
    }

    @Test
    fun `默认不设置到期锁屏 不编造作息`() {
        val p = policy()
        assertEquals(0L, p.lockAtWall)
        assertFalse(p.lockDue(nowWall))
    }

    @Test
    fun `到期锁屏只在窗口内有效`() {
        val p = policy(lockAt = nowWall - 1_000)
        assertTrue(p.lockDue(nowWall))
        assertFalse(policy(lockAt = nowWall + 1_000).lockDue(nowWall))
        assertFalse(policy(lockAt = nowWall - 1_000, enabled = false).lockDue(nowWall))
    }

    @Test
    fun `哨兵在岗时不抢着锁`() {
        val fb = LocalFallback(source = "zongkong")
        val a = fb.decide(
            policies = listOf(policy()), nowWall = nowWall, nowMono = 1_000,
            sentinelHealthy = true, selfHealthy = true, emergencyStopped = false,
            incidentKey = "zk-down",
        )
        assertFalse(a.fire)
        assertNull(a.request)
        assertTrue(a.reason.contains("哨兵在岗"))
    }

    @Test
    fun `自己也不健康时不越权兜底`() {
        val fb = LocalFallback(source = "zongkong")
        val a = fb.decide(
            policies = listOf(policy()), nowWall = nowWall, nowMono = 1_000,
            sentinelHealthy = false, selfHealthy = false, emergencyStopped = false,
            incidentKey = "zk-down",
        )
        assertFalse(a.fire)
    }

    @Test
    fun `哨兵失联且自己健康时按本地规则兜一次`() {
        val fb = LocalFallback(source = "zongkong")
        val a = fb.decide(
            policies = listOf(policy()), nowWall = nowWall, nowMono = 1_000,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "sentinel-down",
        )
        assertTrue(a.fire)
        val r = a.request
        assertNotNull(r)
        assertEquals("zongkong", r!!.source)
        assertEquals("p-1", r.policyId)
        assertTrue(r.incidentId.contains("p-1"))
        assertTrue(r.requestId.isNotBlank())
        assertTrue(r.reason.isNotBlank())
    }

    @Test
    fun `同一事件不重复锁`() {
        val fb = LocalFallback(source = "zongkong")
        val first = fb.decide(
            policies = listOf(policy()), nowWall = nowWall, nowMono = 1_000,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "sentinel-down",
        )
        assertTrue(first.fire)
        fb.record(first.request!!, LockState.OBSERVED_LOCKED, 1_000)

        val second = fb.decide(
            policies = listOf(policy()), nowWall = nowWall, nowMono = 60_000,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "sentinel-down",
        )
        assertFalse("同一事件已确认锁屏，不许再锁", second.fire)
    }

    @Test
    fun `每事件次数上限默认 1 次`() {
        val fb = LocalFallback(source = "zongkong")
        val first = fb.decide(
            policies = listOf(policy()), nowWall = nowWall, nowMono = 1_000,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "e",
        )
        assertTrue(first.fire)
        // 执行了但没观测到锁上：也只允许这一次
        fb.record(first.request!!, LockState.UNVERIFIED, 1_000)
        val second = fb.decide(
            policies = listOf(policy()), nowWall = nowWall, nowMono = 10 * 60_000,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "e",
        )
        assertFalse(second.fire)
        assertTrue(second.reason.contains("上限"))
    }

    @Test
    fun `修复宽限内不重锁`() {
        val fb = LocalFallback(source = "zongkong")
        val a = fb.decide(
            policies = listOf(policy()), nowWall = nowWall, nowMono = 1_000,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "e", repairGraceUntilMono = 120_000,
        )
        assertFalse(a.fire)
        assertTrue(a.reason.contains("修复宽限"))
    }

    @Test
    fun `紧急暂停立刻停掉本地自动锁屏`() {
        val fb = LocalFallback(source = "zongkong")
        val a = fb.decide(
            policies = listOf(policy(emergency = LocalPolicy.EMERGENCY_STOPPED)),
            nowWall = nowWall, nowMono = 1_000,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = true,
            incidentKey = "e",
        )
        assertFalse(a.fire)
        assertTrue(a.reason.contains("紧急暂停"))
    }

    @Test
    fun `NOTIFY_ONLY 规则不锁屏`() {
        val fb = LocalFallback(source = "zongkong")
        val a = fb.decide(
            policies = listOf(policy(action = LocalPolicy.ACTION_NOTIFY_ONLY)),
            nowWall = nowWall, nowMono = 1_000,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "e",
        )
        assertFalse(a.fire)
        assertTrue(a.reason.contains("NOTIFY_ONLY"))
    }

    @Test
    fun `没有生效规则时不锁`() {
        val fb = LocalFallback(source = "zongkong")
        val a = fb.decide(
            policies = listOf(policy(enabled = false)), nowWall = nowWall, nowMono = 1_000,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "e",
        )
        assertFalse(a.fire)
    }

    @Test
    fun `多条生效规则取最新版本`() {
        val fb = LocalFallback(source = "zongkong")
        val a = fb.decide(
            policies = listOf(policy(revision = 1), policy(revision = 5)),
            nowWall = nowWall, nowMono = 1_000,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "e",
        )
        assertTrue(a.fire)
        assertEquals("p-5", a.request!!.policyId)
    }
}
