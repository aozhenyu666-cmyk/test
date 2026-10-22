package com.moling.core.cycle

import kotlinx.serialization.Serializable

/** 第十批 T7：有预算的主动循环；预算跨重启保留，用户可调，消耗不能重启清零。 */
@Serializable
data class AutonomyBudget(
    val day: String = "",
    val searches: Int = 0,
    val fetches: Int = 0,
    val jobs: Int = 0,
    val unread: Int = 0,
    val maxSearches: Int = 4,
    val maxFetches: Int = 12,
    val maxJobs: Int = 6,
    val maxUnread: Int = 2,
    val enabled: Boolean = false,
) {
    fun fresh(day: String) = if (this.day == day) this else copy(day = day, searches = 0, fetches = 0, jobs = 0, unread = 0)
    fun canSearch() = enabled && searches < maxSearches
    fun canFetch() = enabled && fetches < maxFetches
    fun canJob() = enabled && jobs < maxJobs
    fun canUnread() = unread < maxUnread
    fun spendSearch() = if (canSearch()) copy(searches = searches + 1) else null
    fun spendFetch() = if (canFetch()) copy(fetches = fetches + 1) else null
    fun spendJob() = if (canJob()) copy(jobs = jobs + 1) else null
    fun spendUnread() = if (canUnread()) copy(unread = unread + 1) else null
}

@Serializable
data class CycleState(
    val seenEventIds: List<String> = emptyList(),
    val budget: AutonomyBudget = AutonomyBudget(),
    val lastAutoAt: Long = 0,
)

/** 同一事件只处理一次；用户请求优先于自动事件。 */
object CognitiveCycle {
    const val KEEP = 300
    fun once(st: CycleState, eventId: String): Pair<CycleState, Boolean> {
        if (eventId.isBlank() || eventId in st.seenEventIds) return st to false
        return st.copy(seenEventIds = (st.seenEventIds + eventId).takeLast(KEEP)) to true
    }
}

/** 安静期 / 用户停止：后台只能准备，不能前台出声。 */
@Serializable
data class QuietPeriod(val until: Long = 0, val reason: String = "") {
    fun active(now: Long) = until > now
    fun start(now: Long, minutes: Int, reason: String) = QuietPeriod(now + minutes * 60_000L, reason)
}