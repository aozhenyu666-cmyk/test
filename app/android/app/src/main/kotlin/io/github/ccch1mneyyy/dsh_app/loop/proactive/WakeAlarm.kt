package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.loop.core.WakeMode

/**
 * The scheduler's single alarm. An alarm is only a reminder to evaluate again; the decision is
 * always made from the persisted state, so a late, lost or duplicate alarm cannot cause a wrong one.
 */
object WakeAlarm {
    const val ACTION = "io.github.ccch1mneyyy.dsh_app.loop.PROACTIVE_WAKE"
    @Volatile var lastError: String? = null
        private set

    fun set(context: Context, atWall: Long?, mode: WakeMode) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context)
        am.cancel(pi)
        if (atWall == null || mode == WakeMode.OPPORTUNISTIC) return
        lastError = try {
            if (mode == WakeMode.EXACT) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, pi)
            null
        } catch (t: Throwable) {
            // e.g. SecurityException on a ROM that withdrew exact alarms: degrade, never crash.
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, pi) }
            "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ProactiveWakeReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Alarm, boot and app update. After a force stop Android delivers none of these until DSH is
 * opened again; the settings page says so instead of promising background recovery.
 */
class ProactiveWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != WakeAlarm.ACTION && action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            ProactiveRuntime.ensure(context)
            ProactiveRuntime.tick(action.substringAfterLast('.'))
        }.onFailure { Log.w("DshProactive", "wake", it) }
    }
}
