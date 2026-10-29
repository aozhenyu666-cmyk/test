package cn.companion.pet.core.proactive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 主动交流的频率档位。
 *
 * 为什么用「每小时期望次数」而不是「每天几次」：
 * 用户要的是活跃时段每小时 6~12 次机会，按天配额会让白天无所事事、晚上突然刷屏。
 *
 * @param ratePerHour 活跃时段的期望开口次数/小时（不是硬上限，硬上限见 [RandomTriggerConfig]）
 */
@Serializable
enum class ProactiveMode(
    val label: String,
    val ratePerHour: Double,
) {
    /** 日常：每小时 8 次左右，落在用户要的 6~12 区间中部。 */
    @SerialName("daily")
    DAILY("日常", 8.0),

    /** 高频：每小时 12 次，想密集陪伴时用。 */
    @SerialName("high")
    HIGH("高频", 12.0),

    /** 实验：每小时 20 次，只用于压测与观察，正式环境不建议常开。 */
    @SerialName("experiment")
    EXPERIMENT("实验", 20.0),
    ;

    companion object {
        fun fromName(name: String?): ProactiveMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DAILY
    }
}
