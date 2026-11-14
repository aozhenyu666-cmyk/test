package io.github.ccch1mneyyy.dsh_app.loop.guardian

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages mutual trust & cross-APK pairing between DSH Go and `大肥鱼哨兵`.
 *
 * When both APKs are signed with the same certificate (e.g. local debug build or co-signed release),
 * `authenticate` automatically trusts the guardian without requiring manual pairing.
 * When signed with different certificates (e.g. separate CI runs), a short-lived 6-digit pairCode
 * establishes a pinned `(certSha256, pairToken)` bond stored atomically in private app storage.
 */
object GuardianPairing {
    private val rng = SecureRandom()
    private val lock = Object()

    data class State(
        val pinnedCertSha256: String = "",
        val pairToken: String = "",
        val pairCode: String = "",
        val pairCodeExpiresAtWall: Long = 0L,
        val pairedAtWall: Long = 0L,
    )

    private fun storeFile(c: Context): File =
        File(File(File(c.filesDir, "loop"), "guardian"), "pairing.json")

    private fun readState(c: Context): State = synchronized(lock) {
        val f = storeFile(c)
        if (!f.exists()) return State()
        return runCatching {
            val m = GuardianJson.parse(f.readText(Charsets.UTF_8)) as? Map<*, *> ?: return State()
            State(
                pinnedCertSha256 = (m["pinnedCertSha256"] as? String).orEmpty(),
                pairToken = (m["pairToken"] as? String).orEmpty(),
                pairCode = (m["pairCode"] as? String).orEmpty(),
                pairCodeExpiresAtWall = (m["pairCodeExpiresAtWall"] as? Number)?.toLong() ?: 0L,
                pairedAtWall = (m["pairedAtWall"] as? Number)?.toLong() ?: 0L,
            )
        }.getOrDefault(State())
    }

    private fun writeState(c: Context, state: State) = synchronized(lock) {
        val target = storeFile(c)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        val payload = GuardianJson.encode(
            linkedMapOf(
                "pinnedCertSha256" to state.pinnedCertSha256,
                "pairToken" to state.pairToken,
                "pairCode" to state.pairCode,
                "pairCodeExpiresAtWall" to state.pairCodeExpiresAtWall,
                "pairedAtWall" to state.pairedAtWall,
            ),
        )
        runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { tmp.delete() }
    }

    fun certSha256(c: Context, pkg: String): String? = runCatching {
        val pm = c.packageManager
        val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()
                ?: info.signingInfo?.signingCertificateHistory?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }.getOrNull()

    fun uidPackages(c: Context, uid: Int): List<String> =
        runCatching { c.packageManager.getPackagesForUid(uid)?.toList().orEmpty() }.getOrDefault(emptyList())

    fun isGuardianInstalled(c: Context): Boolean =
        runCatching { c.packageManager.getPackageInfo(GuardianProtocol.GUARDIAN_PKG, 0); true }.getOrDefault(false)

    fun sameCertWithGuardian(c: Context): Boolean {
        val own = certSha256(c, c.packageName) ?: return false
        val guardian = certSha256(c, GuardianProtocol.GUARDIAN_PKG) ?: return false
        return GuardianAuthCore.isValidHexSha256(own) && own.equals(guardian, ignoreCase = true)
    }

    /** Generates or refreshes a 6-digit pairing code valid for 10 minutes for cross-cert installation. */
    fun generatePairCode(c: Context, nowWall: Long = System.currentTimeMillis()): Map<String, Any?> = synchronized(lock) {
        val prev = readState(c)
        val code = "%06d".format(rng.nextInt(1_000_000))
        val expiresAt = nowWall + GuardianProtocol.PAIR_CODE_TTL_MS
        val next = prev.copy(pairCode = code, pairCodeExpiresAtWall = expiresAt)
        writeState(c, next)
        statusMap(c, next, nowWall)
    }

