package com.zongkong.core.moling

import com.zongkong.core.Status
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId

/*
 * 墨灵 ↔ 总控 v2 训练兑换的总控侧外壳（纯逻辑，ContentProvider 只负责验调用方和存账本）。
 *
 * 和墨灵 core/link/GateV2.kt、core/redeem/TrainingRedeem.kt 的字段名一一对应（交叉测试在 tools/core-tests/ContractTest）：
 *  - 请求 / 响应都是字符串键值：version、requestId、method、payloadJson；响应加 ok、code、retryAfterMs；
 *  - v1（status / command / result）完全不动，ZK_LINK_VERSION 仍是 1；旧墨灵不调 v2 就一切照旧；
 *  - 题目下发时**不带答案**；回执里的通行由总控填，墨灵只转抄。
 */
object TrainingWire {
    const val V2 = 2
    const val PROTOCOL_REVISION = 1L
    const val POLICY_ID = "training"

    const val CAPABILITIES = "v2.capabilities"
    const val TRAINING_POLICY = "v2.training.policy"
    const val CHALLENGE_ISSUE = "v2.challenge.issue"
    const val CHALLENGE_SUBMIT = "v2.challenge.submit"
    const val RECEIPT = "v2.receipt"

    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val UNKNOWN = "UNKNOWN"
    private const val MAX_PAYLOAD = 16 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // ---- 和墨灵同名的 DTO ----

    @Serializable
    data class Capabilities(val versions: List<Int>, val training: Boolean, val capture: Boolean = false, val protocolRevision: Long = PROTOCOL_REVISION)

    @Serializable
    data class PolicyView(
        val policyId: String = POLICY_ID,
        val enabled: Boolean,
        val allowedPackages: Set<String>,
        val minutesPerPass: Int,
        val dailyMaxPasses: Int,
        val dailyMaxMinutes: Int,
        val remainingPassesToday: Int,
        val remainingMinutesToday: Int,
        val strict: Boolean,
        val policyRevision: Long,
        val validUntil: Long = 0,
    )

    @Serializable data class IssueRequest(val policyId: String = POLICY_ID, val packageName: String = "")
    @Serializable data class Choice(val id: String, val text: String)
    @Serializable data class Question(val id: String, val stem: String, val options: List<Choice>)

    @Serializable
    data class Bundle(
        val challengeId: String,
        val nonce: String,
        val policyId: String = POLICY_ID,
        val packageName: String,
        val issuedAt: Long,
        val expiresAt: Long,
        val rubricVersion: Long = 1,
        val policyRevision: Long,
        val questions: List<Question>,
    )

    @Serializable data class Answer(val questionId: String, val optionId: String)
    @Serializable data class Submission(val requestId: String = "", val challengeId: String, val nonce: String, val answers: List<Answer>, val submittedAt: Long = 0)
    @Serializable data class ReceiptQuery(val requestId: String = "")

    @Serializable
    data class Pass(val passId: String, val challengeId: String, val packageName: String, val issuedAt: Long, val expiresAt: Long, val minutes: Int, val policyRevision: Long)

    @Serializable
    data class Receipt(val receiptId: String, val requestId: String, val state: String, val at: Long, val message: String, val pass: Pass? = null)

    // ---- 处理 ----

