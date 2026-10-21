package com.moling.core.rhythm

import com.moling.core.mind.BEIJING
import kotlinx.serialization.Serializable
import java.time.LocalDate

/*
 * 作息（v4 C7）：用亮屏 / 熄屏推算昨晚睡了多久。只用系统的“使用情况”事件，不加权限。
 */

/** 一次亮屏（[on] = true）或熄屏。 */
data class ScreenEvent(val at: Long, val on: Boolean)

/** 一晚：[day] 是醒来那天（北京日历日）。[wakeUps]：中间亮屏不到 3 分钟的次数。 */
@Serializable
data class SleepNight(val day: String, val sleepAt: Long, val wakeAt: Long, val wakeUps: Int) {
    val hours: Double get() = (wakeAt - sleepAt) / 3_600_000.0
}

object Rhythm {
    private const val MIN = 60_000L
    /** 中间亮屏短于这么久，算“醒了一下”，不打断睡眠。 */
    const val BLIP_MS = 3 * MIN
    /** 最长的一段熄屏至少这么久才算睡了。 */
    const val MIN_SLEEP_MS = 3 * 60 * MIN

    /**
     * [day] 那天早上醒来的那一晚：在前一天 20:00 到当天 12:00 之间，找最长的一段熄屏（中间短亮屏并进去）。
     * 事件不够或没有 3 小时以上的熄屏返回 null（不瞎猜）。
     */
    fun lastNight(events: List<ScreenEvent>, day: String): SleepNight? {
        val d = LocalDate.parse(day)
        val from = d.minusDays(1).atTime(20, 0).atZone(BEIJING).toInstant().toEpochMilli()
        val to = d.atTime(12, 0).atZone(BEIJING).toInstant().toEpochMilli()
        val evs = events.filter { it.at in from..to }.sortedBy { it.at }
        // 熄屏段：每个熄屏事件到下一个亮屏事件
        val off = mutableListOf<LongArray>()
        var offAt = -1L
        for (e in evs) {
            if (!e.on && offAt < 0) offAt = e.at
            if (e.on && offAt >= 0) { off += longArrayOf(offAt, e.at); offAt = -1 }
        }
        if (off.isEmpty()) return null
        // 把间隔短于 BLIP 的熄屏段并起来
        val merged = mutableListOf<Triple<Long, Long, Int>>()
        for (seg in off) {
            val last = merged.lastOrNull()
            if (last != null && seg[0] - last.second < BLIP_MS) merged[merged.size - 1] = Triple(last.first, seg[1], last.third + 1)
            else merged += Triple(seg[0], seg[1], 0)
        }
        val best = merged.maxByOrNull { it.second - it.first } ?: return null
        if (best.second - best.first < MIN_SLEEP_MS) return null
        return SleepNight(day, best.first, best.second, best.third)
    }

    /** 睡得少的那天给排水道的提醒（没有就空）。 */
    fun advice(n: SleepNight?): String =
        if (n != null && n.hours < 6) "昨晚只睡了 %.1f 小时：主河道每段别超过 45 分钟，多留水潭".format(n.hours) else ""
}
