package com.zongkong.core.plan

import com.zongkong.core.DayClock
import com.zongkong.core.DayLog
import com.zongkong.core.boss.CommitStatus
import com.zongkong.core.boss.Dossier
import com.zongkong.core.device.PcSide
import com.zongkong.core.hq.HqSession
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Rec
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import java.time.ZoneId

/*
 * 今天的清单：一天下来做了什么、没做什么，照着记录列出来。
 *
 * 不评判、不要你写报告——总控自己把账列清楚，你只看一眼、补一句明天第一步。
 * 日终验收交的就是这张单子；问“今天哪些没完成”，参谋也照这张单子答。
 */
object DayReport {

    enum class Mark(val sign: String, val label: String) {
        DONE("✓", "做完"),
        WAIT("✓?", "做完待确认"),
        DOING("…", "在做"),
        STUCK("✗", "卡住"),
        MOVED("→", "改期"),
        TODO("○", "没做"),
        CANCELLED("—", "取消"),
    }

    data class Item(val key: String, val title: String, val mark: Mark, val minutes: Int, val note: String = "", val main: Boolean = false) {
        fun line(): String = buildString {
            append(mark.sign).append(' ').append(title)
            if (main) append("（主线）")
            append("：").append(mark.label)
            if (minutes > 0) append("，$minutes 分钟")
            if (note.isNotBlank()) append("，").append(note)
        }
    }

    data class Report(
        val date: String,
        /** 今天排了的（主线在最前）。 */
        val planned: List<Item>,
        /** 没排但今天做了 / 做完的。 */
        val extra: List<Item>,
        val workMinutes: Int,
        val playMinutes: Int,
        val bounces: Int,
        val emergencies: Int,
        val pcMinutes: Int,
        val pcFunMinutes: Int,
        val pcKnown: Boolean,
        /** 今天到期的约定：(内容, 结果)。 */
        val promises: List<Pair<String, String>>,
        val hqDone: Boolean,
        /** 明天第一步（已经排好的话）。 */
        val tomorrow: String,
    ) {
        val doneCount: Int get() = planned.count { it.mark == Mark.DONE || it.mark == Mark.WAIT }
        val notDone: List<Item> get() = planned.filter { it.mark != Mark.DONE && it.mark != Mark.WAIT && it.mark != Mark.CANCELLED }
        val empty: Boolean get() = planned.isEmpty() && extra.isEmpty()
    }

    private fun mark(a: Rec, sessionKey: String?): Mark = when {
        a.key == sessionKey -> Mark.DOING
        else -> when (a.actionStatus) {
            ActionStatus.DONE -> Mark.DONE
            ActionStatus.CONFIRM -> Mark.WAIT
            ActionStatus.DOING -> Mark.DOING
            ActionStatus.STUCK -> Mark.STUCK
            ActionStatus.RESCHEDULED -> Mark.MOVED
            ActionStatus.CANCELLED -> Mark.CANCELLED
            ActionStatus.TODO -> Mark.TODO
        }
    }

    private fun note(a: Rec): String = when (a.actionStatus) {
        // “达到。”和“做完”重复，去掉；“部分达到”留着
        ActionStatus.DONE -> a[F.RESULT].removePrefix("达到。").removePrefix("达到").trim().take(40)
        ActionStatus.CONFIRM -> a[F.RESULT].take(40)
        ActionStatus.STUCK -> a[F.STUCK].ifBlank { a[F.BREAK].take(30) }
        else -> a[F.BREAK].take(30).let { if (it.isNotBlank()) "停在：$it" else "" }
    }

