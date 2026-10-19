package com.zongkong.app.system

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.zongkong.app.zk
import com.zongkong.core.hq.Hq

/**
 * 震动：总控的杀手锏。锁屏了可以不看，震动没法完全忽略。
 *
 *  - 轻（两下短的）：提醒到点
 *  - 重（三下长的）：追问、超时、该进总控时间了
 *  - 连续：每 20 秒重震一次，直到你打开总控（最多 [MAX_MINUTES] 分钟）。总控时间到点不来、失联太久时用
 *
 * 按闹钟的级别震（静音模式下也会震）。睡觉时段（默认 23:30–7:00）只轻震，不连续震。
 */
object Buzz {
    enum class Level { GENTLE, FIRM }

    const val MAX_MINUTES = 10
    private const val TAG = "Buzz"

    private val GENTLE = longArrayOf(0, 120, 120, 120)
    private val FIRM = longArrayOf(0, 600, 250, 600, 250, 900)

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    fun once(context: Context, level: Level) {
        val app = context.zk
        val cfg = app.store.config.value.hq
        if (!cfg.vibrate) return
        val quiet = Hq.quiet(cfg, System.currentTimeMillis(), app.store.zone)
        val pattern = if (level == Level.FIRM && !quiet) FIRM else GENTLE
        try {
            val v = vibrator(context) ?: return
            if (!v.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(pattern, -1)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        } catch (e: Exception) {
            Log.w(TAG, "震动失败", e)
        }
    }

    /** 开始连续震动（守护服务每 20 秒震一次），直到打开总控或到时。 */
    fun relentless(context: Context, reason: String, minutes: Int = MAX_MINUTES) {
        val app = context.zk
        val cfg = app.store.config.value.hq
        if (!cfg.vibrate) return
        if (Hq.quiet(cfg, System.currentTimeMillis(), app.store.zone)) {
            once(context, Level.GENTLE)
            return
        }
        app.store.buzzUntil = System.currentTimeMillis() + minutes * 60_000L
        app.store.buzzReason = reason
        once(context, Level.FIRM)
    }

    /** 守护服务每次检查时调用：连续震动期间就震一下。 */
    fun tick(context: Context) {
        val app = context.zk
        val until = app.store.buzzUntil
        if (until == 0L) return
        if (System.currentTimeMillis() > until) {
            stop(context)
            return
        }
        once(context, Level.FIRM)
    }

    fun stop(context: Context) {
        context.zk.store.buzzUntil = 0
        context.zk.store.buzzReason = ""
    }
}
