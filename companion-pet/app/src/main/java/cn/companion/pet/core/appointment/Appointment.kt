package cn.companion.pet.core.appointment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户**明确给了时间**的约定。
 *
 * 与 `ThreadBook`（话题线）的分工：
 * - `ThreadBook`：不确定时间，"这事还没聊完，过阵子回来问问"；
 * - `Appointment`：**确定时间**，"十分钟后问问我进度"。
 *
 * 为什么必须单独建一个模型：用户说"十分钟后"时，他期待的是**十分钟后**
 * 被问到，而不是"过一会儿也许会问"。话题线的相对跟进满足不了这个承诺。
 *
 * 三条硬规则（缺一条就不该存在）：
 * 1. 必须有明确时间（[dueAtMillis]）；
 * 2. 必须有具体话题（[topic]），用户能看到自己要被告知什么；
 * 3. 必须有依据（[basis]），来自用户原话，不许由模型凭空编。
 */
@Serializable
data class Appointment(
    @SerialName("id") val id: String,
    /** 具体问什么，用户可见。例如"项目进度"。 */
    @SerialName("topic") val topic: String,
    /** 什么时候问。 */
    @SerialName("dueAtMillis") val dueAtMillis: Long,
    /** 依据：用户原话片段，用于让主动消息有上下文而不是模板。 */
    @SerialName("basis") val basis: String,
    /** 状态。 */
    @SerialName("status") val status: AppointmentStatus = AppointmentStatus.PENDING,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("updatedAt") val updatedAt: Long = createdAt,
    /**
     * 版本：改期/取消/更正都 +1。
     *
     * 旧版本号对应的异步生成结果一律作废——防止"用户已经取消了，
     * 但取消前生成的那句话还是发出去了"。
     */
    @SerialName("version") val version: Int = 1,
    /** 改期次数（诊断用，也用于防止无限改期刷屏）。 */
    @SerialName("reschedules") val reschedules: Int = 0,
    /** 迟到容忍：超过这个时长还没发出去就作废，不补发。 */
    @SerialName("lateToleranceMillis") val lateToleranceMillis: Long = 10 * 60_000L,
) {
    /** 还在等待中（没到期、没说、没取消）。 */
    val pending: Boolean get() = status == AppointmentStatus.PENDING

    /** 是不是到点了（含迟到容忍内的迟到）。 */
    fun due(nowMillis: Long): Boolean =
        pending && nowMillis >= dueAtMillis && nowMillis - dueAtMillis <= lateToleranceMillis

    /** 是不是已经过期太久，应当作废（不补发）。 */
    fun overtime(nowMillis: Long): Boolean =
        pending && nowMillis - dueAtMillis > lateToleranceMillis

    /** 用户能看到的一句话，例如「10 分钟后问《项目进度》」。 */
    fun describe(nowMillis: Long): String {
        val whenText = when {
            dueAtMillis <= nowMillis -> "现在该问"
            else -> {
                val minutes = (dueAtMillis - nowMillis) / 60_000L
                when {
                    minutes < 1L -> "马上问"
                    minutes < 60L -> minutes.toString() + " 分钟后问"
                    minutes < 24 * 60L -> (minutes / 60L).toString() + " 小时后问"
                    else -> (minutes / (24L * 60L)).toString() + " 天后问"
                }
            }
        }
        return whenText + "「" + topic + "」"
    }
}

@Serializable
enum class AppointmentStatus {
    /** 等待中。 */
    @SerialName("pending") PENDING,

    /** 正在生成/投递。防重复。 */
    @SerialName("delivering") DELIVERING,

    /** 已经问过，等用户回答。 */
    @SerialName("awaiting") AWAITING,

    /** 用户已经回应，事项完成。 */
    @SerialName("done") DONE,

    /** 用户取消。 */
    @SerialName("cancelled") CANCELLED,

    /** 过期没发出去（重启/长时间后台），不补发。 */
    @SerialName("expired") EXPIRED,
    ;

    val active: Boolean get() = this == PENDING || this == DELIVERING || this == AWAITING
}
