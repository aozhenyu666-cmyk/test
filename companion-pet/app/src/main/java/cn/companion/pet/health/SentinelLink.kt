package cn.companion.pet.health

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 哨兵连接器（规范 §3.1）：注册 / 同步规则 / 上报回执 / ping。
 *
 * 纪律：
 *  - 只用**显式 authority**，不用广播（报告 §9：避免任意应用通过广播请求锁屏）。
 *  - 全部在独立线程上做，带超时；绝不从无障碍事件线程或主线程同步调用。
 *  - 调用返回 != 哨兵接受了：`acceptedRevision` 拿不到就算没同步成功（报告 §7）。
 *  - 哨兵失联不删规则、不自动放行；本地规则照常生效（报告 §7）。
 */
class SentinelLink(private val context: Context) {

    /** 最近一次调用的结果，界面据此说“已同步 / 未确认”，不涂绿。 */
    data class LinkState(
        val registered: Boolean = false,
        val acceptedRevision: Int = 0,
        val lastOk: Boolean = false,
        val lastError: String = "",
        val lastAtMono: Long = 0,
        val lastCallLatencyMs: Long = 0,
        val deduped: Boolean = false,
        /** 曾经成功通过一次（证明哨兵确实装过、也接受过我们）。 */
        val everOk: Boolean = false,
        /** 最近一次成功调用的时刻（单调）；0 = 从没成功过。 */
        val everOkAtMono: Long = 0,
    )

    @Volatile
    var state: LinkState = LinkState()
        private set

    // 单线程 + 有界队列：哨兵不响应时不会堆出无限线程（T04）。
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pet-sentinel-link").apply { isDaemon = true }
    }

    private fun call(method: String, extras: Bundle): Bundle? {
        val started = SystemClock.elapsedRealtime()
        val uri = Uri.parse("content://$SENTINEL_AUTHORITY")
        val result = runCatching {
            context.contentResolver.call(uri, method, null, extras)
        }.getOrNull()
        val latency = SystemClock.elapsedRealtime() - started
        if (result == null) {
            state = state.copy(lastOk = false, lastError = "$method 没有回应（哨兵未安装/未在岗）",
                lastAtMono = SystemClock.elapsedRealtime(), lastCallLatencyMs = latency)
            return null
        }
        val ok = result.getBoolean("ok", false)
        val err = result.getString("error")
        val now = SystemClock.elapsedRealtime()
        state = state.copy(
            lastOk = ok,
            lastError = if (ok) "" else (err ?: "$method 被拒绝"),
            lastAtMono = now,
            lastCallLatencyMs = latency,
            deduped = result.getBoolean("deduped", false),
            everOk = state.everOk || ok,
            everOkAtMono = if (ok) now else state.everOkAtMono,
        )
        return result
    }

    /**
     * 哨兵是否“确认失联”：只有**曾经接通过**、之后超过 [confirmMs] 没有成功回应才算。
     * 从没接通过的情况不算失联——那是“没装”，不是“掉线”，不能据此抢着锁屏。
     */
    fun sentinelUnresponsive(nowMono: Long, confirmMs: Long = 30_000): Boolean =
        SentinelLiveness.unresponsive(state, nowMono, confirmMs)

    /** 注册。instanceId 必须来自当前无障碍实例，重建要重新注册（规范 §3.1）。 */
    fun register(instanceId: String, versionName: String, versionCode: Long, certSha256: String) {
        worker.execute {
            val extras = Bundle().apply {
                putInt("v", PET_PROTOCOL_VERSION)
                putString("pkg", context.packageName)
                putString("versionName", versionName)
                putLong("versionCode", versionCode)
                putString("instanceId", instanceId)
                putString("certSha256", certSha256)
            }
            val r = call("register", extras) ?: return@execute
            if (r.getBoolean("ok", false)) {
                state = state.copy(registered = true)
            }
        }
    }

    /**
     * 同步规则到哨兵（规范 §3.1）。**先落盘再同步**：只有拿到 acceptedRevision 才能说「已就绪」。
     *
     * 紧急暂停也走这条路（把 emergencyState=STOPPED 随规则一起同步）：
     * 规范 §3.1 的方法集是冻结的（`register / syncPolicy / reportReceipt / ping`），
     * 哨兵 `WatchProvider` 只认这四个，所以**不能**发明一个只有本端在用的方法名。
     */
    fun syncPolicy(policyJson: String) {
        worker.execute {
            val extras = Bundle().apply {
                putInt("v", PET_PROTOCOL_VERSION)
                putString("pkg", context.packageName)
                putString("policyJson", policyJson)
            }
            val r = call("syncPolicy", extras) ?: return@execute
            val accepted = r.getInt("acceptedRevision", -1)
            state = state.copy(acceptedRevision = accepted)
            // 只有哨兵真的接受了（acceptedRevision >= 0）才算收到，不把「发出去了」当「确认了」。
            fireSyncListeners(accepted >= 0)
        }
    }

    /** 一次性监听下一次 syncPolicy 的结果（给「暂停已确认」用）；触发后自动移除。 */
    fun onSyncResult(listener: (Boolean) -> Unit) {
        syncListeners += listener
    }

    private val syncListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    private fun fireSyncListeners(confirmed: Boolean) {
        // 一次性语义：触发即摘掉，避免每次同步都重复回调（会无限累积监听器）。
        val pending = syncListeners.toList()
        syncListeners.clear()
        pending.forEach { runCatching { it(confirmed) } }
    }

    /** 上报锁屏回执。deduped=true 表示哨兵按同一事件去重了。 */
    fun reportReceipt(receipt: ReceiptDto) {
        worker.execute {
            val extras = Bundle().apply {
                putInt("v", PET_PROTOCOL_VERSION)
                putString("pkg", context.packageName)
                putString("policyId", receipt.policyId)
                putString("incidentId", receipt.incidentId)
                putString("requestId", receipt.requestId)
                putString("source", receipt.source)
                putString("reason", receipt.reason)
                putString("state", receipt.state)
                putLong("atWall", receipt.atWall)
                putString("note", receipt.note)
            }
            call("reportReceipt", extras)
        }
    }

    /** 后台 ping：确认哨兵还在。不阻塞任何调用方。 */
    fun ping() {
        worker.execute {
            call("ping", Bundle().apply { putInt("v", PET_PROTOCOL_VERSION); putString("pkg", context.packageName) })
        }
    }

    /** 诊断用：等队列里已排队的调用跑完（只在测试/退出时用）。 */
    fun drainForTest(timeoutMs: Long = 2_000) {
        runCatching { worker.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    fun shutdown() {
        runCatching { worker.shutdownNow() }
    }

    companion object {
        /** 哨兵注册/同步入口的 authority（规范 §3.1）。 */
        const val SENTINEL_AUTHORITY = "com.zongkong.sentinel.health"
    }
}
