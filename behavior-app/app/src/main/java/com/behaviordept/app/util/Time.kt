package com.behaviordept.app.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** 时间工具：全部按手机本地时区计算“哪一天”。 */
object Time {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun now(): Long = System.currentTimeMillis()

    fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun startOf(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun today(): LocalDate = LocalDate.now(zone)

    /** 本周一。 */
    fun weekStart(date: LocalDate = today()): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private val mdFmt = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val hmFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val fullFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun md(date: LocalDate): String = mdFmt.format(date)
    fun md(millis: Long): String = md(dateOf(millis))
    fun hm(millis: Long): String = hmFmt.format(Instant.ofEpochMilli(millis).atZone(zone))
    fun full(millis: Long): String = fullFmt.format(Instant.ofEpochMilli(millis).atZone(zone))

    private val weekNames = listOf("一", "二", "三", "四", "五", "六", "日")
    fun weekdayName(date: LocalDate): String = "周" + weekNames[date.dayOfWeek.value - 1]

    /** “今天 / 明天 / 3 天后 / 已过期 2 天”这类相对说法。 */
    fun relativeDay(target: LocalDate, from: LocalDate = today()): String {
        val diff = target.toEpochDay() - from.toEpochDay()
        return when {
            diff == 0L -> "今天"
            diff == 1L -> "明天"
            diff == 2L -> "后天"
            diff > 2L -> "$diff 天后"
            diff == -1L -> "昨天到期"
            else -> "已过期 ${-diff} 天"
        }
    }

    /** mm:ss 或 h:mm:ss。 */
    fun clock(elapsedMillis: Long): String {
        val total = (elapsedMillis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
