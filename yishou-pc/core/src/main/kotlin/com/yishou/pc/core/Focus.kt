package com.yishou.pc.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 专注时段：每天固定的几段，加上手动开始的一段。提前结束的到结束那一刻为止。 */
object Focus {
    data class Span(val start: Long, val end: Long)

    fun active(now: Long, prefs: Prefs, zone: ZoneId): Span? =
        spans(now, prefs, zone)
            .filter { now in it.start until it.end }
            .filter { prefs.focusStoppedAt !in it.start until it.end }
            .maxByOrNull { it.end }

    /** 下一段定时专注的开始时间 */
    fun next(now: Long, prefs: Prefs, zone: ZoneId): Long? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..1L).flatMap { d -> slotSpans(today.plusDays(d), prefs, zone) }
            .map { it.start }.filter { it > now }.minOrNull()
    }

    private fun spans(now: Long, prefs: Prefs, zone: ZoneId): List<Span> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val manual = if (prefs.manualFocusStart > 0 && prefs.manualFocusMinutes > 0) {
            listOf(Span(prefs.manualFocusStart, prefs.manualFocusStart + prefs.manualFocusMinutes * 60_000L))
        } else emptyList()
        // 昨天开始、跨过午夜的那段也要算
        return slotSpans(today.minusDays(1), prefs, zone) + slotSpans(today, prefs, zone) + manual
    }

    private fun slotSpans(date: LocalDate, prefs: Prefs, zone: ZoneId): List<Span> = prefs.focusSlots.map {
        val start = date.atTime(it.hour, it.minute).atZone(zone).toInstant().toEpochMilli()
        Span(start, start + it.minutes * 60_000L)
    }
}
