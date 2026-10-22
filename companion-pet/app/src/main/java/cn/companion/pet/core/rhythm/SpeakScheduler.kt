package cn.companion.pet.core.rhythm

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

data class RhythmConfig(
    val minIntervalMillis: Long = 6 * 60_000L,
    val maxIntervalMillis: Long = 12 * 60_000L,
    val firstAfterScreenOnMinMillis: Long = 60_000L,
    val firstAfterScreenOnMaxMillis: Long = 120_000L,
    /** 夜间安静时段，按一天中的分钟数表示；start > end 表示跨午夜。 */
    val quietStartMinute: Int = 0,
    val quietEndMinute: Int = 7 * 60,
    val focusMultiplier: Double = 2.0,
    val skipRetryMillis: Long = 60_000L,
    /** 被完整忽略一轮后，下次间隔乘以这个系数（提前 50%）。 */
    val afterIgnoredFactor: Double = 0.5,
)

/**
 * 决定下一次什么时候开口。
 *
 * 状态只有一个“锚点 + 间隔”：到期时刻 = 锚点 + 间隔（专注时间隔翻倍）。
 * 熄屏时不计划；安静时段内永远不到期。所有时间由调用方传入。
 */
class SpeakScheduler(
    var config: RhythmConfig = RhythmConfig(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val random: Random = Random.Default,
) {
    private var anchor: Long? = null
    private var interval: Long = 0L
    private var focusScales = true

    val isPlanned: Boolean get() = anchor != null

    /** 亮屏：1–2 分钟内先开口一次。 */
    fun onScreenOn(now: Long) {
        plan(now, randomBetween(config.firstAfterScreenOnMinMillis, config.firstAfterScreenOnMaxMillis), focusScales = false)
    }

    fun onScreenOff() {
        anchor = null
    }

    /** 说完一句（无论用户有没有回应）。 */
    fun onSpoke(now: Long) {
        plan(now, randomInterval(), focusScales = true)
    }

    /** 被忽略升级走完一轮：下次提前 50%。 */
    fun onIgnoredFully(now: Long) {
        plan(now, (randomInterval() * config.afterIgnoredFactor).toLong(), focusScales = true)
    }

    /** 探针返回 skip、或这次条件不满足：1 分钟后再试。 */
    fun retrySoon(now: Long) {
        plan(now, config.skipRetryMillis, focusScales = false)
    }

    /** 到期时刻；未计划时为 null。 */
    fun dueAt(focusActive: Boolean): Long? {
        val a = anchor ?: return null
        val scale = if (focusActive && focusScales) config.focusMultiplier else 1.0
        return a + (interval * scale).toLong()
    }

    fun isDue(now: Long, focusActive: Boolean): Boolean {
        val due = dueAt(focusActive) ?: return false
        return now >= due && !isQuiet(now)
    }

    fun isQuiet(now: Long): Boolean = inQuietHours(now, config.quietStartMinute, config.quietEndMinute, zone)

    private fun plan(now: Long, intervalMillis: Long, focusScales: Boolean) {
        anchor = now
        interval = intervalMillis
        this.focusScales = focusScales
    }

    private fun randomInterval(): Long = randomBetween(config.minIntervalMillis, config.maxIntervalMillis)

    private fun randomBetween(min: Long, max: Long): Long =
        if (max <= min) min else random.nextLong(min, max + 1)

    companion object {
        fun inQuietHours(now: Long, startMinute: Int, endMinute: Int, zone: ZoneId): Boolean {
            if (startMinute == endMinute) return false
            val t = Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
            val m = t.hour * 60 + t.minute
            return if (startMinute < endMinute) m in startMinute until endMinute else m >= startMinute || m < endMinute
        }
    }
}
