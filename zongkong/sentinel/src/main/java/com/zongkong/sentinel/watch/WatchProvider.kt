package com.zongkong.sentinel.watch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.zongkong.sentinel.health.LockState
import com.zongkong.sentinel.health.Mono
import com.zongkong.sentinel.health.Wall
import java.security.MessageDigest

/**
 * 客户端 → 哨兵 的统一入口（规范 §3.1）。exported=true，**跑在 `:watch` 进程**。
 *
 * method：`register` / `syncPolicy` / `reportReceipt` / `ping`
 *
 * 鉴权顺序（报告 §9，一步都不能省）：
 *  1. `Binder.getCallingUid()` → `getPackagesForUid` 必须包含自报的 `pkg`；
 *  2. 调用方实际证书的 SHA-256 必须在**可信清单**里，且与 `pkg` 对应的期望摘要一致；
 *  3. UID 必须已经在 [HealthStore] 里注册过（除 `register`/`ping` 本身）。
 *
 * 不信任消息体自报的身份。签名不一致时**保留鉴权**，宁可接不通也不移除校验。
 * 注意（规范 §9）：调试签名不是抗伪造方案，这里只是「同一个应用边界」的一致性检查。
 */
class WatchProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // provider 跑在 :watch：先把同一份实例建好，再起前台服务，保证调度与通知就位。
        context?.let {
            runCatching { WatchBus.ensure(it) }
            runCatching { WatchService.start(it) }
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return err("没有上下文")
        val e = extras ?: Bundle()
        val callingUid = Binder.getCallingUid()
        return try {
            when (method) {
                M_REGISTER -> register(c, e, callingUid)
                M_SYNC -> syncPolicy(c, e, callingUid)
                M_RECEIPT -> reportReceipt(c, e, callingUid)
                M_PING -> ping(c, e, callingUid)
                else -> err("不认识的请求：$method")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$method 出错", t)
            err(t.message ?: t.javaClass.simpleName)
        }
    }

    // ---------- method 实现 ----------

    private fun register(c: Context, e: Bundle, uid: Int): Bundle {
        val pkg = e.getString(K_PKG).orEmpty()
        val v = e.getInt(K_V, 0)
        val instanceId = e.getString(K_INSTANCE).orEmpty()
        val auth = Auth.check(c, uid, pkg, e.getString(K_CERT).orEmpty())
        if (!auth.ok) return err(auth.error.orEmpty())
        if (v != HealthStore.PROTOCOL_VERSION) {
            return err("协议版本不匹配：客户端 v$v，哨兵 v${HealthStore.PROTOCOL_VERSION}")
        }
        val r = health(c).register(
            pkg = pkg,
            versionName = e.getString(K_VERSION_NAME).orEmpty(),
            versionCode = e.getLong(K_VERSION_CODE, 0),
            instanceId = instanceId,
            certSha256 = auth.actualSha256,
            ruleRevision = e.getInt(K_RULE_REVISION, 0),
            nowWall = clock(c).now(),
            nowMono = mono(c).now(),
        )
        // 新实例：ack 校验基线必须换新，否则旧实例/旧序号会被误判或误接受（T14）。
        if (r.ok) WatchBus.scheduler?.onNewInstance(pkg, instanceId)
        return if (r.ok) {
            ok().apply {
                putInt(K_SERVER_VERSION, r.serverVersion)
            }
        } else {
            err(r.error.orEmpty())
        }
    }

    private fun syncPolicy(c: Context, e: Bundle, uid: Int): Bundle {
        val pkg = e.getString(K_PKG).orEmpty()
        val auth = Auth.check(c, uid, pkg, e.getString(K_CERT).orEmpty())
        if (!auth.ok) return err(auth.error.orEmpty())
        val issuer = PolicyStore.issuerForPackage(pkg) ?: return err("未知客户端：$pkg")
        val body = e.getString(K_POLICY_JSON).orEmpty()
        if (body.isBlank()) return err("policyJson 不能为空")
        val now = clock(c).now()
        val merge = store(c).applySyncPolicy(now, issuer, body)
        return ok().apply {
            putBoolean("accepted", merge.ok)
            putInt(K_ACCEPTED_REVISION, merge.acceptedRevision)
            merge.error?.let { putString(K_ERROR, it) }
        }
    }

    private fun reportReceipt(c: Context, e: Bundle, uid: Int): Bundle {
        val pkg = e.getString(K_PKG).orEmpty()
        val auth = Auth.check(c, uid, pkg, e.getString(K_CERT).orEmpty())
        if (!auth.ok) return err(auth.error.orEmpty())
        val state = e.getString(K_STATE).orEmpty()
        val known = LockState.values().any { it.name == state }
        if (!known) return err("未知的 LockState：$state")
        val incidentId = e.getString(K_INCIDENT).orEmpty()
        val deduped = WatchBus.receiptSeen(incidentId, pkg)
        val hs = health(c)
        hs.onReceipt(pkg, state, e.getLong(K_AT_WALL, 0), e.getString(K_NOTE).orEmpty(), clock(c).now())
        // 回执只落账，不据此判断健康（规范 §5.2：调用返回 ≠ 观测成功）。
        return ok().apply { putBoolean("deduped", deduped) }
    }

    private fun ping(c: Context, e: Bundle, uid: Int): Bundle {
        val pkg = e.getString(K_PKG).orEmpty()
        val auth = Auth.check(c, uid, pkg, e.getString(K_CERT).orEmpty())
        if (!auth.ok) return err(auth.error.orEmpty())
        val snapshot = health(c).snapshot(clock(c).now())
        val rec = snapshot.clients[pkg]
        return ok().apply {
            putInt(K_SERVER_VERSION, HealthStore.PROTOCOL_VERSION)
            putBoolean("registered", rec?.registered == true)
            putBoolean("watchRunning", WatchService.running)
            putString("watchSince", rec?.instanceId.orEmpty())
            putString(
                "emergency",
                snapshot.emergency?.let { "STOPPED@${it.atWall}" } ?: PolicyStore.EMERGENCY_NONE,
            )
            putInt("policyRevision", store(c).load().doc.revision)
        }
    }

    // ---------- 调用方鉴权（报告 §9 / 规范 §3.1） ----------

    object Auth {
        data class Result(
            val ok: Boolean,
            val error: String? = null,
            /** 实际读到的调用方证书摘要（不留空：写进记录用于事后核对）。 */
            val actualSha256: String = "",
        )

        fun check(c: Context, uid: Int, declaredPkg: String, declaredSha: String): Result {
            if (declaredPkg.isBlank()) return Result(false, "缺少 pkg")
            if (uid == 0) return Result(false, "不接受 UID 0")
            val pm = c.packageManager

            // 1. UID → packages 必须含自报的包名。
            val packages = try {
                pm.getPackagesForUid(uid)?.toList().orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
            if (declaredPkg !in packages) {
                return Result(false, "调用方 UID $uid 不属于 $declaredPkg（实际：${packages.joinToString()})")
            }

            // 2. 实际证书必须在可信清单里，且与这个包名对得上。
            val actual = certSha256(c, declaredPkg)
            if (actual.isNullOrBlank()) return Result(false, "读不到 $declaredPkg 的签名证书")
            val expected = TRUSTED[declaredPkg]
            if (expected == null) return Result(false, "$declaredPkg 不在可信证书清单里，拒绝接入", actual)
            // 清单本身写错（少一位 hex）会让 equals 永远 false，这里显式报出来，别让它变成「莫名被拒」。
            if (!isWellFormedCertSha256(expected)) {
                Log.e(TAG, "可信清单里 $declaredPkg 的摘要格式不合法（长度 ${expected.length}），请修常量")
                return Result(false, "哨兵的可信证书清单里 $declaredPkg 的摘要格式不合法（长度 ${expected.length}，应为 $CERT_SHA256_HEX_LEN）", actual)
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                return Result(false, "证书摘要不匹配：$declaredPkg 的实际证书不在可信清单里", actual)
            }
            // 自报摘要只作对照：不一致说明客户端贴错了，但**以实际读到的为准**，并明确告知。
            if (declaredSha.isNotBlank() && !declaredSha.equals(actual, ignoreCase = true)) {
                Log.w(TAG, "$declaredPkg 自报证书摘要与实际不一致（以实际为准）")
            }
            return Result(true, null, actual)
        }

        private fun certSha256(c: Context, pkg: String): String? {
            return try {
                val pm = c.packageManager
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val signers = info.signingInfo?.apkContentsSigners
                    ?: info.signingInfo?.signingCertificateHistory
                val sig = signers?.firstOrNull() ?: return null
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
            } catch (_: Throwable) {
                null
            }
        }
    }

    // ---------- Bundle 帮手 ----------

    private fun ok() = Bundle().apply { putBoolean("ok", true) }

    private fun err(msg: String) = Bundle().apply {
        putBoolean("ok", false)
        putString(K_ERROR, msg.ifBlank { "未知错误" })
    }

    private fun health(c: Context) = WatchBus.health ?: HealthStore(c.filesDir)
    private fun store(c: Context) = WatchBus.policies ?: PolicyStore(c.filesDir)
    private fun clock(c: Context) = WatchBus.clock ?: WatchClock()
    private fun mono(c: Context): Mono = Mono { clock(c).monoNow() }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "SentinelWatchProvider"

        const val AUTHORITY = "com.zongkong.sentinel.health"

        const val M_REGISTER = "register"
        const val M_SYNC = "syncPolicy"
        const val M_RECEIPT = "reportReceipt"
        const val M_PING = "ping"

        const val K_V = "v"
        const val K_PKG = "pkg"
        const val K_VERSION_NAME = "versionName"
        const val K_VERSION_CODE = "versionCode"
        const val K_INSTANCE = "instanceId"
        const val K_CERT = "certSha256"
        const val K_POLICY_JSON = "policyJson"
        const val K_ACCEPTED_REVISION = "acceptedRevision"
        const val K_POLICY_ID = "policyId"
        const val K_INCIDENT = "incidentId"
        const val K_REQUEST = "requestId"
        const val K_SOURCE = "source"
        const val K_REASON = "reason"
        const val K_STATE = "state"
        const val K_AT_WALL = "atWall"
        const val K_NOTE = "note"
        const val K_SERVER_VERSION = "serverVersion"
        const val K_ERROR = "error"
        const val K_RULE_REVISION = "ruleRevision"

        /**
         * 可信证书清单：见 [TrustedCerts]（纯 Kotlin、无 Android 依赖，便于 JVM 单测守格式）。
         */
        val TRUSTED: Map<String, String> get() = TrustedCerts.MAP

        /** SHA-256 十六进制摘要的字符数（32 字节）。写错一位就会让整条鉴权永远失败。 */
        const val CERT_SHA256_HEX_LEN = TrustedCerts.HEX_LEN

        /** 校验摘要格式：64 位 hex。不合法直接报错，不静默放过。 */
        fun isWellFormedCertSha256(s: String): Boolean = TrustedCerts.isWellFormed(s)
    }
}

