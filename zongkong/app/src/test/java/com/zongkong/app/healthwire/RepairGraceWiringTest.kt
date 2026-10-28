package com.zongkong.app.healthwire

import com.zongkong.app.health.HealthConfig
import com.zongkong.app.health.LockArbiter
import com.zongkong.app.health.LockReceipt
import com.zongkong.app.health.LockRequest
import com.zongkong.app.health.LockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **接线级**回归测试（t14）。
 *
 * 为什么要有这个文件 —— t13 的教训：
 * t13 那条「端到端」测试是这样写的：
 * ```
 * val first = fb.decide(policies = ..., repairGraceUntilMono = grace.untilMono)  // ← 自己传的
 * ```
 * 它自己把宽限当参数喂进 `LocalFallback.decide(...)`，**完全绕过了
 * `LocalGuardian.tickNow()` 里那行真实接线**。于是把 `repairGraceUntilMono` 改回 0
 * （修复前的 bug）之后，286 条用例依然全绿 —— 假绿灯。
 *
 * 本文件的做法：通过 [LocalGuardian.tickWithPortsForTest] 驱动
 * **生产 `tickNow()` 调用的同一个 `GuardTickCore.tick(...)`**（不是测试副本），
 * 只把外部副作用（锁屏、上报、诊断、时钟）换成假实现。
 * 因此「宽限到底有没有被传进 decide」这件事被真实覆盖：
 * 把 `GuardTickCore.tick` 里那行 `repairGraceUntilMono = grace.untilMono` 改成 `0L`，
 * `tick 第二次应被修复宽限拦住` 必然失败。
 */
class RepairGraceWiringTest {

    private val cfg = HealthConfig()
    private val nowWall = 1_700_000_000_000L

    private fun policy(graceMs: Long = cfg.repairGraceMs) = LocalPolicy(
        policyId = "p-wiring",
        revision = 1,
        issuer = "user",
        startsAtWall = nowWall - 60_000,
        endsAtWall = nowWall + 3_600_000,
        repairGraceMs = graceMs,
        enabled = true,
    )

    /** 假 ports：只记录副作用，不做真锁屏。 */
    private class FakeWorld(
        var nowMono: Long,
        private val policies: List<LocalPolicy>,
        private val selfHealthy: Boolean = true,
        private val sentinelHealthy: Boolean = false,
        private val emergency: Boolean = false,
        /** null = 读不到 Keyguard（模拟真机采样不到的情形）。 */
        var keyguardLocked: Boolean? = null,
        private val lockState: LockState = LockState.OBSERVED_LOCKED,
    ) {
        val lockRequests = mutableListOf<LockRequest>()
        val receipts = mutableListOf<LockReceipt>()
        val diags = mutableListOf<Pair<String, String>>()
        var reported = 0

        fun ports() = GuardTickPorts(
            mono = { nowMono },
            policies = { policies },
            emergencyStopped = { emergency },
            selfHealthy = { selfHealthy },
            probeSentinel = { },
            sentinelHealthy = { sentinelHealthy },
            keyguardLocked = { keyguardLocked },
            lock = { req, wall ->
                lockRequests += req
                LockReceipt(
                    policyId = req.policyId,
                    incidentId = req.incidentId,
                    requestId = req.requestId,
                    source = req.source,
                    reason = req.reason,
                    state = lockState,
                    atWall = wall,
                    note = "fake",
                ).also { receipts += it }
            },
            reportReceipt = { reported++; true },
            notifyUnverified = { },
            diag = { k, d -> diags += k to d },
        )
    }

    /**
     * 生产同一个 core 的实例：为了让「第二次 tick 是否被宽限拦住」这个信号干净，
     * 这里注入一个**宽松仲裁器**（次数上限拉高），否则仲裁自己的上限会掩盖注入的 bug。
     * 注意 fallback 与 grace 都是注入进 `GuardTickCore` 的同一份对象，接线路径不变。
     */
    private fun core(): GuardTickCore = coreFor("sentinel-unreachable", RepairGrace(cfg.repairGraceMs))

