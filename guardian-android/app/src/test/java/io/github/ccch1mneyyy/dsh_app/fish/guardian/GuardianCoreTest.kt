package io.github.ccch1mneyyy.dsh_app.fish.guardian

import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ChallengeAckValidator
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ClampedWallClock
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EmergencyPassQuota
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EmergencyPassState
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EnabledServiceList
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EngineHealthAction
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EngineHealthConfig
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EngineHealthVerdict
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EngineObservation
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EngineState
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.FishHealthEvaluator
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianConstants
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianDecide
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianJson
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.KeyguardFacts
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.LockArbiter
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.LockClassifier
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.LockState
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.MirroredPolicy
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ProbeAck
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ProbeChallenge
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ProbeValidation
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.WindowDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianCoreTest {

    private fun baseHealthyObs(nowMono: Long = 100_000L) = EngineObservation(
        targetPkg = GuardianConstants.CLIENT_FISH_PKG,
        installed = true,
        paired = true,
        permissionA11yEnabled = true,
        a11yConnected = true,
        enginePhase = "running",
        dshServiceRunning = true,
        lastAckAtMono = nowMono - 4_000L,
        lastProbeAtMono = nowMono - 4_000L,
        consecutiveFailures = 0,
    )

    @Test
    fun `evaluator handles starting grace within 45s vs timeout`() {
        val grace = FishHealthEvaluator.evaluate(
            nowMono = 130_000L,
            obs = EngineObservation(
                targetPkg = GuardianConstants.CLIENT_FISH_PKG,
                installed = true,
                paired = true,
                permissionA11yEnabled = true,
                enginePhase = "starting",
                startingSinceMono = 100_000L,
            ),
        )
        assertEquals(EngineState.STARTING_GRACE, grace.state)
        assertEquals(EngineHealthAction.PROBE, grace.action)

        val hung = FishHealthEvaluator.evaluate(
            nowMono = 146_000L,
            obs = EngineObservation(
                targetPkg = GuardianConstants.CLIENT_FISH_PKG,
                installed = true,
                paired = true,
                permissionA11yEnabled = true,
                enginePhase = "starting",
                startingSinceMono = 100_000L,
            ),
        )
        assertEquals(EngineState.UNRESPONSIVE, hung.state)
        assertEquals(EngineHealthAction.SHOW_MODAL_AND_LOCK, hung.action)
    }

    @Test
    fun `evaluator detects engine down and escalates from wake to modal`() {
        val wake = FishHealthEvaluator.evaluate(
            nowMono = 200_000L,
            obs = baseHealthyObs(200_000L).copy(enginePhase = "installed", lastWakeAttemptMono = 0L),
        )
        assertEquals(EngineState.ENGINE_DOWN, wake.state)
        assertEquals(EngineHealthAction.ATTEMPT_ENGINE_WAKE, wake.action)

        val modal = FishHealthEvaluator.evaluate(
            nowMono = 205_000L,
            obs = baseHealthyObs(205_000L).copy(enginePhase = "stopped".let { "error" }, lastWakeAttemptMono = 200_000L),
        )
        assertEquals(EngineState.ENGINE_DOWN, modal.state)
        assertEquals(EngineHealthAction.SHOW_MODAL_AND_LOCK, modal.action)
    }

    @Test
    fun `evaluator distinguishes suspect vs unresponsive and healthy`() {
        val ok = FishHealthEvaluator.evaluate(100_000L, baseHealthyObs(100_000L))
        assertEquals(EngineState.HEALTHY, ok.state)
        assertEquals(EngineHealthAction.NONE, ok.action)

        val suspect = FishHealthEvaluator.evaluate(
            nowMono = 100_000L,
            obs = baseHealthyObs(100_000L).copy(
                lastAckAtMono = 90_000L,
                consecutiveFailures = 2,
                lastFailureReason = "timeout",
            ),
        )
        assertEquals(EngineState.SUSPECT, suspect.state)

        val dead = FishHealthEvaluator.evaluate(
            nowMono = 140_000L,
            obs = baseHealthyObs(140_000L).copy(
                lastAckAtMono = 90_000L,
                consecutiveFailures = 3,
                lastFailureReason = "timeout",
            ),
        )
        assertEquals(EngineState.UNRESPONSIVE, dead.state)
        assertTrue(dead.isCritical)
    }

    @Test
    fun `challenge validator accepts fresh nonce+seq and blocks replays or timeouts`() {
        val validator = ChallengeAckValidator(maxRoundTripMs = 5_000L)
        val c1 = ProbeChallenge(nonce = "n-1", seq = 1L, issuedAtMono = 1_000L)
        val a1 = ProbeAck(1, GuardianConstants.CLIENT_FISH_PKG, "n-1", 1L, true, 1_100L, "id-1", "running", true, true, "{}")

        assertTrue(validator.validate(c1, a1, 1_200L) is ProbeValidation.Accepted)
        assertTrue(validator.validate(c1, a1, 1_300L) is ProbeValidation.Rejected)

        val c2 = ProbeChallenge(nonce = "n-2", seq = 2L, issuedAtMono = 2_000L)
        val wrongNonce = a1.copy(seq = 2L, nonce = "n-1")
        assertTrue(validator.validate(c2, wrongNonce, 2_200L) is ProbeValidation.Rejected)

        val c3 = ProbeChallenge(nonce = "n-3", seq = 3L, issuedAtMono = 3_000L)
        val late = a1.copy(seq = 3L, nonce = "n-3")
        assertTrue(validator.validate(c3, late, 9_500L) is ProbeValidation.Rejected)
    }

    @Test
    fun `clamped clock and emergency pass resist time rollback`() {
        var wall = 1_760_000_000_000L
        var mono = 10_000L
        val clock = ClampedWallClock(wall = { wall }, mono = { mono })
        val t0 = clock.now()
        assertEquals(wall, t0)

        mono += 60_000L
        wall -= 86_400_000L // user tries setting clock back 24h!
        val t1 = clock.now()
        assertEquals(t0 + 60_000L, t1)

        // Emergency pass enforces >= 2 chars, 2/day limit, and monotonic expiry
        var pass = EmergencyPassState()
        val shortReject = EmergencyPassQuota.grant(pass, " ", t1, mono)
        assertFalse(shortReject.ok)

        val g1 = EmergencyPassQuota.grant(pass, "接外卖", t1, mono)
        assertTrue(g1.ok)
        pass = g1.state
        assertTrue(EmergencyPassQuota.isActive(pass, mono + 60_000L))
        assertFalse(EmergencyPassQuota.isActive(pass, mono + EmergencyPassQuota.PASS_DURATION_MS + 1L))
        assertEquals(1, EmergencyPassQuota.remainingCount(pass, t1))

        val g2 = EmergencyPassQuota.grant(pass, "驿站取件", t1, mono + 10_000L)
        assertTrue(g2.ok)
        val g3 = EmergencyPassQuota.grant(g2.state, "第三次尝试", t1, mono + 20_000L)
        assertFalse(g3.ok)

        // Next Asia/Shanghai day resets count
        val nextDay = t1 + 26 * 3_600_000L
        assertEquals(2, EmergencyPassQuota.remainingCount(g2.state, nextDay))
    }

    @Test
    fun `window decider enforces tamper, engine down, mirror rules and safe packages`() {
        val mirror = MirroredPolicy(
            updatedAtWall = 10_000L,
            enginePhase = "installed",
            planActive = true,
            primaryWhy = "晚间学习计划中",
            sessionUntil = 50_000L,
            blocked = listOf("com.ss.android.ugc.aweme"),
            blockWhys = mapOf("com.ss.android.ugc.aweme" to "刷视频锁定至 22:00"),
            allow = listOf("com.example.reader"),
        )
        val healthy = EngineHealthVerdict(EngineState.HEALTHY, EngineHealthAction.NONE, "ok")
        val down = EngineHealthVerdict(EngineState.ENGINE_DOWN, EngineHealthAction.SHOW_MODAL_AND_LOCK, "引擎被关闭")

        // Tier-0 safe app always allowed even when engine down
        val dialer = GuardianDecide.evaluateWindow(
            "com.android.dialer",
            emptyList(),
            setOf("com.launcher"),
            emptySet(),
            mirror,
            down,
            passActive = false,
            modalOnEngineDown = true,
            nowWall = 20_000L,
        )
        assertEquals(WindowDecision.Allow, dialer)

        // Settings tamper on protected name
        val tamper = GuardianDecide.evaluateWindow(
            "com.android.settings",
            listOf("应用信息", "DSH Go 大肥鱼", "强行停止"),
            emptySet(),
            emptySet(),
            mirror,
            healthy,
            passActive = false,
            modalOnEngineDown = true,
            nowWall = 20_000L,
        )
        assertTrue(tamper is WindowDecision.BlockTamper)

        // Engine down modal on ordinary app
        val engineModal = GuardianDecide.evaluateWindow(
            "com.example.game",
            emptyList(),
            emptySet(),
            emptySet(),
            mirror,
            down,
            passActive = false,
            modalOnEngineDown = true,
            nowWall = 20_000L,
        )
        assertTrue(engineModal is WindowDecision.BlockEngineDown)

        // Mirrored app block while engine healthy/recovering
        val blockShortVideo = GuardianDecide.evaluateWindow(
            "com.ss.android.ugc.aweme",
            emptyList(),
            emptySet(),
            emptySet(),
            mirror,
            healthy,
            passActive = false,
            modalOnEngineDown = true,
            nowWall = 20_000L,
        )
        assertTrue(blockShortVideo is WindowDecision.BlockApp)
        assertEquals("刷视频锁定至 22:00", (blockShortVideo as WindowDecision.BlockApp).reason)
    }

    @Test
    fun `lock arbiter, classifier, enabled service list and json helpers work accurately`() {
        val arbiter = LockArbiter(maxAttemptsPerIncident = 2, retryCooldownMs = 60_000L, minGapMs = 10_000L)
        assertTrue(arbiter.decide("inc-1", nowMono = 20_000L, repairGraceUntilMono = 25_000L).fire.not())
        assertTrue(arbiter.decide("inc-1", nowMono = 30_000L).fire)
        arbiter.record("inc-1", LockState.OBSERVED_LOCKED, 30_000L)
        assertFalse(arbiter.decide("inc-1", nowMono = 200_000L).fire)

        val (st, _) = LockClassifier.classify(true, null, KeyguardFacts(true, true, true, true))
        assertEquals(LockState.OBSERVED_LOCKED, st)

        val appended = EnabledServiceList.append("pkg.a/Svc", "pkg.b/Guardian")
        assertEquals("pkg.a/Svc:pkg.b/Guardian", appended)
        assertEquals("pkg.a/Svc", EnabledServiceList.remove(appended, "pkg.b/Guardian"))

        val parsed = MirroredPolicy.fromJson(
            GuardianJson.encode(
                linkedMapOf(
                    "updatedAtWall" to 12345L,
                    "enginePhase" to "running",
                    "planActive" to true,
                    "blocked" to listOf("a.b.c"),
                    "allow" to listOf("x.y.z"),
                    "blockWhys" to mapOf("a.b.c" to "专注\n锁定"),
                ),
            ),
        )
        assertNotNull(parsed)
        assertEquals(listOf("a.b.c"), parsed?.blocked)
        assertEquals("专注\n锁定", parsed?.blockWhys?.get("a.b.c"))
    }
}
