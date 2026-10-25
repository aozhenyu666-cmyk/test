package com.zongkong.sentinel.watch

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.zongkong.sentinel.health.Challenge
import com.zongkong.sentinel.health.ChallengeAck

/**
 * 哨兵 → 客户端 的挑战派发（规范 §3.2，T02/T04）。
 *
 * 这一层是 `:watch` 里的**真实 Binder 调用**，必须遵守：
 *  - **每个客户端一个专属线程**，用完复用，绝不每次新建：某个客户端卡死在 IPC 里时，
 *    只锁住它自己那条线程，另外两个客户端的探测与锁屏照常（T04）。
 *  - **有限在途**：`HealthStore.beginProbe` 已经保证每个客户端最多 1 个在途；
 *    超预算由 `HealthScheduler` 判 wedged，之后**不再发新的**，直到客户端用新实例重新注册。
 *  - 回包必须是完整的 dispatcherAck：缺 `handledAtMono`（执行线程处理时刻）就是后台代答，判失败（T02）。
 *  - **绝不**用 `GuardService.running` / `SentinelService.running` 这类布尔当健康凭据。
 */
class ClientProbe(
    private val context: Context,
    private val scheduler: HealthScheduler,
) : HealthScheduler.ProbeSink {

    private val workers = HashMap<String, MutableList<Worker>>()

    companion object {
        private const val TAG = "SentinelProbe"
        private const val METHOD = "challenge"

        /**
         * 每个客户端最多允许的探测线程数。
         *
         * 为什么不是 1：Binder 调用卡死时线程无法被杀，客户端重连后必须还有一条能干活的线程；
         * 为什么不是无限：卡到 [MAX_THREADS] 条就**停止新建**，直接判失败（规范 §3.2「不许为无法取消的
         * 阻塞调用不停新建线程」）。
         */
        const val MAX_THREADS = 2

        /** 客户端 provider authority（规范 §3.2）。 */
        val AUTHORITIES = mapOf(
            HealthStore.ZONGKONG to "com.zongkong.app.health",
            HealthStore.YISHOU to "com.yishou.app.health",
            HealthStore.PET to "cn.companion.pet.health",
        )
    }

    /** 一个客户端一条常驻线程 + 一个槽位。同一时刻最多一个任务。 */
    private inner class Worker(val pkg: String) : Thread("probe-$pkg") {
        private val lock = Object()
        private var job: (() -> Unit)? = null
        @Volatile private var stopped = false
        @Volatile var busy = false
            private set

        init {
            isDaemon = true
            start()
        }

        fun submit(task: () -> Unit): Boolean = synchronized(lock) {
            if (stopped || busy || job != null) return false
            job = task
            lock.notifyAll()
            true
        }

        fun halt() {
            synchronized(lock) {
                stopped = true
                job = null
                lock.notifyAll()
            }
        }

        override fun run() {
            while (true) {
                val task: (() -> Unit)? = synchronized(lock) {
                    while (!stopped && job == null) {
                        try { lock.wait(1_000) } catch (_: InterruptedException) { return }
                    }
                    val j = job
                    job = null
                    j
                }
                if (stopped) return
                if (task != null) {
                    busy = true
                    try { task() } catch (e: Throwable) { Log.w(TAG, "探测线程出错 $pkg", e) } finally { busy = false }
                }
            }
        }
    }

    /**
     * 发给这个客户端的空闲线程；都卡着且没到上限，就再开一条；到上限返回 null（判失败，不再新建）。
     */
    private fun workerFor(pkg: String): Worker? = synchronized(workers) {
        val list = workers.getOrPut(pkg) { mutableListOf() }
        list.firstOrNull { !it.busy } ?: if (list.size < MAX_THREADS) {
            Worker(pkg).also { list += it }
        } else {
            null
        }
    }

    /** **非阻塞返回**：Binder 调用交给该客户端自己的线程。 */
    override fun dispatch(pkg: String, challenge: Challenge, budgetMs: Long) {
        val w = workerFor(pkg)
        if (w == null || !w.submit { runChallenge(pkg, challenge, budgetMs) }) {
            scheduler.onProbeFailure(pkg, "探测线程已卡死 ${MAX_THREADS} 条，停止新建线程")
        }
    }

    private fun runChallenge(pkg: String, challenge: Challenge, budgetMs: Long) {
        val authority = AUTHORITIES[pkg]
        if (authority == null) {
            scheduler.onProbeFailure(pkg, "哨兵不认识这个客户端")
            return
        }
        val args = Bundle().apply {
            putInt("v", HealthStore.PROTOCOL_VERSION)
            putString("nonce", challenge.nonce)
            putLong("seq", challenge.seq)
            putLong("budgetMs", budgetMs)
            putString("from", "com.zongkong.sentinel")
        }
        val b: Bundle? = try {
            context.contentResolver.call(Uri.parse("content://$authority"), METHOD, null, args)
        } catch (e: Throwable) {
            scheduler.onProbeFailure(pkg, "调不通客户端：${e.message ?: e.javaClass.simpleName}")
            return
        }
        val receivedMono = SystemClock.elapsedRealtime()

        if (b == null) {
            scheduler.onProbeFailure(pkg, "客户端没回包")
            return
        }
        if (!b.getBoolean("ok", false)) {
            scheduler.onProbeFailure(pkg, "客户端拒绝挑战：${b.getString("error") ?: "没说原因"}")
            return
        }
        val ack = ChallengeAck(
            protocolVersion = b.getInt("v", 0),
            clientPkg = b.getString("pkg") ?: "",
            instanceId = b.getString("instanceId") ?: "",
            nonce = b.getString("nonce") ?: "",
            seq = b.getLong("seq", 0),
            handledAtClientMono = b.getLong("handledAtMono", 0),
            ruleRevision = b.getInt("ruleRevision", 0),
            lastReceiptState = b.getString("lastReceiptState") ?: "",
            lastReceiptAtClientMono = b.getLong("lastReceiptAtMono", 0),
            lastEventSeq = b.getLong("eventSeq", 0),
            lastEventAtClientMono = b.getLong("eventAtMono", 0),
        )
        if (ack.clientPkg.isNotBlank() && ack.clientPkg != pkg) {
            scheduler.onProbeFailure(pkg, "回包自称 ${ack.clientPkg}，与本客户端不符")
            return
        }
        if (ack.handledAtClientMono <= 0) {
            // 没有「在执行线程上处理完的时刻」= 后台线程代答，不算 dispatcherAck（T02）。
            scheduler.onProbeFailure(pkg, "回包缺少执行线程处理时刻，判定为代答")
            return
        }
        scheduler.onAck(pkg, challenge, ack, receivedMono)
    }

    fun shutdown() {
        synchronized(workers) {
            workers.values.forEach { list -> list.forEach { it.halt() } }
            workers.clear()
        }
    }
}

