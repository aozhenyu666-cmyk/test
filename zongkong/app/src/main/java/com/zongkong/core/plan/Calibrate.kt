package com.zongkong.core.plan

import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.Plan
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 统筹规划部的三件工具（都从你自己的记录里算，不靠感觉）：
 *
 *  估时校准：计划 45 分钟的事，你实际要多久？算出你的“估时系数”，排时间时自动乘上。
 *  精力地图：一天 24 小时，你在哪几个钟头真正干成了事。主线排在高的那几格。
 *  倒推规划：从截止日往回排，每步按校准后的时长、一天一步，最后留一天余量。
 *  （外加）在制品上限：同时“进行中”的超过 3 步就是在假装忙。
 */
object Calibrate {
    data class Sample(val actionKey: String, val title: String, val planned: Int, val actual: Int) {
        val ratio: Double get() = actual.toDouble() / planned
    }

    /** 每一步：计划时长（计划时间的起止）和实际时长（开始 → 暂停 / 做完 之间加起来）。 */
    fun samples(work: Work, zone: ZoneId): List<Sample> {
        val byAction = work.events.groupBy { it.actionKey }
        return work.actions().mapNotNull { a ->
            val p = a.plan ?: return@mapNotNull null
            if (!p.end.contains('T')) return@mapNotNull null
            val s = TimeParse.startMillis(p, zone) ?: return@mapNotNull null
            val e = TimeParse.endMillis(p, zone) ?: return@mapNotNull null
            val planned = ((e - s) / 60_000).toInt()
            if (planned < 5) return@mapNotNull null
            val actual = actualMinutes(byAction[a.key].orEmpty().sortedBy { it.at }.map { it.at to it.type })
            if (actual < 3 || a.actionStatus != ActionStatus.DONE && a.actionStatus != ActionStatus.CONFIRM) return@mapNotNull null
            Sample(a.key, a.title, planned, actual)
        }
    }

    /** 开始到下一次暂停 / 做完 / 卡住算一段，加起来。 */
    fun actualMinutes(events: List<Pair<Long, String>>): Int {
        var total = 0L
        var open: Long? = null
        for ((at, type) in events) {
            when {
                type == "start" -> if (open == null) open = at
                type == "pause" || type.startsWith("done:") || type.startsWith("stuck:") -> open?.let { total += (at - it).coerceIn(0, 6 * 3600_000L); open = null }
            }
        }
        return (total / 60_000).toInt()
    }

    /** 估时系数：实际 / 计划 的中位数。少于 3 个样本不给（免得一次意外把系数带偏）。 */
    fun factor(samples: List<Sample>): Double? {
        if (samples.size < 3) return null
        val r = samples.takeLast(20).map { it.ratio }.sorted()
        val mid = r.size / 2
        return if (r.size % 2 == 1) r[mid] else (r[mid - 1] + r[mid]) / 2
    }

    /** 精力地图：最近 [days] 天，每个钟头真正在做事的分钟数（开始 → 暂停 / 做完）。 */
    fun energy(work: Work, zone: ZoneId, now: Long, days: Int = 14): IntArray {
        val out = IntArray(24)
        val since = now - days * 86_400_000L
        work.events.filter { it.at >= since }.groupBy { it.actionKey }.values.forEach { evs ->
            var open: Long? = null
            evs.sortedBy { it.at }.forEach { e ->
                when {
                    e.type == "start" -> if (open == null) open = e.at
                    e.type == "pause" || e.type.startsWith("done:") || e.type.startsWith("stuck:") -> open?.let { s ->
                        var t = s
                        val end = minOf(e.at, s + 6 * 3600_000L)
                        while (t < end) {
                            val h = Instant.ofEpochMilli(t).atZone(zone).hour
                            val next = minOf(end, Instant.ofEpochMilli(t).atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli())
                            out[h] += ((next - t) / 60_000).toInt()
                            t = next
                        }
                        open = null
                    }
                }
            }
        }
        return out
    }

    /** 最好的几个钟头（有数据的）。 */
    fun bestHours(energy: IntArray, n: Int = 3): List<Int> = energy.indices.filter { energy[it] > 0 }.sortedByDescending { energy[it] }.take(n).sorted()

    /** 同时“进行中”的步数。 */
    fun wip(work: Work): Int = work.actions().count { it.actionStatus == ActionStatus.DOING }

    const val WIP_LIMIT = 3

    /**
     * 倒推：[steps]（标题, 你估的分钟）从截止日往回排。每天最多一步，最后一步在截止前一天做完（留一天余量），
     * 时长按估时系数放大，时间放在精力最好的钟头（没有数据就晚上 20 点）。
     * 太挤（天数不够）就从今天起每天一步，并标出来。
     */
    data class Slot(val title: String, val plan: Plan, val minutes: Int)

    fun backward(steps: List<Pair<String, Int>>, deadline: LocalDate, today: LocalDate, zone: ZoneId, factor: Double?, bestHour: Int?): Pair<List<Slot>, Boolean> {
        if (steps.isEmpty()) return emptyList<Slot>() to false
        val lastDay = deadline.minusDays(1)
        val available = java.time.temporal.ChronoUnit.DAYS.between(today, lastDay).toInt() + 1
        val crowded = available < steps.size
        val gap = if (crowded || steps.size == 1) 1 else (available / steps.size).coerceAtLeast(1)
        val hour = bestHour ?: 20
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val out = steps.mapIndexed { i, (title, est) ->
            val fromEnd = steps.size - 1 - i
            val day = if (crowded) today.plusDays(i.toLong()) else lastDay.minusDays((fromEnd * gap).toLong())
            val minutes = ((est.coerceAtLeast(10)) * (factor ?: 1.0)).toInt().coerceIn(10, 240)
            val start = ZonedDateTime.of(day.atTime(hour.coerceIn(0, 23), 0), zone)
            Slot(title, Plan(start.format(fmt), start.plusMinutes(minutes.toLong()).format(fmt)), minutes)
        }
        return out to crowded
    }

    /** 给总控看的一段。 */
    fun brief(work: Work, zone: ZoneId, now: Long): String {
        val s = samples(work, zone)
        val f = factor(s)
        val e = energy(work, zone, now)
        val best = bestHours(e)
        return buildString {
            append(if (f == null) "估时系数：样本不够（${s.size}/3）。" else "估时系数 ×%.1f（%d 个样本）：计划 60 分钟的事实际约 %d 分钟。".format(f, s.size, (60 * f).toInt()))
            if (best.isNotEmpty()) append("精力最好的钟头：${best.joinToString("、") { "$it 点" }}。")
            val w = wip(work)
            if (w > WIP_LIMIT) append("同时进行中 $w 步，超过上限 $WIP_LIMIT。")
        }
    }
}
