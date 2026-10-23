package com.yishou.app.companion.core

/** 这一帧要不要留 */
enum class FramePick(val label: String) {
    PICK("留下"),
    NO_CHANGE("没什么变化"),
    TOO_SOON("太密了"),
    TOO_MANY("这一分钟够了"),
}

/**
 * 关键帧筛选：在 T1 的三道闸（重复 / 太密 / 这一分钟够了）前面加一道「变化够不够大」。
 *
 * 为什么要「变化大」：字幕滚动、光标闪、视频里飘过的粒子特效，画面每帧都在变，
 * 但内容没变。按变化量筛，既不漏掉真正的翻页，也不至于每两秒就把一张图送进模型。
 */
class FrameSelector(
    /** 变化分数低于这个就当没变（分数由采集侧算，0–1） */
    private val minChange: Double = 0.12,
    minIntervalMs: Long = 1_500,
    windowMs: Long = 60_000,
    maxPerWindow: Int = 24,
) {

    private val cadence = FrameCadence(minIntervalMs, windowMs, maxPerWindow)

    /** [changeScore] 是这一帧相对上一帧的变化量，0–1 */
    fun offer(fingerprint: Long, changeScore: Double, atMs: Long): FramePick {
        if (changeScore < minChange) return FramePick.NO_CHANGE
        return when (cadence.offer(fingerprint, atMs)) {
            FrameDecision.KEEP -> FramePick.PICK
            FrameDecision.SAME_AS_LAST -> FramePick.NO_CHANGE
            FrameDecision.TOO_SOON -> FramePick.TOO_SOON
            FrameDecision.TOO_MANY -> FramePick.TOO_MANY
        }
    }

    fun keptCount(): Int = cadence.keptCount()
}

/**
 * 帧间变化量的粗算：把两帧的低分辨率灰度采样比一遍。
 * 只用来判「翻页了没有」，不追求准，追求便宜到可以每帧都算。
 */
object FrameDiff {

    /** 采样点数量：200 个点足够看出翻页，又不至于每帧都在算 */
    const val SAMPLES = 200

    /**
     * @param prev 上一帧采样（长度应等于 [SAMPLES]）
     * @param next 当前帧采样
     * @return 0–1，0 表示一模一样
     */
    fun changeRatio(prev: IntArray, next: IntArray): Double {
        val n = minOf(prev.size, next.size)
        if (n == 0) return 0.0
        var changed = 0
        for (i in 0 until n) {
            if (kotlin.math.abs(prev[i] - next[i]) > DELTA) changed++
        }
        return changed.toDouble() / n
    }

    /** 单点差超过这个灰度值才算变了（压缩噪声会有一点点差） */
    const val DELTA = 18
}