    fun statusMap(c: Context, state: State = readState(c), nowWall: Long = System.currentTimeMillis()): Map<String, Any?> {
        val ownCert = certSha256(c, c.packageName).orEmpty()
        val guardianCert = certSha256(c, GuardianProtocol.GUARDIAN_PKG).orEmpty()
        val sameCert = GuardianAuthCore.isValidHexSha256(ownCert) &&
            ownCert.equals(guardianCert, ignoreCase = true)
        val paired = sameCert || (
            GuardianAuthCore.isValidHexSha256(state.pinnedCertSha256) &&
                state.pairToken.isNotBlank() &&
                guardianCert.equals(state.pinnedCertSha256, ignoreCase = true)
            )
        val activeCode = if (nowWall <= state.pairCodeExpiresAtWall) state.pairCode else ""
        return linkedMapOf(
            "installed" to isGuardianInstalled(c),
            "guardianPkg" to GuardianProtocol.GUARDIAN_PKG,
            "sameCert" to sameCert,
            "paired" to paired,
            "ownCertSha256" to ownCert,
            "guardianCertSha256" to guardianCert,
            "pairCode" to activeCode,
            "pairCodeExpiresAtWall" to if (activeCode.isNotEmpty()) state.pairCodeExpiresAtWall else 0L,
            "pairedAtWall" to state.pairedAtWall,
        )
    }

    data class PairResult(
        val ok: Boolean,
        val pairToken: String = "",
        val sameCert: Boolean = false,
        val error: String = "",
    )

    /** Handles `pair` ContentProvider call from Guardian. */
    fun handlePair(
        c: Context,
        callingUid: Int,
        declaredPkg: String,
        suppliedCode: String,
        nowWall: Long = System.currentTimeMillis(),
    ): PairResult = synchronized(lock) {
        val uidErr = GuardianAuthCore.verifyPingUid(callingUid, uidPackages(c, callingUid), declaredPkg)
        if (uidErr != null) return@synchronized PairResult(ok = false, error = uidErr)
        val actual = certSha256(c, declaredPkg)
        if (!GuardianAuthCore.isValidHexSha256(actual)) {
            return@synchronized PairResult(ok = false, error = "invalid_caller_cert")
        }
        val normalized = actual!!.uppercase()
        val own = certSha256(c, c.packageName)
        val sameCert = GuardianAuthCore.isValidHexSha256(own) && normalized.equals(own, ignoreCase = true)
        val state = readState(c)
        val codeValid = suppliedCode.isNotBlank() &&
            nowWall <= state.pairCodeExpiresAtWall &&
            GuardianAuthCore.constantTimeEquals(suppliedCode.trim(), state.pairCode)
        if (!sameCert && !codeValid) {
            return@synchronized PairResult(
                ok = false,
                error = if (state.pairCode.isBlank() || nowWall > state.pairCodeExpiresAtWall) {
                    "pair_code_required"
                } else {
                    "pair_code_mismatch"
                },
            )
        }
        val tokenBytes = ByteArray(24).also { rng.nextBytes(it) }
        val token = tokenBytes.joinToString("") { "%02x".format(it) }
        val next = State(
            pinnedCertSha256 = normalized,
            pairToken = token,
            pairCode = "",
            pairCodeExpiresAtWall = 0L,
            pairedAtWall = nowWall,
        )
        writeState(c, next)
        PairResult(ok = true, pairToken = token, sameCert = sameCert)
    }

    /** Authenticates an incoming Binder `call` from Guardian. */
    fun authenticate(
        c: Context,
        callingUid: Int,
        declaredPkg: String,
        suppliedPairToken: String?,
    ): GuardianAuthOutcome {
        val state = readState(c)
        return GuardianAuthCore.verifyCaller(
            callingUid = callingUid,
            uidPackages = uidPackages(c, callingUid),
            declaredPkg = declaredPkg,
            actualCertSha256 = certSha256(c, declaredPkg),
            ownCertSha256 = certSha256(c, c.packageName),
            pinnedCertSha256 = state.pinnedCertSha256,
            suppliedPairToken = suppliedPairToken,
            storedPairToken = state.pairToken,
        )
    }

    /** Verifies a wake Intent for [EngineWakeActivity]. */
    fun authenticateWakeIntent(c: Context, intent: Intent?): Boolean {
        val declared = intent?.getStringExtra(GuardianProtocol.K_PKG).orEmpty()
        if (declared != GuardianProtocol.GUARDIAN_PKG) return false
        val actual = certSha256(c, declared) ?: return false
        if (!GuardianAuthCore.isValidHexSha256(actual)) return false
        val own = certSha256(c, c.packageName)
        if (GuardianAuthCore.isValidHexSha256(own) && actual.equals(own, ignoreCase = true)) {
            return true
        }
        val state = readState(c)
        val token = intent?.getStringExtra(GuardianProtocol.K_PAIR_TOKEN)
        return actual.equals(state.pinnedCertSha256, ignoreCase = true) &&
            state.pairToken.isNotBlank() &&
            GuardianAuthCore.constantTimeEquals(token, state.pairToken)
    }
}
