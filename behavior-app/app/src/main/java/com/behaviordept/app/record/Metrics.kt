package com.behaviordept.app.record

import com.behaviordept.app.data.Event
import com.behaviordept.app.data.EventType
import com.behaviordept.app.data.Review
import com.behaviordept.app.data.Rule
import com.behaviordept.app.data.UsageDay
import com.behaviordept.app.guard.RuleLogic
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 看板和复盘里的数字，全部从事件和记录里算，不手填（PRD M6）。
 * PRD 的四周成功标准：每周训练 ≥ 5 天；自测按时 ≥ 70%；娱乐未超限天数 ≥ 80%；折腾 : 训练 ≤ 1 : 4。
 */
object Metrics {
    /** 算作“当天有训练”的日期：专注至少 1 分钟，或者有任何训练类事件。 */
    fun trainedDates(events: List<Event>, dateOf: (Long) -> LocalDate): Set<LocalDate> =
        events.filter { it.type in EventType.TRAINING && (it.type != EventType.SESSION || (it.value ?: 0.0) >= 1.0) }
            .map { dateOf(it.time) }
            .toSet()

    fun minutesOf(events: List<Event>, type: String): Int = events.filter { it.type == type }.sumOf { it.value ?: 0.0 }.roundToInt()

    /** 每天的专注分钟数（热力图用）。 */
    fun dailyMinutes(events: List<Event>, dates: List<LocalDate>, dateOf: (Long) -> LocalDate): List<Pair<LocalDate, Int>> {
        val byDay = events.filter { it.type == EventType.SESSION }.groupBy { dateOf(it.time) }
        return dates.map { d -> d to (byDay[d]?.sumOf { it.value ?: 0.0 } ?: 0.0).roundToInt() }
    }

    /**
     * 自测按时完成率：区间内完成的自测里，在到期当天或之前完成的比例；
     * 现在还拖着没做的到期自测也算进分母。返回 (按时, 应做)。
     */
    fun reviewOnTime(reviews: List<Review>, from: Long, to: Long, overdueNow: Int, dateOf: (Long) -> LocalDate): Pair<Int, Int> {
        val done = reviews.filter { it.finishedAt != null && it.finishedAt >= from && it.finishedAt < to && it.dueAt != null }
        val onTime = done.count { r -> !dateOf(r.finishedAt!!).isAfter(dateOf(r.dueAt!!)) }
        return onTime to (done.size + overdueNow)
    }

    /** 某条规则每天的用时。 */
    fun ruleDaily(usage: List<UsageDay>, rule: Rule, dates: List<LocalDate>): List<Pair<LocalDate, Int>> {
        val byDate = usage.groupBy { it.date }
        return dates.map { d ->
            val m = byDate[d.toString()].orEmpty().associate { it.packageName to it.minutes }
            d to RuleLogic.minutesFor(rule, m)
        }
    }

    /** 所有规则都没超限的日子算“守住”。只算有用时数据的日子。返回 (守住, 有数据的天数)。 */
    fun usageKept(usage: List<UsageDay>, rules: List<Rule>, dates: List<LocalDate>): Pair<Int, Int> {
        if (rules.isEmpty()) return 0 to 0
        val withData = usage.map { it.date }.toSet()
        val counted = dates.filter { it.toString() in withData }
        val kept = counted.count { d ->
            rules.all { r -> ruleDaily(usage, r, listOf(d)).first().second <= r.dailyLimitMin }
        }
        return kept to counted.size
    }

    fun percent(part: Int, whole: Int): Int? = if (whole == 0) null else (part * 100.0 / whole).roundToInt()
}
