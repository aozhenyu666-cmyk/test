package com.zongkong.sentinel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 探问必须有界：对方卡住，不能把哨兵一起拖死。
 *
 * 真机事故（Android 14；`kill -STOP` 冻结总控 100 秒）：
 *  - 哨兵的探测线程卡在不可取消的 Binder 调用里，`probing` 永远为 true
 *  - `watch()` 排队的 `if (!alive) takeover()` 因此永远等不到
 *  - **冻结期间打开 YouTube，没有被拦，直接留在前台**（T05 失败）
 *  - `kill -CONT` 恢复后调用才返回，心跳被立刻续上 →
 *    `health.json` 里 `consecutiveFailures: 0`，看上去「一直很健康」
 *
 * 修复要点：`ProbeTimeout.await` 给同步 Binder 调用加上限，超时 = 叫不应。
 */
class ProbeTimeoutTest {

    private fun single(): ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "probe-test").apply { isDaemon = true } }

    @Test
    fun `正常返回时原样透传结论`() {
        val exec = single()
        try {
            val r = ProbeTimeout.await(exec, 1_000L) { MainGuard.GUARDING }
            assertEquals(MainGuard.GUARDING, r)
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun `卡住的调用超时后判为叫不应`() {
        val exec = single()
        val release = CountDownLatch(1)
        try {
            val started = System.nanoTime()
            // 模拟「总控被冻结」：调用永不返回，直到测试放行。
            val r = ProbeTimeout.await(exec, 200L) {
                release.await(30, TimeUnit.SECONDS)
                MainGuard.GUARDING
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals("超时必须当作不在（NOT_GUARDING），不能假装健康", MainGuard.NOT_GUARDING, r)
            assertTrue("必须在预算内返回，实际 ${elapsedMs}ms", elapsedMs < 3_000)
        } finally {
            release.countDown()
            exec.shutdownNow()
        }
    }

    @Test
    fun `内部抛异常也算叫不应`() {
        val exec = single()
        try {
            val r = ProbeTimeout.await(exec, 500L) { throw IllegalStateException("Binder 挂了") }
            assertEquals(MainGuard.NOT_GUARDING, r)
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun `卡死之后不会无限新建线程`() {
        val exec = single()
        val release = CountDownLatch(1)
        val created = AtomicInteger(0)
        val counting = Executors.newSingleThreadExecutor { r ->
            created.incrementAndGet()
            Thread(r, "probe-count").apply { isDaemon = true }
        }
        try {
            // 连续三次探问，对方一直卡着：每次都超时，但线程只建一条。
            repeat(3) {
                val r = ProbeTimeout.await(counting, 120L) {
                    release.await(30, TimeUnit.SECONDS)
                    MainGuard.GUARDING
                }
                assertEquals(MainGuard.NOT_GUARDING, r)
            }
            assertEquals("单线程 executor：卡死也不该繁殖线程", 1, created.get())
        } finally {
            release.countDown()
            exec.shutdownNow()
            counting.shutdownNow()
        }
    }

    @Test
    fun `超时预算默认值是有限正数`() {
        assertTrue("默认预算必须显式有限", ProbeTimeout.DEFAULT_TIMEOUT_MS in 1..30_000)
    }
}
