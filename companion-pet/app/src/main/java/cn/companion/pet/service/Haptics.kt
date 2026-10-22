package cn.companion.pet.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 自定义震动节奏。比系统默认的“嗡”一下更像轻敲。 */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    /** 开口时：两下很轻的敲。 */
    fun tap() = play(longArrayOf(0, 18, 90, 18), intArrayOf(0, 90, 0, 70))

    /** 被忽略第二级：一次短促但明显的震动。 */
    fun nudge() = play(longArrayOf(0, 40, 80, 60), intArrayOf(0, 180, 0, 140))

    /** 锁屏心跳：像心跳一样的两下，很温和。 */
    fun heartbeat() = play(longArrayOf(0, 30, 120, 45), intArrayOf(0, 70, 0, 100))

    private fun play(timings: LongArray, amps: IntArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val effect = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, -1)
            else VibrationEffect.createWaveform(timings, -1)
            v.vibrate(effect)
        }
    }
}
