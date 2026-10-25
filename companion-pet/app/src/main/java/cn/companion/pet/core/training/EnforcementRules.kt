package cn.companion.pet.core.training

import kotlinx.serialization.Serializable

/**
 * 强制规则：这是**明确的限制**，不是建议。
 *
 * 用户已明确要求：不完成、拒绝回复、胡编乱造 → 切换到严格刷题模态。
 * 因此这里的判定结果会直接驱动状态切换，而不是只弹一句提示。
 *
 * 边界（必须保留）：
 * - 技术故障（麦克风/网络/识别失败）不触发强制切换；
 * - 紧急退出永远可用，且不因未答题而阻塞；
 * - 规则只改变 interactionMode 与训练入口，不扩大到整机锁控；
 * - 不根据关键词做人格判断，只按可观察行为分类。
 */
@Serializable
enum class TriggerReason {
    /** 明确拒绝继续（说不想做、别问了、别烦我）。 */
    REFUSAL,
    /** 该回答时一直没回答，且输入通道正常。 */
    NO_RESPONSE,
    /** 回答与题目无关、无依据地编造，且不承认不确定。 */
    FABRICATION,
    /** 技术故障：不触发强制。 */
    TECHNICAL,
    /** 正常回答。 */
    NORMAL,
}

@Serializable
enum class EnforcementAction {
    /** 无需动作。 */
    NONE,
    /** 再给一次机会（追一次具体缺口）。 */
    ONE_MORE_CHANCE,
    /** 强制切换到严格刷题模态。 */
    FORCE_COACH,
    /** 已处于严格模态：升级为必须完成当前槽位才能退出。 */
    LOCK_COACH_SLOT,
    /** 技术降级：不追究，给恢复入口。 */
    DOWNGRADE_TECHNICAL,
}

@Serializable
data class EnforcementDecision(
    val action: EnforcementAction,
    val reason: TriggerReason,
    val message: String,
    /** 是否计入“拒绝/未完成”次数，用于升级。 */
    val countsAsViolation: Boolean,
)

/**
 * 规则执行器。连续违规达到上限后强制进入严格模态，且不会自动退化回温和。
 *
 * 阈值语义：
 * - 第 1 次：只追一次具体缺口（ONE_MORE_CHANCE），不强制。
 * - 第 2 次起：强制切到严格刷题（FORCE_COACH）。
 * - 已在严格模态且再次违规：锁定当前槽位（LOCK_COACH_SLOT）。
 */
class EnforcementRules(
    private val violationLimit: Int = 2,
) {
    private var violations = 0

    val violationCount: Int get() = violations

    fun reset() { violations = 0 }

    fun evaluate(
        reason: TriggerReason,
        mode: InteractionMode,
        hasPendingQuestion: Boolean,
    ): EnforcementDecision {
        if (reason == TriggerReason.TECHNICAL) {
            return EnforcementDecision(
                EnforcementAction.DOWNGRADE_TECHNICAL,
                TriggerReason.TECHNICAL,
                "输入通道有问题，先不追究这一步。可以改用文字，或者修好再说。",
                countsAsViolation = false,
            )
        }
        if (reason == TriggerReason.NORMAL) {
            return EnforcementDecision(EnforcementAction.NONE, reason, "", countsAsViolation = false)
        }
        if (!hasPendingQuestion && reason == TriggerReason.NO_RESPONSE) {
            // 没有待答问题时不算沉默违规。
            return EnforcementDecision(EnforcementAction.NONE, reason, "", countsAsViolation = false)
        }

        violations++
        val message = when (reason) {
            TriggerReason.REFUSAL -> "这一步还没做完。不做可以，但今天这次训练会停在原地。"
            TriggerReason.NO_RESPONSE -> "你还没答这一问。我先按没完成算。"
            TriggerReason.FABRICATION -> "这个回答和题目对不上。我要的是这一问的具体依据，不是编一个。"
            else -> ""
        }
        return when {
            // 已在严格模态：不退化，锁定当前槽位。
            mode == InteractionMode.COACH -> EnforcementDecision(
                EnforcementAction.LOCK_COACH_SLOT,
                reason,
                message + "先完成当前这一问，其他入口暂时收起。",
                countsAsViolation = true,
            )
            violations < violationLimit -> EnforcementDecision(
                EnforcementAction.ONE_MORE_CHANCE,
                reason,
                message,
                countsAsViolation = true,
            )
            else -> EnforcementDecision(
                EnforcementAction.FORCE_COACH,
                reason,
                "改成陪练模式。先把这题做完，再回到随便聊。",
                countsAsViolation = true,
            )
        }
    }
}