    /** 指定 incidentKey 与（可共享的）宽限状态机。 */
    private fun coreFor(incidentKey: String, grace: RepairGrace): GuardTickCore = GuardTickCore(
        fallback = LocalFallback(
            arbiter = LockArbiter(
                maxAttemptsPerIncident = 99,
                retryCooldownMs = 0L,
                minGapBetweenLocksMs = 0L,
            ),
            source = "zongkong",
        ),
        grace = grace,
        incidentKey = incidentKey,
    )

    /**
     * 主用例：锁屏确认后，下一个 tick 必须被**修复宽限**拦住。
     *
     * 这是把 t13 那个 bug（`repairGraceUntilMono` 恒传 0）放回去会变红的那一条。
     *
     * 设计要点 —— 为什么第二次 tick 换一个 incidentKey：
     *   如果沿用同一个 incidentKey，`LockArbiter` 会用「同一事件已确认锁屏」把第二次拦掉。
     *   那样即使宽限完全失效、注入的 bug 也照样「没再锁」，用例就变成恒真（假绿灯）。
     *   换成**新事件**（模拟宽限期内又来了一个新故障）之后，仲裁会放行，
     *   **只有宽限能拦住它** —— 于是「没有新回执」这条断言才真正在测宽限接线。
     *   两次 tick 共享同一个 [RepairGrace] 实例（模拟同一进程内的真实状态）。
     */
    @Test
    fun `tick 第二次应被修复宽限拦住`() {
        val world = FakeWorld(nowMono = 1_000L, policies = listOf(policy()))
        val grace = RepairGrace(cfg.repairGraceMs)
        val first = coreFor("sentinel-unreachable", grace)
        val second = coreFor("sentinel-unreachable-next", grace)

        // ---- 第一次 tick：条件齐了 → 真的锁 ----
        val out1 = first.tick(world.ports(), nowWall)
        assertTrue("第一次应该锁，实际：$out1（${first.lastDecision}）", out1 is TickOutcome.Fired)
        assertEquals(1, world.lockRequests.size)
        assertEquals(1, first.lockCount)
        assertNotNull(first.lastReceipt)

        // 锁屏确认后宽限必须已经起算
        assertTrue(
            "锁屏确认后必须写入宽限时刻，实际 until=${first.repairGraceUntilMono}",
            first.repairGraceUntilMono > 0L,
        )
        assertEquals(1_000L + cfg.repairGraceMs, first.repairGraceUntilMono)

        // ---- 第二次 tick：15 秒后、新事件 ----
        world.nowMono = 1_000L + 15_000L
        val out2 = second.tick(world.ports(), nowWall)

        // 核心断言 ①：没有再锁（否则会把用户挡在修权限界面外）
        assertEquals(
            "第二次 tick 不许再锁（宽限期内）。实际锁了 ${world.lockRequests.size} 次；判定理由：${second.lastDecision}",
            1,
            world.lockRequests.size,
        )
        // 第二次那条 core 自己一次都没锁过（每个 core 各自计数，world 才是全局）。
        assertEquals(0, second.lockCount)
        // 核心断言 ②：理由必须点明修复宽限 —— 证明拦住它的是宽限，而不是别的机制
        assertTrue(
            "第二次 lastDecision 必须包含「修复宽限」，实际：${second.lastDecision}",
            second.lastDecision.contains("修复宽限"),
        )
        // 核心断言 ③：没有新回执
        assertTrue(out2 is TickOutcome.NotFired)
        assertNull(second.lastReceipt)
    }

    /**
     * 同 incidentKey 版本：确实也不该重锁，但拦它的是仲裁的去重而不是宽限，
     * 所以单独一条、只断言「没再锁」——不把两层保护混为一谈。
     */
    @Test
    fun `同一事件在宽限期内也不会被重复执行`() {
        val world = FakeWorld(nowMono = 1_000L, policies = listOf(policy()))
        val core = core()
        assertTrue(core.tick(world.ports(), nowWall) is TickOutcome.Fired)
        world.nowMono = 1_000L + 15_000L
        val second = core.tick(world.ports(), nowWall)
        assertTrue(second is TickOutcome.NotFired)
        assertEquals(1, world.lockRequests.size)
    }

