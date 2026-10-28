package com.yishou.app.healthwire

import com.yishou.app.health.LockArbiter
import com.yishou.app.health.Policy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 跨应用契约：哨兵 `emergencyStop` 传播到一手（规范 §6 / 报告 §8）。
 *
 * 这里钉住三件最要紧的事：
 *  1. **停了就真不能再自动锁屏**（`LocalGuard` 的硬前置条件）——「暂停了但兜底还在锁」
 *     比不暂停更糟；
 *  2. **幂等**：重复调用仍 `ok=true`；
 *  3. 暂停不会因为**窗口翻页**（新 policyId）或**进程重启**（内存事实清空）而丢。
 *
 * 用内存假件代替真文件，所以能在 JVM 上直接跑，不依赖 Android。
 */
class EmergencyStopWireTest {

    /** 内存版的落点：模拟 PolicyStore 的四个动作（含独立暂停标记）。 */
    private class FakeSink(
        initial: LocalPolicy.Doc = LocalPolicy.Doc(),
        private var failStop: Boolean = false,
        private var failMark: Boolean = false,
    ) : StopSink {
        var doc: LocalPolicy.Doc = initial
        private var flag: Boolean = false
        var stopCalls = 0
        var markCalls = 0
        var lastMark: EmergencyStop.PauseMark? = null

        fun failNextStop(v: Boolean) { failStop = v }
        fun failNextMark(v: Boolean) { failMark = v }

        override fun load(): LocalPolicy.Doc = doc

        override fun stopAll(nowWall: Long): Boolean {
            stopCalls++
            if (failStop) return false
            doc = LocalPolicy.stopAll(doc)
            return true
        }

        override fun markEmergencyStopped(nowWall: Long, reason: String, bySentinel: Boolean): Boolean {
            markCalls++
            if (failMark) return false
            flag = true
            lastMark = EmergencyStop.PauseMark(nowWall, reason, bySentinel)
            return true
        }

        override fun clearEmergencyStopped(): Boolean {
            flag = false
            return true
        }

        override fun emergencyStopped(): Boolean = flag
    }

    private fun policy(
        id: String = "yishou-window-1000",
        start: Long = 1_000,
        end: Long = 1_000_000,
        enabled: Boolean = true,
        emergency: String = "NONE",
    ) = Policy(
        policyId = id,
        revision = 1,
        issuer = "yishou",
        requiredApps = setOf("com.zongkong.app", "com.yishou.app", "cn.companion.pet"),
        startsAtWall = start,
        endsAtWall = end,
        lockAtWall = null,
        failureAction = "LOCK",
        repairGraceMs = 120_000,
        emergencyState = emergency,
        enabled = enabled,
    )

    private fun facts(
        nowWall: Long = 5_000,
        emergencyStopped: Boolean,
        sentinelLinked: Boolean = false,
        selfHealthy: Boolean = true,
    ) = GuardFacts(
        nowWall = nowWall,
        nowMono = nowWall,
        sentinelLinked = sentinelLinked,
        selfHealthy = selfHealthy,
        emergencyStopped = emergencyStopped,
        adminActive = true,
        secureLockSet = true,
    )

    private var seq = 0
    private val nextId = { "req-${++seq}" }

    @Before
    fun setUp() {
        Facts.reset()
        seq = 0
    }

    @After
    fun tearDown() {
        Facts.reset()
    }

    // ---------- 核心：停了就不能再锁 ----------

    @Test
    fun `默认情形下 LocalGuard 会锁 证明这组前置条件本来是可触发的`() {
        val plan = LocalGuard.plan(listOf(policy()), facts(emergencyStopped = false), LockArbiter(), nextId)
        assertTrue("基线：规则生效 + 哨兵失联 + 自己健康 → 应该允许锁", plan.fire)
    }

