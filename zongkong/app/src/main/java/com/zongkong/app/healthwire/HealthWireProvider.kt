package com.zongkong.app.healthwire

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.zongkong.app.health.HealthConfig
import java.security.MessageDigest

/**
 * 总控开给哨兵的健康口子（规范 §3.2，authority `com.zongkong.app.health`）。
 *
 * method：
 *  - `challenge`：哨兵发挑战 → **投到无障碍执行线程** → 处理完带回；超时 ok=false。
 *  - `emergencyStop`：哨兵要求本客户端停掉自己的自动锁屏（必须立刻生效，不等网络/模型）。
 *  - `resumeEmergency`：哨兵取消暂停 → 必须传到客户端（不然本地兜底一直停着）。
 *  - `ping`：只回答「这个 provider 在不在」，**绝不**当作健康凭据。
 *
 * 只接受哨兵的签名（按调用方 UID 反查包名 + 证书摘要比对）。
 * 不信任消息体自报身份；签名不一致一律拒绝（宁可接不通，也不移除校验）。
 */
class HealthWireProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { runCatching { GuardHealth.init(it) } }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return err("没有上下文")
        val e = extras ?: Bundle()
        return try {
            if (!Auth.callerIsSentinel(c)) return err("只接受哨兵（com.zongkong.sentinel）的签名连接")
            when (method) {
                M_CHALLENGE -> challenge(c, e)
                M_EMERGENCY -> emergencyStop(c, e)
                M_RESUME -> resumeEmergency(c, e)
                M_PING -> ping(c, e)
                M_STATUS -> status(c, e)
                else -> err("不认识的请求：$method")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$method 出错", t)
            err(t.message ?: t.javaClass.simpleName)
        }
    }

    // ---------- challenge（T02 核心） ----------

    private fun challenge(c: Context, e: Bundle): Bundle {
        val v = e.getInt(K_V, 0)
        val nonce = e.getString(K_NONCE).orEmpty()
        val seq = e.getLong(K_SEQ, 0L)
        val budget = e.getLong(K_BUDGET, HealthConfig().challengeBudgetMs)
        val out = GuardHealth.challenge(v = v, nonce = nonce, seq = seq, budgetMs = budget)
        return when (out) {
            is ChallengeOutcome.Ack -> ok().apply {
                putInt(K_V, out.protocolVersion)
                putString(K_PKG, out.pkg)
                putString(K_INSTANCE, out.instanceId)
                putString(K_NONCE, out.nonce)
                putLong(K_SEQ, out.seq)
                putLong(K_HANDLED_AT, out.handledAtMono)
                putInt(K_RULE_REV, out.facts.ruleRevision)
                putString(K_RECEIPT_STATE, out.facts.lastReceiptState)
                putLong(K_RECEIPT_AT, out.facts.lastReceiptAtMono)
                putLong(K_EVENT_SEQ, out.facts.eventSeq)
                putLong(K_EVENT_AT, out.facts.eventAtMono)
            }
            is ChallengeOutcome.Timeout -> err("dispatcher timeout").apply {
                // 明确标出这是「执行线程没回话」，不是「客户端拒绝」。
                putString("timeoutKind", "dispatcher")
                putLong("budgetMs", out.budgetMs)
            }
            is ChallengeOutcome.Fail -> err(out.error)
        }
    }

    // ---------- emergencyStop（规范 §6） ----------

    private fun emergencyStop(c: Context, e: Bundle): Bundle {
        val reason = e.getString(K_REASON).orEmpty().ifBlank { "哨兵要求紧急暂停" }
        val store = GuardHealth.policies()
        // 先让内存立刻生效（不依赖磁盘、不依赖网络）；再落盘。
        GuardHealth.setPaused(true)
        val out = store.markEmergencyStopped(reason)
        if (out.failed) {
            // P0-6b：落盘失败**不能**回 ok=true。以前这里照样回 ok，哨兵就把它记成
            // 「客户端已确认停止」，而磁盘上一个字都没写，重启即失守。
            // 现在明确回 ok=false：内存里已生效，但不可持久化 —— 让哨兵去记「未确认」。
            GuardHealth.init(c)
            runCatching { com.zongkong.app.guard.Diag.log(c, "emergency_stop_unpersisted", "落盘失败：${out.error}") }
            return err("紧急暂停没能写入磁盘（内存里已生效，重启会丢）：${out.error}").apply {
                putBoolean("stoppedInMemory", true)
                putBoolean("persisted", false)
            }
        }
        // 本地立即生效：之后的自动锁屏一律被 LocalFallback 拦住（不看网络、不看模型）。
        GuardHealth.init(c)
        runCatching { com.zongkong.app.guard.Diag.log(c, "emergency_stop", "收到哨兵的紧急暂停：$reason") }
        return ok().apply {
            putBoolean("stopped", true)
            putBoolean("persisted", true)
            putInt(K_RULE_REV, out.revision)
            putString(K_REASON, reason)
            putLong(K_UNTIL, store.emergency?.untilWall ?: 0L)
        }
    }

    // ---------- resumeEmergency（P0-6c：取消暂停必须能传到客户端） ----------

    /**
     * 哨兵取消紧急暂停时调用。
     *
     * 以前 `WatchService.cancelEmergency` 只改自己的策略文件，**不通知客户端**
     * （`SENTINEL-V2-NOTES.md:348` 自认），客户端那条 STOPPED 就永远留在那里 ——
     * 用户以为「恢复监管了」，实际本地兜底一直是停的。
     */
    private fun resumeEmergency(c: Context, e: Bundle): Bundle {
        val why = e.getString(K_REASON).orEmpty().ifBlank { "哨兵取消紧急暂停" }
        val store = GuardHealth.policies()
        val out = store.clearEmergency(why = why)
        if (out.failed) {
            return err("恢复监管没能写入磁盘（内存里已恢复）：${out.error}")
        }
        GuardHealth.setPaused(false)
        runCatching { com.zongkong.app.guard.Diag.log(c, "emergency_resume", why) }
        return ok().apply {
            putBoolean("stopped", false)
            putInt(K_RULE_REV, out.revision)
        }
    }

    // ---------- ping / status ----------

    private fun ping(c: Context, e: Bundle): Bundle = ok().apply {
        // 只证明 provider 活着。**不是**健康凭据（规范 §0.1）。
        putInt(K_V, PROTOCOL_VERSION)
        putString(K_PKG, c.packageName)
        putBoolean("providerAlive", true)
    }

    private fun status(c: Context, e: Bundle): Bundle {
        val store = GuardHealth.policies()
        val v = GuardHealth.verdict(GuardHealth.permissionEnabled(), HealthConfig())
        // P0-6a：报告的是「到点后还算不算暂停」，不是「磁盘上有没有 STOPPED 字样」。
        val emergency = store.emergencyStatus()
        return ok().apply {
            putInt(K_V, PROTOCOL_VERSION)
            putString(K_PKG, c.packageName)
            putString("healthStatus", v.status.name)
            putString("healthReason", v.reason)
            putString("healthAction", v.action.name)
            putString(K_INSTANCE, GuardHealth.instanceId.orEmpty())
            putInt(K_RULE_REV, store.revision)
            putString(K_RECEIPT_STATE, GuardHealth.ledger.lastReceiptState)
            putLong(K_EVENT_SEQ, GuardHealth.ledger.lastEventSeq)
            putBoolean("emergencyStopped", emergency.active)
            putLong(K_UNTIL, emergency.untilWall)
            putString("emergencyWhy", emergency.why)
        }
    }

    private fun ok() = Bundle().apply { putBoolean("ok", true) }

    private fun err(msg: String) = Bundle().apply {
        putBoolean("ok", false)
        putString("error", msg.ifBlank { "未知错误" })
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /** 调用方鉴权：只认哨兵的包名 + 签名。 */
    object Auth {
        const val SENTINEL = "com.zongkong.sentinel"

        /** 哨兵 debug.keystore 的证书 SHA-256（实测 apksigner：与总控同一把）。 */
        private const val SENTINEL_CERT = "B742DCD196AF01FA96428498F5297D3165F66AB8C8FF6B85DD09185DBC318AC9"

        fun callerIsSentinel(c: Context): Boolean = try {
            val pm = c.packageManager
            val uid = Binder.getCallingUid()
            if (uid == 0) {
                false
            } else {
                val pkgs = pm.getPackagesForUid(uid)?.toList().orEmpty()
                val okPkg = pkgs.any { it == SENTINEL }
                val okSig = pkgs.any {
                    it == SENTINEL && (SENTINEL_CERT.isBlank() || certMatches(pm, it, SENTINEL_CERT))
                }
                okPkg && okSig
            }
        } catch (t: Throwable) {
            Log.w(TAG, "鉴权失败", t)
            false
        }

        fun sha256(pm: PackageManager, pkg: String): String? = try {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val sig = info.signingInfo?.apkContentsSigners?.firstOrNull()
                ?: info.signingInfo?.signingCertificateHistory?.firstOrNull()
            sig?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02X".format(b) } }
        } catch (t: Throwable) {
            null
        }

        private fun certMatches(pm: PackageManager, pkg: String, expected: String): Boolean =
            sha256(pm, pkg)?.equals(expected, ignoreCase = true) == true
    }

    companion object {
        private const val TAG = "ZkHealthWire"

        const val AUTHORITY = "com.zongkong.app.health"
        const val PROTOCOL_VERSION = 2

        const val M_CHALLENGE = "challenge"
        const val M_EMERGENCY = "emergencyStop"
        const val M_RESUME = "resumeEmergency"
        const val M_PING = "ping"
        const val M_STATUS = "status"

        const val K_V = "v"
        const val K_PKG = "pkg"
        const val K_NONCE = "nonce"
        const val K_SEQ = "seq"
        const val K_BUDGET = "budgetMs"
        const val K_INSTANCE = "instanceId"
        const val K_HANDLED_AT = "handledAtMono"
        const val K_RULE_REV = "ruleRevision"
        const val K_RECEIPT_STATE = "lastReceiptState"
        const val K_RECEIPT_AT = "lastReceiptAtMono"
        const val K_EVENT_SEQ = "eventSeq"
        const val K_EVENT_AT = "eventAtMono"
        const val K_REASON = "reason"
        /** 紧急暂停的到期墙钟（P0-6a）；0 = 没有暂停。 */
        const val K_UNTIL = "untilWall"
    }
}
