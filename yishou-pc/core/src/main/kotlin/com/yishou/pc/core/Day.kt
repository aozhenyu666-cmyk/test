package com.yishou.pc.core

/** 一次放行：哪一类、从几点到几点、说好去做什么、回来做什么。 */
data class Pass(
    val category: String,
    val start: Long,
    val end: Long,
    val what: String,
    val then: String,
    val emergency: Boolean = false,
)

/** 从当天的逃跑记录算出来的状态。 */
data class DayState(
    val events: List<Event> = emptyList(),
    /** 每一类已经放出去的分钟数（急事不算额度） */
    val used: Map<String, Int> = emptyMap(),
    val passes: List<Pass> = emptyList(),
    /** 每一类想打开了几次 */
    val gates: Map<String, Int> = emptyMap(),
    /** 每一类放行了几次（不含急事） */
    val grants: Map<String, Int> = emptyMap(),
    val declines: Int = 0,
    val returns: Int = 0,
    val emergencies: Int = 0,
    /** 说好回来做的，答了“做了”的次数和一共答了几次 */
    val kept: Int = 0,
    val answered: Int = 0,
) {
    fun activePass(category: String, now: Long): Pass? = passes.lastOrNull { it.category == category && now in it.start until it.end }

    /** 这一类最近一次已经结束的放行（结束后 [window] 毫秒内算“刚到时间”） */
    fun justExpired(category: String, now: Long, window: Long = 15 * 60_000L): Pass? =
        passes.lastOrNull { it.category == category && it.end <= now && now - it.end <= window }

    /** 还没回答“做了吗”的那次放行：最近一次已经结束、写了“回来做什么”、之后没有回答过的。 */
    fun pendingFollowUp(now: Long): Pass? {
        val last = passes.filter { it.end <= now && it.then.isNotBlank() }.maxByOrNull { it.end } ?: return null
        val answered = events.any { it.type == EventType.FOLLOWUP && it.time >= last.end }
        return if (answered) null else last
    }

    companion object {
        fun from(events: List<Event>): DayState {
            val sorted = events.sortedBy { it.time }
            val passes = sorted.filter { it.type == EventType.GRANT || it.type == EventType.EMERGENCY }.map {
                Pass(it.category, it.time, it.time + it.minutes * 60_000L, it.what, it.then, it.type == EventType.EMERGENCY)
            }
            val grants = sorted.filter { it.type == EventType.GRANT }
            val followups = sorted.filter { it.type == EventType.FOLLOWUP }
            return DayState(
                events = sorted,
                used = grants.groupBy { it.category }.mapValues { (_, es) -> es.sumOf { it.minutes } },
                passes = passes,
                gates = sorted.filter { it.type == EventType.GATE }.groupingBy { it.category }.eachCount(),
                grants = grants.groupingBy { it.category }.eachCount(),
                declines = sorted.count { it.type == EventType.DECLINE },
                returns = sorted.count { it.type == EventType.RETURN },
                emergencies = sorted.count { it.type == EventType.EMERGENCY },
                kept = followups.count { it.done },
                answered = followups.size,
            )
        }
    }
}