    @Test
    fun `emergencyStop 之后 LocalGuard 不再触发自动锁屏`() {
        val sink = FakeSink(LocalPolicy.Doc(policies = listOf(policy())))
        val out = EmergencyStop.stopLocal(sink, 5_000, "哨兵要求紧急暂停")
        assertTrue("停止必须成功", out.ok)
        assertTrue("已停止的 policyId 要能报给哨兵", out.policyIds.contains("yishou-window-1000"))

        // 用**落盘之后**的规则再算一次兜底决策
        val plan = LocalGuard.plan(
            policies = sink.load().policies,
            facts = facts(emergencyStopped = sink.emergencyStopped()),
            arbiter = LockArbiter(),
            newRequestId = nextId,
        )
        assertFalse("暂停之后绝不能再自动锁屏", plan.fire)
        assertTrue(plan.reason.contains("紧急暂停"))
    }

    @Test
    fun `即使规则上的 STOPPED 被新规则顶掉 独立暂停标记仍然拦住锁屏`() {
        val sink = FakeSink(LocalPolicy.Doc(policies = listOf(policy())))
        assertTrue(EmergencyStop.stopLocal(sink, 5_000, "哨兵").ok)

        // 模拟窗口翻页：RuleBuilder 生成了一条**新 policyId、emergencyState=NONE** 的规则。
        val fresh = policy(id = "yishou-window-2000", start = 2_000, end = 2_000_000)
        sink.doc = LocalPolicy.Doc(LocalPolicy.SCHEMA_VERSION, listOf(fresh))

        val guardStopped = LocalPolicy.anyStopped(sink.load())
        assertFalse("新规则本身不是 STOPPED（这就是那个坑）", guardStopped)
        assertTrue("独立暂停标记必须还在", sink.emergencyStopped())

        // 生产里 tick() 用的是「三条来源任一成立」，这里同样按那个口径算。
        val emergency = Facts.paused || guardStopped || sink.emergencyStopped()
        val plan = LocalGuard.plan(
            policies = sink.load().policies,
            facts = facts(nowWall = 5_000, emergencyStopped = emergency),
            arbiter = LockArbiter(),
            newRequestId = nextId,
        )
        assertFalse("窗口翻页后也不能偷偷恢复锁屏", plan.fire)
    }

    @Test
    fun `没有本地规则时也算停住 且写下暂停标记`() {
        val sink = FakeSink(LocalPolicy.Doc())
        val out = EmergencyStop.stopLocal(sink, 5_000, "哨兵")
        assertTrue("没有规则 = 没有可锁项，仍是 ok", out.ok)
        assertTrue(out.policyIds.isEmpty())
        assertTrue("没有规则也必须写暂停标记，否则后来的新规则会立刻开始锁", sink.emergencyStopped())
        assertTrue(out.note.contains("没有本地规则"))
    }

    // ---------- 幂等 ----------

    @Test
    fun `重复调用 emergencyStop 仍然 ok 且不重复写盘`() {
        val sink = FakeSink(LocalPolicy.Doc(policies = listOf(policy())))
        val first = EmergencyStop.stopLocal(sink, 5_000, "哨兵")
        assertTrue(first.ok)
        assertFalse("第一次不该标记 alreadyStopped", first.alreadyStopped)
        val callsAfterFirst = sink.stopCalls

        val second = EmergencyStop.stopLocal(sink, 6_000, "哨兵")
        assertTrue("幂等：重复调用仍 ok=true", second.ok)
        assertTrue("第二次应报告 alreadyStopped", second.alreadyStopped)
        assertEquals("已经是 STOPPED 时不再重复写规则文件", callsAfterFirst, sink.stopCalls)
        assertEquals(first.policyIds, second.policyIds)
        assertEquals("时间戳跟着本次调用走", 6_000L, second.atWall)
    }

    @Test
    fun `连续五次调用都 ok 且规则始终全 STOPPED`() {
        val sink = FakeSink(LocalPolicy.Doc(policies = listOf(policy(id = "a"), policy(id = "b"))))
        repeat(5) { i ->
            val out = EmergencyStop.stopLocal(sink, 1_000L + i, "哨兵")
            assertTrue("第 ${i + 1} 次也要 ok", out.ok)
            assertEquals(2, out.policyIds.size)
        }
        assertTrue(sink.load().policies.all { it.emergencyState == "STOPPED" })
    }

    // ---------- 写盘失败不谎称成功 ----------

    @Test
    fun `规则写盘失败时 ok=false 不让哨兵显示已确认`() {
        val sink = FakeSink(LocalPolicy.Doc(policies = listOf(policy())), failStop = true)
        val out = EmergencyStop.stopLocal(sink, 5_000, "哨兵")
        assertFalse("写不进去就不能回 ok", out.ok)
        assertTrue(out.note.contains("写入失败"))
    }

