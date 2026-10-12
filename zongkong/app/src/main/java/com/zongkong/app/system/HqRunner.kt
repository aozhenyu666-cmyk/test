package com.zongkong.app.system

import android.content.Context
import android.util.Log
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.hq.Hq
import com.zongkong.core.hq.HqTrigger
import com.zongkong.core.plan.Plans
import java.time.Instant

/**
 * 总控主动找你：
 *  - 每日总控时间：提前 10 分钟轻震提醒；到点全屏 + 连续震动；5 分钟还没来，自动进入（其他应用一打开就回总控）
 *  - 参谋定时发消息：早上第一条；午后主线还没开始问一句
 *  - 失联阶梯：一天没交流，参谋主动找你 + 重震；两天严管；三天下次打开别的应用直接进总控时间（在守护服务里）
 *
 * 守护服务每 20 秒调一次，系统闹钟兜底。每件事每天只做一次。
 */
object HqRunner {
    private const val TAG = "HqRunner"

    fun check(context: Context) {
        try {
            run(context)
        } catch (e: Exception) {
            Log.w(TAG, "检查出错", e)
        }
    }

    private fun run(context: Context) {
        val app = context.zk
        val store = app.store
        val config = store.config.value
        val cfg = config.hq
        val now = System.currentTimeMillis()
        val zone = store.zone
        if (config.pausedUntil > now) return

        // 进去了没封存、超时一小时：自动收掉，别把手机锁一整夜
        store.hq.value.active?.let { s ->
            if (now > s.endsAt + 60 * 60_000L) app.actions.hq.exitEarly("超时一小时没封存，自动结束")
        }

        when (Hq.due(cfg, store.hq.value.sessions, now, zone)) {
            Hq.Due.SOON -> if (store.firstAlert("hq-soon")) {
                Notifications.alert(context, "10 分钟后是总控时间", "${DayClock.hhmm(cfg.at)} 开始，${cfg.minutes} 分钟。手上的事收个尾。", "hq")
                Buzz.once(context, Buzz.Level.GENTLE)
            }
            Hq.Due.NOW -> if (store.firstAlert("hq-now")) {
                Notifications.hqCall(context, "总控时间到了", "接下来 ${cfg.minutes} 分钟，把四个部门修一遍。" + if (cfg.autoLock) "5 分钟后自动进入。" else "", fullScreen = true)
                Buzz.relentless(context, "总控时间到了")
                if (cfg.speak) Speaker.speak(context, "总控时间到了。")
            }
            Hq.Due.OVERDUE -> if (cfg.autoLock && store.firstAlert("hq-auto")) {
                app.actions.hq.start(HqTrigger.SCHEDULED)
                Notifications.hqCall(context, "总控时间已经开始", "除了总控和白名单里的应用，其他的打开就回到这里。做完就解锁。", fullScreen = true)
                Buzz.relentless(context, "总控时间已经开始")
            }
            Hq.Due.NONE -> {}
        }

        val minute = Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }

        // 失联阶梯（第三级在守护服务里：打开别的应用直接进总控时间）
        val absence = store.absence(now)
        if (absence >= Hq.Absence.NUDGE && minute >= 10 * 60 && store.firstAlert("absent")) {
            val days = ((now - store.lastContact) / 86_400_000L).toInt()
            app.chat.proactive(
                "absent",
                "[定时·失联] 他已经 $days 天没和你交流了。用一两句话把他拉回来：不责备，不说教，问一个具体的、马上能回答的问题（关于今天的主线或手上的事），最后给【选项】。不要改任何东西。",
                fallback = "已经 $days 天没说话了。今天手上最要紧的是哪件？回一句就行。",
            )
            Buzz.once(context, Buzz.Level.FIRM)
        }

        if (!cfg.proactive) return

        // 早上第一条
        if (minute in cfg.morningAt until cfg.morningAt + 180 && store.firstAlert("morning")) {
            app.chat.proactive(
                "morning",
                "[定时·早上] 早上了，给他发今天的第一条消息：今天的主线是什么（没定就问他要不要现在挑一个，给【选项】），再提醒一个他最可能卡住的地方（看计划里的预演）。最多三句。不要改任何东西。",
                fallback = "早上好。今天的主线定了吗？没定的话，打开总控挑一件。",
            )
        }

        // 午后：主线还没开始
        val plan = Plans.active(store.plans.value, now, zone)
        val main = Plans.mainAction(store.plans.value, store.work.value, now, zone)
        if (plan != null && main != null && minute in 13 * 60 + 30 until 17 * 60 && !Plans.isRestDay(plan, now, zone) &&
            Plans.progress(plan, store.work.value, now, zone).todayPlanMinutes == 0 && store.firstAlert("noon-main")
        ) {
            app.chat.proactive(
                "noon",
                "[定时·午后] 下午了，今天的主线「${main.title}」还一分钟没动。问他卡在哪，给【选项】：现在开始｜拆成 5 分钟的一步｜改到晚上。最多两句。",
                fallback = "下午了，「${main.title}」还没开始。现在开始，还是拆一步？",
            )
            Buzz.once(context, Buzz.Level.FIRM)
        }
    }

    /** 下一个要叫醒手机的时刻（给系统闹钟兜底）。 */
    fun nextAlarm(context: Context, now: Long): Long? {
        val app = context.zk
        val cfg = app.store.config.value.hq
        val zone = app.store.zone
        if (!cfg.enabled) return null
        val start = Hq.todayStart(cfg, now, zone)
        val morning = DayClock.at(DayClock.date(now, zone), cfg.morningAt, zone)
        return listOf(start - 10 * 60_000, start, start + 5 * 60_000 + 5_000, morning, morning + 86_400_000L, start + 86_400_000L - 10 * 60_000)
            .filter { it > now }.minOrNull()
    }
}
