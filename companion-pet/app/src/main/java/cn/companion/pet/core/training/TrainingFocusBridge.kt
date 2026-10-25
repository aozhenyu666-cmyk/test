package cn.companion.pet.core.training

/**
 * 训练 ↔ 专注联动。用户要求：训练没做完就切到娱乐 App，必须拦截，不是提醒。
 *
 * 与既有 FocusGuard 的分工：
 * - FocusGuard 管“专注会话本身”的契约与到期；
 * - 这里只决定“训练进行中，当前前台是否必须被拦回训练”。
 * 两者都不扩大到整机锁控，紧急退出始终可用。
 */
enum class FocusVerdict {
    /** 在训练界面内，正常。 */
    IN_TRAINING,
    /** 在允许的材料/浏览器内，正常（读资料）。 */
    ALLOWED_READING,
    /** 训练未完成却去了娱乐 App：必须拦截。 */
    BLOCK_BACK_TO_TRAINING,
    /** 训练已完成或已结束：不拦。 */
    NOT_APPLICABLE,
}

data class FocusDecision(
    val verdict: FocusVerdict,
    val reason: String,
    /** 是否应拉起拦截层。 */
    val shouldBlock: Boolean,
)

/**
 * 判定规则（明确限制，不是建议）：
 * 1. 训练会话 active 且当前槽位未完成 → 离开到非允许 App 必须拦截。
 * 2. 允许阅读类 App 不拦（读资料是任务一部分）。
 * 3. 训练完成/结束后不拦，也不自动延长限制。
 * 4. 锁屏/电话等系统场景不在这里处理，由调用方跳过。
 */
class TrainingFocusBridge(
    private val allowedReadingPackages: Set<String> = emptySet(),
) {
    fun decide(
        session: TrainingSession?,
        foregroundPackage: String?,
        ownPackage: String,
        allowedFocusPackages: Set<String> = emptySet(),
    ): FocusDecision {
        if (session == null || !session.active) {
            return FocusDecision(FocusVerdict.NOT_APPLICABLE, "没有进行中的训练", shouldBlock = false)
        }
        val pkg = foregroundPackage
        if (pkg.isNullOrBlank()) {
            // 没有可靠前台信息：不拦，避免误伤。
            return FocusDecision(FocusVerdict.NOT_APPLICABLE, "前台不明", shouldBlock = false)
        }
        if (pkg == ownPackage) {
            return FocusDecision(FocusVerdict.IN_TRAINING, "在训练界面内", shouldBlock = false)
        }
        if (pkg in allowedReadingPackages || pkg in allowedFocusPackages) {
            return FocusDecision(FocusVerdict.ALLOWED_READING, "允许的阅读/资料应用", shouldBlock = false)
        }
        return FocusDecision(
            FocusVerdict.BLOCK_BACK_TO_TRAINING,
            "这一步还没做完，先回到训练。",
            shouldBlock = true,
        )
    }
}
