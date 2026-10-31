package com.zongkong.app.healthwire

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地自检（P0-1）：**不经过哨兵**，自己往无障碍线程投一个探针，测那条线程还活不活着。
 *
 * ## 为什么需要它
 *
 * 「自己健康」原先只看 [HealthLedger] 里的 `dispatcherAck`，而那个 ack **只有哨兵发起 challenge 才会产生**。
 * 于是就出现了一个闭环：
 *
 * 1. 哨兵沉默 → 不再有 challenge → 没有新 ack；
 * 2. 没有新 ack → `selfHealthy = false`；
 * 3. `LocalFallback.decide` 要求 `selfHealthy && !sentinelHealthy` 才兜底 → 永不进入；
 * 4. 同时 `sentinelHealthy` 的缓存窗口（60s）比 ack 新鲜窗口（30s）更长，导致
 *    `selfHealthy = true` **必然蕴含** `sentinelHealthy = true`；
 * 5. 两者叠加：本地兜底 → `LocalLockExecutor` → `LockReceipt` 整条执行链**在生产上不可达**。
 *
 * 这个类的职责就是把第 1 步拆掉：测同一件事（那条线程还活不活着），但**发起方是自己**，
 * 不依赖对端还愿不愿意发挑战。
 *
 * ## 语义一致性（不许退化成假信号）
 *
 * 只有真的在无障碍线程上跑过探针才算通过 —— 不读开关、不读 `GuardService.running`、
 * 不读任何进程内布尔。投递被拒、投递成功但线程没跑（卡死）、等待超时，统统算失败。
 *
 * ## 缓存
 *
 * 巡检本身 15s 一次，但任何高频调用方都不该把无障碍线程戳爆，所以结论缓存 [cacheMs]。
 * 缓存只复用**结论**，不会在执行探针时持锁，也不会吞掉失败。
 *
 * @param budgetMs 单次探针的等待预算。无障碍线程正常时是毫秒级，默认值已经极宽松。
 * @param cacheMs 两次真实探针之间的最小间隔；0 = 每次都真投（测试用）。
 * @param nowMono 单调时钟（生产传 `SystemClock::elapsedRealtime`）。**必须可注入**：单元测试里
 *                `isReturnDefaultValues` 会让 `SystemClock` 恒返回 0，不注入就没法验证缓存。
 */
class LocalSelfCheck(
    private val budgetMs: Long = DEFAULT_BUDGET_MS,
    private val cacheMs: Long = DEFAULT_CACHE_MS,
    private val nowMono: () -> Long,
) {

    /** 一次自检的结论。失败也带原因，便于写诊断日志与界面显示。 */
    enum class Verdict(val label: String) {
        /** 探针真的在无障碍线程上跑完了。 */
        OK("通过"),
        /** 无障碍投递口没挂上（服务没连或已销毁）。 */
        NO_POST("投递口没挂上"),
        /** 投递被拒（Handler 已退出 / 服务正在销毁）。 */
        REJECTED("投递被拒"),
        /** 投进去了，但预算内没跑 —— 线程卡死。这是最该报出来的那一种。 */
        TIMEOUT("线程没有在预算内响应"),
        /** 命中了缓存，这次没有真的投递。 */
        CACHED("缓存结论"),
    }

    data class Result(val verdict: Verdict, val ok: Boolean, val atMono: Long)

    private var lastOkAtMono = 0L
    private var lastFailAtMono = 0L
    private var lastSpokeAtMono = 0L

    /** 最近一次结论是否通过（不触发新的探针）。 */
    fun fresh(nowMono: Long = this.nowMono(), windowMs: Long = FRESH_WINDOW_MS): Boolean =
        lastOkAtMono > lastFailAtMono && nowMono - lastOkAtMono <= windowMs

    /**
     * 做一次自检。
     *
     * @param post 生产传 `GuardHealth.a11yPost`（投递到无障碍服务的主线程 Handler）；
     *             传 null 表示当前没有无障碍投递口。
     */
    fun probe(post: ((() -> Unit) -> Boolean)?): Result {
        val now = nowMono()

        // 缓存：同一窗口内复用结论，不重复戳线程。
        if (cacheMs > 0 && lastSpokeAtMono > 0 && now - lastSpokeAtMono < cacheMs) {
            return Result(Verdict.CACHED, lastOkAtMono > lastFailAtMono, now)
        }
        lastSpokeAtMono = now

        if (post == null) {
            lastFailAtMono = now
            return Result(Verdict.NO_POST, false, now)
        }

        val latch = CountDownLatch(1)
        val handledAt = AtomicLong(0L)
        val accepted = try {
            post {
                handledAt.set(nowMono())
                latch.countDown()
            }
        } catch (_: Throwable) {
            false
        }
        if (!accepted) {
            lastFailAtMono = now
            return Result(Verdict.REJECTED, false, now)
        }

        val returned = try {
            latch.await(budgetMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!returned || handledAt.get() <= 0L) {
            lastFailAtMono = now
            return Result(Verdict.TIMEOUT, false, now)
        }

        lastOkAtMono = handledAt.get()
        return Result(Verdict.OK, true, now)
    }

    /** 测试/诊断用：清掉所有状态。 */
    fun reset() {
        lastOkAtMono = 0L
        lastFailAtMono = 0L
        lastSpokeAtMono = 0L
    }

    companion object {
        /** 单次预算：无障碍线程正常时探针是毫秒级，800ms 已经极宽松。 */
        const val DEFAULT_BUDGET_MS = 800L

        /** 两次真实探针的最小间隔。 */
        const val DEFAULT_CACHE_MS = 5_000L

        /** 自检结论的有效期：超过这个时间就不再认为「自己还健康」（与 ack 新鲜窗口一致）。 */
        const val FRESH_WINDOW_MS = 30_000L
    }
}
