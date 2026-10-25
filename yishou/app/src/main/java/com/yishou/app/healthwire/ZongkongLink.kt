package com.yishou.app.healthwire

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.yishou.app.link.Link
import com.yishou.app.link.Partner
import com.yishou.app.link.ZongkongStatus
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一手 → 总控 的状态读取，**永不阻塞无障碍事件线程**（报告 §8、规范 §8 的「一手 gate/GateService.kt」一行）。
 *
 * 两层：
 *  - [read] / [readLocal]：纯内存读缓存，事件线程可以随便调，纳秒级返回。
 *  - [requestRefresh]：把真正的跨进程 `contentResolver.call` 丢到**单个后台线程 + 硬超时**里；
 *    超时就记失联（[StatusCache.expireFetch]），等下一轮再试。
 *
 * 在途刷新只有一个（[inFlight] 守卫），线程也只有一个：线程冻结时不会堆线程（T04）。
 */
object ZongkongLink {

    private const val TAG = "ZongkongLink"

    /** 缓存 TTL：30s 内算新鲜（总控状态变化不快，事件一秒好几次不该每次都问）。 */
    private const val TTL_MS = 30_000L

    /** 在途刷新最晚多久必须有结果；超时判失联。 */
    private const val LOST_AFTER_MS = 8_000L

    private val cache = StatusCache(ttlMs = TTL_MS, lostAfterMs = LOST_AFTER_MS)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yishou-zongkong").apply { isDaemon = true }
    }

    /** 后台刷新操作本身的硬超时（比失联预算略短，留出让主线程看到结果的时间）。 */
    private const val CALL_TIMEOUT_MS = 5_000L

    private val inFlight = AtomicBoolean(false)

    private val uri: Uri = Uri.parse("content://${Link.ZONGKONG_AUTHORITY}")

    /** 事件线程：纯内存读。 */
    fun read(): StatusRead = cache.read(System.currentTimeMillis())

    /** 兼容旧调用点：需要「总控状态」但可以接受 null（失联 = null）。 */
    fun readOrNull(): ZongkongStatus? = read().status

    /**
     * 事件线程：请求一次后台刷新。已有在途且没超期时直接返回，不排队、不堆线程。
     * 无论刷新成功、失败还是超时，事件线程都不会停在这里等。
     */
    fun requestRefresh(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (!cache.beginFetch(now)) return
        if (!inFlight.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    val started = SystemClock.elapsedRealtime()
                    val s = fetchWithTimeout(app, CALL_TIMEOUT_MS)
                    val cost = SystemClock.elapsedRealtime() - started
                    if (cost > CALL_TIMEOUT_MS) {
                        cache.expireFetch(System.currentTimeMillis(), "总控响应 ${cost}ms，超过预算")
                    } else {
                        cache.finishFetch(s)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "刷新总控状态失败", e)
                    cache.failFetch(System.currentTimeMillis(), e.message ?: e.javaClass.simpleName)
                } finally {
                    inFlight.set(false)
                }
            }
        } catch (e: Throwable) {
            // 线程池满了/关了：如实记失联，绝不回退成「读旧值当现状」。
            inFlight.set(false)
            cache.failFetch(now, "刷新任务提交失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 给**非事件线程**（界面 / ViewModel 的 IO 协程）用的一次性读取：真读一次、带超时、顺手更新缓存。
     * 无障碍事件线程**不要**调这个（那会在事件链路上等 IPC）。
     */
    fun fetchOnce(context: Context, timeoutMs: Long = CALL_TIMEOUT_MS): ZongkongStatus? {
        val app = context.applicationContext
        val snapshot = try {
            fetchWithTimeout(app, timeoutMs)
        } catch (e: Throwable) {
            StatusSnapshot(null, ok = false, atWall = System.currentTimeMillis(), error = e.message ?: e.javaClass.simpleName)
        }
        cache.finishFetch(snapshot)
        return snapshot.status
    }

    /**
     * 真正读一次。`call` 本身没法拆成「可取消的分片」，所以放进单独的线程里等，
     * 超时后主路径（事件线程）立刻走失联分支；这个后台线程自己跑完就结束，不会再被复用去等第二次。
     */
    private fun fetchWithTimeout(context: Context, timeoutMs: Long): StatusSnapshot {
        val nowWall = System.currentTimeMillis()
        if (!Partner.zongkongGenuine(context)) {
            return StatusSnapshot(null, ok = false, atWall = nowWall, error = "总控不在（没装或签名不对）")
        }
        val holder = arrayOfNulls<Any>(2) // [0] Bundle, [1] Throwable
        val done = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            try {
                holder[0] = context.contentResolver.call(uri, Link.STATUS, null, null)
            } catch (e: Throwable) {
                holder[1] = e
            } finally {
                done.countDown()
            }
        }
        worker.isDaemon = true
        worker.start()
        val finished = try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            return StatusSnapshot(null, ok = false, atWall = nowWall, error = "总控 ${timeoutMs}ms 内没有回应")
        }
        val err = holder[1] as? Throwable
        if (err != null) {
            return StatusSnapshot(null, ok = false, atWall = nowWall, error = err.message ?: err.javaClass.simpleName)
        }
        val b = holder[0] as? android.os.Bundle
            ?: return StatusSnapshot(null, ok = false, atWall = nowWall, error = "总控返回空")
        if (b.containsKey(Link.ERROR)) {
            return StatusSnapshot(null, ok = false, atWall = nowWall, error = b.getString(Link.ERROR).orEmpty())
        }
        @Suppress("DEPRECATION")
        val map = b.keySet().associateWith { b.get(it) }
        return StatusSnapshot(ZongkongStatus.fromMap(map), ok = true, atWall = System.currentTimeMillis())
    }

    /** 单测 / 排障用：清掉缓存，模拟「刚到失联 → 恢复」。 */
    fun resetCache() {
        cache.reset()
        inFlight.set(false)
    }
}
