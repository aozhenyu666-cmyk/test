package com.behaviordept.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.behaviordept.app.data.AppSettings
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 每日提醒：用 AlarmManager 定到具体时刻。有“精确闹钟”权限时准点响，没有就退化为系统允许的近似时间。
 * 每次响过之后由 ReminderReceiver 排下一天。
 */
object ReminderScheduler {
    private const val REQUEST_DAILY = 2001

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_DAILY)
        return PendingIntent.getBroadcast(context, REQUEST_DAILY, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 下一次提醒时刻：今天的提醒时间已过就排到明天。 */
    fun nextTrigger(hour: Int, minute: Int, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val today = now.toLocalDate().atTime(LocalTime.of(hour, minute))
        return if (today.isAfter(now.plusSeconds(5))) today else today.plusDays(1)
    }

    fun reschedule(context: Context, settings: AppSettings) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context)
        am.cancel(pi)
        if (!settings.reminderEnabled) return
        val at = nextTrigger(settings.reminderHour, settings.reminderMinute)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (canExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            // 精确闹钟权限刚被收回：退回近似时间。
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
