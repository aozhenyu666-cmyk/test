package com.moling.core.redeem

import com.moling.core.link.GateCapabilities
import com.moling.core.link.GateError
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import kotlinx.serialization.Serializable

/*
 * 第十三批 G3：训练兑换的**墨灵侧**接口与本地模型。
 *
 * 规矩（和 partner-blueprint/v13-contracts.md 第 3.2 节一致）：
 *  - 判分 rubric、nonce、截止时间、每日额度和临时通行令都在总控；墨灵只拿题干/选项，不拿判分表；
 *  - 墨灵不给自己发奖：没有总控回执里的 TemporaryPass，activePass 永远是 null；
 *  - 回执里的 pass 必须和挑战 id / 包名 / 策略修订 / 截止时间对得上，对不上当没发；
 *  - 重复提交同一挑战返回原回执，不重复增加时长；
 *  - 并发提交由 @Synchronized 串行化，只有一个能写入通行令；
 *  - 总控不支持 v2 训练协议 / 没开策略 / 严格期 / 没额度 → 明确拒绝，不伪造成功；
 *  - 本文件不实现总控侧判分（那要改 zongkong/，本批明确不做），测试用 Fake 总控验证协议。
 *
 * 讨论版三手棋的模型 0–3 分只用于陪练，绝不在这里兑换成授权。
 */

@Serializable
data class UnlockPolicyView(
    val policyId: String,
    val enabled: Boolean = false,
    val allowedPackages: Set<String> = emptySet(),
    /** 单次放行分钟数；总控应把上限卡在 15 分钟内。 */
    val minutesPerPass: Int = 0,
    val dailyMaxPasses: Int = 0,
    val dailyMaxMinutes: Int = 0,
    /** -1 = 总控没告诉墨灵剩余额度（以总控判定为准）。 */
    val remainingPassesToday: Int = -1,
    val remainingMinutesToday: Int = -1,
    val strict: Boolean = false,
    val policyRevision: Long = 0,
    /** 0 = 长期有效。 */
    val validUntil: Long = 0,
)

@Serializable
data class ChallengeChoice(val id: String, val text: String)

/** 墨灵只拿题干和选项；判分表在总控。 */
@Serializable
data class ChallengeQuestion(val id: String, val stem: String, val options: List<ChallengeChoice>)

@Serializable
data class ChallengeBundle(
    val challengeId: String,
    val nonce: String,
    val policyId: String,
    val packageName: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val rubricVersion: Long = 0,
    val policyRevision: Long = 0,
    val questions: List<ChallengeQuestion> = emptyList(),
)

@Serializable
data class ChallengeAnswer(val questionId: String, val optionId: String)

@Serializable
data class UnlockSubmission(
    val requestId: String,
    val challengeId: String,
    val nonce: String,
    val answers: List<ChallengeAnswer>,
    val submittedAt: Long,
)

/** 总控数据库里的一次性记录索引；不当作墨灵签名，也不能本地构造。 */
@Serializable
data class TemporaryPass(
    val passId: String,
    val challengeId: String,
    val packageName: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val minutes: Int,
    val policyRevision: Long,
)

@Serializable
data class ExecutionReceipt(
    val receiptId: String = "",
    val requestId: String = "",
    /** PASSED / DENIED / FAILED / EXPIRED / REJECTED / ACTIVE；空 = 还不知道。 */
    val state: String = "",
    val at: Long = 0,
    val message: String = "",
    /** 只有总控能填；墨灵永远不生成。 */
    val pass: TemporaryPass? = null,
)

@Serializable
data class RedeemState(
    val challenge: ChallengeBundle? = null,
    val receipts: List<ExecutionReceipt> = emptyList(),
    val pass: TemporaryPass? = null,
    val lastMessage: String = "",
)

/** 总控 v2 训练兑换的端口。实现方负责 ContentProvider/Binder、包名与签名校验；这里只管协议。 */
interface GateV2Port {
    /** 总控声明的能力；null = 连不上或只有 v1。 */
    fun capabilities(): GateCapabilities? = null

    /** 总控当前生效的训练策略。 */
    fun policy(): PortResult<UnlockPolicyView>

    fun issue(policyId: String, packageName: String, requestId: String): PortResult<ChallengeBundle>

    fun submit(submission: UnlockSubmission): PortResult<ExecutionReceipt>

