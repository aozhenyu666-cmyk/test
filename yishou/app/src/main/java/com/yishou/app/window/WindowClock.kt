package com.yishou.app.window

import com.yishou.app.settings.AppPrefs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 陪练窗口和每日定时的时间计算。纯函数，方便测试。 */
object WindowClock {

    data class Span(val start: Long, val end: Long) {
        operator fun contains(t: Long) = t in start until end
    }

    fun today(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    fun startOfDay(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun at(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** 下一个严格晚于 now 的 hour:minute。 */
    fun nextDaily(now: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val d = today(now, zone)
        val t = at(d, hour, minute, zone)
        return if (t > now) t else at(d.plusDays(1), hour, minute, zone)
    }

    /**
     * 可能与 date 这一天有关的全部窗口：前一天和当天的定时窗口（开启时），以及手动开始的窗口。
     * 被“提前结束”截断的窗口，结束时间改为提前结束的时刻。
     */
    fun spans(date: LocalDate, p: AppPrefs, zone: ZoneId): List<Span> {
        val len = p.windowMinutes * 60_000L
        val raw = buildList {
            if (p.windowEnabled) {
                for (d in listOf(date.minusDays(1), date)) {
                    val s = at(d, p.windowHour, p.windowMinute, zone)
                    add(Span(s, s + len))
                }
            }
            if (p.manualWindowStart > 0) add(Span(p.manualWindowStart, p.manualWindowStart + len))
        }
        return raw.mapNotNull { s ->
            val stop = p.windowStoppedAt
            when {
                stop <= 0 || stop !in s -> s
                stop <= s.start -> null
                else -> Span(s.start, stop)
            }
        }
    }

    /** 此刻正在进行的窗口，没有则为 null。 */
    fun active(now: Long, p: AppPrefs, zone: ZoneId): Span? =
        spans(today(now, zone), p, zone).filter { now in it }.maxByOrNull { it.end }

    /** 下一次定时窗口的开始时间；定时窗口关闭时为 null。 */
    fun nextScheduledStart(now: Long, p: AppPrefs, zone: ZoneId): Long? =
        if (p.windowEnabled) nextDaily(now, p.windowHour, p.windowMinute, zone) else null

    /** date 这一天里窗口实际经过的分钟数（截止到 now）。 */
    fun windowMinutesOn(date: LocalDate, p: AppPrefs, zone: ZoneId, now: Long): Int {
        val dayStart = startOfDay(date, zone)
        val dayEnd = minOf(startOfDay(date.plusDays(1), zone), now)
        val ms = spans(date, p, zone)
            .distinct()
            .sumOf { s -> (minOf(s.end, dayEnd) - maxOf(s.start, dayStart)).coerceAtLeast(0) }
        return (ms / 60_000).toInt()
    }

    /**
     * 每晚总结属于哪一天。任务可能因系统省电推迟到零点以后才执行，
     * 只要还没到“当天总结时间前 2 小时”，就算前一天的总结。
     */
    fun summaryDate(now: Long, p: AppPrefs, zone: ZoneId): LocalDate {
        val d = today(now, zone)
        val target = at(d, p.summaryHour, p.summaryMinute, zone)
        return if (now >= target - 2 * 3_600_000L) d else d.minusDays(1)
    }
}
