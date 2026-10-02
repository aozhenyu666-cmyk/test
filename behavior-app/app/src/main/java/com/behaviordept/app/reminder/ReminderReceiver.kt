package com.behaviordept.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.behaviordept.app.container
import com.behaviordept.app.data.EventType
import com.behaviordept.app.today.computeNextAction
import com.behaviordept.app.today.description
import com.behaviordept.app.today.headline
import com.behaviordept.app.util.Time
import kotlinx.coroutines.launch

/** 每日提醒到点：通知里直接写出今日一件事，然后排下一天。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DAILY) return
        val pending = goAsync()
        val c = context.container
        c.appScope.launch {
            try {
                val next = computeNextAction(c.study.allUnits(), Time.now())
                Notifications.showDaily(context, "今日一件事：${next.headline()}", next.description() + "。点开，按开始。")
                c.events.log(EventType.REMINDER_SENT, note = next.headline())
                ReminderScheduler.reschedule(context, c.settings.current())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DAILY = "com.behaviordept.app.DAILY_REMINDER"
    }
}

/** 开机、App 更新、精确闹钟权限变化后，重新排每日提醒。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val c = context.container
        c.appScope.launch {
            try {
                ReminderScheduler.reschedule(context, c.settings.current())
            } finally {
                pending.finish()
            }
        }
    }
}
