package io.github.ccch1mneyyy.dsh_app.loop.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GuardianProtocolTest {
    private val certA = "A1B2C3D4".repeat(8)
    private val certB = "0F1E2D3C".repeat(8)
    private val certBad = "SHORT"

    @Test
    fun authRejectsInvalidUidAndUnexpectedPackages() {
        assertEquals(
            "invalid_uid",
            GuardianAuthCore.verifyPingUid(0, listOf(GuardianProtocol.GUARDIAN_PKG), GuardianProtocol.GUARDIAN_PKG),
        )
        assertEquals(
            "unexpected_package:com.evil.app",
            GuardianAuthCore.verifyPingUid(10100, listOf("com.evil.app"), "com.evil.app"),
        )
        assertEquals(
            "uid_package_mismatch",
            GuardianAuthCore.verifyPingUid(10100, listOf("com.other.app"), GuardianProtocol.GUARDIAN_PKG),
        )
        assertNull(
            GuardianAuthCore.verifyPingUid(10100, listOf(GuardianProtocol.GUARDIAN_PKG), GuardianProtocol.GUARDIAN_PKG),
        )
    }

    @Test
    fun authAcceptsSameCertWithoutPairToken() {
        val out = GuardianAuthCore.verifyCaller(
            callingUid = 10200,
            uidPackages = listOf(GuardianProtocol.GUARDIAN_PKG),
            declaredPkg = GuardianProtocol.GUARDIAN_PKG,
            actualCertSha256 = certA.lowercase(),
            ownCertSha256 = certA,
            pinnedCertSha256 = null,
            suppliedPairToken = null,
            storedPairToken = null,
        )
        assertTrue(out.ok)
        assertEquals("same_cert", out.trustedBy)
        assertEquals(certA, out.actualCertSha256)
    }

    @Test
    fun authRequiresValidPinnedCertAndPairTokenWhenCertsDiffer() {
        val unpaired = GuardianAuthCore.verifyCaller(
            callingUid = 10201,
            uidPackages = listOf(GuardianProtocol.GUARDIAN_PKG),
            declaredPkg = GuardianProtocol.GUARDIAN_PKG,
            actualCertSha256 = certB,
            ownCertSha256 = certA,
            pinnedCertSha256 = "",
            suppliedPairToken = "",
            storedPairToken = "",
        )
        assertFalse(unpaired.ok)
        assertEquals("not_paired", unpaired.error)

        val wrongToken = GuardianAuthCore.verifyCaller(
            callingUid = 10201,
            uidPackages = listOf(GuardianProtocol.GUARDIAN_PKG),
            declaredPkg = GuardianProtocol.GUARDIAN_PKG,
            actualCertSha256 = certB,
            ownCertSha256 = certA,
            pinnedCertSha256 = certB,
            suppliedPairToken = "wrong-token",
            storedPairToken = "secret-token-1234",
        )
        assertFalse(wrongToken.ok)
        assertEquals("cert_or_token_mismatch", wrongToken.error)

        val validPaired = GuardianAuthCore.verifyCaller(
            callingUid = 10201,
            uidPackages = listOf(GuardianProtocol.GUARDIAN_PKG),
            declaredPkg = GuardianProtocol.GUARDIAN_PKG,
            actualCertSha256 = certB,
            ownCertSha256 = certA,
            pinnedCertSha256 = certB,
            suppliedPairToken = "secret-token-1234",
            storedPairToken = "secret-token-1234",
        )
        assertTrue(validPaired.ok)
        assertEquals("paired", validPaired.trustedBy)

        val malformedCert = GuardianAuthCore.verifyCaller(
            callingUid = 10201,
            uidPackages = listOf(GuardianProtocol.GUARDIAN_PKG),
            declaredPkg = GuardianProtocol.GUARDIAN_PKG,
            actualCertSha256 = certBad,
            ownCertSha256 = certA,
            pinnedCertSha256 = certB,
            suppliedPairToken = "secret-token-1234",
            storedPairToken = "secret-token-1234",
        )
        assertFalse(malformedCert.ok)
        assertEquals("invalid_caller_cert", malformedCert.error)
    }

    @Test
    fun policySnapshotRoundTripsJsonCleanly() {
        val snap = GuardianPolicySnapshot(
            updatedAtWall = 1_760_000_000_000L,
            enginePhase = "running",
            dshServiceRunning = true,
            proactiveTransport = true,
            a11yConnected = true,
            planActive = true,
            primaryKind = "refuse",
            primaryWhy = "拒绝模式：先做主线\"任务\"\n别刷视频",
            lockUntil = 1_760_000_900_000L,
            refuseUntil = 1_760_000_900_000L,
            breakUntil = 0L,
            emergencyUntil = 1_760_000_180_000L,
            sessionUntil = 1_760_000_600_000L,
            sessionGraceMs = 45_000L,
            blocked = listOf("tv.danmaku.bili", "com.zhihu.android"),
            blockWhys = mapOf("tv.danmaku.bili" to "计划未验收"),
            allow = listOf("com.android.dialer", "com.tencent.mm"),
            todayMinutes = mapOf("tv.danmaku.bili" to 12),
        )
        val encoded = snap.toJson()
        val decoded = GuardianPolicySnapshot.fromJson(encoded)
        assertNotNull(decoded)
        assertEquals(snap, decoded)
    }

    @Test
    fun mirrorStoreDeduplicatesCapsAndAcksOfflineIncidents() {
        val dir = Files.createTempDirectory("guardian-mirror-test").toFile()
        try {
            val store = GuardianMirrorStore(dir)
            val snap = GuardianPolicySnapshot(
                updatedAtWall = 5000L,
                enginePhase = "running",
                planActive = true,
                blocked = listOf("com.example.game"),
            )
            store.saveSnapshot(snap)
            assertEquals(snap, store.loadSnapshot())

            val batch1 = listOf(
                GuardianOfflineIncident(id = "inc-1", type = "engine_down", atWall = 1000L, detail = "killed"),
                GuardianOfflineIncident(id = "inc-2", type = "guardian_emergency_pass", atWall = 2000L, reason = "接电话"),
            )
            assertEquals(2, store.enqueueOfflineIncidents(batch1))
            // Re-submitting duplicate inc-1 only adds inc-3.
            val batch2 = listOf(
                GuardianOfflineIncident(id = "inc-1", type = "engine_down", atWall = 1000L),
                GuardianOfflineIncident(id = "inc-3", type = "recovery_requested", atWall = 3000L),
            )
            assertEquals(1, store.enqueueOfflineIncidents(batch2))
            assertEquals(listOf("inc-1", "inc-2", "inc-3"), store.peekOfflineIncidents().map { it.id })

            store.ackOfflineIncidents(listOf("inc-1", "inc-3"))
            assertEquals(listOf("inc-2"), store.peekOfflineIncidents().map { it.id })

            // Queue cap test (> MAX_QUEUE).
            val huge = (1..250).map { idx ->
                GuardianOfflineIncident(id = "bulk-$idx", type = "engine_down", atWall = idx.toLong())
            }
            assertEquals(250, store.enqueueOfflineIncidents(huge))
            assertEquals(GuardianMirrorStore.MAX_QUEUE, store.peekOfflineIncidents().size)
            assertEquals("bulk-250", store.peekOfflineIncidents().last().id)
        } finally {
            dir.deleteRecursively()
        }
    }
}
