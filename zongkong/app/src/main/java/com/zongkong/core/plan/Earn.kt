package com.zongkong.core.plan

import com.zongkong.core.DayClock
import com.zongkong.core.EarnConfig
import com.zongkong.core.work.Rec
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import java.time.ZoneId

/*
 * 挣来的娱乐（0.10）。
 *
 * 以前：早上部署一过（甚至是总控替你排的、后台自动验收的），娱乐就只受“每天 120 分钟”管——
 * 不交流、不做事反而最舒服。现在：
 *   - 每天只先给一个底数（默认 20 分钟）
 *   - 做完一步 +20，实打实做事每 30 分钟 +10，上限还是每天的总上限
 *   - 主线（没定主线就看今天第一件排了时间的事）过了点 30 分钟还没开始：欠着，娱乐先关；
 *     开始做、改个时间、或者取消 / 标卡住（写原因），马上解开
 * 不评分、不审作文：只看你做没做。
 */
object Earn {
    const val CAP = 240

    /** 今天做完的步数（同一步只算一次）。 */
    fun doneToday(work: Work, now: Long, zone: ZoneId): Int {
        val from = DayClock.startOf(DayClock.date(now, zone), zone)
        return work.events.filter { it.at in from..now && (it.type == "done:YES" || it.type == "done:PARTIAL") }
            .map { it.actionKey }.distinct().size
    }

    /** 今天挣到的分钟（不含底数）。 */
    fun earned(work: Work, now: Long, zone: ZoneId, cfg: EarnConfig): Int {
        val from = DayClock.startOf(DayClock.date(now, zone), zone)
        val worked = Plans.minutesByAction(work, from, now).values.sum()
        return (doneToday(work, now, zone) * cfg.perAction + (worked / 30) * cfg.perHalfHour).coerceIn(0, CAP)
    }

    /**
     * 欠着的那一步：今天排了时间、过了开始时间 [EarnConfig.mainGraceMinutes] 分钟、还开着、今天没开始过、也没在做。
     * 定了主线就只看主线；没定主线，排了时间的每一步都算（最早的那一步）。没欠返回 null。
     * 解开：开始做、改时间、取消或标卡住。
     */
    fun debt(work: Work, book: PlanBook, now: Long, zone: ZoneId, cfg: EarnConfig): Rec? {
        val today = DayClock.date(now, zone)
        val from = DayClock.startOf(today, zone)
        val to = DayClock.startOf(today.plusDays(1), zone)
        fun start(a: Rec) = a.plan?.takeIf { it.hasTime }?.let { TimeParse.startMillis(it, zone) }
        fun owing(a: Rec): Boolean {
            val s = start(a) ?: return false
            return a.actionStatus.open && a.actionStatus != com.zongkong.core.work.ActionStatus.STUCK && s in from until to && now >= s + cfg.mainGraceMinutes * 60_000L &&
                work.session?.actionKey != a.key &&
                work.events.none { it.actionKey == a.key && it.type == "start" && it.at >= from }
        }
        val main = Plans.mainAction(book, work, now, zone)
        val pool = if (main != null && start(main)?.let { it in from until to } == true) listOf(main) else work.actions()
        return pool.filter(::owing).minByOrNull { start(it) ?: Long.MAX_VALUE }
    }
}
