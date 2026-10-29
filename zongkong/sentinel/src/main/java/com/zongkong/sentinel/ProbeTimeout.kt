package com.zongkong.sentinel

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

/**
 * 有界探问：跨进程问一句「你还在吗」，**必须有超时**。
 *
 * 为什么单独抽出来：`ContentResolver.call` 是不可取消的同步 Binder 调用。
 * 总控进程还活着、但执行线程被冻住（被系统冻结 / 卡在锁上 / 主线程 ANR）时，
 * 这个调用会**无限期挂着**，既不返回也不抛异常。
 *
 * 真机实测（Android 14，把总控 `kill -STOP` 冻结 100 秒）：
 *  - 哨兵的探测线程卡在 Binder 里，`probing` 标志永远是 true
 *  - 于是 `watch()` 里排队的接管回调 `if (!alive) takeover()` **永远等不到**
 *  - 冻结期间打开 YouTube：**没有被拦，直接留在前台**
 *  - 恢复（`kill -CONT`）后调用才返回，心跳立刻被续上 →
 *    `health.json` 里 `consecutiveFailures: 0`，看上去「一直很健康」
 *
 * 这正是报告 T05 要防的「总控显示开启但停止响应」，而旧代码在这一点上是不设防的。
 *
 * 处理原则：**超时 = 叫不应**。宁可当作掉线（停掉心跳、允许哨兵接管），
 * 也不要因为对方卡住而让自己一起挂死。误判代价很小：调用方（`watch()`）
 * 只在心跳已经过期 90 秒之后才会来问这一句。
 */
object ProbeTimeout {

    /** 探问默认上限。健康的总控是毫秒级回应；3 秒已经是极宽裕的余量。 */
    const val DEFAULT_TIMEOUT_MS = 3_000L

    /**
     * 在 [exec] 上跑 [block]，最多等 [timeoutMs]。
     *
     * 超时、被打断、内部抛异常，一律返回 [MainGuard.NOT_GUARDING]——「没有正面证据」。
     *
     * 注意用**单线程** executor：卡住的那次调用会永久占住那条线程（Binder 不可取消），
     * 后续探问排队并照样超时。这样不会因为对方卡死而不断新建线程（规范 §3.2）。
     */
    fun await(
        exec: ExecutorService,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        block: () -> MainGuard,
    ): MainGuard = try {
        val future = exec.submit(Callable { block() })
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        MainGuard.NOT_GUARDING
    } catch (_: Throwable) {
        MainGuard.NOT_GUARDING
    }
}
