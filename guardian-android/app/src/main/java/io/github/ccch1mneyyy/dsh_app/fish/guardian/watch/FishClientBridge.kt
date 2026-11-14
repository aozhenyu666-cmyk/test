package io.github.ccch1mneyyy.dsh_app.fish.guardian.watch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EnabledServiceList
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianConstants
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianJson
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ProbeAck
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ProbeChallenge
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

/** Ring log written atomically by `:watch` without racing with the UI process. */
object ArcLog {
    private const val TAG = "FishGuardianWatch"
    private const val FILE_NAME = "watch-log.txt"
    private const val MAX_LINES = 100
    private val ZONE = ZoneId.of("Asia/Shanghai")
    private val lock = Object()

    fun append(c: Context, msg: String) = synchronized(lock) {
        runCatching {
            val z = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZONE)
            val stamp = "%02d-%02d %02d:%02d:%02d".format(z.monthValue, z.dayOfMonth, z.hour, z.minute, z.second)
            val f = File(c.filesDir, FILE_NAME)
            val prev = if (f.exists()) f.readLines(Charsets.UTF_8) else emptyList()
            val next = (prev + "$stamp  $msg").takeLast(MAX_LINES)
            val tmp = File(c.filesDir, "$FILE_NAME.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(next.joinToString("\n").toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                f.writeText(next.joinToString("\n"), Charsets.UTF_8)
                tmp.delete()
            }
            Log.i(TAG, msg)
        }
    }

    fun recent(c: Context, limit: Int = 30): List<String> = synchronized(lock) {
        val f = File(c.filesDir, FILE_NAME)
        if (!f.exists()) return emptyList()
        return runCatching { f.readLines(Charsets.UTF_8).takeLast(limit).asReversed() }.getOrDefault(emptyList())
    }
}

/** Cross-process pairing credentials stored in `filesDir/fish_pairing.json`. */
object FishPairingStore {
    private val lock = Object()

    data class Bond(
        val targetPkg: String = "",
        val pinnedCertSha256: String = "",
        val pairToken: String = "",
    )

    private fun file(c: Context) = File(c.filesDir, "fish_pairing.json")

    fun load(c: Context): Bond = synchronized(lock) {
        val f = file(c)
        if (!f.exists()) return Bond()
        return runCatching {
            val m = GuardianJson.map(GuardianJson.parse(f.readText(Charsets.UTF_8)))
            Bond(
                targetPkg = GuardianJson.str(m, "targetPkg"),
                pinnedCertSha256 = GuardianJson.str(m, "pinnedCertSha256"),
                pairToken = GuardianJson.str(m, "pairToken"),
            )
        }.getOrDefault(Bond())
    }

    fun save(c: Context, bond: Bond) = synchronized(lock) {
        val f = file(c)
        val tmp = File(c.filesDir, "fish_pairing.json.tmp")
        val payload = GuardianJson.encode(
            linkedMapOf(
                "targetPkg" to bond.targetPkg,
                "pinnedCertSha256" to bond.pinnedCertSha256,
                "pairToken" to bond.pairToken,
            ),
        )
        runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                f.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { tmp.delete() }
    }
}

/**
 * Safe ContentProvider + WakeActivity bridge between `大肥鱼哨兵` and DSH Go.
 */
