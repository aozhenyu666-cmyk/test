package com.yishou.app.initiative

import android.content.Context
import android.util.Log
import com.yishou.app.MainActivity
import com.yishou.app.YishouApp
import com.yishou.app.initiative.Initiative.State
import com.yishou.app.system.Notifications
import com.yishou.app.system.Reminders
import com.yishou.app.window.WindowClock
import java.time.ZoneId

/**
 * 一手的主动性执行层：取数 → [Initiative.decide] → 发通知 / 记次数。
 * 判断全在 pure core 里，这里只做 Android 的脏活。
 */
object InitiativeRunner {
    private const val TAG = "Initiative"
    private const val PREFS = "initiative"
    private const val ACK_WINDOW_MS = 45 * 60_000L

    /** 发出去的这一路（以后要加“窗口开场邀请”之类，在这里加）。
     *  目前只有一路：叫他走一手。 */
    private const val ID = 5

    /** 由闹钟调用（已经在一个 IO 协程里）。 */
    suspend fun check(context: Context) {
        try {
            evaluate(context)
        } catch (e: Exception) {
            Log.w(TAG, "主动性检查出错", e)
        }
    }

    private suspend fun evaluate(context: Context) {
        val app = context.applicationContext as YishouApp
        val p = app.settings.app.value
        val dao = app.database.dao()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val local = java.time.Instant.ofEpochMilli(now).atZone(zone)
        val today = WindowClock.today(now, zone).toString()
        val st = load(context, today).settle(dao.lastRound()?.createdAt ?: 0, now, ACK_WINDOW_MS)
        val dayStart = WindowClock.startOfDay(WindowClock.today(now, zone), zone)
        val rounds = dao.roundsBetween(dayStart, now + 1).size
        val task = dao.getCurrentTask()
        val stuck = task?.let { dao.getBreakpoint(it.id) }?.stuck.orEmpty()
        val cfg = InitiativeConfig(
            enabled = p.proactiveEnabled,
            gapMinutes = p.proactiveGapMinutes,
        )
        val sig = Signal(
            now = now,
            hour = local.hour,
            minute = local.minute,
            roundsToday = rounds,
            goal = (p.baseSets * p.setSize).coerceAtLeast(1),
            lastRoundAt = dao.lastRound()?.createdAt ?: 0,
            lastPushAt = if (st.date == today) st.lastAt else 0,
            pushCountToday = if (st.date == today) st.count else 0,
            ignoredStreak = st.streak,
            windowActive = WindowClock.active(now, p, zone) != null,
            windowStoppedAt = p.windowStoppedAt,
            stuck = stuck,
        )
        val push = Initiative.decide(cfg, sig)
        if (push == null) {
            if (st != load(context, today)) save(context, st)
            return
        }
        Notifications.showReminder(context, ID, push.title, push.text, MainActivity::class.java)
        save(context, st.pushed(now))
        Reminders.scheduleInitiative(context)
        Log.i(TAG, "主动叫一手：${push.reason}")
    }

    private fun load(context: Context, today: String): State {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(
            date = sp.getString("date", "") ?: "",
            lastAt = sp.getLong("last_at", 0),
            count = sp.getInt("count", 0),
            streak = sp.getInt("streak", 0),
            pendingAt = sp.getLong("pending_at", 0),
        ).onDay(today)
    }

    private fun save(context: Context, st: State) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("date", st.date)
            .putLong("last_at", st.lastAt)
            .putInt("count", st.count)
            .putInt("streak", st.streak)
            .putLong("pending_at", st.pendingAt)
            .apply()
    }
}