    @Test
    fun `暂停标记写失败时 ok=false`() {
        val sink = FakeSink(LocalPolicy.Doc(policies = listOf(policy())), failMark = true)
        val out = EmergencyStop.stopLocal(sink, 5_000, "哨兵")
        assertFalse("只有标记落盘才算真的停住", out.ok)
        assertTrue(out.note.contains("暂停标记"))
    }

    // ---------- 与「恢复」的对称性 ----------

    @Test
    fun `恢复之后 LocalGuard 又可以正常判断 证明暂停不是永久锁死`() {
        val sink = FakeSink(LocalPolicy.Doc(policies = listOf(policy())))
        assertTrue(EmergencyStop.stopLocal(sink, 5_000, "哨兵").ok)

        // 用户主动恢复：清 STOPPED + 清标记
        sink.doc = LocalPolicy.resumeAll(sink.load())
        sink.clearEmergencyStopped()

        val emergency = Facts.paused || LocalPolicy.anyStopped(sink.load()) || sink.emergencyStopped()
        assertFalse(emergency)
        val plan = LocalGuard.plan(sink.load().policies, facts(emergencyStopped = false), LockArbiter(), nextId)
        assertTrue("恢复后应该能重新判断（是否锁还是看其他四个条件）", plan.fire)
    }

    @Test
    fun `已经手动标 STOPPED 的规则 stopLocal 不再写盘但依然 ok`() {
        val sink = FakeSink(LocalPolicy.Doc(policies = listOf(policy(emergency = "STOPPED"))))
        val out = EmergencyStop.stopLocal(sink, 5_000, "哨兵")
        assertTrue(out.ok)
        assertTrue(out.alreadyStopped)
        assertEquals("已经全 STOPPED，不必再写规则文件", 0, sink.stopCalls)
        assertTrue("但暂停标记还是要写（它是跨窗口/跨重启的那道保险）", sink.markCalls >= 1)
    }

    // ---------- isPaused 三来源合一（唯一判定入口） ----------

    @Test
    fun `isPaused 三条来源任一成立就算暂停`() {
        val none = LocalPolicy.Doc()
        val stoppedDoc = LocalPolicy.Doc(policies = listOf(policy(emergency = "STOPPED")))
        val liveDoc = LocalPolicy.Doc(policies = listOf(policy()))

        // 三条都假 → 不暂停
        assertFalse(EmergencyStop.isPaused(false, liveDoc, false))
        // ① 内存
        assertTrue("内存事实成立", EmergencyStop.isPaused(true, liveDoc, false))
        // ② 规则 STOPPED
        assertTrue("规则字段成立", EmergencyStop.isPaused(false, stoppedDoc, false))
        // ③ 独立标记
        assertTrue("独立标记成立", EmergencyStop.isPaused(false, liveDoc, true))
        // 空规则文档 + 无标记 = 不暂停（不能因为「没规则」就当成暂停）
        assertFalse(EmergencyStop.isPaused(false, none, false))
    }

    @Test
    fun `重启后内存事实清空 但独立标记仍让 isPaused 为 true`() {
        val sink = FakeSink(LocalPolicy.Doc(policies = listOf(policy())))
        assertTrue(EmergencyStop.stopLocal(sink, 5_000, "哨兵", bySentinel = true).ok)

        // 模拟进程重启：内存里的 Facts.paused 已经没了（这里用 false 表示），
        // 规则也被窗口翻页换成了全新的（非 STOPPED）。
        sink.doc = LocalPolicy.Doc(LocalPolicy.SCHEMA_VERSION, listOf(policy(id = "new", emergency = "NONE")))
        val memoryPaused = false

        assertTrue(
            "重启 + 换窗口之后也必须还算暂停",
            EmergencyStop.isPaused(memoryPaused, sink.load(), sink.emergencyStopped()),
        )
        val plan = LocalGuard.plan(
            sink.load().policies,
            facts(emergencyStopped = EmergencyStop.isPaused(memoryPaused, sink.load(), sink.emergencyStopped())),
            LockArbiter(),
            nextId,
        )
        assertFalse("重启后不能偷偷恢复锁屏", plan.fire)
    }

