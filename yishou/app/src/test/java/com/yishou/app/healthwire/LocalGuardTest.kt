package com.yishou.app.healthwire

import com.yishou.app.health.LockArbiter
import com.yishou.app.health.LockState
import com.yishou.app.health.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 有限兜底（规范 §5.3 / §0 铁律 3、5）：只有「规则生效 + 哨兵失联 + 自己健康 + 有锁屏能力 + 去重通过」
 * 五件事同时成立才动手；一次事件最多 1–2 次，有冷却，有宽限。
 */
class LocalGuardTest {

    private fun policy(
        id: String = "p1",
        start: Long = 1_000,
        end: Long = 1_000_000,
        enabled: Boolean = true,
        failure: String = "LOCK",
        emergency: String = "NONE",
        required: Set<String> = setOf("com.yishou.app"),
    ) = Policy(
        policyId = id,
        revision = 1,
        issuer = "yishou",
        requiredApps = required,
        startsAtWall = start,
        endsAtWall = end,
        lockAtWall = null,
        failureAction = failure,
        repairGraceMs = 120_000,
        emergencyState = emergency,
        enabled = enabled,
    )

    private fun facts(
        nowWall: Long = 5_000,
        nowMono: Long = 5_000,
        sentinelLinked: Boolean = false,
        selfHealthy: Boolean = true,
        emergencyStopped: Boolean = false,
        adminActive: Boolean = true,
        secureLockSet: Boolean = true,
        repairGraceUntilMono: Long = 0,
    ) = GuardFacts(
        nowWall = nowWall,
        nowMono = nowMono,
        sentinelLinked = sentinelLinked,
        selfHealthy = selfHealthy,
        emergencyStopped = emergencyStopped,
        adminActive = adminActive,
        secureLockSet = secureLockSet,
        repairGraceUntilMono = repairGraceUntilMono,
    )

    private var seq = 0
    private val nextId = { "req-${++seq}" }

    @Test
    fun `常规情形 哨兵失联 自己健康 有规则 有能力就允许执行`() {
        val plan = LocalGuard.plan(listOf(policy()), facts(), LockArbiter(), nextId)
        assertTrue(plan.fire)
        assertEquals("p1", plan.policyId)
        assertTrue(plan.requestId.startsWith("req-"))
        assertTrue(plan.incidentId.isNotBlank())
    }

    @Test
    fun `紧急暂停时绝不自动锁屏`() {
        val plan = LocalGuard.plan(listOf(policy()), facts(emergencyStopped = true), LockArbiter(), nextId)
        assertFalse(plan.fire)
        assertTrue(plan.reason.contains("紧急暂停"))
    }

    @Test
    fun `规则标 STOPPED 时也不锁`() {
        val plan = LocalGuard.plan(listOf(policy(emergency = "STOPPED")), facts(), LockArbiter(), nextId)
        assertFalse(plan.fire)
        assertTrue(plan.reason.contains("紧急暂停"))
    }

    @Test
    fun `没有生效规则时不动手`() {
        val outWindow = LocalGuard.plan(listOf(policy(start = 1, end = 2)), facts(nowWall = 5_000), LockArbiter(), nextId)
        assertFalse(outWindow.fire)
        assertTrue(outWindow.reason.contains("没有生效"))

        val disabled = LocalGuard.plan(listOf(policy(enabled = false)), facts(), LockArbiter(), nextId)
        assertFalse(disabled.fire)
    }

    @Test
    fun `哨兵在线时一手不叠一层`() {
        val plan = LocalGuard.plan(listOf(policy()), facts(sentinelLinked = true), LockArbiter(), nextId)
        assertFalse(plan.fire)
        assertTrue(plan.reason.contains("哨兵"))
    }

    @Test
    fun `自己不太健康时不做兜底 因为那只是假装执行`() {
        val plan = LocalGuard.plan(listOf(policy()), facts(selfHealthy = false), LockArbiter(), nextId)
        assertFalse(plan.fire)
        assertTrue(plan.reason.contains("不健康"))
    }

    @Test
    fun `既没有设备管理员也没有安全锁屏时只提醒`() {
        val plan = LocalGuard.plan(
            listOf(policy()),
            facts(adminActive = false, secureLockSet = false),
            LockArbiter(),
            nextId,
        )
        assertFalse(plan.fire)
        assertTrue(plan.notifyOnly)
    }

    @Test
    fun `NOTIFY_ONLY 规则不锁屏 只提醒`() {
        val plan = LocalGuard.plan(listOf(policy(failure = "NOTIFY_ONLY")), facts(), LockArbiter(), nextId)
        assertFalse(plan.fire)
        assertTrue(plan.notifyOnly)
        assertTrue(plan.reason.contains("只提醒"))
    }

