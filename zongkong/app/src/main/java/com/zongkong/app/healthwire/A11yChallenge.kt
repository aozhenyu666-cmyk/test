package com.zongkong.app.healthwire

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/*
 * T02 的核心：挑战必须投递到**真正承担无障碍执行的那个线程**（a11y 主线程 Handler），
 * 在那里处理完再带回；Binder 线程只负责等，并且必须带超时。
 *
 * 本文件是纯 Kotlin（只依赖 java.util.concurrent），可以在 JVM 单测里直接验证：
 *   - 没连上 → ok=false
 *   - 投递成功且 a11y 线程跑了 → 带回 nonce/seq/instanceId/handledAtMono
 *   - a11y 线程被冻结（post 后永不执行）→ 超时 → ok=false + "dispatcher timeout"
 *   - 绝不在这里"自己造 ack"
 */

/** 哨兵发来的挑战（已从 Bundle 里读出来）。 */
data class A11yChallengeRequest(
    val v: Int = 2,
    val nonce: String,
    val seq: Long,
    val budgetMs: Long = 5_000,
)

/** ack 里除挑战回显之外的本地事实（规则版本、锁屏回执、事件进度）。 */
data class AckFacts(
    val ruleRevision: Int = 0,
    val lastReceiptState: String = "",
    val lastReceiptAtMono: Long = 0,
    val eventSeq: Long = 0,
    val eventAtMono: Long = 0,
)

/** 一次挑战的结果。只有 [Ack] 算真实 dispatcherAck。 */
sealed interface ChallengeOutcome {
    data class Ack(
        val protocolVersion: Int,
        val pkg: String,
        val instanceId: String,
        val nonce: String,
        val seq: Long,
        val handledAtMono: Long,
        val facts: AckFacts,
    ) : ChallengeOutcome

    data class Fail(val error: String) : ChallengeOutcome

    /** 投递出去了，但 a11y 线程在预算内没有回话（线程冻结 / 卡死时的唯一正确结论）。 */
    data class Timeout(val budgetMs: Long) : ChallengeOutcome

    val ok: Boolean get() = this is Ack

    fun errorText(): String = when (this) {
        is Ack -> ""
        is Fail -> error
        is Timeout -> "dispatcher timeout"
    }
}

/**
 * 挑战处理器。一个实例复用于所有请求（上限在途数，避免线程冻结时无限堆线程）。
 *
 * @param maxInFlight 同时未完成的挑战上限。哨兵那边每个客户端也只有一个在途请求，这里只是兜底。
 */
class A11yChallengeHandler(private val maxInFlight: Int = 2) {
    private val inFlight = AtomicInteger(0)

    /** 当前在途请求数（诊断/单测用）。 */
    val pending: Int get() = inFlight.get()

    /**
     * @param post 把 [Runnable] 投到 a11y 主线程；返回 false 表示投不进去（服务没连、Looper 已退出）。
     * @param mono 单调时钟（生产上是 SystemClock.elapsedRealtime）。
     * @param facts 读取本地事实；**在 a11y 线程回话之后**才调用。
     * @param onHandled 在 a11y 线程上处理完时回调（记录 seq/时刻）。
     * @param onFailed 失败时回调（记失败原因，供看门狗使用）。
     */
    fun handle(
        req: A11yChallengeRequest,
        pkg: String,
        instanceId: String?,
        post: (() -> Unit) -> Boolean,
        mono: () -> Long,
        facts: () -> AckFacts,
        budgetCapMs: Long = 20_000L,
        onHandled: (Long) -> Unit = {},
        onFailed: (String) -> Unit = {},
    ): ChallengeOutcome {
        if (req.v < 2) return fail("协议版本过旧：${req.v}", onFailed)
        if (req.nonce.isBlank()) return fail("nonce 为空", onFailed)
        // 无障碍服务没连上：立刻如实回答，不要假装健康。
        if (instanceId.isNullOrBlank()) return fail("not connected", onFailed)
        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet()
            return fail("too many in flight（已有 $maxInFlight 个未完成）", onFailed)
        }
        try {
            val budget = req.budgetMs
                .let { if (it <= 0L) 5_000L else it }
                .coerceAtMost(budgetCapMs)
                .coerceAtLeast(300L)
            val latch = CountDownLatch(1)
            val handledAt = AtomicLong(0L)
            val captured = java.util.concurrent.atomic.AtomicReference<AckFacts?>(null)
            val posted = post {
                try {
                    // 关键：facts 也在**a11y 线程上**读，保证带回的是「执行线程处理完那一刻」的本地事实，
                    // 而不是 Binder 线程事后补读的（那样等于后台代答，T02 禁止）。
                    captured.set(facts())
                    val at = mono()
                    handledAt.set(at)
                    onHandled(at)
                } finally {
                    latch.countDown()
                }
            }
            if (!posted) return fail("not connected", onFailed)
            val done = try {
                latch.await(budget, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!done) {
                // 线程冻结/卡死：如实报超时，不构造 ack。
                onFailed("dispatcher timeout（${budget}ms 内 a11y 线程没有处理挑战）")
                return ChallengeOutcome.Timeout(budget)
            }
            // 只有真正在 a11y 线程上跑过（handledAt > 0）才可能走到这里。
            if (handledAt.get() <= 0L) return fail("dispatcher did not report handled", onFailed)
            return ChallengeOutcome.Ack(
                protocolVersion = 2,
                pkg = pkg,
                instanceId = instanceId,
                nonce = req.nonce,
                seq = req.seq,
                handledAtMono = handledAt.get(),
                facts = captured.get() ?: AckFacts(),
            )
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun fail(error: String, onFailed: (String) -> Unit): ChallengeOutcome {
        onFailed(error)
        return ChallengeOutcome.Fail(error)
    }
}