    fun receipt(requestId: String): PortResult<ExecutionReceipt>
}

/**
 * 墨灵侧的训练兑换客户端。不联网、不判分；只做本地约束、幂等缓存和回执核对。
 * [port] 为 null = 总控没装/连不上，直接拒绝，不伪造题目或通行令。
 */
class TrainingRedeemClient(
    private val port: GateV2Port?,
    initial: RedeemState = RedeemState(),
) {
    private var state: RedeemState = initial

    fun snapshot(): RedeemState = state

    /** 发起一局训练。没有可用策略/没额度/严格期/不支持 v2 都明确拒绝，不给题。 */
    @Synchronized
    fun start(packageName: String, now: Long): PortResult<ChallengeBundle> {
        val p = port ?: return fail(GateError.NOT_AVAILABLE, "总控没装或连不上，不能兑换")
        p.capabilities()?.let {
            if (!it.supportsTraining()) return fail(GateError.NOT_AVAILABLE, "总控这个版本不支持训练兑换")
        }
        val policy = when (val r = p.policy()) {
            is PortResult.Ok -> r.value
            is PortResult.Failed -> return r
        }
        if (!policy.enabled) return fail(GateError.POLICY_DENIED, "总控里没有启用训练兑换策略")
        if (policy.strict) return fail(GateError.STRICT_MODE, "现在是严格期，训练不能换娱乐")
        if (packageName.isBlank() || (policy.allowedPackages.isNotEmpty() && packageName !in policy.allowedPackages)) {
            return fail(GateError.SCOPE_DENIED, "这个应用不在你选的放行范围里")
        }
        if (policy.minutesPerPass !in 1..15) return fail(GateError.POLICY_DENIED, "单次放行时长不合理，先在总控里改策略")
        if (policy.remainingPassesToday == 0 || policy.remainingMinutesToday == 0) return fail(GateError.NO_QUOTA, "今天没有可兑换的次数/额度了")
        if (policy.validUntil in 1..now) return fail(GateError.EXPIRED, "训练兑换策略已经过期")
        state.pass?.takeIf { it.expiresAt > now }?.let { return fail(GateError.NOT_AVAILABLE, "还有一次没到期的放行，先用完再来") }
        val today = com.moling.core.mind.dayKey(now)
        val requestId = "issue-${policy.policyId}-${policy.policyRevision}-${packageName}-${today}"
        return when (val r = p.issue(policy.policyId, packageName, requestId)) {
            is PortResult.Ok -> {
                val c = r.value
                if (c.challengeId.isBlank() || c.nonce.isBlank() || c.questions.isEmpty() || c.expiresAt <= now) {
                    fail(GateError.NOT_AVAILABLE, "总控给的题目不完整或已过期")
                } else if (c.packageName != packageName || c.policyId != policy.policyId) {
                    fail(GateError.SCOPE_DENIED, "总控给的挑战和这次请求对不上")
                } else {
                    commit(state.copy(challenge = c, lastMessage = "一局三题，做对至少两题才可能放行（由总控判）"))
                    PortResult.Ok(c)
                }
            }
            is PortResult.Failed -> r
        }
    }

    /**
     * 交答案。本地只检查题目/选项范围与非空；对不对由总控判。
     * 同一挑战重复交返回原回执（不再请求总控，也不再加时长）。
     */
    @Synchronized
    fun submit(answers: List<ChallengeAnswer>, now: Long): PortResult<ExecutionReceipt> {
        val p = port ?: return fail(GateError.NOT_AVAILABLE, "总控没装或连不上")
        val c = state.challenge ?: return fail(GateError.NOT_AVAILABLE, "还没有这一局的题目")
        cachedReceipt(c.challengeId)?.let { return PortResult.Ok(it) }
        if (now >= c.expiresAt) return fail(GateError.EXPIRED, "这一局已经超时，回到原闸门规则")
        val qs = c.questions.associateBy { it.id }
        if (answers.isEmpty()) return fail(GateError.INVALID_ANSWER, "还没选答案")
        if (answers.map { it.questionId }.distinct().size != answers.size) return fail(GateError.INVALID_ANSWER, "同一个问题交了两次答案")
        answers.firstOrNull { it.questionId !in qs }?.let { return fail(GateError.INVALID_ANSWER, "答案里有这道题没有的问题") }
        answers.firstOrNull { a -> qs[a.questionId]?.options?.none { it.id == a.optionId } != false }?.let {
            return fail(GateError.INVALID_ANSWER, "答案里有这道题没有的选项")
        }
        val submission = UnlockSubmission(
            requestId = "submit-${c.challengeId}",
            challengeId = c.challengeId,
            nonce = c.nonce,
            answers = answers,
            submittedAt = now,
        )
        return when (val r = p.submit(submission)) {
            is PortResult.Ok -> acceptReceipt(r.value, c, now)
            is PortResult.Failed -> r
        }
    }

    /** 查上一次提交的结果；只转抄总控的回执，不自己生成放行。 */
    @Synchronized
    fun query(now: Long): PortResult<ExecutionReceipt> {
        val p = port ?: return fail(GateError.NOT_AVAILABLE, "总控没装或连不上")
        val c = state.challenge ?: return fail(GateError.NOT_AVAILABLE, "没有可查的这一局")
        val requestId = "submit-${c.challengeId}"
        return when (val r = p.receipt(requestId)) {
            is PortResult.Ok -> acceptReceipt(r.value, c, now)
            is PortResult.Failed -> r
        }
    }

    /** 当前有效的临时通行令；过期就作废，不续期、不重复使用。 */
    @Synchronized
    fun activePass(now: Long): TemporaryPass? {
        val pass = state.pass ?: return null
        if (pass.expiresAt <= now) {
            commit(state.copy(pass = null, lastMessage = "放行到点了，恢复原闸门规则"))
            return null
        }
        return pass
    }

    // ------------------------------------------------------------------ 回执核对

    private fun acceptReceipt(receipt: ExecutionReceipt, challenge: ChallengeBundle, now: Long): PortResult<ExecutionReceipt> {
        val stateOk = receipt.state.trim().uppercase() in setOf("PASSED", "REDEEMED", "ACTIVE", "OK")
        val pass = receipt.pass
        if (pass != null) {
            val bad = pass.challengeId != challenge.challengeId ||
                pass.packageName != challenge.packageName ||
                pass.policyRevision != challenge.policyRevision ||
                pass.expiresAt <= now ||
                pass.minutes !in 1..15 ||
                !stateOk
            if (bad) {
                // 回执和这次挑战对不上：当成拒绝，绝不落成本地放行。
                val denied = receipt.copy(state = "REJECTED", pass = null, message = "总控回执和这次挑战对不上，这次不发奖")
                remember(denied)
                return fail(GateError.REPLAY, denied.message)
            }
        }
        if (!stateOk && pass != null) {
            val denied = receipt.copy(state = "REJECTED", pass = null, message = "总控没确认通过，不发奖")
            remember(denied)
            return fail(GateError.POLICY_DENIED, denied.message)
        }
        val safe = if (pass != null) receipt.copy(pass = null) else receipt
        val next = if (pass != null) {
            safe.copy(
                state = receipt.state,
                message = receipt.message.ifBlank { "这次放行 ${pass.minutes} 分钟，到点自动结束" },
                pass = pass,
            )
        } else {
            safe.copy(
                message = receipt.message.ifBlank {
                    if (stateOk) "总控说通过了，但没发行临时放行；这次不放行" else "总控没通过，回到原闸门规则"
                },
            )
        }
        remember(next)
        if (pass == null) {
            return if (stateOk) fail(GateError.NOT_AVAILABLE, next.message) else PortResult.Ok(next)
        }
        commit(state.copy(pass = pass, lastMessage = next.message))
        return PortResult.Ok(next)
    }

    private fun remember(receipt: ExecutionReceipt) {
        val list = (state.receipts.filterNot { it.requestId == receipt.requestId && receipt.requestId.isNotBlank() } + receipt).takeLast(20)
        commit(state.copy(receipts = list, lastMessage = receipt.message.ifBlank { state.lastMessage }))
    }

    private fun cachedReceipt(challengeId: String): ExecutionReceipt? {
        val c = state.challenge ?: return null
        if (c.challengeId != challengeId) return null
        return state.receipts.lastOrNull { it.requestId == "submit-${challengeId}" }
    }

    private fun commit(next: RedeemState) {
        state = next
    }

    private fun <T> fail(code: GateError, message: String): PortResult<T> = PortResult.Failed(PortError(code.code, message))
}
