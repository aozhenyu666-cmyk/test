package com.zongkong.app.guard

/** 启动请求和真正显示分开记；旧请求、离开应用、权限关闭后不再弹兜底。 */
class PageDelivery(private val timeoutMs: Long = 1_000) {
    enum class Result { WAITING, VISIBLE, FALLBACK, CANCELLED }
    var active = false; private set
    private var requestedAt = 0L
    private var fallbackSent = false

    fun request(now: Long) {
        requestedAt = now
        active = true
        fallbackSent = false
    }

    fun cancel() { active = false }

    fun observe(now: Long, visible: Boolean, relevant: Boolean): Result {
        if (!active) return Result.CANCELLED
        if (visible) {
            active = false
            return Result.VISIBLE
        }
        if (!relevant) {
            cancel()
            return Result.CANCELLED
        }
        if (!fallbackSent && now - requestedAt >= timeoutMs) {
            fallbackSent = true
            return Result.FALLBACK
        }
        return Result.WAITING
    }
}

