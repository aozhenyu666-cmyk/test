package cn.companion.pet.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import cn.companion.pet.PetApp
import cn.companion.pet.core.rhythm.SpeakScheduler
import cn.companion.pet.data.Settings
import java.time.ZoneId

/**
 * 锁屏心跳（“防跑马”模式，默认关）：熄屏期间每 N 分钟一次温和震动，不发声、不亮屏。
 * 用户亮屏后，桌宠问一句刚才在想什么；忽略不升级。
 *
 * 用 setAndAllowWhileIdle：Doze 下系统可能推迟几分钟，这是可以接受的误差。
 */
object Heartbeat {
    private const val PREFS = "heartbeat"
    private const val KEY_PENDING = "pending"

    fun schedule(context: Context, settings: Settings) {
        if (!settings.heartbeatEnabled) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + settings.heartbeatIntervalMin.coerceAtLeast(5) * 60_000L
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
    }

    /** 亮屏时取出“熄屏期间敲过”的标记，并清掉。 */
    fun consumePending(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = p.getBoolean(KEY_PENDING, false)
        if (v) p.edit().putBoolean(KEY_PENDING, false).apply()
        return v
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1, Intent(context, HeartbeatReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    class HeartbeatReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val settings = (context.applicationContext as PetApp).settings
            if (!settings.heartbeatEnabled || settings.userStopped) return
            val power = context.getSystemService(PowerManager::class.java)
            if (power.isInteractive) return
            val quiet = SpeakScheduler.inQuietHours(
                System.currentTimeMillis(), settings.quietStartMin, settings.quietEndMin, ZoneId.systemDefault(),
            )
            if (!quiet) {
                Haptics(context).heartbeat()
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PENDING, true).apply()
            }
            schedule(context, settings)
        }
    }
}
