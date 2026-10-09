package com.yishou.app.window

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.yishou.app.YishouApp
import com.yishou.app.summary.SummaryScheduler
import com.yishou.app.system.Notifications
import com.yishou.app.system.Reminders
import java.time.ZoneId

/** 用 AlarmManager 安排每天的“陪练窗口开始”提醒。 */
object WindowScheduler {
    private const val TAG = "WindowScheduler"

    /** 按当前设置重新安排下一次提醒；定时窗口关闭时取消。 */
    fun reschedule(context: Context) {
        val app = context.applicationContext as YishouApp
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        am.cancel(pi)
        val next = WindowClock.nextScheduledStart(System.currentTimeMillis(), app.settings.app.value, ZoneId.systemDefault())
            ?: return
        try {
            if (canExact(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } else {
                // 没有精确闹钟权限时退而求其次，系统可能推迟几分钟；权限页会提示开启
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "安排精确闹钟被拒绝，改用普通闹钟", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    fun canExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WindowAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** 到点：发出“陪练窗口开始”通知，并安排明天的提醒。 */
class WindowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as YishouApp
        val prefs = app.settings.app.value
        if (prefs.windowEnabled) {
            Notifications.showWindowStart(context, prefs.windowMinutes)
            Reminders.scheduleWindowCheck(context, System.currentTimeMillis() + Reminders.WINDOW_CHECK_MINUTES * 60_000L)
        }
        WindowScheduler.reschedule(context)
    }
}

/** 开机、更新应用、改系统时间或时区之后，重新安排两个定时。 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> {
                WindowScheduler.reschedule(context)
                Reminders.scheduleNudge(context)
                SummaryScheduler.schedule(context, replace = intent.action != Intent.ACTION_MY_PACKAGE_REPLACED)
            }
        }
    }
}
