package cn.companion.pet.core.training

import kotlinx.serialization.Serializable

/**
 * 五题换休息的结算规则（R2.1 §6）。
 *
 * 关键约束：
 * - 合格题目按**凭据**去重：同题重复回答、回调重放、同凭据被两个来源返回，都不重复计数。
 * - 提示后通过可以计入“训练完成”，但**若已透露答案**，必须再做一题未见过的迁移题才产生资格。
 * - 独立通过率只统计未获实质提示的首次作答。
 * - 技术失败不扣分、不加题、不清已得凭据。
 * - 资格单次发放、单次消费；重启不重新计时。
 */
@Serializable
enum class PassKind {
    /** 首次独立答对。 */
    FIRST_TRY_INDEPENDENT,
    /** 有提示但未泄露答案后答对。 */
    PROMPTED_PASS,
    /** 迁移题独立答对（提示泄露后必需）。 */
    TRANSFER_PASS,
}

@Serializable
data class QuestionCredit(
    /** 凭据唯一键：questionId + rubricVersion，防同题重复计数。 */
    val creditId: String,
    val questionId: String,
    val rubricVersion: Int,
    val kind: PassKind,
    /** 这一题是否曾经泄露过答案（泄露则必须补迁移题）。 */
    val answerRevealed: Boolean,
    val grantedAt: Long,
)

@Serializable
data class SetState(
    val credits: List<QuestionCredit> = emptyList(),
    /** 已完成的迁移题数（对泄露过答案的题补）。 */
    val transferDone: Int = 0,
    /** 本轮是否已经发过休息资格。 */
    val grantIssued: Boolean = false,
    val grantIssuedAt: Long = 0,
)

sealed interface CreditResult {
    /** 计入成功。 */
    data class Counted(val state: SetState) : CreditResult
    /** 同一题已计过，不重复。 */
    data class Duplicate(val creditId: String) : CreditResult
    /** 需要先补迁移题。 */
    data class NeedsTransfer(val reason: String) : CreditResult
}

/**
 * 题组结算器：纯逻辑，时间由调用方给。
 */
class QuestionSetSettlement(
    /** 需要多少道合格题才发休息。 */
    private val requiredCredits: Int = 5,
) {

    /**
     * 记一次通过。
     *
     * - 若这一题泄露过答案且不是迁移题，要求先补迁移题（返回 NeedsTransfer）。
     * - 同一 creditId 只计一次（返回 Duplicate）。
     */
    fun record(
        state: SetState,
        questionId: String,
        rubricVersion: Int,
        kind: PassKind,
        answerRevealed: Boolean,
        now: Long,
    ): CreditResult {
        val creditId = questionId + "@" + rubricVersion
        if (state.credits.any { it.creditId == creditId }) return CreditResult.Duplicate(creditId)

        // 泄露过答案但不是迁移题：必须先补一道未见过的迁移题。
        if (answerRevealed && kind != PassKind.TRANSFER_PASS) {
            return CreditResult.NeedsTransfer("这题答案已经说破了，再做一道没见过的同类题才算")
        }

        val credit = QuestionCredit(
            creditId = creditId,
            questionId = questionId,
            rubricVersion = rubricVersion,
            kind = kind,
            answerRevealed = answerRevealed,
            grantedAt = now,
        )
        val next = state.copy(
            credits = state.credits + credit,
            transferDone = state.transferDone + if (kind == PassKind.TRANSFER_PASS) 1 else 0,
        )
        return CreditResult.Counted(next)
    }

    /** 是否已达到发休息的门槛。 */
    fun eligible(state: SetState): Boolean =
        state.credits.size >= requiredCredits && !state.grantIssued

    /** 统计：独立通过 / 提示后通过 / 迁移通过，分开计数。 */
    fun stats(state: SetState): Map<PassKind, Int> =
        PassKind.entries.associateWith { kind -> state.credits.count { it.kind == kind } }

    /** 独立通过率（只算未获实质提示的首次作答）。 */
    fun independentRate(state: SetState): Double {
        val total = state.credits.size
        if (total == 0) return 0.0
        val independent = state.credits.count { it.kind == PassKind.FIRST_TRY_INDEPENDENT }
        return independent.toDouble() / total
    }

    val required: Int get() = requiredCredits
}