/**
 * `:watch` 进程内的共享入口。
 *
 * 只在**同一进程**内传递服务实例（WatchProvider 与 WatchService 都在 `:watch`），
 * 不用它做跨进程通信——跨进程一律走 ContentProvider 或文件（规范 §2.1）。
 */
object WatchBus {
    @Volatile var scheduler: HealthScheduler? = null
    @Volatile var policies: PolicyStore? = null
    @Volatile var health: HealthStore? = null
    @Volatile var clock: WatchClock? = null

    /**
     * provider 先被调到、服务还没起时也要有同一份实例，不能各自建一份缓存互相看不见。
     * （仍然只在**同一个进程内**共享；跨进程一律走 provider 调用或磁盘文件。）
     */
    @Synchronized
    fun ensure(c: android.content.Context) {
        if (policies == null) policies = PolicyStore(c.filesDir)
        if (health == null) health = HealthStore(c.filesDir)
        if (clock == null) {
            clock = WatchClock().also { it.reanchorFrom(policies!!.load().doc.updatedAtWall) }
        }
    }

    private val seen = LinkedHashSet<String>()

    /** 同一 incidentId + pkg 的重复回执：去重但不丢账（返回是否重复）。 */
    @Synchronized
    fun receiptSeen(incidentId: String, pkg: String): Boolean {
        if (incidentId.isBlank()) return false
        val key = "$pkg|$incidentId"
        val dup = !seen.add(key)
        if (seen.size > 500) {
            val walk = seen.iterator()
            repeat(200) { if (walk.hasNext()) { walk.next(); walk.remove() } }
        }
        return dup
    }

    fun bind(scheduler: HealthScheduler?, policies: PolicyStore?, health: HealthStore?, clock: WatchClock?) {
        this.scheduler = scheduler
        this.policies = policies
        this.health = health
        this.clock = clock
    }
}

