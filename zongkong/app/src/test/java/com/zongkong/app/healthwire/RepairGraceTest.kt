package com.zongkong.app.healthwire

import com.zongkong.app.health.HealthConfig
import com.zongkong.app.health.LockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15 修复宽限。
 *
 * 背景（真实缺口）：`LocalPolicy.decide()` 早就有 `repairGraceUntilMono` 参数、
 * 共享内核也早就有宽限分支，但 `LocalGuardian.tickNow()` 从来没传过它 ——
 * 恒用默认 0，宽限永远不生效，锁屏后就会一直重锁，把用户挡在修复权限的界面外面。
 *
 * 这些用例不 sleep：用假单调时钟推进，逐条钉住
 *  ① 宽限期内 decide 返回 fire=false 且理由是「修复宽限中」；
 *  ② 宽限过期后仍可再锁；
 *  ③ 锁屏确认后 until = now + repairGraceMs。
 */
class RepairGraceTest {

    private val cfg = HealthConfig()
    private val nowWall = 1_700_000_000_000L

    private fun policy(graceMs: Long = cfg.repairGraceMs) = LocalPolicy(
        policyId = "p-grace",
        revision = 1,
        issuer = "user",
        startsAtWall = nowWall - 60_000,
        endsAtWall = nowWall + 3_600_000,
        repairGraceMs = graceMs,
        enabled = true,
    )

    // ---------- ① 宽限期内不锁 ----------

    @Test
    fun `宽限期内 decide 返回 fire=false 且理由是修复宽限中`() {
        val fb = LocalFallback(source = "zongkong")
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)

        // 锁屏确认：从时刻 1_000 起算宽限
        grace.onLockConfirmed(nowMono = 1_000L)
        val until = grace.untilMono
        assertEquals(1_000L + cfg.repairGraceMs, until)

