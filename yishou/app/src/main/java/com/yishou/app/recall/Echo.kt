package com.yishou.app.recall

import com.yishou.app.data.Round
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 今天该回响的一条：哪一轮、第几天的回响、隔了几天。 */
data class EchoItem(val round: Round, val stage: Int, val ageDays: Int)

/**
 * 间隔回响：有效的一轮在第 1、3、7 天各回想一次。
 * 错过了不要紧：第 1 天的回响在第 1–2 天都算，第 3 天的在 3–6 天，第 7 天的在 7–13 天。
 * 每一档每天最多出一条（挑那天说得最长的一手），一天最多 [MAX_PER_DAY] 条。
 */
object Echo {
    val STAGES = listOf(1, 3, 7)
    const val MAX_PER_DAY = 3

    fun stageFor(ageDays: Int): Int? = when (ageDays) {
        in 1..2 -> 1
        in 3..6 -> 3
        in 7..13 -> 7
        else -> null
    }

    /**
     * rounds：最近 14 天的轮次；done：已经回响过的 (roundId, stage)；
     * doneToday：今天已经回响过的档位，这些档今天不再出。
     */
    fun due(
        rounds: List<Round>,
        done: Set<Pair<Long, Int>>,
        doneToday: Set<Int>,
        today: LocalDate,
        zone: ZoneId,
    ): List<EchoItem> =
        rounds.asSequence()
            .filter { it.effective && it.judged && it.userAnswer.isNotBlank() }
            .mapNotNull { r ->
                val day = Instant.ofEpochMilli(r.createdAt).atZone(zone).toLocalDate()
                val age = ChronoUnit.DAYS.between(day, today).toInt()
                val stage = stageFor(age) ?: return@mapNotNull null
                if ((r.id to stage) in done || stage in doneToday) null else EchoItem(r, stage, age)
            }
            .groupBy { it.stage }
            .mapNotNull { (_, items) -> items.maxWithOrNull(compareBy<EchoItem> { it.round.userAnswer.length }.thenBy { it.round.createdAt }) }
            .sortedBy { it.stage }
            .take((MAX_PER_DAY - doneToday.size).coerceAtLeast(0))
}
