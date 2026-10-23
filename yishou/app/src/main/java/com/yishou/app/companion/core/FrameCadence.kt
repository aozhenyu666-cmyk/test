package com.yishou.app.companion.core

/** 这一帧要不要留下 */
enum class FrameDecision(val label: String) {
    KEEP("留下"),
    SAME_AS_LAST("和上一帧一样"),
    TOO_SOON("太密了"),
    TOO_MANY("这一分钟够了"),
}

/**
 * 关键帧闸门：动画、滚动字幕、鼠标光标每帧都在变，不能一变就调一次模型。
 * 三道闸：跟上一张留下的一模一样就丢、间隔太短就丢、这个窗口里够了就丢。
 * 指纹由调用方算（Android 那侧用采样点的像素），这里只按数字比较。
 */
class FrameCadence(
    private val minIntervalMs: Long = 1_000,
    private val windowMs: Long = 60_000,
    private val maxPerWindow: Int = 45,
) {

    private var lastFingerprint: Long? = null
    private var lastKeptAt: Long? = null
    private val keptAt = ArrayDeque<Long>()

    fun offer(fingerprint: Long, atMs: Long): FrameDecision {
        // 时钟倒退（换设备时间、SystemClock 重置）：旧窗口作废，别把能留的帧卡死
        val last = lastKeptAt
        if (last != null && atMs < last) keptAt.clear()

        if (fingerprint == lastFingerprint) return FrameDecision.SAME_AS_LAST

        val prev = lastKeptAt
        if (prev != null && atMs >= prev && atMs - prev < minIntervalMs) return FrameDecision.TOO_SOON

        while (keptAt.isNotEmpty() && atMs - keptAt.first() >= windowMs) keptAt.removeFirst()
        if (keptAt.size >= maxPerWindow) return FrameDecision.TOO_MANY

        lastFingerprint = fingerprint
        lastKeptAt = atMs
        keptAt.addLast(atMs)
        return FrameDecision.KEEP
    }

    fun keptCount(): Int = keptAt.size
}