    /**
     * 反向对照：把宽限状态机人为清空（等价于「那行传参永远传 0」的效果），
     * 同样的两次 tick 就会重锁 —— 证明本用例确实在测接线，而不是恒真。
     *
     * 注意 incidentKey 要换：同一 incidentId 已被仲裁记成「已确认锁屏」，
     * 那时仲裁自己会拦（那是另一层保护，不是宽限）。换成新事件才能真正隔离出宽限的作用。
     */
    @Test
    fun `宽限被清掉后第二次 tick 会重锁 证明用例非恒真`() {
        val world = FakeWorld(nowMono = 1_000L, policies = listOf(policy()))
        val core = core()

        assertTrue(core.tick(world.ports(), nowWall) is TickOutcome.Fired)
        assertEquals(1, world.lockRequests.size)

        // 模拟「宽限没有生效」：清掉，并换一个新事件（避免仲裁的同事件去重掩盖信号）
        core.grace.clear()
        val newEvent = coreForNewEvent()

        world.nowMono = 1_000L + 15_000L
        val second = newEvent.tick(world.ports(), nowWall)
        assertTrue("宽限被清掉后应该重锁，实际：$second（${newEvent.lastDecision}）", second is TickOutcome.Fired)
        assertEquals(2, world.lockRequests.size)
    }

    /** 宽限过期之后，同样的 tick 必须恢复可执行（不能让宽限变成永久豁免）。 */
    @Test
    fun `宽限过期后第二次 tick 恢复可再锁`() {
        val world = FakeWorld(nowMono = 1_000L, policies = listOf(policy(graceMs = 20_000L)))
        val core = core()

        assertTrue(core.tick(world.ports(), nowWall) is TickOutcome.Fired)
        assertEquals(1, world.lockRequests.size)

        // 越过宽限（20s）再加一个 tick 间隔；换新事件以隔离仲裁的同事件去重
        val newEvent = coreForNewEvent()
        world.nowMono = 1_000L + 20_000L + 15_000L
        val second = newEvent.tick(world.ports(), nowWall)
        assertTrue("宽限过期后必须能再锁，实际：$second（${newEvent.lastDecision}）", second is TickOutcome.Fired)
        assertEquals(2, world.lockRequests.size)
    }

    /** 另一个 core 实例，用不同 incidentKey（模拟「换了新故障事件」）。 */
    private fun coreForNewEvent(): GuardTickCore = GuardTickCore(
        fallback = LocalFallback(
            arbiter = LockArbiter(
                maxAttemptsPerIncident = 99,
                retryCooldownMs = 0L,
                minGapBetweenLocksMs = 0L,
            ),
            source = "zongkong",
        ),
        grace = RepairGrace(defaultGraceMs = cfg.repairGraceMs),
        incidentKey = "sentinel-unreachable-2",
    )

    /** 观测到真解锁会重新起算宽限，但不会凭空产生宽限。 */
    @Test
    fun `真观测到解锁会重新起算宽限`() {
        val world = FakeWorld(nowMono = 1_000L, policies = listOf(policy()), keyguardLocked = true)
        val core = core()

        // 第一次：keyguard 锁着 → 锁屏 + 起宽限
        assertTrue(core.tick(world.ports(), nowWall) is TickOutcome.Fired)
        val firstUntil = core.repairGraceUntilMono

        // 宽限快过期时观测到解锁 → 重新起算
        world.nowMono = firstUntil - 1_000L
        world.keyguardLocked = false
        core.tick(world.ports(), nowWall)
        assertTrue("解锁后应重新起算，实际 until=${core.repairGraceUntilMono}", core.repairGraceUntilMono > firstUntil)
    }

    /** 紧急暂停时既不锁，也清掉宽限（暂停结束后按当时事实重新判断）。 */
    @Test
    fun `紧急暂停时 tick 不锁且清空宽限`() {
        val world = FakeWorld(nowMono = 1_000L, policies = listOf(policy()), emergency = true)
        val core = core()
        val out = core.tick(world.ports(), nowWall)
        assertTrue(out is TickOutcome.EmergencyStopped)
        assertEquals(0, world.lockRequests.size)
        assertEquals(0L, core.repairGraceUntilMono)
        assertTrue(core.lastDecision.contains("紧急暂停"))
    }

