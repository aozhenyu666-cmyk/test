package cn.companion.pet.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 健康适配器 / 本地规则 / 锁屏出口的 JVM 测试。
 *
 * 对应本轮必须守住的几条：
 *  - T02：挑战必须在执行线程上处理；执行线程冻结 → 超时报错，不代答。
 *  - T04：在途请求有上限，不无限新建线程。
 *  - T07：同一事件去重、冷却、次数上限。
 *  - T09/T10：权限/观测分开记，没观测到就 UNVERIFIED，不报安全锁定。
 *  - T16：紧急暂停本地可达，不依赖网络。
 *  - T19：结束计时不等于任务通过（这里用“运行器不碰目标库”来证明边界）。
 *  - §4：改时间/重启不延长监管。
 */
class PetHealthWireTest {

    // ---------- 挑战派发（T02 的核心） ----------

    /** 用一个真的另一条线程当“执行线程”，模拟 Handler.post。 */
    private class FakeExecutor {
        val thread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "fake-a11y-main").apply { isDaemon = true }
        }
        fun post(task: Runnable): Boolean {
            thread.execute(task)
            return true
        }
        fun shutdown() = thread.shutdownNow()
    }

    private fun dispatcher(
        ex: FakeExecutor?,
        instance: String? = "inst-1",
        mono: Mono = Mono { 1_000L },
        facts: () -> ExecutorFacts = { ExecutorFacts(ruleRevision = 3) },
        maxInFlight: Int = ChallengeDispatcher.MAX_IN_FLIGHT,
    ) = ChallengeDispatcher(
        clientPkg = "cn.companion.pet",
        mono = mono,
        executorConnected = { instance != null && ex != null },
        currentInstanceId = { instance },
        postToExecutor = { task -> ex?.post(task) ?: false },
        readFactsOnExecutor = facts,
        maxInFlight = maxInFlight,
    )

    @Test
    fun `challenge is handled on the executor thread, not on the caller thread`() {
        val ex = FakeExecutor()
        try {
            val onExecutor = java.util.concurrent.atomic.AtomicReference<String>()
            val d = dispatcher(ex, facts = {
                onExecutor.set(Thread.currentThread().name)
                ExecutorFacts(ruleRevision = 7, eventSeq = 42)
            })
            val r = d.handle("nonce-a", 1, 2_000)
            assertTrue("应该成功", r.ok)
            val f = r.facts!!
            assertEquals("nonce 必须原样带回", "nonce-a", f.nonce)
            assertEquals(1L, f.seq)
            assertEquals(7, f.ruleRevision)
            assertEquals(42L, f.eventSeq)
            assertEquals("事实只能在执行线程上读", "fake-a11y-main", onExecutor.get())
            assertEquals("处理时刻由执行线程填", 1_000L, f.handledAtMono)
        } finally {
            ex.shutdown()
        }
    }

    /** T02：执行线程冻结（任务永远跑不完）时，必须超时报错，绝不自己构造 ack。 */
    @Test
    fun `frozen executor times out instead of answering on binder thread`() {
        val frozen = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "frozen-a11y").apply { isDaemon = true }
        }
        val blocker = CountDownLatch(1)
        val onExecutor = AtomicInteger(0)
        try {
            // 先占住那条线程，模拟卡死的无障碍主线程。
            frozen.execute {
                runCatching { blocker.await(10, TimeUnit.SECONDS) }
            }
            Thread.sleep(50)
            val d = dispatcher(
                FakeExecutor().also { /* 不用它的线程，单独用 frozen */ },
                facts = { onExecutor.incrementAndGet(); ExecutorFacts() },
            )
            // 换成冻结的线程池
            val frozenDispatcher = ChallengeDispatcher(
                clientPkg = "cn.companion.pet",
                mono = { 5_000L },
                executorConnected = { true },
                currentInstanceId = { "inst-frozen" },
                postToExecutor = { task ->
                    frozen.execute(task)
                    true
                },
                readFactsOnExecutor = { onExecutor.incrementAndGet(); ExecutorFacts() },
            )
            val startedAt = System.nanoTime()
            val r = frozenDispatcher.handle("nonce-frozen", 9, 300)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertFalse("执行线程冻结时不能报成功", r.ok)
            assertEquals("dispatcher timeout", r.error)
            assertNull("超时时不能带任何事实", r.facts)
            assertTrue("必须真的等到超时（>=250ms），实际 ${elapsedMs}ms", elapsedMs >= 250)
            assertTrue("预算内不该等到 5s 上限", elapsedMs < 5_000)
            assertEquals("执行线程冻结，facts 一次都不该被读到", 0, onExecutor.get())
            // 用完即弃的 dispatcher 只是证明同一套逻辑；这里显式忽略
            assertNotNull(d)
        } finally {
            blocker.countDown()
            frozen.shutdownNow()
        }
    }

    @Test
    fun `challenge fails fast when accessibility service is not connected`() {
        val d = dispatcher(null, instance = null)
        val startedAt = System.nanoTime()
        val r = d.handle("n", 1, 5_000)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertFalse(r.ok)
        assertEquals("not connected", r.error)
        assertTrue("没连上必须立刻返回，不能等预算", elapsedMs < 1_000)
    }

    @Test
    fun `challenge rejects bad budget`() {
        val ex = FakeExecutor()
        try {
            val r = dispatcher(ex).handle("n", 1, 0)
            assertFalse(r.ok)
            assertEquals("bad budget", r.error)
        } finally {
            ex.shutdown()
        }
    }

    /** C-2：预算必须有上限，否则哨兵能给一个把执行线程拖住很久的值。 */
    @Test
    fun `challenge clamps an absurd budget instead of honouring it`() {
        val frozen = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "clamp-a11y").apply { isDaemon = true }
        }
        val blocker = CountDownLatch(1)
        try {
            // 执行线程被长久占住（比测试用的上限更久），模拟卡死。
            frozen.execute { runCatching { blocker.await(30, TimeUnit.SECONDS) } }
            Thread.sleep(50)
            val d = ChallengeDispatcher(
                clientPkg = "cn.companion.pet",
                mono = { 1L },
                executorConnected = { true },
                currentInstanceId = { "inst" },
                postToExecutor = { task -> frozen.execute(task); true },
                readFactsOnExecutor = { ExecutorFacts() },
                // 把这个 dispatcher 的上限调成 800ms，测试不必真的等生产值的 20s。
                maxBudgetMs = 800L,
            )
            // 哨兵要 10 分钟预算 —— 必须被压到 800ms 上限，而不是真的等 10 分钟。
            val startedAt = System.nanoTime()
            val r = d.handle("n", 1, 600_000)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertFalse("执行线程冻结时仍然不能报成功", r.ok)
            assertEquals("dispatcher timeout", r.error)
            assertTrue(
                "必须被压到上限内返回（约 800ms），实际 ${elapsedMs}ms",
                elapsedMs < 5_000,
            )
            assertTrue("而且不能瞬间返回，说明真的等了这个预算：${elapsedMs}ms", elapsedMs >= 700)
        } finally {
            blocker.countDown()
            frozen.shutdownNow()
        }
    }

    /** C-2：生产常量就是 20s 上限 / 500ms 下限，与总控、一手对齐。 */
    @Test
    fun `budget bounds match the other two clients`() {
        assertEquals(20_000L, ChallengeDispatcher.MAX_BUDGET_MS)
        assertEquals(500L, ChallengeDispatcher.MIN_BUDGET_MS)
    }

    /** T04：在途请求有上限，避免为不可取消的阻塞调用无限新建线程。 */
    @Test
    fun `in-flight challenges are bounded`() {
        val gate = CountDownLatch(1)
        val slow = java.util.concurrent.Executors.newFixedThreadPool(1) { r ->
            Thread(r, "slow-a11y").apply { isDaemon = true }
        }
        try {
            val d = ChallengeDispatcher(
                clientPkg = "cn.companion.pet",
                mono = { 1L },
                executorConnected = { true },
                currentInstanceId = { "inst" },
                postToExecutor = { task -> slow.execute { gate.await(5, TimeUnit.SECONDS); task.run() }; true },
                readFactsOnExecutor = { ExecutorFacts() },
                maxInFlight = 1,
            )
            val results = mutableListOf<ChallengeResult>()
            // 三个线程同一时刻起跑，确保它们真的并发进入 handle（否则测不到在途上限）。
            val startGate = CountDownLatch(1)
            val workers = (1..3).map {
                Thread {
                    startGate.await(3, TimeUnit.SECONDS)
                    val r = d.handle("n$it", it.toLong(), 200)
                    synchronized(results) { results += r }
                }.apply { isDaemon = true; start() }
            }
            startGate.countDown()
            workers.forEach { it.join(3_000) }
            gate.countDown()
            assertTrue("至少有一个成功把请求投进去了", results.isNotEmpty())
            assertTrue(
                "必须有请求被在途上限挡掉：" + results.map { it.error },
                results.any { it.error == "too many in-flight challenges" },
            )
        } finally {
            gate.countDown()
            slow.shutdownNow()
        }
    }

    // ---------- 本地规则（§4） ----------

    private fun tmpDir(): File = Files.createTempDirectory("pet-health").toFile()

    private class FakeTime(var wall: Long = 1_000_000L, var mono: Long = 50_000L) {
        val wallFn: Wall = Wall { wall }
        val monoFn: Mono = Mono { mono }
        fun advance(ms: Long) { wall += ms; mono += ms }
    }

    @Test
    fun `policy round-trips and json matches wire spec field names`() {
        val dir = tmpDir()
        val t = FakeTime()
        val store = PetPolicyStore(dir, t.wallFn, t.monoFn)
        val r = store.upsert(
            PolicyDto(
                policyId = "pet-window", revision = 0, issuer = "user",
                requiredApps = listOf("cn.companion.pet"),
                startsAtWall = t.wall, endsAtWall = t.wall + 3_600_000,
                lockAtWall = t.wall + 1_800_000, enabled = true,
            ),
        )
        assertTrue(r.isSuccess)
        assertEquals(1, r.getOrNull())
        val raw = File(dir, "policy/policies.json").readText()
        // 规范 §4 的字段名逐字对齐，四边要能互读。
        listOf(
            "schemaVersion", "policies", "policyId", "revision", "issuer", "requiredApps",
            "startsAtWall", "endsAtWall", "lockAtWall", "failureAction", "repairGraceMs",
            "emergencyState", "enabled",
        ).forEach { assertTrue("缺少字段 $it", raw.contains("\"$it\"")) }

        val reloaded = PetPolicyStore(dir, t.wallFn, t.monoFn)
        assertEquals(1, reloaded.policies().size)
        assertEquals(1, reloaded.revision())
        assertTrue(reloaded.effective().isNotEmpty())
    }

    /** 报告 §7：规则过期必须停止执行。 */
    @Test
    fun `expired window stops being effective`() {
        val dir = tmpDir()
        val t = FakeTime()
        val store = PetPolicyStore(dir, t.wallFn, t.monoFn)
        store.upsert(
            PolicyDto("w", 0, "user", listOf("cn.companion.pet"),
                startsAtWall = t.wall, endsAtWall = t.wall + 60_000, enabled = true),
        )
        assertTrue(store.effective().isNotEmpty())
        t.advance(61_000)
        assertTrue("窗口过期必须停止执行", store.effective().isEmpty())
    }

    /** 规范 §5.3：一个 APP 只能改自己的规则。 */
    @Test
    fun `cannot modify another issuer policy`() {
        val dir = tmpDir()
        val t = FakeTime()
        val store = PetPolicyStore(dir, t.wallFn, t.monoFn)
        val r = store.upsert(PolicyDto("x", 0, "zongkong", listOf("com.zongkong.app"),
            startsAtWall = t.wall, endsAtWall = t.wall + 1_000, enabled = true))
        assertTrue("不能改别人的规则", r.isFailure)
    }

    /** T14：改系统时间往回拨不能延长监管。 */
    @Test
    fun `wall clock rollback does not extend the window`() {
        val dir = tmpDir()
        val t = FakeTime()
        val store = PetPolicyStore(dir, t.wallFn, t.monoFn)
        store.upsert(
            PolicyDto("w", 0, "user", listOf("cn.companion.pet"),
                startsAtWall = t.wall, endsAtWall = t.wall + 600_000, enabled = true),
        )
        val before = store.nowWall()
        // 用户把系统时间往回调 1 小时，单调时钟不变。
        t.wall -= 3_600_000
        val after = store.nowWall()
        assertTrue("墙钟被压紧，不该跟着往回跳：before=$before after=$after", after >= before)
    }

    @Test
    fun `corrupt policy file is quarantined and yields no policy`() {
        val dir = tmpDir()
        File(dir, "policy").mkdirs()
        File(dir, "policy/policies.json").writeText("{ not json")
        val store = PetPolicyStore(dir, { 1L }, { 1L })
        assertTrue(store.policies().isEmpty())
        assertTrue("损坏文件必须另存备份", File(dir, "policy").listFiles().orEmpty().any { it.name.startsWith("policies.corrupt-") })
        assertNotNull(store.lastError)
    }

    // ---------- 锁屏执行与回执（T09/T10） ----------

    private class FakeGateway(
        var admin: Boolean = true,
        var lockReturns: Boolean = true,
        var facts: KeyguardFacts? = null,
        var globalLock: Boolean = false,
    ) : LockGateway {
        var lockCalls = 0
        override fun isAdminActive() = admin
        override fun lockNow(): Boolean { lockCalls++; return lockReturns }
        override fun globalLockAction(): Boolean { lockCalls++; return globalLock }
        override fun keyguardFacts(): KeyguardFacts? = facts
    }

    @Test
    fun `observed lock is reported as locked`() {
        val gw = FakeGateway(facts = KeyguardFacts(true, true, true, false))
        val a = LocalLockExecutor(gw).execute(LockRequest("p", "i", "r", "pet", "到期"), 100L, 200L)
        assertEquals(LockState.OBSERVED_LOCKED, a.state)
        assertEquals(1, gw.lockCalls)
    }

    /** T09：没设置安全锁屏时，lockNow 可能只是休眠，不能报成安全锁定。 */
    @Test
    fun `no secure lock set is unverified, never locked`() {
        val gw = FakeGateway(facts = KeyguardFacts(keyguardShowing = true, deviceLocked = true, secureLockSet = false, interactive = false))
        val a = LocalLockExecutor(gw).execute(LockRequest("p", "i", "r", "pet", "到期"), 1L, 1L)
        assertEquals(LockState.UNVERIFIED, a.state)
        assertTrue("必须说明只是休眠", a.note.contains("休眠"))
    }

    /** T10：调用没抛异常、但读不到系统状态 → UNVERIFIED，不记成功。 */
    @Test
    fun `cannot observe keyguard is unverified`() {
        val a = LocalLockExecutor(FakeGateway(facts = null)).execute(LockRequest("p", "i", "r", "pet", "x"), 1L, 1L)
        assertEquals(LockState.UNVERIFIED, a.state)
    }

    @Test
    fun `api call failure is failed not unverified`() {
        val a = LocalLockExecutor(FakeGateway(lockReturns = false)).execute(LockRequest("p", "i", "r", "pet", "x"), 1L, 1L)
        assertEquals(LockState.FAILED, a.state)
    }

    /** 没有管理员权限时走无障碍通道；两条都没成功就是 FAILED 并说明权限。 */
    @Test
    fun `without admin uses a11y channel and reports permission clearly`() {
        val gw = FakeGateway(admin = false, globalLock = false)
        val a = LocalLockExecutor(gw).execute(LockRequest("p", "i", "r", "pet", "x"), 1L, 1L)
        assertEquals(LockState.FAILED, a.state)
        assertTrue(a.note.contains("设备管理员"))
    }

    // ---------- 仲裁（T07） ----------

    private fun planter(): LocalLockPlanner = LocalLockPlanner(requester = { "req-1" })

    private fun policy(repairGraceMs: Long = 120_000) = Policy(
        policyId = "pet-window", revision = 1, issuer = "user",
        requiredApps = setOf("cn.companion.pet"),
        startsAtWall = 0, endsAtWall = 1_000_000, lockAtWall = 500_000,
        repairGraceMs = repairGraceMs, enabled = true,
    )

    private fun ctx(
        effective: Boolean = true,
        emergency: Boolean = false,
        grace: Long = 0,
        sentinelDown: Boolean = true,
        selfHealthy: Boolean = true,
    ) = LocalLockContext(effective, emergency, grace, sentinelDown, selfHealthy)

    @Test
    fun `sentinel in post means client does not grab the lock`() {
        val p = planter()
        val d = p.plan(ctx(sentinelDown = false), policy(), "inc-1", 1_000L, "reason")
        assertFalse(d.fire)
        assertTrue(d.reason.contains("哨兵在岗"))
    }

    @Test
    fun `unhealthy self is never allowed to fall back`() {
        val p = planter()
        val d = p.plan(ctx(selfHealthy = false), policy(), "inc-1", 1_000L, "reason")
        assertFalse(d.fire)
        assertTrue(d.reason.contains("自己不健康"))
    }

    @Test
    fun `emergency pause blocks the lock`() {
        val p = planter()
        val d = p.plan(ctx(emergency = true), policy(), "inc-1", 1_000L, "reason")
        assertFalse(d.fire)
        assertTrue(d.reason.contains("紧急暂停"))
    }

    @Test
    fun `expired policy never locks`() {
        val p = planter()
        val d = p.plan(ctx(effective = false), policy(), "inc-1", 1_000L, "reason")
        assertFalse(d.fire)
        assertTrue(d.reason.contains("生效窗口"))
    }

    /** T07：同一事件只锁一次。 */
    @Test
    fun `same incident fires once then is deduped`() {
        val arbiter = LockArbiter()
        val p = LocalLockPlanner(arbiter, requester = { "req" })
        val first = p.plan(ctx(), policy(), "inc-9", 1_000L, "r")
        assertTrue(first.fire)
        p.record(first.request!!, LockState.OBSERVED_LOCKED, 1_000L)
        val second = p.plan(ctx(), policy(), "inc-9", 2_000L, "r")
        assertFalse("同一事件已确认锁屏，不重复执行", second.fire)
        assertTrue(second.reason.contains("不重复"))
    }

    /** 换个 incidentId 但距上次锁屏不足 30s：也不许连着锁（防四个 App 锁屏风暴）。 */
    @Test
    fun `global min gap between different incidents`() {
        val p = LocalLockPlanner(LockArbiter(), requester = { "req" })
        val a = p.plan(ctx(), policy(), "inc-a", 1_000L, "r")
        assertTrue(a.fire)
        p.record(a.request!!, LockState.FAILED, 1_000L)
        val b = p.plan(ctx(), policy(), "inc-b", 5_000L, "r")
        assertFalse("距上次锁屏不足 30s，不许再锁", b.fire)
        assertTrue(b.reason.contains("不足"))
    }

    /** T15：解锁后的修复宽限内不立刻重锁。 */
    @Test
    fun `repair grace prevents immediate relock`() {
        val p = LocalLockPlanner(LockArbiter(), requester = { "req" })
        val d = p.plan(ctx(grace = 200_000L), policy(), "inc-new", 100_000L, "r")
        assertFalse(d.fire)
        assertTrue(d.reason.contains("修复宽限"))
    }

    @Test
    fun `fire carries full traceability ids`() {
        val p = LocalLockPlanner(LockArbiter(), requester = { "req-xyz" })
        val d = p.plan(ctx(), policy(), "inc-trace", 1_000L, "哨兵失联")
        assertTrue(d.fire)
        val r = d.request!!
        assertEquals("pet-window", r.policyId)
        assertEquals("inc-trace", r.incidentId)
        assertEquals("req-xyz", r.requestId)
        assertEquals("pet", r.source)
        assertEquals("哨兵失联", r.reason)
    }

    // ---------- 紧急暂停（T16） ----------

    @Test
    fun `emergency stop is local and blocks locks immediately`() {
        val dir = tmpDir()
        val store = EmergencyPauseStore(dir)
        assertFalse(store.blocks("pet-window"))
        store.stop("误锁了，先停", 1_000L, 2_000L)
        assertTrue(store.blocks("pet-window"))
        assertTrue("空 policyId 表示停全部", store.blocks("any"))
        // 落盘：进程被杀后仍然生效，不需要网络/模型。
        val reloaded = EmergencyPauseStore(dir)
        assertTrue(reloaded.blocks("pet-window"))
        assertEquals("误锁了，先停", reloaded.current().reason)
    }

    @Test
    fun `emergency stop scoped to one policy does not stop others`() {
        val dir = tmpDir()
        val store = EmergencyPauseStore(dir)
        store.stop("只停这一个", 1L, 1L, policyId = "pet-window")
        assertTrue(store.blocks("pet-window"))
        assertFalse(store.blocks("another-window"))
    }

    @Test
    fun `emergency resume clears the stop`() {
        val dir = tmpDir()
        val store = EmergencyPauseStore(dir)
        store.stop("x", 1L, 1L)
        store.resume(2L, 3L)
        assertFalse(store.blocks("pet-window"))
        assertFalse(store.current().stopped)
    }

    @Test
    fun `sentinel ack makes the pause confirmed`() {
        val dir = tmpDir()
        val store = EmergencyPauseStore(dir)
        store.stop("x", 1L, 1L)
        assertEquals(0L, store.current().sentinelAckAtWall)
        store.markSentinelAck(9_000L)
        assertEquals(9_000L, EmergencyPauseStore(dir).current().sentinelAckAtWall)
    }

    // ---------- 回执 ----------

    @Test
    fun `receipt persists five-state`() {
        val dir = tmpDir()
        val store = PetReceiptStore(dir)
        store.save(ReceiptDto("p", "i", "r", "pet", "到期", "OBSERVED_LOCKED", 10L, 20L, "已观察到系统锁屏界面"))
        val reloaded = PetReceiptStore(dir).load()!!
        assertEquals("OBSERVED_LOCKED", reloaded.state)
        assertEquals("pet", reloaded.source)
    }

    // ---------- 能力状态（不许涂绿） ----------

    @Test
    fun `capability tells the truth about missing pieces`() {
        val none = lockCapability(adminActive = false, a11yConnected = false, secureLockSet = null)
        assertFalse(none.usable)
        assertTrue(none.summary.contains("未激活"))
        assertTrue(none.summary.contains("读不到"))

        val noSecure = lockCapability(adminActive = true, a11yConnected = true, secureLockSet = false)
        assertTrue(noSecure.usable)
        assertTrue("没设安全锁屏必须写清楚只是休眠", noSecure.summary.contains("休眠"))
    }

    // ---------- 协议常量 / 鉴权常量 ----------

    @Test
    fun `wire constants match the frozen spec`() {
        assertEquals(2, PET_PROTOCOL_VERSION)
        assertEquals("cn.companion.pet.health", PET_HEALTH_AUTHORITY)
        assertEquals("com.zongkong.sentinel.health", SentinelLink.SENTINEL_AUTHORITY)
        assertEquals("com.zongkong.sentinel", SentinelTrust.SENTINEL_PKG)
    }

    /**
     * 规范 §3.1/§9：证书摘要必须是完整的 64 位小写 hex。
     * 哨兵那边刚出过少一位字符导致鉴权永远失败的 bug，所以这里锁长度。
     */
    @Test
    fun `trusted cert digests are 64 hex chars`() {
        SentinelTrust.TRUSTED_SENTINEL_CERTS.forEach { d ->
            assertEquals("证书摘要长度必须是 64：$d", 64, d.length)
            assertTrue("必须是小写 hex：$d", d.all { c -> c in "0123456789abcdef" })
        }
    }

    /** 本应用自己的证书摘要也必须是 64 位（打 APK 后由 apksigner 实测确认）。 */
    @Test
    fun `own cert digest constant is well formed`() {
        assertEquals(64, PetCert.SELF_SHA256.length)
        assertTrue(PetCert.SELF_SHA256.all { it in "0123456789abcdef" })
    }

    // ---------- 哨兵在岗判定（报告 §7/§8） ----------

    /** 没装哨兵 ≠ 哨兵掉线：不能因为“没人应”就抢着锁屏。 */
    @Test
    fun `never linked sentinel is not treated as unresponsive`() {
        val never = SentinelLink.LinkState(lastOk = false, lastError = "没有回应")
        assertEquals(SentinelLiveness.Verdict.NEVER_LINKED, SentinelLiveness.verdict(never, 100_000L))
        assertFalse(SentinelLiveness.unresponsive(never, 100_000L))
    }

    @Test
    fun `recent success counts as alive`() {
        val alive = SentinelLink.LinkState(lastOk = true, everOk = true, lastAtMono = 10_000L, everOkAtMono = 10_000L)
        assertEquals(SentinelLiveness.Verdict.ALIVE, SentinelLiveness.verdict(alive, 20_000L))
        assertFalse(SentinelLiveness.unresponsive(alive, 20_000L))
    }

    @Test
    fun `silence beyond confirm budget is confirmed unresponsive`() {
        val stale = SentinelLink.LinkState(lastOk = true, everOk = true, lastAtMono = 10_000L, everOkAtMono = 10_000L)
        assertEquals(SentinelLiveness.Verdict.UNRESPONSIVE, SentinelLiveness.verdict(stale, 10_000L + 30_001))
        assertTrue(SentinelLiveness.unresponsive(stale, 10_000L + 30_001))
        // 预算边界内不算失联
        assertFalse(SentinelLiveness.unresponsive(stale, 10_000L + 29_000))
    }

    /** 失败后仍在“确认预算”内：先不动，别一失败就锁。 */
    @Test
    fun `recent failure inside budget is not yet unresponsive`() {
        val failing = SentinelLink.LinkState(lastOk = false, everOk = true, everOkAtMono = 10_000L, lastError = "超时")
        assertFalse(SentinelLiveness.unresponsive(failing, 20_000L))
        assertTrue(SentinelLiveness.unresponsive(failing, 10_000L + 30_001))
    }

    // ---------- 边界纪律（报告 §3.5 / T19） ----------

    /** T19：专注计时结束只结束约束，不能推出思考任务通过。 */
    @Test
    fun `focus expiry never implies cognitive task passed`() {
        assertFalse(BoundaryRules.focusExpiryImpliesTaskPassed())
        assertFalse(BoundaryRules.userReplyResolvesCognitiveTask())
    }

    /** 健康协议的事实清单里不许出现认知/任务字段。 */
    @Test
    fun `health whitelist contains no cognition fields`() {
        assertTrue(
            "健康事实白名单里混进了越界字段：" + (BoundaryRules.HEALTH_FACTS_WHITELIST intersect BoundaryRules.FORBIDDEN_IN_HEALTH),
            (BoundaryRules.HEALTH_FACTS_WHITELIST intersect BoundaryRules.FORBIDDEN_IN_HEALTH).isEmpty(),
        )
        // 屏幕文字、录音也不属于本轮协议（报告 §9）。
        assertFalse(BoundaryRules.HEALTH_FACTS_WHITELIST.any { it == "screenText" || it == "recordings" })
    }

    /** 挑战回应里上报的字段就是规范 §3.2 那一组，多的不许有。 */
    @Test
    fun `challenge facts carry only wire spec fields`() {
        val f = HealthFacts("pkg", "inst", "nonce", 1, 2, 3, "OBSERVED_LOCKED", 4, 5, 6)
        assertEquals("pkg", f.pkg)
        assertEquals("inst", f.instanceId)
        assertEquals("nonce", f.nonce)
        assertEquals(1L, f.seq)
        assertEquals(2L, f.handledAtMono)
        assertEquals(3, f.ruleRevision)
        assertEquals("OBSERVED_LOCKED", f.lastReceiptState)
        assertEquals(4L, f.lastReceiptAtMono)
        assertEquals(5L, f.eventSeq)
        assertEquals(6L, f.eventAtMono)
    }

    // ---------- 规则清单编码 ----------

    /**
     * F-06 回归：发出的报文里 issuer 必须是**哨兵认的值** `"pet"`。
     *
     * 这条替换掉原来的 `encoded policy json is interoperable` —— 那是假绿灯：
     * 它只断言「能被同桌自己的 PolicyDto 读回」，被测方和验证方是同一个实现，
     * 所以 `user`（哨兵拒绝的那个值）也能读回、也能绿，永远发现不了 F-06。
     *
     * 现在的断言依据是**对方的期望值**：哨兵 `watch/PolicyStore.kt:161`
     * `issuerForPackage("cn.companion.pet") -> "pet"`，以及 `:97-98` 的
     * `if (p.issuer != issuer) return Merge(false, "叫 X 的调用方不能写 Y 的规则")`。
     */
    @Test
    fun `wire json carries the issuer the sentinel actually accepts`() {
        // 哨兵 PolicyStore.kt:161 的期望值。硬编码并注明来源：本方没有哨兵的类可引用。
        val sentinelExpectedIssuer = "pet"

        val p = Policy(
            // 本地存的是 user（本机用户设的窗口）。
            policyId = "pet-window", revision = 2, issuer = ISSUER_USER,
            requiredApps = setOf("cn.companion.pet", "com.zongkong.app"),
            startsAtWall = 100, endsAtWall = 200, lockAtWall = 150, enabled = true,
        )
        assertEquals("前提：本地存的确实是 user", "user", p.issuer)

        val json = PetGuardRuntime.encodePolicies(listOf(p))

        // ① 发出去的报文里 issuer 是哨兵认的值，不是本地的 user。
        assertTrue(
            "报文里必须是 \"issuer\":\"pet\"，实际：$json",
            json.contains("\"issuer\":\"$sentinelExpectedIssuer\""),
        )
        assertFalse(
            "报文里绝不能出现哨兵会拒的 \"issuer\":\"user\"：$json",
            json.contains("\"issuer\":\"user\""),
        )

        // ② 结构字段仍然齐全（规范 §4）。
        listOf(
            "schemaVersion", "policyId", "revision", "issuer", "requiredApps",
            "startsAtWall", "endsAtWall", "lockAtWall", "failureAction",
            "repairGraceMs", "emergencyState", "enabled",
        ).forEach { assertTrue("缺少字段 $it：$json", json.contains("\"$it\"")) }
        assertTrue(json.contains("\"policyId\":\"pet-window\""))
        assertTrue(json.contains("\"lockAtWall\":150"))

        // ③ 用哨兵的门（照抄它的判断）验证：issuer 相等才放行。
        val wireIssuerInJson = Regex("\"issuer\":\"([^\"]+)\"").find(json)!!.groupValues[1]
        assertEquals(
            "哨兵 PolicyStore.kt:97 的 if (p.issuer != issuer) 会在这里放行",
            sentinelExpectedIssuer,
            wireIssuerInJson,
        )
        // 反证：如果还发 user，哨兵那条门就会拒。
        assertNotEquals(sentinelExpectedIssuer, p.issuer)
    }

    /**
     * 把哨兵的 merge 门照抄一份，用真实报文跑一遍：
     * 证明「修之前会被拒、修之后能进」。这是跨应用契约测试该有的样子 ——
     * 断言依据来自对方实现，不是自己读回自己。
     */
    @Test
    fun `sentinel merge gate accepts our wire json but would reject the local issuer`() {
        // 哨兵 PolicyStore.kt:161：cn.companion.pet -> "pet"
        val sentinelIssuerForPet = "pet"
        // 哨兵 PolicyStore.kt:97-98：issuer 不等就 Merge(false, ...)
        fun sentinelMergeAccepts(policyIssuer: String, callerIssuer: String) = policyIssuer == callerIssuer

        val p = Policy(
            policyId = "pet-window", revision = 1, issuer = ISSUER_USER,
            requiredApps = setOf("cn.companion.pet"),
            startsAtWall = 0, endsAtWall = 1_000, lockAtWall = 500, enabled = true,
        )
        val wireJson = PetGuardRuntime.encodePolicies(listOf(p))
        val wireIssuer = Regex("\"issuer\":\"([^\"]+)\"").find(wireJson)!!.groupValues[1]

        assertTrue(
            "修好之后：哨兵的门必须放行（wire=$wireIssuer, sentinel=$sentinelIssuerForPet）",
            sentinelMergeAccepts(wireIssuer, sentinelIssuerForPet),
        )
        // 反证 F-06 的存在：直接把本地 issuer 发过去就会被拒。
        assertFalse(
            "如果直接发本地 ${p.issuer}，哨兵会 Merge(false) —— 这正是 F-06",
            sentinelMergeAccepts(p.issuer, sentinelIssuerForPet),
        )
    }

    @Test
    fun `wire issuer normalisation keeps local storage untouched`() {
        // 归一化只作用于报文；本地仍存 user，ownedByPet 语义不变。
        assertEquals("pet", wireIssuer("user"))
        assertEquals("pet", wireIssuer("pet"))
        // 别人的 issuer 不冒名顶替，原样发出去让哨兵照规矩拒。
        assertEquals("zongkong", wireIssuer("zongkong"))
        assertEquals("yishou", wireIssuer("yishou"))
        // 本地判定不受影响
        assertTrue(Policy("w", 1, ISSUER_USER, emptySet(), 0, 1, enabled = true).ownedByPet())
        assertTrue(Policy("w", 1, ISSUER_PET, emptySet(), 0, 1, enabled = true).ownedByPet())
        assertFalse(Policy("w", 1, "zongkong", emptySet(), 0, 1, enabled = true).ownedByPet())
    }

    @Test
    fun `wire json round-trips into the sentinel schema shape`() {
        val p = Policy(
            policyId = "pet-window", revision = 2, issuer = ISSUER_USER,
            requiredApps = setOf("cn.companion.pet"),
            startsAtWall = 100, endsAtWall = 200, lockAtWall = 150, enabled = true,
        )
        val json = PetGuardRuntime.encodePolicies(listOf(p))
        assertTrue(json.contains("\"schemaVersion\":2"))
        assertTrue(json.contains("\"emergencyState\":\"NONE\""))
        assertTrue(json.contains("\"enabled\":true"))
    }
}
