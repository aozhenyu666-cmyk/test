package com.yishou.app.gate

const val WAIT_MS = 10 * 60_000L
const val WAIT_PASS_MS = 5 * 60_000L

/** 只认同一页面连续等待；离开或使用后必须重新开始。 */
class WaitFallback(private val clock: () -> Long) {
    private var startedAt: Long? = null

    @Synchronized fun start(): Long = clock().also { startedAt = it }
    @Synchronized fun leave() { startedAt = null }
    @Synchronized fun remainingMs(): Long {
        val start = startedAt ?: return WAIT_MS
        val elapsed = (clock() - start).coerceAtLeast(0L)
        return (WAIT_MS - elapsed).coerceAtLeast(0L)
    }
    @Synchronized fun done(): Boolean = startedAt != null && remainingMs() == 0L
    @Synchronized fun consume(): Boolean {
        if (!done()) return false
        startedAt = null
        return true
    }
}
