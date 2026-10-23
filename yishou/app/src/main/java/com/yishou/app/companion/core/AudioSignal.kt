package com.yishou.app.companion.core

/** PCM 16 位单声道的几个小工具；探针要判断「到底有没有声音」 */
object Pcm {

    /** 均方根。全是静音时是 0；正常说话通常几百到几千。 */
    fun rmsOf(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / count)
    }

    fun peakOf(samples: ShortArray, count: Int = samples.size): Int {
        var peak = 0
        for (i in 0 until count) {
            val v = kotlin.math.abs(samples[i].toInt())
            if (v > peak) peak = v
        }
        return peak
    }

    /** 一帧音频占多少毫秒 */
    fun durationMs(count: Int, sampleRate: Int): Long =
        if (sampleRate <= 0) 0 else count * 1_000L / sampleRate
}

/**
 * 内录有没有真的收到声音。
 *
 * 系统允许采集、AudioRecord 也初始化成功，但目标 App 可能正在静音、或者当前没有播放内容。
 * 这两种情况都表现为「读到全是 0」，不能据此说这个 App 不支持内录——所以单独记「没有有效声音」。
 */
class AudioSignal(
    private val threshold: Double = 200.0,
    private val minActiveMs: Long = 400,
) {

    var maxRms: Double = 0.0
        private set

    var activeMs: Long = 0L
        private set

    var totalMs: Long = 0L
        private set

    fun feed(rms: Double, ms: Long) {
        totalMs += ms
        if (rms > maxRms) maxRms = rms
        if (rms >= threshold) activeMs += ms
    }

    /** 到底有没有听到人声/音乐 */
    fun heard(): Boolean = activeMs >= minActiveMs

    /** 给界面看的一句话，不冒充结论 */
    fun summary(): String = when {
        totalMs <= 0 -> "还没开始读音频"
        heard() -> "收到声音 ${activeMs / 1_000.0} 秒，峰值 ${maxRms.toInt()}"
        else -> "读到 ${totalMs / 1_000.0} 秒静音（峰值 ${maxRms.toInt()}）"
    }
}