    @Test
    fun `bySentinel 标记能区分暂停来源 供界面如实说明`() {
        val local = FakeSink(LocalPolicy.Doc(policies = listOf(policy())))
        EmergencyStop.stopLocal(local, 5_000, "我自己按的", bySentinel = false)
        assertEquals(false, local.lastMark?.bySentinel)
        assertEquals("我自己按的", local.lastMark?.reason)
        assertEquals(5_000L, local.lastMark?.atWall)

        val remote = FakeSink(LocalPolicy.Doc(policies = listOf(policy())))
        EmergencyStop.stopLocal(remote, 7_000, "哨兵要求紧急暂停", bySentinel = true)
        assertEquals(true, remote.lastMark?.bySentinel)
        assertEquals("哨兵要求紧急暂停", remote.lastMark?.reason)
        assertEquals(7_000L, remote.lastMark?.atWall)
    }

    @Test
    fun `暂停标记保留时间与理由 重启后界面能说清是哪一次`() {
        val sink = FakeSink(LocalPolicy.Doc(policies = listOf(policy())))
        EmergencyStop.stopLocal(sink, 1_700_000_000_000L, "开会", bySentinel = false)
        val mark = sink.lastMark
        assertNotNull(mark)
        assertEquals(1_700_000_000_000L, mark!!.atWall)
        assertEquals("开会", mark.reason)
        assertFalse(mark.bySentinel)
    }

    @Test
    fun `暂停跨窗口仍然生效 这是明确的设计决定而不是遗留问题`() {
        // 当前窗口的规则被暂停
        val sink = FakeSink(LocalPolicy.Doc(policies = listOf(policy(id = "w1", start = 1_000, end = 10_000))))
        assertTrue(EmergencyStop.stopLocal(sink, 5_000, "哨兵", bySentinel = true).ok)

        // 时间推进到下一个窗口（原窗口早已结束），规则被换成新的 policyId
        sink.doc = LocalPolicy.Doc(
            LocalPolicy.SCHEMA_VERSION,
            listOf(policy(id = "w2", start = 100_000, end = 200_000, emergency = "NONE")),
        )
        val nowWall = 150_000L
        val paused = EmergencyStop.isPaused(false, sink.load(), sink.emergencyStopped())
        assertTrue("新窗口里也仍然算暂停（保守语义）", paused)

        val plan = LocalGuard.plan(
            sink.load().policies,
            facts(nowWall = nowWall, emergencyStopped = paused),
            LockArbiter(),
            nextId,
        )
        assertFalse("新窗口里也不会自动锁屏", plan.fire)
        // 界面据此必须显示「紧急暂停生效中」——这是 captain 裁决要求的界面义务，
        // 由 YishouGuard.snapshot().emergencyStopped 暴露给 WatchHealthScreen 的横幅。
        assertTrue(paused)
    }

    // ---------- 契约常量 ----------

    @Test
    fun `method 名与哨兵 ClientCall 一致`() {
        assertEquals("emergencyStop", HealthProvider.METHOD_EMERGENCY)
        assertEquals("ping", HealthProvider.METHOD_PING)
        assertEquals("challenge", HealthProvider.METHOD_CHALLENGE)
        assertEquals("com.yishou.app.health", HealthProvider.AUTHORITY)
        assertEquals(2, HealthProvider.PROTOCOL_VERSION)
    }

    @Test
    fun `emergencyStop 与 ping 的回包键覆盖哨兵需要的字段`() {
        // 哨兵 ClientCall.notifyEmergency 只读 ok；这里保证还给了 policyIds/atWall 便于显示「已确认停止」
        assertNotNull(HealthProvider.K_OK)
        assertNotNull(HealthProvider.K_STOPPED)
        assertNotNull(HealthProvider.K_POLICY_IDS)
        assertNotNull(HealthProvider.K_STOPPED_COUNT)
        assertNotNull(HealthProvider.K_AT_WALL)
        assertNotNull(HealthProvider.K_AT_MONO)
        // ping 需要的
        assertNotNull(HealthProvider.K_PKG)
        assertNotNull(HealthProvider.K_INSTANCE)
    }
}
