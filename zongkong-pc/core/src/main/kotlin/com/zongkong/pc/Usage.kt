package com.zongkong.pc

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 一段连续在用同一个东西的时间。[title] 是窗口标题（截短），给总控核对用。 */
@Serializable
data class Seg(val from: Long, val to: Long, val key: String, val label: String, val cat: String, val title: String = "") {
    val ms: Long get() = (to - from).coerceAtLeast(0)
}

/** 一天的电脑使用（北京时间，凌晨 4 点换日——和手机一样）。 */
@Serializable
data class UsageDay(val date: String, val segs: List<Seg> = emptyList()) {
    fun totalMs(): Long = segs.sumOf { it.ms }
    fun byKey(): Map<String, Long> = segs.groupBy { it.key }.mapValues { (_, v) -> v.sumOf { it.ms } }
    fun byCat(): Map<Cat, Long> = segs.groupBy { Cat.of(it.cat) }.mapValues { (_, v) -> v.sumOf { it.ms } }
    fun catMinutes(c: Cat): Int = ((byCat()[c] ?: 0L) / 60_000).toInt()
    fun keyMinutes(key: String): Int = ((byKey()[key] ?: 0L) / 60_000).toInt()
    fun totalMinutes(): Int = (totalMs() / 60_000).toInt()

    /** 用得最多的几样：名字、分钟、类。 */
    fun top(n: Int): List<Triple<String, Int, Cat>> = segs.groupBy { it.key }
        .map { (_, v) -> Triple(v.first().label, (v.sumOf { it.ms } / 60_000).toInt(), Cat.of(v.first().cat)) }
        .sortedByDescending { it.second }.take(n)

    /** 从 [since] 起一直在用（中间断开不超过 [gapMs]）的时长。 */
    fun continuousMs(now: Long, gapMs: Long = 5 * 60_000L): Long {
        var start = now
        var end = now
        for (s in segs.asReversed()) {
            if (end - s.to > gapMs) break
            start = s.from
            end = s.from
        }
        return now - start
    }
}

object Usage {
    /** 两次采样之间隔多久还算连着（超过就另起一段）。 */
    const val JOIN_MS = 5_000L

    /** 逻辑日：凌晨 4 点以前算前一天。 */
    fun day(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).minusHours(4).toLocalDate()

    /** 记一次采样：在用 [t]，从 [at] - [stepMs] 到 [at]。 */
    fun add(d: UsageDay, t: Target, title: String, at: Long, stepMs: Long): UsageDay {
        val last = d.segs.lastOrNull()
        val short = title.take(80)
        if (last != null && last.key == t.key && at - last.to <= JOIN_MS + stepMs) {
            return d.copy(segs = d.segs.dropLast(1) + last.copy(to = at, title = if (short.isNotBlank()) short else last.title))
        }
        return d.copy(segs = d.segs + Seg(at - stepMs, at, t.key, t.label, t.cat.name, short))
    }

    /** 一段时间里的使用：按东西合计，再列出每一段（给总控核对“我 2 点到 4 点在写作业”这种话）。 */
    fun between(d: UsageDay, from: Long, to: Long): List<Seg> = d.segs.mapNotNull { s ->
        val a = maxOf(s.from, from)
        val b = minOf(s.to, to)
        if (b > a) s.copy(from = a, to = b) else null
    }

    fun hm(t: Long, zone: ZoneId): String = Instant.ofEpochMilli(t).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }

    /** 给人和模型看的一段文字。 */
    fun describe(segs: List<Seg>, zone: ZoneId, maxLines: Int = 40): String = buildString {
        if (segs.isEmpty()) { append("这段时间没有在用电脑（或者总控没在运行）。"); return@buildString }
        val total = segs.sumOf { it.ms }
        append("共 ${total / 60_000} 分钟。按东西：")
        append(segs.groupBy { it.key }.map { (_, v) -> v.first().label to v.sumOf { it.ms } }.sortedByDescending { it.second }.take(8)
            .joinToString("，") { "${it.first} ${it.second / 60_000} 分" })
        append("\n按时间（≥1 分钟的段）：\n")
        segs.filter { it.ms >= 60_000 }.takeLast(maxLines).forEach {
            append("${hm(it.from, zone)}–${hm(it.to, zone)} ${it.label}（${Cat.of(it.cat).label}）")
            if (it.title.isNotBlank() && !it.key.startsWith("app:")) append("「${it.title.take(40)}」")
            append('\n')
        }
    }
}
