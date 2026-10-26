package com.yishou.app.system

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.yishou.app.YishouApp

/**
 * 震动的说法：手机揣在身上、声音关着时，靠震法就能分清是什么事。
 * - 新的一手：轻点两下
 * - 提醒：三下，一下比一下重（涟漪）
 * - 拉回：长、短、长，来两遍
 * - 这手过了：干脆的一下
 * - 再改一下：短促两下
 * - 热身呼吸：吸气时慢慢变强，呼气时慢慢变弱
 */
object Haptics {
    enum class Kind { NEW_MOVE, REMIND, PULL, PASS, REDO, INHALE, EXHALE }

    fun play(context: Context, kind: Kind) {
        val app = context.applicationContext as YishouApp
        if (!app.settings.app.value.haptics) return
        val v = vibrator(app) ?: return
        if (!v.hasVibrator()) return
        val (timings, amps) = pattern(kind)
        try {
            v.vibrate(
                if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, -1)
                else VibrationEffect.createWaveform(timings, -1),
            )
        } catch (e: Exception) {
            // 没有震动权限或系统拒绝，就不震
        }
    }

    /** 时长（毫秒，先停后震交替）和对应强度 0–255 */
    fun pattern(kind: Kind): Pair<LongArray, IntArray> = when (kind) {
        Kind.NEW_MOVE -> longArrayOf(0, 40, 90, 40) to intArrayOf(0, 120, 0, 120)
        Kind.REMIND -> longArrayOf(0, 60, 140, 80, 140, 120) to intArrayOf(0, 70, 0, 150, 0, 255)
        Kind.PULL -> longArrayOf(0, 400, 150, 120, 150, 400, 500, 400, 150, 120, 150, 400) to
            intArrayOf(0, 255, 0, 200, 0, 255, 0, 255, 0, 200, 0, 255)
        Kind.PASS -> longArrayOf(0, 35) to intArrayOf(0, 200)
        Kind.REDO -> longArrayOf(0, 30, 70, 30) to intArrayOf(0, 160, 0, 160)
        Kind.INHALE -> ramp(4_000, rising = true)
        Kind.EXHALE -> ramp(6_000, rising = false)
    }

    private fun ramp(total: Long, rising: Boolean): Pair<LongArray, IntArray> {
        val steps = 10
        val t = LongArray(steps) { total / steps }
        val a = IntArray(steps) { i ->
            val f = (i + 1f) / steps
            (12 + 60 * (if (rising) f else 1f - f + 0.1f)).toInt().coerceIn(1, 255)
        }
        return t to a
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
