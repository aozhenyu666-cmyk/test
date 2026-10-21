package com.moling.core.usage

import com.moling.core.flow.Flow
import com.moling.core.flow.FlowPlan
import kotlinx.serialization.Serializable

/*
 * 手机使用的脉络（v8）：只用系统“使用情况访问”给的“哪个应用、几点到几点在前台”，不看屏幕里的内容。
 * 整理成：今天各应用用了多久、哪几个小时被娱乐拉走、拿起手机几次、最长一次刷了多久、
 * 计划里的每条水道实际被占掉多少；每天留一份，看一周的规律。
 */

/** 一段前台使用。[category] 和大脑的分类一致（ENTERTAINMENT / SHOPPING / WORK / ACTOR / OTHER）。 */
data class Session(val pkg: String, val label: String, val category: String, val start: Long, val end: Long) {
    val minutes: Double get() = (end - start).coerceAtLeast(0) / 60_000.0
}

/** 一天的使用（存下来看规律）。[playByHour] 24 个数：每小时娱乐几分钟。 */
@Serializable
data class UsageDay(
    val day: String,
    val screenMin: Int = 0,
    val playMin: Int = 0,
    val workMin: Int = 0,
    val pickups: Int = 0,
    val longestPlay: Int = 0,
    val playByHour: List<Int> = List(24) { 0 },
    /** 用得最多的几个：“抖音 85”。 */
    val top: List<String> = emptyList(),
)

object UsageLog {
    private fun isPlay(c: String) = c == "ENTERTAINMENT" || c == "SHOPPING"

    /** 把一天的会话整理成 [UsageDay]。两段之间隔不到 1 分钟、同一个应用的算一段（算“最长一次”用）。 */
    fun day(day: String, sessions: List<Session>, dayStart: Long): UsageDay {
        val s = sessions.filter { it.end > it.start }.sortedBy { it.start }
        val byHour = IntArray(24)
        var playMs = 0.0
        var workMs = 0.0
        s.forEach { x ->
            if (isPlay(x.category)) {
                playMs += x.minutes
                var t = x.start
                while (t < x.end) {
                    val h = (((t - dayStart) / 3_600_000L).toInt()).coerceIn(0, 23)
                    val hourEnd = dayStart + (h + 1) * 3_600_000L
                    val e = minOf(x.end, hourEnd)
                    byHour[h] += ((e - t) / 60_000L).toInt()
                    t = if (e > t) e else x.end
                }
            }
            if (x.category == "WORK") workMs += x.minutes
        }
        // 连着的同一个娱乐应用合成一段
        var longest = 0.0
        var curPkg = ""
        var curStart = 0L
        var curEnd = 0L
        s.filter { isPlay(it.category) }.forEach { x ->
            if (x.pkg == curPkg && x.start - curEnd < 60_000L) curEnd = maxOf(curEnd, x.end)
            else { curPkg = x.pkg; curStart = x.start; curEnd = x.end }
            longest = maxOf(longest, (curEnd - curStart) / 60_000.0)
        }
        // 拿起手机：和上一段隔了 5 分钟以上的开始
        var pickups = 0
        var lastEnd = 0L
        s.forEach { x -> if (x.start - lastEnd > 5 * 60_000L) pickups++; lastEnd = maxOf(lastEnd, x.end) }
        val top = s.groupBy { it.label.ifBlank { it.pkg } }.mapValues { e -> e.value.sumOf { it.minutes } }
            .entries.sortedByDescending { it.value }.take(6).filter { it.value >= 1 }.map { "${it.key} ${it.value.toInt()}" }
        return UsageDay(
            day, s.sumOf { it.minutes }.toInt(), playMs.toInt(), workMs.toInt(), pickups, longest.toInt(), byHour.toList(), top,
        )
    }

    /** 计划里的每条（到现在为止的部分）被娱乐占了多少分钟：“主河道 14:00–15:30 被占 35 分钟（抖音）”。 */
    fun planVsActual(plan: FlowPlan?, sessions: List<Session>, dayStart: Long, now: Long): List<String> {
        plan ?: return emptyList()
        return plan.channels.filter { it.startMin >= 0 && dayStart + it.startMin * 60_000L < now }.mapNotNull { c ->
            val a = dayStart + c.startMin * 60_000L
            val b = minOf(now, dayStart + c.endMin * 60_000L)
            val hit = sessions.filter { isPlay(it.category) && it.end > a && it.start < b }
            val min = hit.sumOf { (minOf(it.end, b) - maxOf(it.start, a)).coerceAtLeast(0) } / 60_000L
            if (min < 3 || c.kind.name == "FREE" || c.kind.name == "POOL") null
            else "${c.kind.short}「${c.title}」${Flow.fmt(c.startMin)}–${Flow.fmt(c.endMin)}：被娱乐占了 $min 分钟（${hit.groupBy { it.label }.maxByOrNull { e -> e.value.sumOf { it.minutes } }?.key.orEmpty()}）"
        }
    }

    /** 几天里最容易被拉走的时段：“22–24 点（平均 48 分钟）”。 */
    fun weakHours(days: List<UsageDay>, top: Int = 2): List<String> {
        if (days.isEmpty()) return emptyList()
        val avg = (0 until 24).map { h -> h to days.sumOf { it.playByHour.getOrElse(h) { 0 } } / days.size.toDouble() }
        // 两个小时一组，找最重的几组
        val pairs = (0 until 23).map { h -> h to avg[h].second + avg[h + 1].second }.sortedByDescending { it.second }
        val out = mutableListOf<Pair<Int, Double>>()
        for (p in pairs) {
            if (out.size >= top || p.second < 10) break
            if (out.none { kotlin.math.abs(it.first - p.first) < 2 }) out += p
        }
        return out.map { "${it.first}–${it.first + 2} 点（平均 ${it.second.toInt()} 分钟）" }
    }

    /** 给她思考 / 对话用的一段话。 */
    fun text(today: UsageDay?, plan: List<String>, week: List<UsageDay>): String = buildString {
        today?.let { d ->
            appendLine("今天亮屏用了 ${d.screenMin} 分钟，娱乐 ${d.playMin} 分钟，拿起手机 ${d.pickups} 次，最长一次连着刷 ${d.longestPlay} 分钟。")
            if (d.top.isNotEmpty()) appendLine("用得最多：" + d.top.joinToString("、") { it + " 分钟" })
        }
        plan.forEach { appendLine(it) }
        val past = week.filter { it.day != today?.day }.takeLast(7)
        if (past.isNotEmpty()) {
            appendLine("前 ${past.size} 天平均：娱乐 ${past.sumOf { it.playMin } / past.size} 分钟、拿起 ${past.sumOf { it.pickups } / past.size} 次。")
            weakHours(past + listOfNotNull(today)).takeIf { it.isNotEmpty() }?.let { appendLine("最容易被拉走的时段：" + it.joinToString("、")) }
        }
    }.trim()
}
