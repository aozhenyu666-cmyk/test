package cn.companion.pet.core.rhythm

/**
 * 被忽略后的升级顺序：
 * 0–60 秒等待 → 吸引注意的动作 → 再 60 秒短震动并移到靠中间 → 再 60 秒回到边缘、结束。
 */
enum class EscalationLevel { WAITING, ATTENTION, NUDGE, RETREAT }

object IgnoreEscalation {
    const val STEP_MILLIS = 60_000L

    fun levelAt(shownAt: Long, now: Long, stepMillis: Long = STEP_MILLIS): EscalationLevel {
        val elapsed = now - shownAt
        return when {
            elapsed < stepMillis -> EscalationLevel.WAITING
            elapsed < 2 * stepMillis -> EscalationLevel.ATTENTION
            elapsed < 3 * stepMillis -> EscalationLevel.NUDGE
            else -> EscalationLevel.RETREAT
        }
    }
}
