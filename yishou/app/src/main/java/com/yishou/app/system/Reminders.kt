package com.yishou.app.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yishou.app.MainActivity
import com.yishou.app.YishouApp
import com.yishou.app.data.RoundSource
import com.yishou.app.llm.CoachMessages
import com.yishou.app.window.WindowActivity
import com.yishou.app.window.WindowClock
import com.yishou.app.window.WindowScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 防止“先停了就好几天不回来”的三个提醒：
 * - 每天检查：到点时当天还没应过一手，就提醒，并写明隔了多久、上次卡在哪；
 * - 定时窗口开始 10 分钟还没走第一手，再提醒一次；
 * - 陪练窗口里“先停”选的休息时间到了，叫你回来。
 */
object Reminders {
    private const val ACTION = "action"
    private const val NUDGE = "nudge"
    private const val WINDOW_CHECK = "window_check"
    private const val PAUSE_END = "pause_end"
    const val WINDOW_CHECK_MINUTES = 10

    fun scheduleNudge(context: Context) {
        val app = context.applicationContext as YishouApp
        val p = app.settings.app.value
        val pi = pending(context, NUDGE, 10)
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pi)
        if (!p.nudgeEnabled) return
        set(am, WindowClock.nextDaily(System.currentTimeMillis(), p.nudgeHour, p.nudgeMinute, ZoneId.systemDefault()), pi)
    }

    fun scheduleWindowCheck(context: Context, at: Long) {
        set(context.getSystemService(AlarmManager::class.java), at, pending(context, WINDOW_CHECK, 11))
    }

    fun schedulePauseEnd(context: Context, at: Long) {
        set(context.getSystemService(AlarmManager::class.java), at, pending(context, PAUSE_END, 12))
    }

    fun cancelPauseEnd(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, PAUSE_END, 12))
    }

    private fun set(am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            if (WindowScheduler.canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pending(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        code,
        Intent(context, ReminderReceiver::class.java).putExtra(ACTION, action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal suspend fun handle(context: Context, action: String?) {
        val app = context.applicationContext as YishouApp
        val dao = app.database.dao()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        when (action) {
            NUDGE -> {
                scheduleNudge(context)
                val todayStart = WindowClock.startOfDay(WindowClock.today(now, zone), zone)
                if (dao.roundsBetween(todayStart, now + 1).isNotEmpty()) return
                val last = dao.lastRound()
                val stuck = dao.getCurrentTask()?.let { dao.getBreakpoint(it.id) }?.stuck?.takeIf { it.isNotBlank() }
                val gap = last?.let { CoachMessages.formatGap((now - it.createdAt) / 60_000) }
                val text = buildString {
                    append(if (gap != null) "距离上一手已经 $gap。" else "还没有走过一手。")
                    if (stuck != null) append("上次卡在：$stuck")
                }
                Notifications.showReminder(context, Notifications.ID_NUDGE, "今天还没应一手", text, MainActivity::class.java)
            }
            WINDOW_CHECK -> {
                val span = WindowClock.active(now, app.settings.app.value, zone) ?: return
                if (dao.countRounds(RoundSource.WINDOW, span.start, now + 1) > 0) return
                Notifications.showReminder(
                    context, Notifications.ID_WINDOW,
                    "陪练窗口已经开始 $WINDOW_CHECK_MINUTES 分钟",
                    "还没走第一手。现在点开，从断点接着来。",
                    WindowActivity::class.java,
                )
            }
            PAUSE_END -> {
                if (WindowClock.active(now, app.settings.app.value, zone) == null) return
                Notifications.showReminder(
                    context, Notifications.ID_WINDOW,
                    "休息时间到",
                    "回到陪练，接着走下一手。",
                    WindowActivity::class.java,
                )
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminders.handle(context, intent.getStringExtra("action"))
            } finally {
                pending.finish()
            }
        }
    }
}
