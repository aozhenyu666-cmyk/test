package com.zongkong.app.system

import android.content.Context
import android.util.Log
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.boss.Intensity
import com.zongkong.core.hq.Hq
import com.zongkong.core.info.Intake
import com.zongkong.core.initiative.Initiative
import com.zongkong.core.initiative.Initiative.State
import com.zongkong.core.initiative.Signal
import java.time.Instant

/**
 * 主动性的执行层：把 Store 里的状况拼成 [Signal]，交给纯函数 [Initiative.decide] 判断，
 * 决定要发就发通知 / 震一下 / 念出来，并把「今天发了几次、被忽略了几次」记下来。
 *
 * 为什么不自己算时间：判断逻辑全在 core 里，可以单测；这一层只负责取数和副作用。
 * 守护服务每 20 秒会走到这里（见 [Reminders.check]），所以判断是连续的，不是到点才想。
 */
object InitiativeRunner {
    private const val TAG = "Initiative"
    private const val PREFS = "initiative"

    /** 发出去以后等这么久还没得到回应，就记一次“被忽略”。 */
    private const val ACK_WINDOW_MS = 30 * 60_000L

    fun check(context: Context) {
        try {
            evaluate(context)
        } catch (e: Exception) {
            Log.w(TAG, "主动性检查出错", e)
        }
    }

    private fun evaluate(context: Context) {
        val app = context.zk
        val store = app.store
        val now = System.currentTimeMillis()
        val today = DayClock.date(now, store.zone).toString()
        val st = load(context, today)
        val kept = st.settle(store.lastContact, now, ACK_WINDOW_MS)
        val push = Initiative.decide(store.config.value.initiative, signal(context, now, today, kept))
        if (push == null) {
            if (kept != st) save(context, kept)
            return
        }
        // 真的发出去才记一次
        send(context, push.title, push.text, push.firm)
        save(context, kept.pushed(now))
        store.logWake("主动·${push.kind.title}（${push.reason}）")
    }

    private fun signal(context: Context, now: Long, today: String, st: State): Signal {
        val app = context.zk
        val store = app.store
        val cfg = store.config.value
        val zone = store.zone
        val minute = Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
        val sameDay = st.date == today
        return Signal(
            now = now,
            minute = minute,
            activeStart = cfg.activeStart,
            activeEnd = cfg.activeEnd,
            quiet = Hq.quiet(cfg.hq, now, zone),
            paused = cfg.pausedUntil > now,
            hqActive = store.hq.value.active != null,
            focusing = store.focusLock(now) != null,
            lastContactAt = store.lastContact,
            lastPushAt = if (sameDay) st.lastAt else 0,
            pushCountToday = if (sameDay) st.count else 0,
            ignoredStreak = st.streak,
            backlog = Intake.stats(store.work.value, store.intake.value, now, zone).backlog,
            intensity = Intensity.level(cfg.boss, store.stance(now)),
        )
    }

    /** 一条通知 +（按设置）念出来；催过还没回应的，语气更硬，并让参谋真的开口。 */
    private fun send(context: Context, title: String, text: String, firm: Boolean) {
        Notifications.alert(context, title, text, "capture")
        Buzz.once(context, if (firm) Buzz.Level.FIRM else Buzz.Level.GENTLE)
        val app = context.zk
        val cfg = app.store.config.value
        val now = System.currentTimeMillis()
        if (cfg.voice.speakProactive && !Hq.quiet(cfg.hq, now, app.store.zone)) {
            Voice.speak(context, text)
        }
        // 催过一轮还没回应：不再只是弹通知，让参谋用对话把人拉回来
        if (firm && cfg.ai.ready) {
            app.chat.proactive(
                "capture",
                "[主动·收集] 你刚提醒他记一条，他没回。用一句话问一个具体的问题：刚才这段时间他在做什么、手上卡在哪，" +
                    "告诉他记一句就够（别超过 20 字），最后给【选项】记一条｜等会儿｜今天不收了。不要改任何东西。",
                fallback = text,
            )
        }
    }

    /** 下一次该叫醒手机的时刻；没有就返回 null。 */
    fun nextAlarm(context: Context, now: Long): Long? {
        return try {
            val store = context.zk.store
            val today = DayClock.date(now, store.zone).toString()
            val st = load(context, today)
            Initiative.nextAlarmAt(store.config.value.initiative, signal(context, now, today, st))
        } catch (e: Exception) {
            Log.w(TAG, "算下一次主动性失败", e)
            null
        }
    }

    // ---------- 计数（单独一个 prefs 文件，不碰 Store 的存法） ----------

    private fun load(context: Context, today: String): State {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(
            date = p.getString("date", "") ?: "",
            lastAt = p.getLong("last_at", 0),
            count = p.getInt("count", 0),
            streak = p.getInt("streak", 0),
            pendingAt = p.getLong("pending_at", 0),
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
