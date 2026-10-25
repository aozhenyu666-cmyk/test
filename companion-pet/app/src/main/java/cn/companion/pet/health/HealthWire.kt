package cn.companion.pet.health

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/*
 * 同桌侧健康协议 v2 的“派发”部分（纯 Kotlin，可在 JVM 单测里直接跑）。
 *
 * 契约见 _shared/WIRE-SPEC-v2.md §3.2；核心是 T02：
 *   挑战必须投递到**真正承担无障碍执行的线程**（这里是感知服务的 Looper 主线程），
 *   并在那里读事实、取时刻，再把结果带回来。
 *   Binder 线程不许自己构造 ack；执行线程冻结时必须等到超时，然后如实报错。
 */

/** 与本应用 manifest 里 provider 的 authority 必须逐字一致。 */
const val PET_HEALTH_AUTHORITY = "cn.companion.pet.health"

/** 协议版本；哨兵按这个值判断能不能用 v2 能力。 */
const val PET_PROTOCOL_VERSION = 2

/** 在执行线程上读到的、可以对外说的事实（不含任何屏幕文字）。 */
data class ExecutorFacts(
    /** 当前本地规则版本号（PolicyStore 的 revision）。 */
    val ruleRevision: Int = 0,
    /** 最近一次锁屏回执的状态名（LockState.name）；空 = 从没有过。 */
    val lastReceiptState: String = "",
    /** 该回执的时刻（单调）。 */
    val lastReceiptAtMono: Long = 0,
    /** 最近处理的可比对窗口事件序号。 */
    val eventSeq: Long = 0,
    /** 该事件的时刻（单调）。 */
    val eventAtMono: Long = 0,
)

/** 一次挑战的真实回应。只有 [ok] 为真时 [facts] 才有内容。 */
data class ChallengeResult(
    val ok: Boolean,
    val error: String? = null,
    val facts: HealthFacts? = null,
)

/** 规范 §3.2 要求返回的字段。 */
data class HealthFacts(
    val pkg: String,
    val instanceId: String,
    val nonce: String,
    val seq: Long,
    /** 在执行线程上处理完的时刻（SystemClock.elapsedRealtime）。 */
    val handledAtMono: Long,
    val ruleRevision: Int,
    val lastReceiptState: String,
    val lastReceiptAtMono: Long,
    val eventSeq: Long,
    val eventAtMono: Long,
)

/**
 * 挑战派发器。
 *
 * @param clientPkg 自己的包名。
 * @param mono 单调时钟（生产环境 SystemClock.elapsedRealtime）。
 * @param executorConnected 无障碍执行线程当前是否在线（只做“先决条件”判断，不当健康凭据）。
 * @param currentInstanceId 本次无障碍连接实例；重新连接必须换新，null = 还没连上。
 * @param postToExecutor 把任务投递到真实执行线程；返回是否投递成功。
 * @param readFactsOnExecutor **只在执行线程上**调用，读取对外事实。
 * @param maxInFlight 同时在途的挑战上限（规范：不许为不可取消的阻塞调用无限新建线程）。
 * @param maxBudgetMs 单次挑战预算上限（C-2）。生产用 [MAX_BUDGET_MS]；
 *   测试可以调小，免得为了验「上限生效」真的等 20 秒。
 */
class ChallengeDispatcher(
    private val clientPkg: String,
    private val mono: Mono,
    private val executorConnected: () -> Boolean,
    private val currentInstanceId: () -> String?,
    private val postToExecutor: (Runnable) -> Boolean,
    private val readFactsOnExecutor: () -> ExecutorFacts,
    private val maxInFlight: Int = MAX_IN_FLIGHT,
    private val maxBudgetMs: Long = MAX_BUDGET_MS,
) {
    private val inFlight = AtomicInteger(0)

    fun handle(nonce: String, seq: Long, budgetMs: Long): ChallengeResult {
        val instanceId = currentInstanceId()
        if (instanceId.isNullOrBlank() || !executorConnected()) {
            // 无障碍没连上：立刻回错，不伪装成健康。
            return ChallengeResult(false, "not connected")
        }
        if (budgetMs <= 0) return ChallengeResult(false, "bad budget")
        // C-2：预算上限。哨兵传什么都不能让它把我们的线程拖住太久；
        // 与总控/一手同为 20s 上限，下限 500ms 防止哨兵给一个短到必然超时的值。
        val budget = budgetMs.coerceIn(MIN_BUDGET_MS, maxBudgetMs)
        // 原子地占一个名额：先查后加会有竞态，多个 Binder 线程同时进来就漏过上限。
        while (true) {
            val cur = inFlight.get()
            if (cur >= maxInFlight) return ChallengeResult(false, "too many in-flight challenges")
            if (inFlight.compareAndSet(cur, cur + 1)) break
        }
        try {
            val latch = CountDownLatch(1)
            val handledAt = AtomicLong(0)
            val factsRef = AtomicReference<ExecutorFacts>()
            val failure = AtomicReference<String>()
            val delivered = runCatching {
                postToExecutor(
                    Runnable {
                        try {
                            factsRef.set(readFactsOnExecutor())
                            // 时刻必须在这里取：Binder 线程取的时刻不能证明执行线程活着。
                            handledAt.set(mono.now())
                        } catch (t: Throwable) {
                            failure.set("executor threw " + t.javaClass.simpleName)
                        } finally {
                            latch.countDown()
                        }
                    },
                )
            }.getOrDefault(false)
            if (!delivered) return ChallengeResult(false, "executor unavailable")

            val done = runCatching { latch.await(budget, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!done) {
                // 执行线程冻结时走这条路：如实报超时，绝不代答。
                return ChallengeResult(false, "dispatcher timeout")
            }
            failure.get()?.let { return ChallengeResult(false, it) }
            val facts = factsRef.get() ?: return ChallengeResult(false, "executor returned nothing")
            return ChallengeResult(
                ok = true,
                error = null,
                facts = HealthFacts(
                    pkg = clientPkg,
                    instanceId = instanceId,
                    nonce = nonce,
                    seq = seq,
                    handledAtMono = handledAt.get(),
                    ruleRevision = facts.ruleRevision,
                    lastReceiptState = facts.lastReceiptState,
                    lastReceiptAtMono = facts.lastReceiptAtMono,
                    eventSeq = facts.eventSeq,
                    eventAtMono = facts.eventAtMono,
                ),
            )
        } finally {
            inFlight.decrementAndGet()
        }
    }

    companion object {
        /** 感知服务只有一条执行线程，超过这个数的在途请求一定是异常堆积。 */
        const val MAX_IN_FLIGHT = 4

        /** C-2：单次挑战预算的上下限（与总控 / 一手一致，上限 20s）。 */
        const val MIN_BUDGET_MS = 500L
        const val MAX_BUDGET_MS = 20_000L
    }
}
