package com.zongkong.app.director

import android.content.Context
import com.zongkong.app.health.HealthWatch
import com.zongkong.app.sense.SenseHub
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.director.Facts
import com.zongkong.core.hq.Hq
import com.zongkong.core.info.Intake
import com.zongkong.core.ledger.Ledger
import com.zongkong.core.plan.Aims
import com.zongkong.core.plan.Earn
import com.zongkong.core.plan.Plans
import com.zongkong.core.work.ActionStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 施工队 A 的 FactsSource：从总控的 Store 里取出“此刻的事实”。
 *
 * 原则：能拿到就给，拿不到就留默认值，并在 [Facts.unknown] 里明说哪一块看不见 ——
 * 导演需要知道自己瞎了哪只眼，而不是把“没数据”当成“没在做事”。
 */
object PhoneFacts {

    fun of(context: Context, now: Long): Facts {
        val store = context.zk.store
        val config = store.config.value
        val zone = store.zone
        val unknown = mutableListOf<String>()

        val minute = runCatching {
            val t = java.time.Instant.ofEpochMilli(now).atZone(zone)
            t.hour * 60 + t.minute
        }.getOrDefault(0)

        val quiet = runCatching { Hq.quiet(config.hq, now, zone) }.getOrDefault(false)
        val inFocus = runCatching { store.focusLock(now) != null }.getOrDefault(false)
        val hqActive = runCatching { store.hq.value.active != null }.getOrDefault(false)

        val work = store.work.value
        val book = store.plans.value
        val day = runCatching { Plans.day(book, DayClock.dateKey(now, zone)) }.getOrNull()
        val mainKey = day?.mainActionKey.orEmpty()
        val rec = mainKey.takeIf { it.isNotBlank() }?.let { work.rec(it) }
        if (mainKey.isBlank()) unknown += "今天的主线（还没定）"

        val started = work.session?.actionKey == mainKey && mainKey.isNotBlank()
        val done = rec?.actionStatus == ActionStatus.DONE
        val plannedAt = parseAt(rec?.plan, zone)
        val lastProgressAt = work.events.filter { it.actionKey == mainKey && mainKey.isNotBlank() }
            .maxOfOrNull { it.at } ?: 0L

        val upcoming = runCatching {
            work.actions()
                .filter { it.actionStatus.open && it.key != mainKey }
                .mapNotNull { a ->
                    val at = parseAt(a.plan, zone)
                    if (at in (now + 1)..(now + 3 * 3600_000L)) a.title else null
                }
                .distinct()
                .take(5)
        }.getOrDefault(emptyList())

        val weekDeliverables = runCatching {
            Plans.active(book, now, zone)?.outcomes?.filter { !it.done }?.map { it.title }.orEmpty()
        }.getOrDefault(emptyList())
        if (weekDeliverables.isEmpty()) unknown += "本周要交的东西（没有生效中的周计划）"

        val playMin = runCatching { store.today.value.playMinutes }.getOrDefault(0)
        val today = DayClock.date(now, zone)
        val todayFrom = DayClock.startOf(today, zone)

        // 做事多少分钟：和“挣来的娱乐”同一个口径，导演说出的数字才和首页对得上
        val workMin = runCatching { Plans.minutesByAction(work, todayFrom, now).values.sum() }.getOrDefault(0)

        // 今天还能玩多少：min(每日上限, 底数 + 挣到的) - 已经玩的
        val playLeft = runCatching {
            val cap = if (config.dailyQuotaMin > 0) config.dailyQuotaMin else Earn.CAP
            val earned = config.earn.baseMinutes + Earn.earned(work, now, zone, config.earn)
            (minOf(cap, earned) - playMin).coerceAtLeast(0)
        }.getOrDefault(-1)

        // 今天花了多少（账本；读不到就 0，不编）
        val spend = runCatching { Ledger.total(Ledger.day(store.ledger.value, today, zone)) }.getOrDefault(0L)

        // 收件箱积压几条
        val inbox = runCatching { Intake.stats(work, store.intake.value, now, zone).backlog }.getOrDefault(0)

        val health = runCatching { HealthWatch.health(context, now) }.getOrNull()
        if (health == null) unknown += "无障碍健康"

        return Facts(
            now = now,
            minute = minute,
            quiet = quiet,
            inFocus = inFocus,
            hqActive = hqActive,
            mainTitle = rec?.title.orEmpty(),
            mainKey = mainKey,
            mainPlannedAt = plannedAt,
            mainStarted = started,
            mainDone = done,
            lastProgressAt = lastProgressAt,
            foregroundPkg = SenseHub.lastPkg,
            foregroundLabel = SenseHub.lastLabel,
            foregroundCategory = SenseHub.lastCategory,
            foregroundMinutes = SenseHub.foregroundMinutes(now),
            playMinutesToday = playMin,
            workMinutesToday = workMin,
            playLeftMin = playLeft,
            spendTodayCents = spend,
            credit = runCatching { store.credibility(now) }.getOrDefault(-1),
            stance = runCatching {
                when (store.stance(now).name) {
                    "TRUST" -> "信任"
                    "NORMAL" -> "正常"
                    "WARY" -> "审慎"
                    "STRICT" -> "严格"
                    else -> ""
                }
            }.getOrDefault(""),
            lastContactAt = runCatching { store.lastContact }.getOrDefault(0L),
            health = health,
            aims = runCatching { Aims.open(book).take(4).map { it.title } }.getOrDefault(emptyList()),
            weekDeliverables = weekDeliverables,
            upcoming = upcoming,
            inboxCount = inbox,
            unknown = unknown,
        )
    }

    /** 行动的计划时间：有几点几分就按它，只有日期就按当天零点。解析不了返回 0。 */
    private fun parseAt(plan: com.zongkong.core.work.Plan?, zone: ZoneId): Long {
        val s = plan?.start?.trim().orEmpty()
        if (s.isBlank()) return 0
        return try {
            if (s.contains('T')) LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
            else LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }
}
