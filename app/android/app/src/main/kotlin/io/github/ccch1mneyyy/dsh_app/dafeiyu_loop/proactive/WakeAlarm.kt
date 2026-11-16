package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.MainActivity
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.WakeMode

/**
 * 大肥鱼时钟内核原生闹钟桥（双槽 Doze 穿透自续调度）：
 * - Slot 0 (`PROACTIVE_WAKE`)：内核小顶堆最早唤醒点（走 `setExactAndAllowWhileIdle`）；
 * - Slot 1 (`CLOCK_URGENT`)：高优/强闹钟任务（若用户授权精确闹钟，走 `setAlarmClock` 穿透省电白名单）；
 * 每次触发都会带短 WakeLock 执行 `ProactiveRuntime.onAlarmWake`，并在当场立刻自续重排下一个闹钟。
 */
object WakeAlarm {
    const val ACTION = "io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.PROACTIVE_WAKE"
    const val ACTION_URGENT = "io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.CLOCK_URGENT"
    private const val SLOT_MAIN = 0
    private const val SLOT_URGENT = 1

    @Volatile var lastError: String? = null
        private set

    fun set(context: Context, atWall: Long?, mode: WakeMode, urgentAtWall: Long? = null) {
        scheduleSlot(context, SLOT_MAIN, ACTION, atWall, mode, useAlarmClock = false)
        scheduleSlot(context, SLOT_URGENT, ACTION_URGENT, urgentAtWall, mode, useAlarmClock = true)
    }

    private fun scheduleSlot(
        context: Context,
        slot: Int,
        action: String,
        atWall: Long?,
        mode: WakeMode,
        useAlarmClock: Boolean,
    ) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, slot, action)
        am.cancel(pi)
        if (atWall == null || atWall <= 0L || mode == WakeMode.OPPORTUNISTIC) return
        lastError = try {
            if (useAlarmClock && mode == WakeMode.EXACT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    100 + slot,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(atWall, showIntent), pi)
            } else if (mode == WakeMode.EXACT) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, pi)
            }
            null
        } catch (t: Throwable) {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, pi) }
            "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun pending(context: Context, slot: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        slot,
        Intent(context, ProactiveWakeReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Alarm, boot, clock jump and app update receiver.
 * Holds a short partial WakeLock so Doze wakes run the clock kernel tick and reschedule cleanly.
 */
class ProactiveWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val valid = action == WakeAlarm.ACTION ||
            action == WakeAlarm.ACTION_URGENT ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        if (!valid) return
        val pm = context.getSystemService(PowerManager::class.java)
        val wl = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh-app:clock-wake")?.apply {
                setReferenceCounted(false)
                acquire(3_000L)
            }
        }.getOrNull()
        try {
            ProactiveRuntime.ensure(context)
            when (action) {
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                    ProactiveRuntime.onSystemClockJump(action.substringAfterLast('.'))
                else -> ProactiveRuntime.tick(action.substringAfterLast('.'))
            }
        } catch (t: Throwable) {
            Log.w("DshProactive", "wake", t)
        } finally {
            runCatching { if (wl?.isHeld == true) wl.release() }
        }
    }
}
