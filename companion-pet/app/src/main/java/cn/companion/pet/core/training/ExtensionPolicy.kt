package cn.companion.pet.core.training

import kotlinx.serialization.Serializable

/**
 * S7：`我在想 / 还在读` 的有限宽限。
 *
 * 用户说“我在想”是合理请求，必须允许；但不能无限刷新。
 * 规则（本地代码决定，模型无权提高）：
 * - 次数上限（默认 2 次）；
 * - 累计延长上限（默认 3 分钟）；
 * - 每次都有明确的新截止时间；
 * - 用完后不再延长，转而缩小问题或进入 RECOVERY。
 *
 * 这不是惩罚：技术故障（录音/STT/权限弹窗/真实通话）不计入这里，
 * 那些走 TECHNICAL_BLOCK，不消耗用户的宽限额度。
 */
@Serializable
data class ExtensionState(
    val usedTimes: Int = 0,
    val usedMillis: Long = 0,
    /** 本次延长后的新截止（单调时钟）。 */
    val extendedUntil: Long = 0,
)

sealed interface ExtensionResult {
    /** 允许延长，给出新的截止时间。 */
    data class Granted(val until: Long, val remainingTimes: Int, val remainingMillis: Long) : ExtensionResult

    /** 不允许，给出可执行的原因。 */
    data class Denied(val reason: String) : ExtensionResult

    /** 技术故障：不消耗额度，另行处理。 */
    data object Technical : ExtensionResult
}

class ExtensionPolicy(
    /** 最多允许几次“我在想”。 */
    private val maxTimes: Int = 2,
    /** 每次延长的时长。 */
    private val perExtensionMillis: Long = 90_000L,
    /** 累计延长上限。 */
    private val maxTotalMillis: Long = 180_000L,
) {

    fun request(
        state: ExtensionState,
        now: Long,
        technicalBlock: Boolean = false,
    ): Pair<ExtensionResult, ExtensionState> {
        // 技术故障不消耗额度：不能把麦克风坏了算成用户拖延。
        if (technicalBlock) return ExtensionResult.Technical to state

        if (state.usedTimes >= maxTimes) {
            return ExtensionResult.Denied("已经延长 " + state.usedTimes + " 次了，先把这一问说完，或者说“我不会”") to state
        }
        val nextTotal = state.usedMillis + perExtensionMillis
        if (nextTotal > maxTotalMillis) {
            return ExtensionResult.Denied("累计延长到上限了，先答这一问，或者换小一点的问题") to state
        }
        val until = now + perExtensionMillis
        val next = state.copy(usedTimes = state.usedTimes + 1, usedMillis = nextTotal, extendedUntil = until)
        return ExtensionResult.Granted(until, maxTimes - next.usedTimes, maxTotalMillis - next.usedMillis) to next
    }

    /** 新的一题：额度重置。同一会话里换题不应继承上一题的宽限。 */
    fun reset(): ExtensionState = ExtensionState()

    val limitTimes: Int get() = maxTimes
    val limitTotalMillis: Long get() = maxTotalMillis
}

/** 输入/录音的总时长上限：防止长时间占用 WAITING_INPUT 冻结整个任务。 */
class InputDurationPolicy(
    private val maxRecordingMillis: Long = 120_000L,
) {
    /** 超过上限就应收束并给出可见状态，而不是一直挂着。 */
    fun exceeded(startedAt: Long, now: Long): Boolean = now - startedAt >= maxRecordingMillis

    fun remaining(startedAt: Long, now: Long): Long =
        (maxRecordingMillis - (now - startedAt)).coerceAtLeast(0)

    val limit: Long get() = maxRecordingMillis
}