    /** 节流：不足 15s 直接跳过（不会因为 UI 频繁调用而漏判或重复锁）。 */
    @Test
    fun `不足节流间隔的 tick 被跳过`() {
        val world = FakeWorld(nowMono = 1_000L, policies = listOf(policy()))
        val core = core()
        assertTrue(core.tick(world.ports(), nowWall) is TickOutcome.Fired)
        world.nowMono = 1_000L + 5_000L
        assertTrue(core.tick(world.ports(), nowWall) is TickOutcome.Throttled)
        assertEquals(1, world.lockRequests.size)
    }

    /** 哨兵恢复在岗之后不该再兜底（本地不抢哨兵的活）。 */
    @Test
    fun `哨兵在岗时不锁`() {
        val world = FakeWorld(nowMono = 1_000L, policies = listOf(policy()), sentinelHealthy = true)
        val core = core()
        val out = core.tick(world.ports(), nowWall)
        assertTrue(out is TickOutcome.NotFired)
        assertEquals(0, world.lockRequests.size)
        assertTrue(core.lastDecision.contains("哨兵在岗"))
    }

    /** 自己也不健康时不越权兜底。 */
    @Test
    fun `自己不健康时不锁`() {
        val world = FakeWorld(nowMono = 1_000L, policies = listOf(policy()), selfHealthy = false)
        val core = core()
        val out = core.tick(world.ports(), nowWall)
        assertTrue(out is TickOutcome.NotFired)
        assertEquals(0, world.lockRequests.size)
    }

    /** 没有规则时不锁。 */
    @Test
    fun `没有本地规则时不锁`() {
        val world = FakeWorld(nowMono = 1_000L, policies = emptyList())
        val core = core()
        assertTrue(core.tick(world.ports(), nowWall) is TickOutcome.NoPolicies)
        assertEquals(0, world.lockRequests.size)
    }

    /** 锁屏失败回执不起宽限：否则「一直锁不上」会退化成「永远不锁」。 */
    @Test
    fun `锁屏失败回执不起宽限`() {
        val world = FakeWorld(
            nowMono = 1_000L,
            policies = listOf(policy()),
            lockState = LockState.FAILED,
        )
        val core = core()
        assertTrue(core.tick(world.ports(), nowWall) is TickOutcome.Fired)
        assertEquals("失败回执不许起宽限", 0L, core.repairGraceUntilMono)

        // 下一次 tick 仍会尝试（不会被宽限永久豁免）
        world.nowMono = 1_000L + 15_000L
        assertTrue(core.tick(world.ports(), nowWall) is TickOutcome.Fired)
        assertEquals(2, world.lockRequests.size)
    }

    /** 回执必须上报，并带上四个 id（T17 要求）。 */
    @Test
    fun `回执上报且带齐四个 id`() {
        val world = FakeWorld(nowMono = 1_000L, policies = listOf(policy()))
        val core = core()
        core.tick(world.ports(), nowWall)
        val r = core.lastReceipt
        assertNotNull(r)
        assertTrue(r!!.policyId.isNotBlank())
        assertTrue(r.incidentId.isNotBlank())
        assertTrue(r.requestId.isNotBlank())
        assertEquals("zongkong", r.source)
        assertTrue(r.reason.isNotBlank())
        assertEquals(1, world.reported)
    }

    /** 无 Keyguard 事实（读不到）时不能崩，且仍走「锁屏确认即起宽限」的退化策略。 */
    @Test
    fun `读不到 Keyguard 时仍按退化策略起宽限`() {
        val world = FakeWorld(nowMono = 1_000L, policies = listOf(policy()), keyguardLocked = null)
        val core = core()
        assertTrue(core.tick(world.ports(), nowWall) is TickOutcome.Fired)
        assertTrue("退化策略：锁屏确认即起宽限", core.repairGraceUntilMono > 0L)
    }
}
