package com.zongkong.core.work

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * 把 GPT 或你写的时间说法变成计划时间。认得：
 *  2026-10-10 09:00 / 10-10 9:00 / 10月10日 9点 / 10/10
 *  今天 / 明天 / 后天 / 今晚 / 明早 / 周六 / 下周一 / 星期三
 *  20:00-21:00 / 20:00~21:00 / 20:00 到 21:00 / 9点半 / 晚上8点 / 60分钟 / 1.5小时
 * 认不出时返回 null，不瞎猜。
 */
object TimeParse {
    private val ISO_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private val WEEK = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)

    fun parse(text: String, now: Long, zone: ZoneId): Plan? {
        val t = text.trim().replace('：', ':').replace('～', '~').replace('—', '-').replace('－', '-')
        if (t.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var date: LocalDate? = null
        var rest = t

        // 绝对日期
        Regex("(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?").find(rest)?.let { m ->
            date = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
            rest = rest.removeRange(m.range)
        }
        if (date == null) {
            Regex("(?<![\\d:.])(\\d{1,2})(?:[-/.]|月)(\\d{1,2})(?:日|号)?(?![\\d:])(?!\\s*(?:小时|h|分钟|min))").find(rest)?.let { m ->
                val mo = m.groupValues[1].toInt()
                val d = m.groupValues[2].toInt()
                if (mo in 1..12 && d in 1..31) {
                    var c = runCatching { LocalDate.of(today.year, mo, d) }.getOrNull()
                    // 已经过去很久的月份算明年
                    if (c != null && c.isBefore(today.minusDays(60))) c = c.plusYears(1)
                    date = c
                    rest = rest.removeRange(m.range)
                }
            }
        }
        // 相对日期
        if (date == null) {
            when {
                rest.contains("大后天") -> { date = today.plusDays(3); rest = rest.replace("大后天", "") }
                rest.contains("后天") -> { date = today.plusDays(2); rest = rest.replace("后天", "") }
                rest.contains("明天") || rest.contains("明早") || rest.contains("明晚") -> { date = today.plusDays(1) }
                rest.contains("今天") || rest.contains("今晚") || rest.contains("今早") -> { date = today }
            }
            Regex("(下下|下)?(?:周|星期|礼拜)([一二三四五六日天])").find(rest)?.let { m ->
                val dow = DayOfWeek.of(WEEK.getValue(m.groupValues[2][0]))
                val base = when (m.groupValues[1]) {
                    "下" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).minusDays(1)
                    "下下" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusDays(6)
                    else -> null
                }
                date = if (base == null) today.with(TemporalAdjusters.nextOrSame(dow)) else base.with(TemporalAdjusters.next(dow))
                rest = rest.removeRange(m.range)
            }
        }

        val pm = Regex("晚上|下午|今晚|明晚|傍晚|晚").containsMatchIn(rest)
        val am = Regex("早上|上午|明早|今早|早").containsMatchIn(rest) && !pm
        val times = Regex("(\\d{1,2})(?::(\\d{2})|点(半|(\\d{1,2})分?)?)").findAll(rest).toList()
        fun toTime(m: MatchResult): LocalTime? {
            var h = m.groupValues[1].toInt()
            val min = when {
                m.groupValues[2].isNotEmpty() -> m.groupValues[2].toInt()
                m.groupValues[3] == "半" -> 30
                m.groupValues[4].isNotEmpty() -> m.groupValues[4].toInt()
                else -> 0
            }
            if (pm && h in 1..11) h += 12
            if (am && h == 12) h = 0
            if (h !in 0..23 || min !in 0..59) return null
            return LocalTime.of(h, min)
        }
        val start = times.getOrNull(0)?.let(::toTime)
        var end = times.getOrNull(1)?.let(::toTime)
        if (start != null && end == null) {
            Regex("(\\d+(?:\\.\\d+)?)\\s*(分钟|小时|h|min)").find(rest)?.let { m ->
                val n = m.groupValues[1].toDouble()
                val minutes = if (m.groupValues[2] in setOf("小时", "h")) (n * 60).toLong() else n.toLong()
                end = start.plusMinutes(minutes)
            }
        }
        if (date == null && start == null) return null
        val d = date ?: today
        if (start == null) return Plan(d.toString())
        val s = ZonedDateTime.of(d, start, zone)
        val e = end?.let { var z = ZonedDateTime.of(d, it, zone); if (!z.isAfter(s)) z = z.plusDays(1); z }
        return Plan(s.format(ISO_FMT), e?.format(ISO_FMT).orEmpty())
    }

    /** 计划时间的毫秒值（只有日期的算当天 0 点）。 */
    fun startMillis(plan: Plan, zone: ZoneId): Long? = runCatching {
        if (plan.hasTime) java.time.OffsetDateTime.parse(plan.start).toInstant().toEpochMilli()
        else LocalDate.parse(plan.start).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()

    fun endMillis(plan: Plan, zone: ZoneId): Long? = runCatching {
        when {
            plan.end.isNotBlank() && plan.end.contains('T') -> java.time.OffsetDateTime.parse(plan.end).toInstant().toEpochMilli()
            plan.hasTime -> java.time.OffsetDateTime.parse(plan.start).toInstant().toEpochMilli() + 60 * 60_000L
            else -> LocalDate.parse(plan.start).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }.getOrNull()

    /** “今天 20:00–21:00” / “10-12 周六” 这样给人看。 */
    fun label(plan: Plan, now: Long, zone: ZoneId): String {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val s = startMillis(plan, zone) ?: return plan.start
        val sd = Instant.ofEpochMilli(s).atZone(zone)
        val day = when (sd.toLocalDate()) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            today.minusDays(1) -> "昨天"
            else -> "%d-%02d %s".format(sd.monthValue, sd.dayOfMonth, "周" + "一二三四五六日"[sd.dayOfWeek.value - 1])
        }
        if (!plan.hasTime) return day
        val e = if (plan.end.contains('T')) endMillis(plan, zone)?.let { Instant.ofEpochMilli(it).atZone(zone) } else null
        return "$day %02d:%02d".format(sd.hour, sd.minute) + (e?.let { "–%02d:%02d".format(it.hour, it.minute) } ?: "")
    }
}
