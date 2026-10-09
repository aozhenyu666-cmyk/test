package com.zongkong.app.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.zongkong.app.zk
import com.zongkong.core.work.Event
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.WorkOps

/**
 * 行动的到点提醒。
 *  - 主力：无障碍服务每 20 秒检查一次（[check]）。
 *  - 兜底：系统闹钟定在下一个提醒时刻，服务没在跑时也能响。
 * 每条提醒用 key 去重，只发一次。
 */
object Reminders {
    private const val TAG = "Reminders"
    const val ACTION_ALARM = "com.zongkong.app.REMINDER_ALARM"
    const val ACTION_SNOOZE = "com.zongkong.app.REMINDER_SNOOZE"
    const val EXTRA_KEY = "action_key"

    /** 发出现在该发的提醒。 */
    fun check(context: Context) {
        val app = context.zk
        val now = System.currentTimeMillis()
        val work = app.store.work.value
        for (r in Focus.dueReminders(work, now, app.store.zone)) {
            if (!app.store.firstAlert(r.key)) continue
            val thread = work.rec(r.action.threadKey)
            Notifications.taskReminder(context, r.action, thread, r.follow)
            app.store.updateWork { WorkOps.event(it, Event(now, if (r.follow) "followup" else "remind", r.action.key)) }
        }
    }

    /** 把系统闹钟定到下一个提醒时刻。 */
    fun reschedule(context: Context) {
        try {
            val app = context.zk
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context, 7, Intent(context, ReminderReceiver::class.java).setAction(ACTION_ALARM),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.cancel(pi)
            val next = Focus.alarmTimes(app.store.work.value, System.currentTimeMillis(), app.store.zone).firstOrNull() ?: return
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 1_000, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 1_000, pi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "定闹钟失败", e)
        }
    }

    fun snoozeIntent(context: Context, actionKey: String, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id, Intent(context, ReminderReceiver::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_KEY, actionKey),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** 闹钟到点、点了“推迟”、开机后，都走这里。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Reminders.ACTION_SNOOZE -> {
                val key = intent.getStringExtra(Reminders.EXTRA_KEY) ?: return
                context.zk.actions.work.snooze(key, 30)
                Notifications.cancelTask(context, key)
                context.zk.store.updateWork { WorkOps.event(it, Event(System.currentTimeMillis(), "snooze", key)) }
            }
            else -> Reminders.check(context)
        }
        Reminders.reschedule(context)
    }
}

/** 开机、更新后重新定闹钟。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.reschedule(context)
    }
}

/** 提醒里显示的一句话。 */
fun reminderText(a: com.zongkong.core.work.Rec, follow: Boolean): String = when {
    follow -> "到点 ${Focus.FOLLOW_MINUTES} 分钟了还没开始。是忘了、不会做，还是有别的事？点“卡住了”说一下。"
    a[F.CRITERIA].isNotBlank() -> "做到：${a[F.CRITERIA]}"
    else -> "该开始了。"
}
