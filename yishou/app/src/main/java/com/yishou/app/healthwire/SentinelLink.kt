package com.yishou.app.healthwire

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.yishou.app.health.Policy
import com.yishou.app.link.Partner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一手 → 哨兵 的连接器（规范 §3.1）：`register` / `syncPolicy` / `reportReceipt` / `ping`。
 *
 * 硬性约束：
 *  - **全部在后台线程 + 带预算**执行；无障碍事件线程和主线程永远不在这里等跨进程（T04）。
 *  - 在途请求上限 1：不因为哨兵不响应就堆线程。
 *  - 自报 `certSha256` 只是给哨兵做对照，真正的校验在哨兵那边（规范 §3.1：不信任消息体自报身份）。
 *  - 一次失败**不删规则、不自动放行**（规范 §4）。
 */
object SentinelLink {

    private const val TAG = "SentinelLink"

    const val AUTHORITY = "com.zongkong.sentinel.health"
    const val PROTOCOL_VERSION = 2

    const val M_REGISTER = "register"
    const val M_SYNC = "syncPolicy"
    const val M_RECEIPT = "reportReceipt"
    const val M_PING = "ping"

    private const val CALL_TIMEOUT_MS = 3_000L

    private val uri: Uri = Uri.parse("content://$AUTHORITY")

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yishou-sentinel").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    /** 最近一次 ping/register 的结论（给界面说真话用；不代表「健康」，健康只认挑战 ack）。 */
    data class LinkState(
        val reachable: Boolean = false,
        val registered: Boolean = false,
        val serverVersion: Int = 0,
        val emergency: String = "",
        val policyRevision: Int = 0,
        val atWall: Long = 0,
        val error: String = "",
    ) {
        /** 哨兵失联（一手据此决定要不要做本地兜底）。 */
        val lost: Boolean get() = !reachable || atWall <= 0L
    }

    @Volatile private var state = LinkState()
    fun state(): LinkState = state

    private fun patch(change: (LinkState) -> LinkState) {
        state = change(state).copy(atWall = System.currentTimeMillis())
    }

    /** 后台单发：同一时刻只允许一个在途请求，避免哨兵不响应时堆线程。 */
    private fun submit(tag: String, block: () -> Unit) {
        if (!inFlight.compareAndSet(false, true)) {
            Log.d(TAG, "$tag 已有在途请求，跳过本次")
            return
        }
        try {
            executor.execute {
                try {
                    block()
                } catch (e: Throwable) {
                    Log.w(TAG, "$tag 失败", e)
                    patch { it.copy(reachable = false, error = e.message ?: e.javaClass.simpleName) }
                } finally {
                    inFlight.set(false)
                }
            }
        } catch (e: Throwable) {
            inFlight.set(false)
            patch { it.copy(reachable = false, error = "提交失败：${e.message ?: e.javaClass.simpleName}") }
        }
    }

    /** 注册（规范 §3.1）。instanceId 必须来自真实无障碍实例，重建必须换新。 */
    fun register(context: Context, instanceId: String) {
        val app = context.applicationContext
        if (instanceId.isBlank()) return
        submit(M_REGISTER) {
            val extras = android.os.Bundle().apply {
                putInt("v", PROTOCOL_VERSION)
                putString("pkg", app.packageName)
                putString("versionName", versionName(app))
                putLong("versionCode", versionCode(app))
                putString("instanceId", instanceId)
                putString("certSha256", Partner.YISHOU_CERT)
            }
            val (b, err) = callWithTimeout(app, M_REGISTER, extras)
            if (b == null) {
                patch { it.copy(reachable = false, error = err.orEmpty()) }
                return@submit
            }
            val ok = b.getBoolean("ok", false)
            patch {
                it.copy(
                    reachable = true,
                    registered = ok,
                    serverVersion = b.getInt("serverVersion", 0),
                    error = if (ok) "" else (b.getString("error") ?: err.orEmpty()),
                )
            }
            Facts.onRuleRevision(b.getInt("ruleRevision", Facts.snapshot().ruleRevision))
        }
    }

