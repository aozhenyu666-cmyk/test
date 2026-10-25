package cn.companion.pet.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 健康内核的核心行为测试。这些直接对应报告 §11 的验收项：
 * T02（旧 running 不能当健康）、T07（锁屏去重）、T09/T10（锁屏回执）、
 * T11（恢复没重连不能变绿）、T14（改时间/旧实例）。
 */
class HealthCoreTest {

    private val cfg = HealthConfig()

    // ---------- T02：假在线 ----------

    @Test
    fun `never acked is UNKNOWN not HEALTHY`() {
        val v = HealthEvaluator.evaluate(1000, HealthObservation(), cfg)
        assertEquals(HealthStatus.UNKNOWN, v.status)
        assertEquals(HealthAction.PROBE, v.action)
        assertFalse(v.healthy)
    }

    @Test
    fun `fresh ack is HEALTHY`() {
        val obs = HealthObservation(lastAckSeq = 1, lastAckAtMono = 10_000, instanceId = "i1")
        val v = HealthEvaluator.evaluate(15_000, obs, cfg)
        assertEquals(HealthStatus.HEALTHY, v.status)
        assertTrue(v.healthy)
    }

    @Test
    fun `stale ack becomes SUSPECT and asks for a probe`() {
        val obs = HealthObservation(lastAckSeq = 1, lastAckAtMono = 10_000, instanceId = "i1")
        val v = HealthEvaluator.evaluate(10_000 + cfg.staleAfterMs + 1, obs, cfg)
        assertEquals(HealthStatus.SUSPECT, v.status)
        assertEquals(HealthAction.PROBE, v.action)
    }

    @Test
    fun `two consecutive failures suspect then unresponsive after budget`() {
        val base = HealthObservation(
            lastAckSeq = 3,
            lastAckAtMono = 10_000,
            instanceId = "i1",
            consecutiveFailures = 2,
            lastFailureReason = "挑战超时",
        )
        val suspect = HealthEvaluator.evaluate(20_000, base, cfg)
        assertEquals(HealthStatus.SUSPECT, suspect.status)

        // 超过确认预算：收敛到 UNRESPONSIVE 并要求恢复
        val gone = HealthEvaluator.evaluate(10_000 + cfg.confirmUnresponsiveMs + 1, base, cfg)
        assertEquals(HealthStatus.UNRESPONSIVE, gone.status)
        assertEquals(HealthAction.START_RECOVERY, gone.action)
    }

    @Test
    fun `failures with no ack ever escalate immediately`() {
        val obs = HealthObservation(consecutiveFailures = 2, lastFailureReason = "挑战超时")
        val v = HealthEvaluator.evaluate(5_000, obs, cfg)
        assertEquals(HealthStatus.UNRESPONSIVE, v.status)
        assertEquals(HealthAction.START_RECOVERY, v.action)
    }

    // ---------- T01：权限关掉 ----------

    @Test
    fun `permission disabled wins over everything`() {
        val obs = HealthObservation(
            permissionEnabled = false,
            lastAckAtMono = 9_999_999,
            consecutiveFailures = 0,
        )
        val v = HealthEvaluator.evaluate(10_000_000, obs, cfg)
        assertEquals(HealthStatus.DISABLED, v.status)
    }

    @Test
    fun `not installed is UNREGISTERED`() {
        val v = HealthEvaluator.evaluate(0, HealthObservation(installed = false), cfg)
        assertEquals(HealthStatus.UNREGISTERED, v.status)
    }

    @Test
    fun `unregistered is UNREGISTERED not UNKNOWN`() {
        val v = HealthEvaluator.evaluate(0, HealthObservation(registered = false), cfg)
        assertEquals(HealthStatus.UNREGISTERED, v.status)
    }

    // ---------- T11：恢复没重连，不能变绿 ----------

    @Test
    fun `recovering without new ack is not healthy`() {
        val obs = HealthObservation(
            instanceId = "i1",
            lastAckAtMono = 1_000,
            recoveringSinceMono = 5_000,
            recoveriesTried = 1,
        )
        val v = HealthEvaluator.evaluate(10_000, obs, cfg)
        assertEquals(HealthStatus.RECOVERING, v.status)
        assertFalse(v.healthy)
    }

    @Test
    fun `recovering with ack after recovery start becomes HEALTHY`() {
        val obs = HealthObservation(
            instanceId = "i2",
            lastAckAtMono = 8_000,
            recoveringSinceMono = 5_000,
            recoveriesTried = 1,
        )
        val v = HealthEvaluator.evaluate(10_000, obs, cfg)
        assertEquals(HealthStatus.HEALTHY, v.status)
    }

    @Test
    fun `recovering past budget escalates`() {
        val obs = HealthObservation(
            recoveringSinceMono = 1_000,
            recoveriesTried = cfg.maxRecoveries,
        )
        val v = HealthEvaluator.evaluate(1_000 + cfg.recoveryBudgetMs + 1, obs, cfg)
        assertEquals(HealthStatus.UNRESPONSIVE, v.status)
        assertEquals(HealthAction.ESCALATE, v.action)
    }

