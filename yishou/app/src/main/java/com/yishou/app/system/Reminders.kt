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
 * 防止“先停了就好几天不回来”的四个提醒：
 * - 白天一手停了几个小时没人应（默认 3 小时），通知叫你回来，写上那一手；
 * - 每天检查：到点时当天还没应过一手，就提醒，并写明隔了多久、上次卡在哪；
 * - 定时窗口开始 10 分钟还没走第一手，再提醒一次；
 * - 陪练窗口里“先停”选的休息时间到了，叫你回来。
 */
object Reminders {
    private const val ACTION = "action"
    private const val NUDGE = "nudge"
    private const val WINDOW_CHECK = "window_check"
    private const val PAUSE_END = "pause_end"
    private const val IDLE = "idle"
    /** 只在白天叫：8 点到 23 点 */
    private const val DAY_START = 8
    private const val DAY_END = 23
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

    /**
     * 安排下一次“停太久”检查：从最后一轮（没有就从现在）算起 idleHours 小时后；
     * 落在夜里就推到早上 8 点。检查时如果中间应过一手，就按新的最后一轮重新安排，自己续上。
     */
    fun scheduleIdle(context: Context, lastActivity: Long? = null) {
        val app = context.applicationContext as YishouApp
        val hours = app.settings.app.value.idleHours
        val pi = pending(context, IDLE, 13)
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pi)
        if (hours <= 0) return
        val now = System.currentTimeMillis()
        val due = maxOf((lastActivity ?: now) + hours * 3_600_000L, now + 60_000L)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, daytime(due, ZoneId.systemDefault()), pi)
    }

    /** t 落在夜里时推到下一个早上 8 点 */
    private fun daytime(t: Long, zone: ZoneId): Long {
        val z = java.time.Instant.ofEpochMilli(t).atZone(zone)
        return when {
            z.hour < DAY_START -> z.toLocalDate().atTime(DAY_START, 0).atZone(zone).toInstant().toEpochMilli()
            z.hour >= DAY_END -> z.toLocalDate().plusDays(1).atTime(DAY_START, 0).atZone(zone).toInstant().toEpochMilli()
            else -> t
        }
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
            IDLE -> {
                val hours = app.settings.app.value.idleHours
                if (hours <= 0) return
                val last = dao.lastRound()?.createdAt
                val since = last ?: now
                if (last != null && now - last < hours * 3_600_000L - 60_000L) {
                    // 中间应过一手：按新的最后一轮重新安排
                    scheduleIdle(context, last)
                    return
                }
                scheduleIdle(context, now)
                if (WindowClock.active(now, app.settings.app.value, zone) != null) return // 窗口里有自己的提醒
                val task = dao.getCurrentTask() ?: return
                val bp = dao.getBreakpoint(task.id)
                val move = bp?.pendingCoachMove?.takeIf { it.isNotBlank() } ?: bp?.nextQuestion?.takeIf { it.isNotBlank() }
                val gap = CoachMessages.formatGap((now - since) / 60_000)
                Notifications.showReminder(
                    context, Notifications.ID_IDLE,
                    if (last != null) "一手已经停了 $gap" else "一手还在等你",
                    move?.let { "上一手还在等你：$it" } ?: "点开，从断点接着走一手。",
                    MainActivity::class.java,
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
