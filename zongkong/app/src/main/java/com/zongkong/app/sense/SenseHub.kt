package com.zongkong.app.sense

import android.content.Context
import com.zongkong.app.director.DirectorHost
import com.zongkong.app.health.HealthWatch
import com.zongkong.app.window.Relay
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.director.Actor
import com.zongkong.core.director.Sense
import com.zongkong.core.director.SenseKind

/**
 * 施工队 A 的 A4：把手机上的事变成 [Sense] 交给导演。
 *
 * 这里只做“翻译”，不做判断：该不该说话、说什么，都由导演决定。
 * 导演开关关着的时候，每个入口第一行就返回——总控原来的行为一点不变。
 *
 * 事件来源：
 *  - 解锁 / 熄屏：GuardService 的亮屏广播
 *  - 前台切换：GuardService 的窗口事件（总控本来就在收）
 *  - 计划到点 / 进度：每 20 秒的 tick 顺手看一眼 Store
 *  - 兜底 TICK 每 30 分钟，NIGHT 每晚一次
 */
object SenseHub {
    const val CAT_ENTERTAINMENT = "ENTERTAINMENT"
    const val CAT_SHOPPING = "SHOPPING"
    const val CAT_WORK = "WORK"
    const val CAT_ACTOR = "ACTOR"
    const val CAT_OTHER = "OTHER"

    /** 现在前台是什么（导演的 Facts 从这里取，不再问一次无障碍）。 */
    @Volatile var lastPkg = ""
        private set
    @Volatile var lastLabel = ""
        private set
    @Volatile var lastCategory = CAT_OTHER
        private set
    @Volatile var lastSince = 0L
        private set

    /** 最近一次真的收到无障碍事件的时刻（健康四层的第三层）。 */
    @Volatile var lastEventAt = 0L
        private set

    private var lastTickAt = 0L
    private var lastNightDay = ""
    private var lastPlanDueDay = ""
    private var lastLongAt = 0L
    private var lastSessionKey = ""
    private var lastIdleAt = 0L

    fun foregroundMinutes(now: Long): Int = if (lastSince <= 0 || lastPkg.isBlank()) 0 else ((now - lastSince) / 60_000L).toInt()

    /** 前台切到某个应用。 */
    fun foreground(context: Context, pkg: String, now: Long) {
        lastEventAt = now
        if (!DirectorHost.enabled(context)) return
        if (pkg == lastPkg) return
        val app = context.applicationContext
        val cfg = app.zk.store.config.value
        val category = when {
            pkg == Actor.GEMINI_LIVE.pkg || pkg == Actor.CHATGPT.pkg -> CAT_ACTOR
            pkg in cfg.blocked -> CAT_ENTERTAINMENT
            pkg == app.packageName -> CAT_WORK
            else -> CAT_OTHER
        }
        val label = runCatching { com.zongkong.app.data.AppNames.label(app, pkg) }.getOrDefault(pkg)
        lastPkg = pkg
        lastLabel = label
        lastCategory = category
        lastSince = now
        lastLongAt = 0L

        Relay.onForeground(pkg, now)

        push(
            context,
            Sense(
                kind = SenseKind.FOREGROUND,
                at = now,
                source = "无障碍",
                payload = mapOf("pkg" to pkg, "label" to label, "category" to category),
            ),
        )
        if (category == CAT_ACTOR) {
            val who = when (pkg) {
                Actor.GEMINI_LIVE.pkg -> Actor.GEMINI_LIVE.name
                Actor.CHATGPT.pkg -> Actor.CHATGPT.name
                else -> Actor.NONE.name
            }
            push(context, Sense(SenseKind.ACTOR_OPEN, now, "无障碍", mapOf("actor" to who, "label" to label)))
        }
    }

    fun unlock(context: Context, now: Long) {
        lastEventAt = now
        if (!DirectorHost.enabled(context)) return
        push(context, Sense(SenseKind.UNLOCK, now, "系统广播"))
    }

    fun screenOff(context: Context, now: Long) {
        lastEventAt = now
        if (!DirectorHost.enabled(context)) return
        push(context, Sense(SenseKind.SCREEN_OFF, now, "系统广播"))
    }