    /**
     * 处理一次 v2 调用。返回新账本（可能没变）和响应键值。调用方负责：验调用方签名、在锁里读写账本、存盘。
     * [status] 是此刻总控算出的状态（含已发通行加的分钟），用来判断“是不是只因为挣来的娱乐用完了才拦”。
     */
    fun handle(
        method: String,
        req: Map<String, String>,
        policy: TrainingPolicy,
        book: TrainingBook,
        status: Status,
        now: Long,
        zone: ZoneId,
        rnd: java.util.Random,
    ): Pair<TrainingBook, Map<String, String>> {
        val rid = req["requestId"].orEmpty().take(120)
        fun fail(code: String, retryAfter: Long? = null, message: String = "") = book to response(rid, false, code, payload = if (message.isNotBlank()) json.encodeToString(Msg.serializer(), Msg(message)) else null, retryAfterMs = retryAfter)
        if (req["version"] != V2.toString()) return fail(UNSUPPORTED_VERSION)
        val payload = req["payloadJson"].orEmpty().ifBlank { "{}" }
        if (payload.length > MAX_PAYLOAD) return fail(PAYLOAD_TOO_LARGE)
        return when (method) {
            CAPABILITIES -> book to ok(rid, json.encodeToString(Capabilities.serializer(), Capabilities(listOf(1, V2), training = true)))
            TRAINING_POLICY -> book to ok(rid, json.encodeToString(PolicyView.serializer(), view(policy, book, status, now, zone)))
            CHALLENGE_ISSUE -> {
                val r = runCatching { json.decodeFromString(IssueRequest.serializer(), payload) }.getOrNull() ?: return fail(UNKNOWN, message = "请求看不懂")
                if (rid.isBlank()) return fail(UNKNOWN, message = "缺 requestId")
                val o = Training.issue(policy, book, status, r.packageName, rid, now, zone, rnd)
                val c = o.value
                if (!o.ok || c == null) return o.book to response(rid, false, o.code, payload = json.encodeToString(Msg.serializer(), Msg(o.message)), retryAfterMs = o.retryAfterMs)
                o.book to ok(rid, json.encodeToString(Bundle.serializer(), bundle(c)))
            }
            CHALLENGE_SUBMIT -> {
                val s = runCatching { json.decodeFromString(Submission.serializer(), payload) }.getOrNull() ?: return fail(UNKNOWN, message = "答卷看不懂")
                val requestId = s.requestId.ifBlank { rid }
                if (requestId.isBlank()) return fail(UNKNOWN, message = "缺 requestId")
                // 同一道题交两个答案：当作答卷无效（不取其中一个碰运气）
                if (s.answers.map { it.questionId }.distinct().size != s.answers.size) {
                    return fail(Training.INVALID_ANSWER, message = "同一道题交了两个答案")
                }
                val o = Training.submit(policy, book, status, requestId, s.challengeId, s.nonce, s.answers.associate { it.questionId to it.optionId }, now, zone)
                o.book to ok(rid, json.encodeToString(Receipt.serializer(), receipt(o.value!!)))
            }
            RECEIPT -> {
                val q = runCatching { json.decodeFromString(ReceiptQuery.serializer(), payload) }.getOrNull()
                val requestId = q?.requestId?.ifBlank { null } ?: rid
                val r = Training.receipt(book, requestId) ?: return fail(UNKNOWN, message = "总控里没有这张回执")
                book to ok(rid, json.encodeToString(Receipt.serializer(), receipt(r)))
            }
            else -> fail(UNKNOWN, message = "不认识的请求：$method")
        }
    }

    fun view(policy: TrainingPolicy, book: TrainingBook, status: Status, now: Long, zone: ZoneId): PolicyView {
        val p = policy.normalized()
        val (passes, minutes, _) = Training.usedToday(book, now, zone)
        val blocker = Training.blocker(status)
        return PolicyView(
            enabled = p.enabled,
            allowedPackages = p.packages,
            minutesPerPass = p.minutesPerPass,
            dailyMaxPasses = p.dailyMaxPasses,
            dailyMaxMinutes = p.dailyMaxMinutes,
            remainingPassesToday = (p.dailyMaxPasses - passes).coerceAtLeast(0),
            remainingMinutesToday = (p.dailyMaxMinutes - minutes).coerceAtLeast(0),
            strict = blocker?.first == Training.STRICT_MODE,
            policyRevision = p.revision,
        )
    }

    /** 下发给墨灵的题：**不带答案**。 */
    fun bundle(c: TrainingChallenge) = Bundle(
        challengeId = c.id,
        nonce = c.nonce,
        packageName = c.pkg,
        issuedAt = c.issuedAt,
        expiresAt = c.expiresAt,
        policyRevision = c.policyRevision,
        questions = c.questions.map { q -> Question(q.id, q.stem, q.options.map { Choice(it.id, it.text) }) },
    )

    fun receipt(r: TrainingReceipt) = Receipt(
        receiptId = r.receiptId,
        requestId = r.requestId,
        state = r.state,
        at = r.at,
        message = r.message,
        pass = r.pass?.let { Pass(it.passId, it.challengeId, it.pkg, it.issuedAt, it.expiresAt, it.minutes, it.policyRevision) },
    )

    @Serializable data class Msg(val message: String)

    private fun ok(rid: String, payloadJson: String) = response(rid, true, "", payloadJson)

    fun response(requestId: String, ok: Boolean, code: String = "", payload: String? = null, retryAfterMs: Long? = null): Map<String, String> = buildMap {
        put("version", V2.toString())
        put("requestId", requestId)
        put("ok", ok.toString())
        if (code.isNotBlank()) put("code", code)
        if (payload != null) put("payloadJson", payload)
        if (retryAfterMs != null) put("retryAfterMs", retryAfterMs.toString())
    }
}
