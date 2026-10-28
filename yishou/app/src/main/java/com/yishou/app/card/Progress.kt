package com.yishou.app.card

import com.yishou.app.data.CardAttempt
import com.yishou.app.data.CardMode

/**
 * 从做过的题卡算出：各小技能掌握得怎样、下一张练什么、出多难、多查几项、关屏到第几级、哪些错题该重做。
 * 全部由记录推出来，不另存状态；只看核对过答案的题卡。
 */
object Progress {
    /** 掌握度看最近几张 */
    const val WINDOW = 10
    /** 错题隔几天重做 */
    val REDO_DAYS = listOf(1, 3, 7)
    private const val DAY = 86_400_000L

    private fun ok(a: CardAttempt) = a.firstCorrect && a.help == 0

    /** 某个小技能最近的掌握度（自己完成且首答对的比例）；没做过为 null */
    fun mastery(attempts: List<CardAttempt>, skill: Skill): Double? {
        val xs = attempts.filter { it.verified && it.skill == skill.name }.takeLast(WINDOW)
        return if (xs.isEmpty()) null else xs.count(::ok).toDouble() / xs.size
    }

    /** 下一张练什么：没做过的小技能先做；都做过，挑掌握度最低的，同分挑做得少的 */
    fun nextSkill(attempts: List<CardAttempt>, among: List<Skill> = Skill.entries): Skill {
        val counts = attempts.filter { it.verified }.groupingBy { it.skill }.eachCount()
        among.firstOrNull { (counts[it.name] ?: 0) == 0 }?.let { return it }
        return among.minWith(compareBy<Skill> { mastery(attempts, it) ?: 0.0 }.thenBy { counts[it.name] ?: 0 })
    }

    /** 出多难（1–3）：最近 6 张对了八成半以上就加一级，不到一半就减一级。目标正确率七到八成。 */
    fun difficulty(attempts: List<CardAttempt>, skill: Skill): Int {
        val xs = attempts.filter { it.verified && it.skill == skill.name }
        val last = xs.lastOrNull()?.difficulty ?: 1
        val recent = xs.takeLast(6)
        if (recent.size < 6) return last
        val rate = recent.count(::ok).toDouble() / recent.size
        return when {
            rate >= 0.84 -> (last + 1).coerceAtMost(3)
            rate < 0.5 -> (last - 1).coerceAtLeast(1)
            else -> last
        }
    }

    /** 多查几项（1–3）：最近查得准就多查一项，查不准就少查一项 */
    fun checks(attempts: List<CardAttempt>): Int {
        val xs = attempts.filter { it.checks > 0 }
        val last = xs.lastOrNull()?.checks ?: 1
        val recent = xs.takeLast(WINDOW)
        if (recent.size < 5) return last
        val rate = recent.sumOf { it.checksOk }.toDouble() / recent.sumOf { it.checks }.coerceAtLeast(1)
        return when {
            rate >= 0.8 -> (last + 1).coerceAtMost(3)
            rate < 0.5 -> (last - 1).coerceAtLeast(1)
            else -> last
        }
    }

    /**
     * 关屏阶梯（只看训练里做的，不看入口思考页）：1 材料同屏，2 看完收起、可以记几个字，3 收起、不许记。
     * 当前一级最近 8 张里至少 6 张、七成半自己做对，升一级；最近 6 张不到一半，降一级。
     */
    fun level(attempts: List<CardAttempt>): Int {
        val xs = attempts.filter { it.verified && it.mode != CardMode.GATE }
        val cur = xs.lastOrNull()?.level ?: 1
        val atLevel = xs.filter { it.level == cur }
        val last8 = atLevel.takeLast(8)
        val last6 = atLevel.takeLast(6)
        return when {
            last8.size >= 6 && last8.count(::ok).toDouble() / last8.size >= 0.75 && cur < 3 -> cur + 1
            last6.size >= 6 && last6.count(::ok).toDouble() / last6.size < 0.5 && cur > 1 -> cur - 1
            else -> cur
        }
    }

    /**
     * 该重做的错题：做错过（或靠帮助做对）的题卡，隔 1、3、7 天各重做一次；重做又错就从 1 天重来。
     * 返回题卡 id，最早该做的在前。
     */
    fun dueRedo(attempts: List<CardAttempt>, now: Long): List<Long> =
        attempts.filter { it.verified }.groupBy { it.cardId }.mapNotNull { (id, xs) ->
            val sorted = xs.sortedBy { it.createdAt }
            val firstMiss = sorted.indexOfFirst { !ok(it) }
            if (firstMiss < 0) return@mapNotNull null
            val after = sorted.drop(firstMiss + 1)
            val last = sorted.last()
            val due = if (!ok(last)) {
                last.createdAt + REDO_DAYS[0] * DAY
            } else {
                val stage = after.takeLastWhile(::ok).size
                if (stage >= REDO_DAYS.size) return@mapNotNull null
                last.createdAt + REDO_DAYS[stage] * DAY
            }
            if (due <= now) id to due else null
        }.sortedBy { it.second }.map { it.first }

    /** 进步页的几个数 */
    data class Stats(
        val total: Int,
        val firstCorrect: Int,
        val finalCorrect: Int,
        val changedRight: Int,
        val changedWrong: Int,
        val highConf: Int,
        val highConfCorrect: Int,
        val independent: Int,
        val longestHidden: Int,
    ) {
        companion object {
            fun ratio(a: Int, b: Int): String = if (b == 0) "-" else "$a/$b"
        }
    }

    fun stats(attempts: List<CardAttempt>): Stats {
        val xs = attempts.filter { it.verified }
        val high = xs.filter { it.conf == Conf.HIGH.label }
        return Stats(
            total = xs.size,
            firstCorrect = xs.count { it.firstCorrect },
            finalCorrect = xs.count { it.finalCorrect },
            changedRight = xs.count { it.final != it.bet && it.finalCorrect },
            changedWrong = xs.count { it.final != it.bet && it.firstCorrect && !it.finalCorrect },
            highConf = high.size,
            highConfCorrect = high.count { it.firstCorrect },
            independent = xs.count { it.help == 0 },
            longestHidden = xs.filter { it.level >= 2 && ok(it) }.maxOfOrNull { it.level } ?: 1,
        )
    }
}