    /** 上报本地规则修订号（哨兵据此显示「已就绪」）。 */
    fun ping(context: Context) {
        val app = context.applicationContext
        submit(M_PING) {
            val extras = android.os.Bundle().apply {
                putInt("v", PROTOCOL_VERSION)
                putString("pkg", app.packageName)
                putString("certSha256", Partner.YISHOU_CERT)
            }
            val (b, err) = callWithTimeout(app, M_PING, extras)
            if (b == null) {
                patch { it.copy(reachable = false, error = err.orEmpty()) }
                return@submit
            }
            val ok = b.getBoolean("ok", false)
            patch {
                it.copy(
                    reachable = ok,
                    registered = b.getBoolean("registered", false),
                    serverVersion = b.getInt("serverVersion", 0),
                    emergency = b.getString("emergency").orEmpty(),
                    policyRevision = b.getInt("policyRevision", 0),
                    error = if (ok) "" else (b.getString("error") ?: ""),
                )
            }
        }
    }

    /** 把本地规则同步给哨兵（规范 §3.1 syncPolicy）。 */
    fun syncPolicy(context: Context, doc: LocalPolicy.Doc) {
        val app = context.applicationContext
        val body = LocalPolicy.toJson(doc)
        submit(M_SYNC) {
            val extras = android.os.Bundle().apply {
                putInt("v", PROTOCOL_VERSION)
                putString("pkg", app.packageName)
                putString("policyJson", body)
                putString("certSha256", Partner.YISHOU_CERT)
            }
            val (b, err) = callWithTimeout(app, M_SYNC, extras)
            if (b == null) {
                patch { it.copy(reachable = false, error = err.orEmpty()) }
                return@submit
            }
            val ok = b.getBoolean("ok", false)
            patch {
                it.copy(
                    reachable = true,
                    policyRevision = b.getInt("acceptedRevision", it.policyRevision),
                    error = if (ok) "" else (b.getString("error") ?: ""),
                )
            }
        }
    }

    /**
     * 上报锁屏回执（规范 §3.1 reportReceipt）。
     * 回执只落账，不据此判断健康；哨兵那边会用 incidentId 去重。
     */
    fun reportReceipt(context: Context, receipt: com.yishou.app.health.LockReceipt) {
        val app = context.applicationContext
        submit(M_RECEIPT) {
            val extras = android.os.Bundle().apply {
                putInt("v", PROTOCOL_VERSION)
                putString("pkg", app.packageName)
                putString("policyId", receipt.policyId)
                putString("incidentId", receipt.incidentId)
                putString("requestId", receipt.requestId)
                putString("source", receipt.source)
                putString("reason", receipt.reason)
                putString("state", receipt.state.name)
                putLong("atWall", receipt.atWall)
                putString("note", receipt.note)
                putString("certSha256", Partner.YISHOU_CERT)
            }
            val (b, err) = callWithTimeout(app, M_RECEIPT, extras)
            if (b == null) {
                patch { it.copy(reachable = false, error = err.orEmpty()) }
                return@submit
            }
            patch { it.copy(reachable = true, error = if (b.getBoolean("ok", false)) "" else (b.getString("error") ?: "")) }
        }
    }

    /** 真正的跨进程调用，带硬超时（在专用后台线程里等，主线程永远不等）。 */
    private fun callWithTimeout(
        context: Context,
        method: String,
        extras: android.os.Bundle,
    ): Pair<android.os.Bundle?, String?> {
        val holder = arrayOfNulls<Any>(2)
        val done = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            try {
                holder[0] = context.contentResolver.call(uri, method, null, extras)
            } catch (e: Throwable) {
                holder[1] = e
            } finally {
                done.countDown()
            }
        }
        worker.isDaemon = true
        worker.start()
        val finished = try {
            done.await(CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) return null to "哨兵 ${CALL_TIMEOUT_MS}ms 内没有回应"
        (holder[1] as? Throwable)?.let { return null to (it.message ?: it.javaClass.simpleName) }
        val b = holder[0] as? android.os.Bundle ?: return null to "哨兵返回空"
        return b to null
    }

    private fun versionName(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun versionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    /** 单测 / 排障用。 */
    fun reset() {
        state = LinkState()
        inFlight.set(false)
    }

    /** 记一次「哨兵失联」的本地结论（超时 / 拒绝都会走到这里）。 */
    fun markLost(reason: String) {
        patch { it.copy(reachable = false, error = reason) }
    }
}
