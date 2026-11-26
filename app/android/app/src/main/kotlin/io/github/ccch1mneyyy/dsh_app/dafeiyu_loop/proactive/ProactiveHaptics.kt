package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 物理震动破冰反馈：适配 Android 12+ ([VibratorManager]) 与旧版 [Vibrator]。
 * 全链路 [runCatching] 保护，保证在无马达设备或 JVM 单元测试环境下安全无异常。
 */
object ProactiveHaptics {
    private val PLAN_MODAL_TIMINGS = longArrayOf(0L, 70L, 60L, 110L, 60L, 140L)
    private val PLAN_MODAL_AMPLITUDES = intArrayOf(0, 210, 0, 255, 0, 255)

    private val SUPER_SPARK_TIMINGS = longArrayOf(0L, 45L, 50L, 85L)
    private val SUPER_SPARK_AMPLITUDES = intArrayOf(0, 180, 0, 240)

    private val GENTLE_NUDGE_TIMINGS = longArrayOf(0L, 35L)
    private val GENTLE_NUDGE_AMPLITUDES = intArrayOf(0, 130)

    /** 强制计划模态弹窗震动：三连重脉冲提醒，打破发呆/游离态。 */
    fun vibratePlanModal(ctx: Context?) {
        vibrateWaveform(ctx, PLAN_MODAL_TIMINGS, PLAN_MODAL_AMPLITUDES)
    }

    /** 超级大肥鱼战术浮窗 / Gemini Live 破冰震动：干脆双振提示。 */
    fun vibrateSuperSpark(ctx: Context?) {
        vibrateWaveform(ctx, SUPER_SPARK_TIMINGS, SUPER_SPARK_AMPLITUDES)
    }

    /** 轻柔提示震动：单次短促微振。 */
    fun vibrateGentleNudge(ctx: Context?) {
        vibrateWaveform(ctx, GENTLE_NUDGE_TIMINGS, GENTLE_NUDGE_AMPLITUDES)
    }

    /** 5 分钟声学节拍钟：即使手机息屏放桌上未触碰，也发出清晰双音铃声 + 震动打破超级降频。 */
    fun playHeartbeatChime(ctx: Context?, urgent: Boolean = false) {
        if (ctx == null) return
        vibrateSuperSpark(ctx)
        runCatching {
            val vol = if (urgent) 92 else 72
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, vol)
            val tone = if (urgent) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK
            tg.startTone(tone, 260)
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { tg.release() }
            }, 340L)
        }.onFailure {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return@runCatching
                val ring = RingtoneManager.getRingtone(ctx.applicationContext ?: ctx, uri)
                ring?.play()
            }
        }
    }

    private fun vibrateWaveform(ctx: Context?, timings: LongArray, amplitudes: IntArray) {
        if (ctx == null) return
        runCatching {
            val safeCtx = ctx.applicationContext ?: ctx
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = safeCtx.getSystemService(VibratorManager::class.java)
                val vib = vm?.defaultVibrator
                if (vib != null && vib.hasVibrator()) {
                    val effect = buildEffect(vib, timings, amplitudes)
                    vm.vibrate(CombinedVibration.createParallel(effect))
                    return@runCatching
                }
            }
            val vib = safeCtx.getSystemService(Vibrator::class.java)
                ?: @Suppress("DEPRECATION") (safeCtx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                ?: return@runCatching
            if (!vib.hasVibrator()) return@runCatching
            val effect = buildEffect(vib, timings, amplitudes)
            vib.vibrate(effect)
        }
    }

    private fun buildEffect(vib: Vibrator, timings: LongArray, amplitudes: IntArray): VibrationEffect {
        val hasAmp = runCatching { vib.hasAmplitudeControl() }.getOrDefault(false)
        return if (hasAmp && timings.size == amplitudes.size) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
    }
}