    /** 每 20 秒看一眼：健康、兜底 TICK、夜间、计划到点、连续娱乐。都是本地判断，不花钱。 */
    fun tick(context: Context, now: Long) {
        if (!DirectorHost.enabled(context)) return
        val app = context.applicationContext
        val cfg = app.zk.store.config.value
        val dir = cfg.mailuo

        // 四层健康：变化了才发
        runCatching { HealthWatch.check(context, now) { h -> pushHealth(context, now, h) } }

        Relay.tick(now)

        // 连续娱乐
        if (lastCategory == CAT_ENTERTAINMENT && lastSince > 0 && lastLongAt != lastSince) {
            val minutes = ((now - lastSince) / 60_000L).toInt()
            if (minutes >= dir.longEntertainmentMin) {
                lastLongAt = lastSince
                push(
                    context,
                    Sense(
                        SenseKind.LONG_ENTERTAINMENT, now, "无障碍",
                        mapOf("pkg" to lastPkg, "label" to lastLabel, "minutes" to minutes.toString()),
                    ),
                )
            }
        }

        val facts = runCatching { DirectorHost.factsNow(now) }.getOrNull()

        // 计划到点 / 过点没开始
        if (facts != null && facts.mainPlannedAt > 0 && !facts.mainStarted && !facts.mainDone) {
            val late = now - facts.mainPlannedAt
            if (late >= dir.planLateMin * 60_000L) {
                val day = DayClock.dateKey(now, app.zk.store.zone)
                if (day != lastPlanDueDay) {
                    lastPlanDueDay = day
                    push(
                        context,
                        Sense(
                            SenseKind.PLAN_DUE, now, "Store",
                            mapOf(
                                "key" to facts.mainKey,
                                "title" to facts.mainTitle,
                                "lateMin" to (late / 60_000L).toString(),
                            ),
                        ),
                    )
                }
            }
        }

        // 行动开始 / 结束（导演要知道“真去做了”）
        val sessionKey = runCatching { app.zk.store.work.value.session?.actionKey.orEmpty() }.getOrDefault("")
        if (sessionKey != lastSessionKey) {
            val was = lastSessionKey
            lastSessionKey = sessionKey
            if (sessionKey.isNotBlank()) {
                push(context, Sense(SenseKind.PLAN_PROGRESS, now, "Store", mapOf("key" to sessionKey, "state" to "开始")))
            } else if (was.isNotBlank()) {
                push(context, Sense(SenseKind.PLAN_PROGRESS, now, "Store", mapOf("key" to was, "state" to "结束")))
            }
        }

        // 空档：没有在做、近 2 小时也没排行动
        if (facts != null && sessionKey.isBlank() && facts.upcoming.isEmpty() && now - lastIdleAt > 2 * 3600_000L) {
            lastIdleAt = now
            push(context, Sense(SenseKind.IDLE_START, now, "Store"))
        }

        // 兜底 TICK：每 30 分钟一次（导演还能用它看“这个人现在大概在干什么”）
        if (now - lastTickAt >= 30 * 60_000L) {
            lastTickAt = now
            push(context, Sense(SenseKind.TICK, now, "定时"))
        }

        // 夜间整理
        val day = DayClock.dateKey(now, app.zk.store.zone)
        val minuteOfDay = runCatching {
            val t = java.time.Instant.ofEpochMilli(now).atZone(app.zk.store.zone)
            t.hour * 60 + t.minute
        }.getOrDefault(0)
        if (minuteOfDay >= dir.nightAt && day != lastNightDay) {
            lastNightDay = day
            push(context, Sense(SenseKind.NIGHT, now, "定时"))
        }
    }

    /** 无障碍服务重连时清掉前台记忆：中间那一段本来就看不见。 */
    fun reset() {
        lastPkg = ""
        lastLabel = ""
        lastCategory = CAT_OTHER
        lastSince = 0L
        lastLongAt = 0L
    }

    private fun push(context: Context, sense: Sense) = DirectorHost.sense(context, sense)

    private fun pushHealth(context: Context, now: Long, h: com.zongkong.core.director.Health) {
        val payload = mapOf(
            "permission" to h.permission.toString(),
            "connected" to h.connected.toString(),
            "lastEventAt" to h.lastEventAt.toString(),
            "readable" to (h.readable?.toString() ?: "未知"),
            "userDisabled" to h.userDisabled.toString(),
        )
        push(context, Sense(SenseKind.HEALTH, now, "无障碍诊断", payload))
    }
}
