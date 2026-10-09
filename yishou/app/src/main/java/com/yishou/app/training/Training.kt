package com.yishou.app.training

import java.time.LocalDate

/** 一天的训练记录。 */
data class DayLog(
    val date: LocalDate,
    /** 当天有效的手数 */
    val effective: Int,
    /** 当天的目标组数 */
    val target: Int,
    val hit: Boolean,
)

/**
 * 今天的训练安排。像练体能一样：一组是 setSize 手有效回答；
 * 目标组数从起步值开始，连续两天达标加一组，连续两天没达标减一组。
 */
data class TrainingPlan(
    val setSize: Int,
    val targetSets: Int,
    val todayEffective: Int,
    /** 连续达标天数（今天达标了也算上） */
    val streak: Int,
    /** 最近 7 天，最早的在前，最后一天是今天 */
    val days: List<DayLog>,
    /** 今天的目标为什么变了；没变时为空 */
    val note: String,
) {
    val doneSets: Int get() = todayEffective / setSize
    /** 当前这一组已经有几手 */
    val inSet: Int get() = todayEffective % setSize
    val hitToday: Boolean get() = doneSets >= targetSets
}

object Training {
    const val MAX_SETS = 8

    /**
     * effectiveByDay：有过轮次的日子 → 当天有效手数（没有轮次的日子不要放进来）。
     * 从第一天用的日子开始，逐天推算目标。
     */
    fun plan(effectiveByDay: Map<LocalDate, Int>, today: LocalDate, setSize: Int, base: Int): TrainingPlan {
        val size = setSize.coerceAtLeast(1)
        val start = effectiveByDay.keys.filter { it < today }.minOrNull()
        val logs = mutableListOf<DayLog>()
        var target = base.coerceIn(1, MAX_SETS)
        var hitsInRow = 0
        var missesInRow = 0
        var note = ""
        if (start != null) {
            var d: LocalDate = start
            while (d < today) {
                val eff = effectiveByDay[d] ?: 0
                val hit = eff >= target * size
                logs += DayLog(d, eff, target, hit)
                note = ""
                if (hit) {
                    hitsInRow++
                    missesInRow = 0
                    if (hitsInRow >= 2 && target < MAX_SETS) {
                        target++
                        hitsInRow = 0
                        note = "连续两天达标，今天加一组。"
                    }
                } else {
                    missesInRow++
                    hitsInRow = 0
                    if (missesInRow >= 2 && target > 1) {
                        target--
                        missesInRow = 0
                        note = "连续两天没达标，今天减一组，先把手感找回来。"
                    }
                }
                d = d.plusDays(1)
            }
        }
        val todayEff = effectiveByDay[today] ?: 0
        val todayLog = DayLog(today, todayEff, target, todayEff >= target * size)
        var streak = if (todayLog.hit) 1 else 0
        for (log in logs.asReversed()) {
            if (log.hit) streak++ else break
        }
        val byDate = logs.associateBy { it.date }
        val week = (6 downTo 1).map { back ->
            val d = today.minusDays(back.toLong())
            byDate[d] ?: DayLog(d, 0, base.coerceIn(1, MAX_SETS), false)
        } + todayLog
        return TrainingPlan(size, target, todayEff, streak, week, note)
    }
}