    // ---------- 回执校验：T14 ----------

    private fun challenge(seq: Long = 1, nonce: String = "n1", inst: String? = "i1") =
        Challenge(nonce = nonce, seq = seq, issuedAtMono = 1_000, expectedInstanceId = inst)

    private fun ack(seq: Long = 1, nonce: String = "n1", inst: String = "i1", version: Int = 2) =
        ChallengeAck(
            protocolVersion = version, clientPkg = "com.x", instanceId = inst,
            nonce = nonce, seq = seq, handledAtClientMono = 1_100,
        )

    @Test
    fun `valid ack accepted`() {
        val v = AckValidator()
        val r = v.validate(challenge(), ack(), receivedAtMono = 1_200)
        assertTrue(r is AckResult.Accepted)
    }

    @Test
    fun `wrong nonce rejected`() {
        val v = AckValidator()
        val r = v.validate(challenge(), ack(nonce = "other"), 1_200)
        assertTrue(r is AckResult.Rejected)
    }

    @Test
    fun `duplicate seq rejected`() {
        val v = AckValidator()
        v.validate(challenge(seq = 1), ack(seq = 1), 1_200)
        val r = v.validate(challenge(seq = 1), ack(seq = 1), 1_300)
        assertTrue(r is AckResult.Rejected)
    }

    @Test
    fun `old instance rejected`() {
        val v = AckValidator()
        v.resetForNewInstance("i2")
        val r = v.validate(challenge(inst = "i2"), ack(inst = "i1"), 1_200)
        assertTrue(r is AckResult.Rejected)
    }

    @Test
    fun `stale roundtrip rejected`() {
        val v = AckValidator(maxRoundTripMs = 5_000)
        val r = v.validate(challenge(), ack(), receivedAtMono = 1_000 + 6_000)
        assertTrue(r is AckResult.Rejected)
    }

    @Test
    fun `old protocol version rejected`() {
        val v = AckValidator()
        val r = v.validate(challenge(), ack(version = 1), 1_200)
        assertTrue(r is AckResult.Rejected)
    }

    // ---------- T09/T10：锁屏回执 ----------

    @Test
    fun `locked with secure lock observed`() {
        val (state, _) = LockClassifier.classify(
            called = true, error = null,
            facts = KeyguardFacts(keyguardShowing = true, deviceLocked = true, secureLockSet = true, interactive = false),
        )
        assertEquals(LockState.OBSERVED_LOCKED, state)
    }

    @Test
    fun `no secure lock is UNVERIFIED not success`() {
        val (state, note) = LockClassifier.classify(
            called = true, error = null,
            facts = KeyguardFacts(keyguardShowing = false, deviceLocked = true, secureLockSet = false, interactive = false),
        )
        assertEquals(LockState.UNVERIFIED, state)
        assertTrue(note.contains("没有设置安全锁屏"))
    }

    @Test
    fun `unobservable is UNVERIFIED`() {
        val (state, _) = LockClassifier.classify(called = true, error = null, facts = null)
        assertEquals(LockState.UNVERIFIED, state)
    }

    @Test
    fun `api failure is FAILED`() {
        val (state, _) = LockClassifier.classify(called = false, error = RuntimeException("no admin"), facts = null)
        assertEquals(LockState.FAILED, state)
    }

    // ---------- T07：锁屏风暴去重 ----------

    @Test
    fun `same incident only fires once confirmed`() {
        val arb = LockArbiter()
        val req = LockRequest("p1", "inc1", "r1", "sentinel", "health loss")
        assertTrue(arb.decide(req, 1_000).fire)
        arb.record(req, LockState.OBSERVED_LOCKED, 1_000)
        assertFalse(arb.decide(req, 2_000).fire)
    }

    @Test
    fun `repeated triggers on same incident are capped`() {
        val arb = LockArbiter(maxAttemptsPerIncident = 2, retryCooldownMs = 1_000, minGapBetweenLocksMs = 0)
        val req = LockRequest("p1", "inc1", "r1", "sentinel", "health loss")
        assertTrue(arb.decide(req, 0).fire)
        arb.record(req, LockState.UNVERIFIED, 0)
        assertFalse(arb.decide(req, 500).fire)          // 冷却中
        assertTrue(arb.decide(req, 2_000).fire)         // 冷却过了，第 2 次
        arb.record(req, LockState.UNVERIFIED, 2_000)
        assertFalse(arb.decide(req, 10_000).fire)       // 上限
    }

    @Test
    fun `repair grace suppresses relock`() {
        val arb = LockArbiter()
        val req = LockRequest("p1", "inc1", "r1", "sentinel", "health loss")
        val d = arb.decide(req, 1_000, repairGraceUntilMono = 60_000)
        assertFalse(d.fire)
    }