        // 宽限期内再问一次：必须拒绝，并给出「修复宽限」的理由（不是「已达上限」那种）
        val attempt = fb.decide(
            policies = listOf(policy()),
            nowWall = nowWall,
            nowMono = 1_000L + 15_000L, // 下一个 tick（15s 后）
            sentinelHealthy = false,
            selfHealthy = true,
            emergencyStopped = false,
            incidentKey = "sentinel-unreachable",
            repairGraceUntilMono = grace.untilMono,
        )
        assertFalse("宽限期内不许再锁", attempt.fire)
        assertTrue("理由必须点明修复宽限，实际：${attempt.reason}", attempt.reason.contains("修复宽限"))
    }

    @Test
    fun `没传宽限时旧的 bug 会真的重锁（对照用例 证明缺口是真的）`() {
        val fb = LocalFallback(source = "zongkong")
        // 模拟修复前的行为：repairGraceUntilMono 用默认 0
        val attempt = fb.decide(
            policies = listOf(policy()),
            nowWall = nowWall,
            nowMono = 16_000L,
            sentinelHealthy = false,
            selfHealthy = true,
            emergencyStopped = false,
            incidentKey = "sentinel-unreachable",
            // 不传 → 0
        )
        assertTrue("传 0 时仲裁仍会放行 —— 这正是 t13 要修的那个缺口", attempt.fire)
    }

    // ---------- ② 宽限过期后仍可再锁 ----------

    @Test
    fun `宽限过期后仍可再锁`() {
        val fb = LocalFallback(source = "zongkong")
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        grace.onLockConfirmed(nowMono = 1_000L)

        // 宽限到期：expire 清 0
        val expiredAt = grace.untilMono + 1
        assertTrue(grace.expire(expiredAt))
        assertEquals("到期必须清 0，不能永久豁免", 0L, grace.untilMono)
        assertFalse(grace.active(expiredAt))

        val attempt = fb.decide(
            policies = listOf(policy()),
            nowWall = nowWall,
            nowMono = expiredAt,
            sentinelHealthy = false,
            selfHealthy = true,
            emergencyStopped = false,
            incidentKey = "another-event",
            repairGraceUntilMono = grace.untilMono,
        )
        assertTrue("宽限过期后必须能再锁，实际：${attempt.reason}", attempt.fire)
    }

    @Test
    fun `宽限没过期前 expire 不动它`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        grace.onLockConfirmed(nowMono = 1_000L)
        val before = grace.untilMono
        assertFalse(grace.expire(before - 1))
        assertEquals(before, grace.untilMono)
        assertTrue(grace.active(before - 1))
    }

    // ---------- ③ 锁屏确认后 until = now + repairGraceMs ----------

    @Test
    fun `锁屏确认后 until 等于 now 加 repairGraceMs`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        val now = 5_000L
        val until = grace.onLockConfirmed(nowMono = now)
        assertEquals(now + cfg.repairGraceMs, until)
        assertEquals(until, grace.untilMono)
        assertEquals(cfg.repairGraceMs, grace.remainingMs(now))
    }

    @Test
    fun `规则里的 repairGraceMs 可以覆盖默认值 与一手同桌同口径`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        val p = policy(graceMs = 30_000L)
        val until = grace.onLockConfirmed(nowMono = 1_000L, graceMs = p.repairGraceMs)
        assertEquals(31_000L, until)
    }

    @Test
    fun `graceMs 非正数时退回默认值 不会变成没有宽限`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        val until = grace.onLockConfirmed(nowMono = 1_000L, graceMs = 0L)
        assertEquals(1_000L + cfg.repairGraceMs, until)
    }

    // ---------- 解锁观测 / 清空 ----------

    @Test
    fun `观测到 locked 到 unlocked 跳变时重新起算完整宽限`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        // 观测到锁上了
        assertFalse(grace.observe(lockedNow = true, nowMono = 1_000L))
        // 解锁 → 重新起算
        assertTrue(grace.observe(lockedNow = false, nowMono = 20_000L))
        assertEquals(20_000L + cfg.repairGraceMs, grace.untilMono)
    }

    @Test
    fun `一直没有锁过时 unlocked 不算解锁 不会凭空起宽限`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        assertFalse(grace.observe(lockedNow = false, nowMono = 1_000L))
        assertEquals(0L, grace.untilMono)
    }

    @Test
    fun `用户主动恢复后 clear 清空宽限`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        grace.onLockConfirmed(nowMono = 1_000L)
        assertTrue(grace.untilMono > 0)
        grace.clear()
        assertEquals(0L, grace.untilMono)
        assertFalse(grace.active(1_500L))
    }

    // ---------- 哪些回执状态该起宽限 ----------

    @Test
    fun `观测到锁上或接口调用后起宽限 调用失败不起宽限`() {
        assertTrue(RepairGrace.armAfterReceipt(LockState.OBSERVED_LOCKED))
        assertTrue("调过接口但没观测到：用户很可能正去解锁，也要给宽限", RepairGrace.armAfterReceipt(LockState.UNVERIFIED))
        assertTrue(RepairGrace.armAfterReceipt(LockState.API_CALLED))
        assertFalse("失败就别给宽限，否则一直锁不上会变成永远不锁", RepairGrace.armAfterReceipt(LockState.FAILED))
        assertFalse(RepairGrace.armAfterReceipt(LockState.REQUESTED))
    }

    @Test
    fun `宽限文案不涂绿 说人话`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        assertEquals("没有修复宽限", grace.describe(1_000L))
        grace.onLockConfirmed(nowMono = 1_000L)
        assertTrue(grace.describe(2_000L).contains("修复宽限中"))
        assertTrue(grace.describe(grace.untilMono + 1).contains("已过期"))
    }

    // ---------- ④ 生产接线：LocalGuardian 真的计算并写入这个时刻 ----------

    @Test
    fun `LocalGuardian 在锁屏确认后把宽限设成 now 加 repairGraceMs`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        val now = 9_000L
        val until = LocalGuardian.armGraceAfterLock(
            grace = grace,
            policies = listOf(policy()),
            receiptState = LockState.OBSERVED_LOCKED,
            nowWall = nowWall,
            nowMono = now,
        )
        assertEquals("锁屏确认后必须 = now + repairGraceMs", now + cfg.repairGraceMs, until)
        assertEquals(until, grace.untilMono)
        // 这条断言就是 t13 的核心：以前这个值恒为 0。
        assertTrue("宽限时刻必须真的被写进状态机", grace.untilMono > 0L)
    }

    @Test
    fun `LocalGuardian 在只调用了接口没观测到时也给宽限`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        val until = LocalGuardian.armGraceAfterLock(
            grace = grace,
            policies = listOf(policy()),
            receiptState = LockState.UNVERIFIED,
            nowWall = nowWall,
            nowMono = 1_000L,
        )
        assertTrue("UNVERIFIED 也要给宽限（用户可能正去解锁）", until > 0L)
    }

    @Test
    fun `LocalGuardian 在锁屏失败时不起宽限 避免永远不锁`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        val until = LocalGuardian.armGraceAfterLock(
            grace = grace,
            policies = listOf(policy()),
            receiptState = LockState.FAILED,
            nowWall = nowWall,
            nowMono = 1_000L,
        )
        assertEquals(0L, until)
        assertEquals(0L, grace.untilMono)
    }

    @Test
    fun `LocalGuardian 用生效规则里的 repairGraceMs 覆盖默认值`() {
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        val until = LocalGuardian.armGraceAfterLock(
            grace = grace,
            policies = listOf(policy(graceMs = 45_000L)),
            receiptState = LockState.OBSERVED_LOCKED,
            nowWall = nowWall,
            nowMono = 1_000L,
        )
        assertEquals(46_000L, until)
    }

    @Test
    fun `端到端 锁屏后下一次 tick 不再重锁 宽限过后才可再锁`() {
        // 这是 t13 报的那个场景，用假时钟完整走一遍。
        val fb = LocalFallback(source = "zongkong")
        val grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs)
        val lockAt = 1_000L

        // 第一次：条件齐了 → 允许锁
        val first = fb.decide(
            policies = listOf(policy()), nowWall = nowWall, nowMono = lockAt,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "sentinel-unreachable", repairGraceUntilMono = grace.untilMono,
        )
        assertTrue(first.fire)
        fb.record(first.request!!, LockState.OBSERVED_LOCKED, lockAt)
        // 锁屏确认 → 起宽限
        LocalGuardian.armGraceAfterLock(grace, listOf(policy()), LockState.OBSERVED_LOCKED, nowWall, lockAt)

        // 15 秒后下一个 tick：正在宽限，必须不锁（否则会挡住用户修权限）
        val next = fb.decide(
            policies = listOf(policy()), nowWall = nowWall, nowMono = lockAt + 15_000L,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "sentinel-unreachable", repairGraceUntilMono = grace.untilMono,
        )
        assertFalse("宽限期内重锁 = 把用户挡在修复界面外（T15）", next.fire)
        assertTrue(next.reason.contains("修复宽限"))

        // 宽限到期：换一个新事件，允许再次执行
        val afterGrace = lockAt + cfg.repairGraceMs + 1
        assertTrue(grace.expire(afterGrace))
        val again = fb.decide(
            policies = listOf(policy()), nowWall = nowWall, nowMono = afterGrace,
            sentinelHealthy = false, selfHealthy = true, emergencyStopped = false,
            incidentKey = "new-event", repairGraceUntilMono = grace.untilMono,
        )
        assertTrue("宽限过期后必须恢复可执行，实际：${again.reason}", again.fire)
    }
}
