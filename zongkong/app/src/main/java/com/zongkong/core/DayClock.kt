package com.zongkong.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 逻辑日：凌晨 4 点换日。半夜 1 点还算前一天，熬夜不会让“今天”的关卡凭空消失。
 * 关卡时间用“几点几分”的分钟数表示；早于 4 点的时间算作当天深夜（次日凌晨）。
 */
object DayClock {
    const val DAY_START_MIN = 4 * 60

    fun date(now: Long, zone: ZoneId): LocalDate =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).minusMinutes(DAY_START_MIN.toLong()).toLocalDate()

    fun dateKey(now: Long, zone: ZoneId): String = date(now, zone).toString()

    /** 逻辑日开始的时刻（当天 4:00）。 */
    fun startOf(date: LocalDate, zone: ZoneId): Long =
        date.atTime(4, 0).atZone(zone).toInstant().toEpochMilli()

    /** 逻辑日 [date] 里钟面时间 [minute] 对应的时刻。 */
    fun at(date: LocalDate, minute: Int, zone: ZoneId): Long {
        val m = minute.mod(24 * 60)
        val d = if (m < DAY_START_MIN) date.plusDays(1) else date
        return d.atTime(m / 60, m % 60).atZone(zone).toInstant().toEpochMilli()
    }

    /** 便于比较先后：4:00 是 240，次日 3:59 是 1679。 */
    fun rank(minute: Int): Int {
        val m = minute.mod(24 * 60)
        return if (m < DAY_START_MIN) m + 24 * 60 else m
    }

    /** 1 = 周一 … 7 = 周日（按逻辑日）。 */
    fun weekday(now: Long, zone: ZoneId): Int = date(now, zone).dayOfWeek.value

    fun hhmm(minute: Int): String {
        val m = minute.mod(24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** "09:30" / "9:30" / "930" → 570；格式不对返回 null。 */
    fun parse(text: String): Int? {
        val t = text.trim().replace('：', ':')
        val (h, m) = when {
            ':' in t -> t.split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: return null) to (it.getOrNull(1)?.toIntOrNull() ?: return null) }
            t.length in 3..4 && t.all(Char::isDigit) -> t.dropLast(2).toInt() to t.takeLast(2).toInt()
            t.length in 1..2 && t.all(Char::isDigit) -> t.toInt() to 0
            else -> return null
        }
        if (h !in 0..24 || m !in 0..59 || (h == 24 && m != 0)) return null
        return (h * 60 + m).mod(24 * 60)
    }
}