    // ---------- T14：改时间不延长规则 ----------

    @Test
    fun `policy expires regardless of heartbeat`() {
        val p = Policy(
            policyId = "p", revision = 1, issuer = "user",
            requiredApps = setOf("com.zongkong.app"),
            startsAtWall = 0, endsAtWall = 10_000, enabled = true,
        )
        assertTrue(p.effective(5_000))
        assertFalse(p.effective(10_000))
        assertEquals(0, PolicyClock.remainingMs(p, 20_000))
    }

    @Test
    fun `forward wall clock jump is clamped`() {
        var wallV = 1_000L
        var monoV = 1_000L
        val clock = ClampedClock({ wallV }, { monoV }, maxJumpMs = 60_000)
        clock.now() // 立锚
        monoV += 1_000
        wallV += 3_600_000 // 用户把时间往前调了一小时
        // 只认单调流逝的 1 秒，不认一小时
        assertEquals(2_000L, clock.now())
    }

    @Test
    fun `backward wall clock jump does not rewind`() {
        var wallV = 100_000L
        var monoV = 100_000L
        val clock = ClampedClock({ wallV }, { monoV }, maxJumpMs = 60_000)
        clock.now()
        monoV += 1_000
        wallV -= 500_000
        assertEquals(101_000L, clock.now())
    }

    @Test
    fun `lock due only within window`() {
        val p = Policy(
            policyId = "p", revision = 1, issuer = "user", requiredApps = emptySet(),
            startsAtWall = 0, endsAtWall = 100_000, lockAtWall = 50_000, enabled = true,
        )
        assertFalse(p.lockDue(49_000))
        assertTrue(p.lockDue(50_000))
        assertFalse(p.lockDue(100_000))
        assertEquals(50_000L, PolicyClock.nextDeadline(p, 10_000))
        assertNull(PolicyClock.nextDeadline(p, 60_000))
    }

    @Test
    fun `disabled policy never effective`() {
        val p = Policy(
            policyId = "p", revision = 1, issuer = "user", requiredApps = emptySet(),
            startsAtWall = 0, endsAtWall = 100_000, enabled = false,
        )
        assertFalse(p.effective(50_000))
        assertEquals(emptySet<String>(), PolicyClock.missingApps(p, emptySet(), 50_000))
    }

    @Test
    fun `missing apps surfaces unimplemented requirement`() {
        val p = Policy(
            policyId = "p", revision = 1, issuer = "user",
            requiredApps = setOf("com.zongkong.app", "com.yishou.app", "cn.companion.pet"),
            startsAtWall = 0, endsAtWall = 100_000, enabled = true,
        )
        val missing = PolicyClock.missingApps(p, setOf("com.zongkong.app"), 50_000)
        assertEquals(setOf("com.yishou.app", "cn.companion.pet"), missing)
    }

    // ---------- §10：就绪状态不说假话 ----------

    @Test
    fun `no active window reported honestly`() {
        val r = Readiness.overall(emptyList(), 1_000, setOf("a"), emptyMap(), sentinelHealthy = true)
        assertEquals(OverallReadiness.NO_ACTIVE_WINDOW, r)
    }

    @Test
    fun `sentinel down is SENTINEL_UNAVAILABLE`() {
        val p = Policy("p", 1, "u", setOf("a"), 0, 10_000, enabled = true)
        val r = Readiness.overall(listOf(p), 5_000, setOf("a"), mapOf("a" to HealthStatus.HEALTHY), false)
        assertEquals(OverallReadiness.SENTINEL_UNAVAILABLE, r)
    }

    @Test
    fun `one unhealthy app is PARTIAL_FAILURE`() {
        val p = Policy("p", 1, "u", setOf("a", "b"), 0, 10_000, enabled = true)
        val r = Readiness.overall(
            listOf(p), 5_000, setOf("a", "b"),
            mapOf("a" to HealthStatus.HEALTHY, "b" to HealthStatus.SUSPECT), true,
        )
        assertEquals(OverallReadiness.PARTIAL_FAILURE, r)
    }

    @Test
    fun `all healthy is ALL_READY`() {
        val p = Policy("p", 1, "u", setOf("a"), 0, 10_000, enabled = true)
        val r = Readiness.overall(listOf(p), 5_000, setOf("a"), mapOf("a" to HealthStatus.HEALTHY), true)
        assertEquals(OverallReadiness.ALL_READY, r)
    }

    // ---------- §10：统计不塞分母 ----------

    @Test
    fun `no observation means null availability`() {
        assertNull(HealthStats().availability)
        assertNull(HealthStats().recoveryRate)
        assertNull(HealthStats().avgDetectLatencyMs)
    }

    @Test
    fun `availability computed when monitored`() {
        val s = HealthStats(monitoredMs = 10_000, outageTotalMs = 2_500)
        assertEquals(0.75, s.availability!!, 1e-9)
    }
}

