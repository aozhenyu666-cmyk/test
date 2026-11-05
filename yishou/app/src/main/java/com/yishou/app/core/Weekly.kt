package com.yishou.app.core

/**
 * 周报：只看离开 App 也成立的数。
 *
 *  - 专注了多少分钟；
 *  - 定下的下一步做到了几成；
 *  - 行测正确率和每题用时（后半周 vs 前半周）；
 *  - 每月一次的裸考（不开一手，限时做一套）。
 *
 * 裸考没变好，结论就是“换机制”，不是“加功能”。
 * 纯函数，不碰 Android，可以单测。
 */
data class DayRecord(
    val date: String,
    /** 离开手机的专注分钟。 */
    val focusMinutes: Int = 0,
    /** 当天定下的下一步动作有几条。 */
    val actionsSet: Int = 0,
    /** 其中做到了几条。 */
    val actionsDone: Int = 0,
    /** 陪练走了几手。 */
    val rounds: Int = 0,
    /** 其中判定有效几手。 */
    val effectiveRounds: Int = 0,
    /** 行测正确率 0..1，没有记录为 null。 */
    val accuracy: Double? = null,
    /** 平均每题用时（秒），没有记录为 null。 */
    val secondsPerQuestion: Double? = null,
    /** 裸考成绩 0..1；只有裸考周才有。 */
    val nakedScore: Double? = null,
)

enum class WeeklyVerdict {
    KEEP,
    CHANGE_MECHANISM,
    INSUFFICIENT,
}

data class WeeklyReport(
    val days: Int,
    val focusMinutes: Int,
    /** 定下的下一步做到了几成，0..1；没定过为 null。 */
    val settleRate: Double?,
    /** 陪练判定有效率，0..1；没走过为 null。 */
    val effectiveRate: Double?,
    /** 后半周正确率减前半周，正数是变好。 */
    val accuracyDelta: Double?,
    /** 后半周每题用时减前半周（秒），正数是变慢。 */
    val speedDeltaSeconds: Double?,
    val verdict: WeeklyVerdict,
    val lines: List<String>,
)

object Weekly {
    /** 裸考要比上次高出这么多，才算“真的变好”。 */
    const val NAKED_GAIN = 0.02

    /** 下一步做到的比例低于这个数，说明机制没落地。 */
    const val MIN_SETTLE = 0.4

    fun build(records: List<DayRecord>, previousNaked: Double? = null): WeeklyReport {
        val days = records.size
        val focus = records.sumOf { it.focusMinutes }
        val set = records.sumOf { it.actionsSet }
        val done = records.sumOf { it.actionsDone }
        val rounds = records.sumOf { it.rounds }
        val effective = records.sumOf { it.effectiveRounds }

        val settle = if (set > 0) done.toDouble() / set else null
        val effectiveRate = if (rounds > 0) effective.toDouble() / rounds else null
        val accuracyDelta = trend(records.map { it.accuracy })
        val speedDelta = trend(records.map { it.secondsPerQuestion })
        val naked = records.mapNotNull { it.nakedScore }.lastOrNull()

        val verdict = when {
            days == 0 -> WeeklyVerdict.INSUFFICIENT
            // 裸考没变好（或第一次裸考没有基线）→ 换机制
            naked != null && (previousNaked == null || naked <= previousNaked + NAKED_GAIN) ->
                WeeklyVerdict.CHANGE_MECHANISM
            settle == null && accuracyDelta == null && focus == 0 -> WeeklyVerdict.INSUFFICIENT
            settle != null && settle < MIN_SETTLE -> WeeklyVerdict.CHANGE_MECHANISM
            else -> WeeklyVerdict.KEEP
        }

        val lines = buildList {
            add("这周有记录 $days 天，专注 $focus 分钟。")
            settle?.let { add("定下的下一步做到了 ${pct(it)}。") }
            effectiveRate?.let { add("陪练 $rounds 手，判定有效 ${pct(it)}。") }
            accuracyDelta?.let { add("行测正确率后半周比前半周${signedPoints(it)}。") }
            speedDelta?.let { add("每题用时后半周比前半周${signedSeconds(it)}。") }
            naked?.let {
                val base = previousNaked?.let { p -> "，上次 ${pct(p)}" } ?: "，第一次测"
                add("裸考 ${pct(it)}$base。")
            }
            add(
                when (verdict) {
                    WeeklyVerdict.KEEP -> "结论：照现在的做法继续，不加功能。"
                    WeeklyVerdict.CHANGE_MECHANISM -> "结论：换机制，不加功能。"
                    WeeklyVerdict.INSUFFICIENT -> "结论：这周数据不够，先照常跑。"
                },
            )
        }

        return WeeklyReport(
            days = days,
            focusMinutes = focus,
            settleRate = settle,
            effectiveRate = effectiveRate,
            accuracyDelta = accuracyDelta,
            speedDeltaSeconds = speedDelta,
            verdict = verdict,
            lines = lines,
        )
    }

    /** 后半段减前半段；少于 4 个有效值就不下结论。 */
    private fun trend(values: List<Double?>): Double? {
        val vs = values.filterNotNull()
        if (vs.size < 4) return null
        val mid = vs.size / 2
        return vs.drop(mid).average() - vs.take(mid).average()
    }

    private fun pct(x: Double): String = "${(x * 100).toInt()}%"

    private fun signedPoints(x: Double): String =
        if (x >= 0) "高 ${(x * 100).toInt()} 个百分点" else "低 ${(-x * 100).toInt()} 个百分点"

    private fun signedSeconds(x: Double): String =
        if (x >= 0) "慢了 ${x.toInt()} 秒" else "快了 ${(-x).toInt()} 秒"
}
