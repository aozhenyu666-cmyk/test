package com.zongkong.app.system

import android.content.Context
import android.util.Log
import com.zongkong.app.guard.GuardService
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.control.Control
import com.zongkong.core.control.ControlDecision
import com.zongkong.core.control.ControlSignal
import com.zongkong.core.control.DeferralKind
import com.zongkong.core.hq.Hq
import com.zongkong.core.hq.HqTrigger
import java.time.Instant
import kotlin.random.Random

object TakeoverRunner {
    /** 只有实际发布了询问才开始等；重发不会重抽时刻。 */
    @Synchronized
    fun offered(context: Context, reason: String) {
        val store = context.zk.store
        store.lastContact // 首次安装先初始化，不能把初始化误算成询问后的回应。
        val now = System.currentTimeMillis()
        val cfg = store.config.value
        if (!Notifications.canPost(context) || Hq.quiet(cfg.hq, now, store.zone) || store.hq.value.active != null) return
        val sessions = store.hq.value.sessions.filter { it.trigger == HqTrigger.UNANSWERED }
        val date = DayClock.dateKey(now, store.zone)
        if (sessions.count { DayClock.dateKey(it.startedAt, store.zone) == date } >= Control.ROUNDS_PER_DAY) return
        if (sessions.any { it.endedAt > 0 && now < it.endedAt + Control.COOLDOWN_MS }) return
        store.updateControl { Control.offer(it, cfg.takeover, now, reason, Random.Default) }
        Reminders.reschedule(context)
    }

    @Synchronized
    fun check(context: Context) {
        try { evaluate(context) } catch (e: Exception) { Log.w("Takeover", "强督导检查失败", e) }
    }

    private fun evaluate(context: Context) {
        val app = context.zk
        val store = app.store
        val now = System.currentTimeMillis()
        store.refresh(now)
        val completed = store.control.value.deferrals.filter { d ->
            d.resolvedAt == 0L && when (d.kind) {
                DeferralKind.GATE -> store.day(d.date).passed(d.target) != null
                DeferralKind.ACTION -> store.work.value.rec(d.target)?.actionStatus == com.zongkong.core.work.ActionStatus.DONE
                DeferralKind.HQ -> store.hq.value.sessions.any {
                    (it.startedAt >= d.at || it.id == d.target) && it.endedAt > 0 && !it.early
                }
            }
        }.map { it.id }.toSet()
        if (completed.isNotEmpty()) store.updateControl { Control.resolve(it, completed, now) }
        val quiet = Hq.quiet(store.config.value.hq, now, store.zone)
        for (d in store.control.value.deferrals.filter { !quiet && it.resolvedAt == 0L && now >= it.until }) {
            if (store.firstAlert("grace:${d.id}")) {
                Notifications.alert(context, "缓行到期：${d.title}", "这项还没完成，已恢复检查。去补交清单处理。", "control")
                store.logWake("缓行到期：${d.title}，恢复检查，仍待补交")
            }
        }
        val session = store.hq.value.active
        if (session?.trigger == HqTrigger.UNANSWERED && now >= session.endsAt) {
            app.actions.hq.exitEarly("本轮强督导达到时限，未完成事项继续保留", systemEnding = true)
            store.logWake("本轮强督导到时结束，进入冷却")
        }
        val cfg = store.config.value
        val minute = Instant.ofEpochMilli(now).atZone(store.zone).let { it.hour * 60 + it.minute }
        val sessions = store.hq.value.sessions.filter { it.trigger == HqTrigger.UNANSWERED }
        val signal = ControlSignal(
            now, store.lastContact,
            active = minute in cfg.activeStart until cfg.activeEnd,
            quiet = Hq.quiet(cfg.hq, now, store.zone), paused = cfg.pausedUntil > now,
            hqActive = store.hq.value.active != null,
            guardHealthy = GuardService.running && now - store.heartbeat in 0..90_000L,
            canNotify = Notifications.canPost(context),
            roundsToday = sessions.count { DayClock.dateKey(it.startedAt, store.zone) == DayClock.dateKey(now, store.zone) },
            cooldownUntil = sessions.maxOfOrNull { if (it.endedAt > 0) it.endedAt + Control.COOLDOWN_MS else 0L } ?: 0,
        )
        val book = store.control.value
        val e = book.episode
        when (Control.decide(book, cfg.takeover, signal)) {
            ControlDecision.CLEAR -> store.updateControl { it.copy(episode = null) }
            ControlDecision.WARN -> {
                Notifications.alert(context, "强督导预告", "这次询问一直没有回应，稍后将进入 ${e!!.minutes} 分钟总控时间。可回应，或使用今日一次缓行。", "control")
                store.updateControl { it.copy(episode = it.episode?.copy(warnedAt = now)) }
                store.logWake("无回应强督导已预告")
            }
            ControlDecision.UNAVAILABLE -> {
                Notifications.alert(context, "目前无法接管手机", "无障碍服务未正常运行，现在只能提醒。打开权限页检查；任务仍保留。", "settings/perm")
                store.updateControl { it.copy(episode = it.episode?.copy(unavailableNotified = true)) }
            }
            ControlDecision.START -> {
                app.actions.hq.start(HqTrigger.UNANSWERED, e!!.minutes)
                store.updateControl { it.copy(episode = null) }
                Notifications.hqCall(context, "长期未回应，进入强督导", "本轮 ${e.minutes} 分钟。今天有一次临时缓行；未完成项仍需补交。", fullScreen = true)
                Buzz.once(context, Buzz.Level.FIRM)
                store.logWake("强督导开始：${e.reason}，${e.minutes} 分钟")
            }
            ControlDecision.NONE -> {}
        }
    }

    fun nextAlarm(context: Context, now: Long): Long? {
        val store = context.zk.store
        val times = store.control.value.deferrals.filter { it.resolvedAt == 0L && it.until > now }.map { it.until }.toMutableList()
        store.hq.value.active?.let { s ->
            if (s.suspendedUntil > now) times += s.suspendedUntil
            if (s.trigger == HqTrigger.UNANSWERED && s.endsAt > now) times += s.endsAt
        }
        store.control.value.episode?.let { e ->
            times += if (e.warnedAt == 0L) maxOf(now + 60_000, e.dueAt - 5 * 60_000L)
            else maxOf(now + 60_000, e.dueAt, e.warnedAt + 60_000)
        }
        return times.minOrNull()
    }
}