    fun build(
        work: Work,
        book: PlanBook,
        log: DayLog,
        hq: List<HqSession>,
        dossier: Dossier,
        pc: PcSide?,
        now: Long,
        zone: ZoneId,
    ): Report {
        val today = DayClock.date(now, zone)
        val dayFrom = DayClock.startOf(today, zone)
        val dayTo = DayClock.startOf(today.plusDays(1), zone)
        val mins = Plans.minutesByAction(work, dayFrom, minOf(now, dayTo))
        val mainKey = Plans.day(book, today.toString())?.mainActionKey.orEmpty()
        val sessionKey = work.session?.actionKey
        fun startOf(a: Rec) = a.plan?.let { TimeParse.startMillis(it, zone) }

        val plannedRecs = work.actions().filter { a ->
            val s = startOf(a)
            (s != null && s in dayFrom until dayTo) || a.key == mainKey
        }.sortedWith(compareBy({ it.key != mainKey }, { startOf(it) ?: Long.MAX_VALUE }))
        val plannedKeys = plannedRecs.map { it.key }.toSet()
        val planned = plannedRecs.map { Item(it.key, it.title, mark(it, sessionKey), mins[it.key] ?: 0, note(it), it.key == mainKey) }

        val extra = work.actions().filter { a ->
            a.key !in plannedKeys && (
                (mins[a.key] ?: 0) > 0 ||
                    ((a.actionStatus == ActionStatus.DONE || a.actionStatus == ActionStatus.CONFIRM) && a.updatedAt in dayFrom until dayTo)
                )
        }.sortedByDescending { mins[it.key] ?: 0 }
            .map { Item(it.key, it.title, mark(it, sessionKey), mins[it.key] ?: 0, note(it)) }

        val promises = dossier.commitments.filter { it.due in dayFrom until dayTo }.map { c ->
            c.what to when (c.status) {
                CommitStatus.OPEN -> if (c.due < now) "到点了，还没对账" else "还没到点"
                else -> c.status.label + (c.note.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
            }
        }

        val tomorrowFrom = dayTo
        val tomorrowTo = DayClock.startOf(today.plusDays(2), zone)
        val tomorrowMain = Plans.day(book, today.plusDays(1).toString())?.mainActionKey?.let { work.rec(it) }
        val first = tomorrowMain ?: work.actions().filter { it.actionStatus.open }
            .mapNotNull { a -> startOf(a)?.takeIf { it in tomorrowFrom until tomorrowTo }?.let { a to it } }
            .minByOrNull { it.second }?.first
        val tomorrow = first?.let { a ->
            val at = startOf(a)?.takeIf { a.plan?.hasTime == true }?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toString().take(5) + " " }.orEmpty()
            at + a.title
        }.orEmpty()

        val pcToday = pc?.takeIf { it.at > 0 && DayClock.dateKey(it.at, zone) == today.toString() }
        return Report(
            date = today.toString(),
            planned = planned,
            extra = extra,
            workMinutes = mins.values.sum(),
            playMinutes = log.playMinutes,
            bounces = log.bounces,
            emergencies = log.emergencies.size,
            pcMinutes = pcToday?.todayMinutes ?: 0,
            pcFunMinutes = pcToday?.funMinutes ?: 0,
            pcKnown = pcToday != null,
            promises = promises,
            hqDone = hq.any { DayClock.dateKey(it.startedAt, zone) == today.toString() && !it.active && !it.early },
            tomorrow = tomorrow,
        )
    }

    /** 列成纯文字：清单页、日终验收、参谋都用这一份。 */
    fun text(r: Report): String = buildString {
        append("今天（${r.date}）的清单\n")
        if (r.planned.isEmpty()) append("\n排了的：今天没有排进时间的行动。\n")
        else {
            append("\n排了的（做完 ${r.doneCount}/${r.planned.size}）：\n")
            r.planned.forEach { append(it.line()).append('\n') }
        }
        if (r.extra.isNotEmpty()) {
            append("\n另外做了的：\n")
            r.extra.forEach { append(it.line()).append('\n') }
        }
        if (r.promises.isNotEmpty()) {
            append("\n今天到期的约定：\n")
            r.promises.forEach { (w, s) -> append("· ").append(w).append("：").append(s).append('\n') }
        }
        append("\n时间：做事 ${hm(r.workMinutes)}；手机娱乐 ${hm(r.playMinutes)}")
        if (r.bounces > 0) append("，被弹回 ${r.bounces} 次")
        if (r.emergencies > 0) append("，紧急放行 ${r.emergencies} 次")
        if (r.pcKnown) append("；电脑 ${hm(r.pcMinutes)}（娱乐 ${hm(r.pcFunMinutes)}）")
        append('\n')
        append("总控时间：").append(if (r.hqDone) "做了" else "还没做").append('\n')
        append("明天第一步：").append(r.tomorrow.ifBlank { "还没排" }).append('\n')
    }

    fun hm(m: Int): String = if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟"
}