    @Test
    fun `同一事件确认锁上后不再重复锁`() {
        val arbiter = LockArbiter()
        val p = policy()
        val f = facts()
        val first = LocalGuard.plan(listOf(p), f, arbiter, nextId)
        assertTrue(first.fire)
        LocalGuard.record(arbiter, first, LockState.OBSERVED_LOCKED, f.nowMono, f.nowWall)

        val second = LocalGuard.plan(listOf(p), f.copy(nowMono = f.nowMono + 600_000), arbiter, nextId)
        assertFalse(second.fire)
        assertTrue(second.reason.contains("不重复") || second.reason.contains("上限"))
    }

    @Test
    fun `同一事件次数上限之后停止`() {
        val arbiter = LockArbiter(maxAttemptsPerIncident = 1)
        val p = policy()
        var f = facts()
        val first = LocalGuard.plan(listOf(p), f, arbiter, nextId)
        assertTrue(first.fire)
        LocalGuard.record(arbiter, first, LockState.FAILED, f.nowMono, f.nowWall)

        f = f.copy(nowMono = f.nowMono + 10 * 60_000)
        val second = LocalGuard.plan(listOf(p), f, arbiter, nextId)
        assertFalse(second.fire)
        assertTrue(second.reason.contains("上限"))
    }

    @Test
    fun `冷却期内不重试`() {
        val arbiter = LockArbiter(maxAttemptsPerIncident = 2, retryCooldownMs = 180_000)
        val p = policy()
        var f = facts()
        val first = LocalGuard.plan(listOf(p), f, arbiter, nextId)
        assertTrue(first.fire)
        LocalGuard.record(arbiter, first, LockState.FAILED, f.nowMono, f.nowWall)

        f = f.copy(nowMono = f.nowMono + 30_000)
        val second = LocalGuard.plan(listOf(p), f, arbiter, nextId)
        assertFalse(second.fire)
        assertTrue(second.reason.contains("冷却"))
    }

    @Test
    fun `修复宽限期内不重锁`() {
        val arbiter = LockArbiter()
        val plan = LocalGuard.plan(
            listOf(policy()),
            facts(nowMono = 5_000, repairGraceUntilMono = 60_000),
            arbiter,
            nextId,
        )
        assertFalse(plan.fire)
        assertTrue(plan.reason.contains("宽限"))
    }

    @Test
    fun `同一规则同一窗口的 incidentId 稳定 换个日期才变`() {
        val p = policy(start = 1_000, end = 2_000)
        val a = LocalGuard.incidentIdFor(p, facts(nowWall = 5_000))
        val b = LocalGuard.incidentIdFor(p, facts(nowWall = 6_000))
        assertEquals(a, b)
        val nextDay = LocalGuard.incidentIdFor(p, facts(nowWall = 5_000 + 86_400_000L))
        assertTrue("换一天要算新事件", a != nextDay)
        assertTrue(a.startsWith("p1@"))
    }

    @Test
    fun `多条规则时优先选最严格且 id 最小的 保证决策稳定`() {
        val notifyOnly = policy(id = "a-notify", failure = "NOTIFY_ONLY")
        val locking = policy(id = "b-lock", failure = "LOCK")
        val plan = LocalGuard.plan(listOf(notifyOnly, locking), facts(), LockArbiter(), nextId)
        assertTrue(plan.fire)
        assertEquals("b-lock", plan.policyId)
    }

    @Test
    fun `record 会把回执状态写进本地事实`() {
        Facts.reset()
        val arbiter = LockArbiter()
        val plan = LocalGuard.plan(listOf(policy()), facts(), arbiter, nextId)
        assertTrue(plan.fire)
        LocalGuard.record(arbiter, plan, LockState.OBSERVED_LOCKED, 5_000, 5_100)
        assertEquals("OBSERVED_LOCKED", Facts.snapshot().receiptState)
        assertEquals(5_100L, Facts.snapshot().receiptAtMono)
        Facts.reset()
    }

    @Test
    fun `requiredApps 为空的规则不当作可执行`() {
        val plan = LocalGuard.plan(listOf(policy(required = emptySet())), facts(), LockArbiter(), nextId)
        assertFalse(plan.fire)
        assertTrue(plan.reason.contains("没有要求监管"))
        assertNotNull(plan.reason)
    }

    @Test
    fun `锁屏决策不会改其他应用的规则 因为只读入参`() {
        val otherIssuer = policy(id = "zongkong-p").copy(issuer = "zongkong")
        val mine = policy(id = "yishou-p")
        // plan() 不看 issuer（规则已经由 LocalPolicy.merge 过滤过），这里只证明它不修改入参列表
        val input = listOf(otherIssuer, mine)
        val snapshot = input.toList()
        LocalGuard.plan(input, facts(), LockArbiter(), nextId)
        assertEquals(snapshot, input)
    }
}
