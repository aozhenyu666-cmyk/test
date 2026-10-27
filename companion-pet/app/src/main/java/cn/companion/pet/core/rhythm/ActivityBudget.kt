package cn.companion.pet.core.rhythm

import java.time.Instant
import java.time.ZoneId

/**
 * 一个时段允许的活跃度。
 *
 * [dailyCap] 是当天主动开口上限；为 0 表示这个时段**完全不主动开口**。
 */
data class ActivityPlan(val dailyCap: Int, val minIntervalMillis: Long)

/**
 * 按星期几与时段分配活跃度。纯函数，无 I/O、无随机。
 *
 * 依据的是「人在这个点大概是什么状态」，不是「到点了该说句话」：
 * - 工作日白天他在忙，最安静；
 * - 工作日晚上松下来，可以多说几句；
 * - 周末整体更有空，最活跃；
 * - 深夜陪熬夜，但只在 23:00-24:00，**00:00 之后完全安静**，不吵睡觉。
 *
 * 注意：这里只决定「最多能开口几次、隔多久」。用户明确点 x 之后的冷却
 * 由 PresencePolicy.dismissedUntil 先判，任何自动逻辑都不得缩短它。
 */
object ActivityBudget {

    /** 工作日白天。 */
    const val CAP_WEEKDAY_DAY = 25

    /** 工作日晚上与深夜。 */
    const val CAP_EVENING = 40

    /** 周末。 */
    const val CAP_WEEKEND = 60

    /** 00:00-06:00 完全安静。 */
    const val CAP_QUIET_HOURS = 0

    /** 两次开口的最小间隔：1.5 分钟，落在 1-2 分钟区间中部。 */
    const val MIN_INTERVAL_MILLIS: Long = 90_000L

    fun planFor(now: Long, zone: ZoneId): ActivityPlan {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val hour = local.hour
        val day = local.dayOfWeek
        val weekend = day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY

        val cap = when {
            // 凌晨必须完全安静：00:00 之后到 06:00 之前一次都不主动开口。
            hour < 6 -> CAP_QUIET_HOURS
            // 周末整天都算活跃（先判周末，再判时段）。
            weekend -> CAP_WEEKEND
            // 深夜 23:00-24:00 陪熬夜。
            hour >= 23 -> CAP_EVENING
            // 工作日晚上。
            hour >= 18 -> CAP_EVENING
            else -> CAP_WEEKDAY_DAY
        }
        return ActivityPlan(dailyCap = cap, minIntervalMillis = MIN_INTERVAL_MILLIS)
    }
}
