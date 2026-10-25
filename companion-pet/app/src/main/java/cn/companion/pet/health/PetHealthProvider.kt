package cn.companion.pet.health

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import cn.companion.pet.a11y.PerceptionHub

/**
 * 同桌的健康适配器（WIRE-SPEC-v2 §3.2）。
 *
 * 只有一件事：把哨兵发来的 `challenge` **投递到感知服务的真实执行线程**
 * （AccessibilityService 的主线程 Looper），在那里读事实、取时刻，再带回结果。
 *
 * 纪律（违反即 T02 不过）：
 *  - 不在 Binder 线程上自己构造 ack；不回显 nonce 当“答完了”。
 *  - 执行线程没连上 → 立刻 `ok=false / not connected`。
 *  - 带 `budgetMs` 超时；超时 → `ok=false / dispatcher timeout`，不代答。
 *  - 在途请求有上限，不为不可取消的阻塞调用无限新建线程。
 *  - 只回答运行与执行事实，不参与同桌的认知评估（报告 §3.5）。
 */
class PetHealthProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // 方法分派必须在鉴权之后统一做：先认方法再鉴权，就等于给未授权调用方
        // 留了一条「探测有哪些方法」的通道，而且容易顺手把新方法写成免鉴权。
        val verdict = SentinelTrust.check(context, Binder.getCallingUid(), pinStore())
        if (!verdict.ok) {
            // 鉴权失败只记原因，不改协议形状；不因为“先接通”放宽校验（规范 §3.1）。
            Log.w(TAG, "call($method) rejected: " + verdict.reason)
            return bundleOf("ok" to false, "error" to verdict.reason)
        }
        return when (method) {
            METHOD_CHALLENGE -> challenge(extras)
            METHOD_EMERGENCY -> emergencyStop(extras)
            METHOD_PING -> ping()
            else -> bundleOf("ok" to false, "error" to "unknown method: $method")
        }
    }

    // ---------- challenge（规范 §3.2，T02 的核心） ----------

    private fun challenge(extras: Bundle?): Bundle {
        val v = extras?.getInt(KEY_VERSION) ?: 0
        if (v != PET_PROTOCOL_VERSION) {
            return bundleOf("ok" to false, "error" to "protocol version mismatch: $v")
        }
        val nonce = extras?.getString(KEY_NONCE).orEmpty()
        if (nonce.isBlank()) return bundleOf("ok" to false, "error" to "missing nonce")
        val seq = extras?.getLong(KEY_SEQ) ?: 0L
        val budget = extras?.getLong(KEY_BUDGET_MS) ?: DEFAULT_BUDGET_MS

        val result = dispatcher().handle(nonce, seq, budget)
        val facts = result.facts
        if (!result.ok || facts == null) {
            return bundleOf("ok" to false, "error" to (result.error ?: "challenge failed"))
        }
        return bundleOf(
            "ok" to true,
            "error" to null,
            KEY_VERSION to PET_PROTOCOL_VERSION,
            "pkg" to facts.pkg,
            "instanceId" to facts.instanceId,
            KEY_NONCE to facts.nonce,
            KEY_SEQ to facts.seq,
            "handledAtMono" to facts.handledAtMono,
            "ruleRevision" to facts.ruleRevision,
            "lastReceiptState" to facts.lastReceiptState,
            "lastReceiptAtMono" to facts.lastReceiptAtMono,
            "eventSeq" to facts.eventSeq,
            "eventAtMono" to facts.eventAtMono,
        )
    }

    // ---------- emergencyStop（规范 §6；哨兵 → 客户端） ----------

    /**
     * 哨兵的紧急暂停传播到同桌。
     *
     * 语义与一手 `HealthProvider.emergencyStop` 对齐：
     *  - 立即把本地规则置 STOPPED + 写**独立暂停标记**，都原子落盘；
     *  - **写完读回核对**才回 `ok=true`；写盘失败回 `ok=false` + note，
     *    让哨兵显示「暂停未确认」，绝不谎称已停；
     *  - **幂等**：已经是停的直接回 ok=true（`alreadyStopped=true`）；
     *  - 不联网、不请求模型、不等冷静期、不碰无障碍线程（避免把事件通道拖住）。
     *
     * 「本地按钮」与「哨兵下发」走的是**同一个** [PetGuardRuntime.stopLocal]，
     * 所以两条路径不可能出现语义漂移。
     */
    private fun emergencyStop(extras: Bundle?): Bundle {
        val reason = extras?.getString(KEY_REASON).orEmpty().trim().take(200)
        val app = context?.applicationContext as? cn.companion.pet.PetApp
        if (app == null) return bundleOf("ok" to false, "error" to "no application context")
        val outcome = app.guardRuntime.stopLocal(
            reason.ifBlank { "哨兵下发的紧急暂停" },
        )
        if (!outcome.ok) {
            return bundleOf("ok" to false, "error" to outcome.note.ifBlank { "未能确认停止" }, "stopped" to false)
        }
        return bundleOf(
            "ok" to true,
            "error" to null,
            KEY_VERSION to PET_PROTOCOL_VERSION,
            "pkg" to (context?.packageName ?: ""),
            "stopped" to true,
            "alreadyStopped" to outcome.alreadyStopped,
            "policyIds" to outcome.policyIds.joinToString(","),
            "stoppedCount" to outcome.policyIds.size,
            "revision" to outcome.revision,
            "atWall" to outcome.atWall,
            "atMono" to SystemClock.elapsedRealtime(),
            "note" to outcome.note,
        )
    }

    // ---------- ping（在岗探测，明确不是健康凭据） ----------

    /**
     * 只回答「provider 活着 + 是否处于暂停」。
     *
     * **不返回**屏幕文字、窗口内容、任务/认知数据；也**不是**健康凭据
     * （健康只认 challenge 的 dispatcherAck，规范 §0 铁律 1）。
     */
    private fun ping(): Bundle {
        val app = context?.applicationContext as? cn.companion.pet.PetApp
        val stopped = runCatching { app?.guardRuntime?.isPaused() == true }.getOrDefault(false)
        return bundleOf(
            "ok" to true,
            KEY_VERSION to PET_PROTOCOL_VERSION,
            "pkg" to (context?.packageName ?: ""),
            "instanceId" to (PerceptionHub.instanceId ?: ""),
            "stopped" to stopped,
            "atMono" to SystemClock.elapsedRealtime(),
            // 让哨兵一眼看出：provider 能响应 ≠ 无障碍在真的处理挑战。
            "providerAlive" to true,
        )
    }

    private fun dispatcher(): ChallengeDispatcher {
        val app = context?.applicationContext as? cn.companion.pet.PetApp
        return ChallengeDispatcher(
            clientPkg = context?.packageName ?: "",
            mono = { SystemClock.elapsedRealtime() },
            // 只判断“有没有连上”，不当健康凭据：真正的证据是执行线程回填的时刻。
            executorConnected = { PerceptionHub.isConnected() },
            currentInstanceId = { PerceptionHub.instanceId },
            postToExecutor = { task ->
                // 投递到感知服务的 Handler（Looper.getMainLooper()）。服务没连上时不投。
                PerceptionHub.healthExecutorHandler?.post(task) ?: false
            },
            // 这个 lambda 只在执行线程上跑：读规则版本 / 最近回执 / 事件进度，不读任何屏幕内容。
            readFactsOnExecutor = { app?.guardRuntime?.executorFacts() ?: ExecutorFacts() },
        )
    }

    private fun pinStore(): CallerPinStore? {
        val app = context?.applicationContext as? cn.companion.pet.PetApp ?: return null
        return app.callerPinStore
    }

    private fun bundleOf(vararg pairs: Pair<String, Any?>): Bundle = Bundle().apply {
        for ((k, v) in pairs) {
            when (v) {
                null -> putString(k, null)
                is String -> putString(k, v)
                is Int -> putInt(k, v)
                is Long -> putLong(k, v)
                is Boolean -> putBoolean(k, v)
                else -> putString(k, v.toString())
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "PetHealthProvider"
        const val METHOD_CHALLENGE = "challenge"

        /** 哨兵 → 客户端：紧急暂停传播（`ClientCall.notifyEmergency` 用的就是这个名字）。 */
        const val METHOD_EMERGENCY = "emergencyStop"

        /** 哨兵 → 客户端：在岗探测。只证明 provider 活着，**不是**健康凭据。 */
        const val METHOD_PING = "ping"
        const val KEY_VERSION = "v"
        const val KEY_NONCE = "nonce"
        const val KEY_SEQ = "seq"
        const val KEY_BUDGET_MS = "budgetMs"
        const val KEY_REASON = "reason"
        const val DEFAULT_BUDGET_MS = 5_000L
    }
}

/** 调用方鉴权结论。 */
data class TrustVerdict(val ok: Boolean, val reason: String)

/**
 * 只接受哨兵的调用（规范 §3.2：客户端 provider 只接受哨兵的签名）。
 *
 * 做法：调用方 UID 必须解析到唯一包名，然后对该包的签名做 SHA-256，
 * 与哨兵包名/白名单比对。第一次成功配对后把摘要钉住（TOFU），之后不一致一律拒绝。
 * 这样既不依赖“消息体自报身份”，也不会为了接通就移除校验。
 */
object SentinelTrust {

    /** 哨兵的包名。 */
    const val SENTINEL_PKG = "com.zongkong.sentinel"

    /**
     * 允许的哨兵证书 SHA-256（小写十六进制，64 位）。
     *
     * 由队长用 apksigner 实测（总控 / 一手 / 哨兵共用的那把 debug.keystore）：
     *   com.zongkong.sentinel = b742dcd196af01fa96428498f5297d3165f66ab8c8ff6b85dd09185dbc318ac9
     * 写死后就不再依赖 TOFU；换成正式签名时必须同步换这个常量。
     */
    val TRUSTED_SENTINEL_CERTS: Set<String> = setOf(
        "b742dcd196af01fa96428498f5297d3165f66ab8c8ff6b85dd09185dbc318ac9",
    )

    /**
     * 鉴权：不用消息体自报的 pkg，只用 Binder 的真实 UID 反查包名（规范 §3.1 的顺序）。
     * 1) UID → getPackagesForUid，必须含哨兵包名；
     * 2) 该包的签名 SHA-256 必须在可信清单里（或首次配对钉住）；
     * 3) 不信任 extras 里的任何身份字段。
     */
    fun check(context: Context?, callerUid: Int, pin: CallerPinStore?): TrustVerdict {
        if (context == null) return TrustVerdict(false, "no context")
        val pm = context.packageManager
        val pkgs = runCatching { pm.getPackagesForUid(callerUid)?.toList().orEmpty() }.getOrDefault(emptyList())
        if (pkgs.isEmpty()) return TrustVerdict(false, "cannot resolve caller packages for uid $callerUid")
        if (SENTINEL_PKG !in pkgs) return TrustVerdict(false, "untrusted caller: " + pkgs.joinToString(","))
        val digest = runCatching { signatureDigest(pm, SENTINEL_PKG) }.getOrNull()
            ?: return TrustVerdict(false, "cannot read caller signature")
        if (TRUSTED_SENTINEL_CERTS.isNotEmpty()) {
            return if (digest in TRUSTED_SENTINEL_CERTS) TrustVerdict(true, "trusted cert")
            else TrustVerdict(false, "certificate not in trusted list")
        }
        val pinned = pin?.pinned()
        if (pinned == null) {
            val stored = pin?.pin(digest) == true
            return if (stored) TrustVerdict(true, "paired on first contact")
            else TrustVerdict(false, "cannot persist pairing")
        }
        return if (pinned == digest) TrustVerdict(true, "pinned cert matches")
        else TrustVerdict(false, "pinned cert changed")
    }

    private fun signatureDigest(pm: PackageManager, pkg: String): String {
        val info = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }
        val certs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return certs.joinToString("|") { sig ->
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
