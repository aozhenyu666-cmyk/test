package com.yishou.app.voice

import java.util.concurrent.atomic.AtomicLong

/**
 * 轮次号：每开始新的一轮（或被打断）就加一。晚到的旧结果拿着旧号码，一律丢掉——
 * 旧请求晚返回也不能让已经打断的声音再响起来。
 */
class TurnGuard(private val sessionId: Long) {
    private val turn = AtomicLong(0)

    fun next(): RequestTag = RequestTag(sessionId, turn.incrementAndGet())

    fun current(): RequestTag = RequestTag(sessionId, turn.get())

    fun isCurrent(tag: RequestTag): Boolean = tag.sessionId == sessionId && tag.turnId == turn.get()

    /** 打断 / 停：让手上所有在路上的结果都过期 */
    fun invalidate() {
        turn.incrementAndGet()
    }
}