object FishClientBridge {
    private const val TAG = "FishClientBridge"
    private val workerLock = Object()
    private val boundedWorkers = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "fish-probe-worker").apply { isDaemon = true }
    }
    private val rebindLock = Object()

    fun resolveInstalledTarget(c: Context): String? =
        GuardianConstants.CANDIDATE_TARGETS.firstOrNull { isInstalled(c, it) }

    fun isInstalled(c: Context, pkg: String): Boolean = runCatching {
        c.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

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

    fun sameCert(c: Context, targetPkg: String): Boolean {
        val mine = certSha256(c, c.packageName) ?: return false
        val peer = certSha256(c, targetPkg) ?: return false
        return mine.equals(peer, ignoreCase = true)
    }

    data class PingResult(
        val reachable: Boolean,
        val targetPkg: String,
        val sameCert: Boolean = false,
        val paired: Boolean = false,
        val enginePhase: String = "unknown",
        val serviceRunning: Boolean = false,
        val a11yConnected: Boolean = false,
        val error: String = "",
    )

    fun ping(c: Context, targetPkg: String): PingResult {
        val args = Bundle().apply { putString("pkg", c.packageName) }
        val b = callWithTimeout(c, targetPkg, "ping", args, 2_000L)
            ?: return PingResult(false, targetPkg, error = "无法连接大肥鱼健康通道（服务未唤醒或超时）")
        if (!b.getBoolean("ok", false)) {
            return PingResult(false, targetPkg, error = b.getString("error") ?: "ping 被拒绝")
        }
        return PingResult(
            reachable = true,
            targetPkg = targetPkg,
            sameCert = b.getBoolean("sameCert", false),
            paired = b.getBoolean("paired", false),
            enginePhase = b.getString("enginePhase") ?: "unknown",
            serviceRunning = b.getBoolean("dshServiceRunning", false),
            a11yConnected = b.getBoolean("a11yConnected", false),
        )
    }

    data class PairResult(val ok: Boolean, val sameCert: Boolean = false, val error: String = "")

    fun pair(c: Context, targetPkg: String, pairCode: String = ""): PairResult {
        val args = Bundle().apply {
            putString("pkg", c.packageName)
            if (pairCode.isNotBlank()) putString("pairCode", pairCode.trim())
        }
        val b = callWithTimeout(c, targetPkg, "pair", args, 3_000L)
            ?: return PairResult(false, error = "连接大肥鱼超时，请确认大肥鱼已安装")
        if (!b.getBoolean("ok", false)) {
            return PairResult(false, error = b.getString("error") ?: "配对失败")
        }
        val token = b.getString("pairToken").orEmpty()
        val peerCert = certSha256(c, targetPkg).orEmpty()
        FishPairingStore.save(c, FishPairingStore.Bond(targetPkg, peerCert, token))
        return PairResult(ok = true, sameCert = b.getBoolean("sameCert", false))
    }

    /** Ensures we hold a fresh pairToken whenever certs match or bond is incomplete. */
    fun ensureAutoPairIfSameCert(c: Context, targetPkg: String): Boolean {
        val bond = FishPairingStore.load(c)
        val actualPeer = certSha256(c, targetPkg).orEmpty()
        val alreadyBound = bond.targetPkg == targetPkg &&
            bond.pairToken.isNotBlank() &&
            bond.pinnedCertSha256.equals(actualPeer, ignoreCase = true)
        if (alreadyBound) return true
        if (sameCert(c, targetPkg)) {
            return pair(c, targetPkg, "").ok
        }
        return false
    }

    sealed interface ChallengeOutcome {
        data class Success(val ack: ProbeAck, val receivedAtMono: Long) : ChallengeOutcome
        data class Failure(val reason: String, val authMissing: Boolean = false) : ChallengeOutcome
    }

    fun challenge(c: Context, targetPkg: String, challenge: ProbeChallenge, budgetMs: Long): ChallengeOutcome {
        val bond = FishPairingStore.load(c)
        val args = Bundle().apply {
            putInt("v", GuardianConstants.PROTOCOL_VERSION)
            putString("pkg", c.packageName)
            putString("pairToken", bond.pairToken)
            putString("nonce", challenge.nonce)
            putLong("seq", challenge.seq)
            putLong("budgetMs", budgetMs)
        }
        val b = callWithTimeout(c, targetPkg, "challenge", args, budgetMs + 500L)
            ?: return ChallengeOutcome.Failure("大肥鱼健康探针无响应（可能被强行停止或卡死）")
        val received = SystemClock.elapsedRealtime()
        if (!b.getBoolean("ok", false)) {
            val err = b.getString("error") ?: "挑战被拒绝"
            val needsPair = err == "not_paired" || err == "cert_or_token_mismatch"
            return ChallengeOutcome.Failure(err, authMissing = needsPair)
        }
        val ack = ProbeAck(
            protocolVersion = b.getInt("v", 0),
            targetPkg = b.getString("targetPkg") ?: targetPkg,
            nonce = b.getString("nonce").orEmpty(),
            seq = b.getLong("seq", 0L),
            a11yConnected = b.getBoolean("a11yConnected", false),
            handledAtClientMono = b.getLong("handledAtMono", 0L),
            a11yInstanceId = b.getString("a11yInstanceId").orEmpty(),
            enginePhase = b.getString("enginePhase") ?: "unknown",
            dshServiceRunning = b.getBoolean("dshServiceRunning", false),
            proactiveTransport = b.getBoolean("proactiveTransport", false),
            policyMirrorJson = b.getString("policyMirrorJson").orEmpty(),
        )
        return ChallengeOutcome.Success(ack, received)
    }

    data class WakeOutcome(val ok: Boolean, val detail: String)

    fun wakeEngine(c: Context, targetPkg: String, allowActivityFallback: Boolean): WakeOutcome {
        val bond = FishPairingStore.load(c)
        val args = Bundle().apply {
            putString("pkg", c.packageName)
            putString("pairToken", bond.pairToken)
        }
        val b = callWithTimeout(c, targetPkg, "ensureEngine", args, 3_000L)
        if (b != null && b.getBoolean("ok", false)) {
            val res = b.getString("wakeResult").orEmpty()
            val needsFg = b.getBoolean("needsForeground", false)
            if (!needsFg) {
                return WakeOutcome(true, "已通知大肥鱼恢复引擎（$res）")
            }
        }
        if (!allowActivityFallback) {
            return WakeOutcome(false, b?.getString("error") ?: "系统限制后台拉起，需从弹窗点击唤醒")
        }
        return runCatching {
            val wake = Intent().apply {
                component = ComponentName(targetPkg, "io.github.ccch1mneyyy.dsh_app.loop.guardian.EngineWakeActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("pkg", c.packageName)
                putExtra("pairToken", bond.pairToken)
            }
            c.startActivity(wake)
            WakeOutcome(true, "已通过前台通道唤醒大肥鱼引擎")
        }.getOrElse { err ->
            // Final fallback: open DSH Go main launcher if EngineWakeActivity couldn't start directly.
            val launch = c.packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launch != null && runCatching { c.startActivity(launch) }.isSuccess) {
                WakeOutcome(true, "已打开大肥鱼主界面以恢复引擎")
            } else {
                WakeOutcome(false, "拉起大肥鱼失败：${err.message ?: err.javaClass.simpleName}")
            }
        }
    }

    fun uploadOfflineIncidents(c: Context, targetPkg: String, stores: GuardianStores): Int {
        val pending = stores.loadIncidents()
        if (pending.isEmpty()) return 0
        val bond = FishPairingStore.load(c)
        val payload = GuardianJson.encode(pending.map { it.toMap() })
        val args = Bundle().apply {
            putString("pkg", c.packageName)
            putString("pairToken", bond.pairToken)
            putString("offlineEventsJson", payload)
        }
        val b = callWithTimeout(c, targetPkg, "reportOfflineEvents", args, 3_000L) ?: return 0
        if (!b.getBoolean("ok", false)) return 0
        stores.ackIncidents(pending.map { it.id }.toSet())
        return pending.size
    }

    fun canWriteSecureSettings(c: Context): Boolean = runCatching {
        c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun rebindTargetA11y(c: Context, targetPkg: String): Boolean = synchronized(rebindLock) {
        if (!canWriteSecureSettings(c)) return@synchronized false
        val comp = ComponentName(targetPkg, "io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService")
        if (runCatching { c.packageManager.getServiceInfo(comp, 0) }.isFailure) return@synchronized false
        runCatching {
            val cr = c.contentResolver
            val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val cur = Settings.Secure.getString(cr, key).orEmpty()
            val flat = comp.flattenToString()
            val stripped = EnabledServiceList.remove(cur, flat)
            Settings.Secure.putString(cr, key, stripped)
            val restored = EnabledServiceList.append(stripped, flat)
            val wrote = Settings.Secure.putString(cr, key, restored)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            wrote
        }.getOrDefault(false)
    }

    private fun callWithTimeout(
        c: Context,
        targetPkg: String,
        method: String,
        args: Bundle,
        timeoutMs: Long,
    ): Bundle? {
        val authority = targetPkg + GuardianConstants.AUTHORITY_SUFFIX
        val done = java.util.concurrent.CompletableFuture<Bundle?>()
        try {
            boundedWorkers.execute {
                val res = try {
                    c.contentResolver.call(Uri.parse("content://$authority"), method, null, args)
                } catch (t: Throwable) {
                    Log.w(TAG, "$targetPkg.$method failed: ${t.message}")
                    null
                }
                done.complete(res)
            }
            return done.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            return null
        }
    }
}
