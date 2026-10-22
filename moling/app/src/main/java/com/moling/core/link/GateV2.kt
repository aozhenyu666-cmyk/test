package com.moling.core.link

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 第十三批 G2/G3：墨灵 ↔ 总控的 v2 扩展（能力协商 + 训练兑换的传输外壳）。
 *
 * 这里**不**把 ZK_LINK_VERSION 从 1 改掉，也不动 v1 的 status/command/result：
 *  - 新方法单独走 v2.capabilities / v2.challenge.issue / v2.challenge.submit / v2.receipt；
 *  - 总控不支持时调用方只使用 v1，不发新方法；
 *  - 请求/响应的 Bundle 仍只放字符串（version/requestId/method/payload），不传对象或 Intent；
 *  - TemporaryPass 永远由总控发行（见 [com.moling.core.redeem.TemporaryPass]），本地不签名、不伪造。
 */

const val GATE_V2 = 2

/** v2 方法名。 */
object GateV2Method {
    const val CAPABILITIES = "v2.capabilities"
    const val CHALLENGE_ISSUE = "v2.challenge.issue"
    const val CHALLENGE_SUBMIT = "v2.challenge.submit"
    const val RECEIPT = "v2.receipt"
}

/** 总控声明的能力。不是权限授予：只是一个“支持什么协议”的说明。 */
@Serializable
data class GateCapabilities(
    val versions: List<Int> = emptyList(),
    /** 支持训练兑换协议（有总控侧 rubric 与临时通行记录）。 */
    val training: Boolean = false,
    /** 支持限定捕获租约（本批未接）。 */
    val capture: Boolean = false,
    /** 总控侧的协议修订，用来拒绝旧客户端的新请求（不匹配就退回 v1 行为）。 */
    val protocolRevision: Long = 0,
) {
    fun supportsTraining(): Boolean = versions.contains(GATE_V2) && training
}

/** v2 固定错误码（响应里的 code 字段）。 */
enum class GateError(val code: String, val retryable: Boolean) {
    UNSUPPORTED_VERSION("UNSUPPORTED_VERSION", false),
    CALLER_REJECTED("CALLER_REJECTED", false),
    SCOPE_DENIED("SCOPE_DENIED", false),
    EXPIRED("EXPIRED", false),
    RATE_LIMITED("RATE_LIMITED", true),
    PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE", false),
    REVISION_CONFLICT("REVISION_CONFLICT", false),
    NOT_AVAILABLE("NOT_AVAILABLE", true),
    REPLAY("REPLAY", false),
    POLICY_DENIED("POLICY_DENIED", false),
    NO_QUOTA("NO_QUOTA", false),
    STRICT_MODE("STRICT_MODE", false),
    INVALID_ANSWER("INVALID_ANSWER", false),
    UNKNOWN("UNKNOWN", false),
    ;

    companion object {
        fun of(code: String): GateError = entries.firstOrNull { it.code == code.trim().uppercase() } ?: UNKNOWN
        /** 只有明确可重试的错误才允许重试；结果未知不算可重试。 */
        fun retryable(code: String): Boolean = of(code).retryable
    }
}

/** v2 的 Bundle 键值外壳。payload 是契约 JSON 字符串。 */
object GateV2Wire {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun request(method: String, requestId: String, createdAt: Long, expiresAt: Long, payloadJson: String = "{}"): Map<String, String> =
        mapOf(
            "version" to GATE_V2.toString(),
            "requestId" to requestId,
            "method" to method,
            "createdAt" to createdAt.toString(),
            "expiresAt" to expiresAt.toString(),
            "payloadJson" to payloadJson,
        )

    fun response(requestId: String, ok: Boolean, code: String = "", payloadJson: String? = null, nextCursor: String? = null, retryAfterMs: Long? = null): Map<String, String> =
        buildMap {
            put("version", GATE_V2.toString())
            put("requestId", requestId)
            put("ok", ok.toString())
            if (code.isNotBlank()) put("code", code)
            if (payloadJson != null) put("payloadJson", payloadJson)
            if (nextCursor != null) put("nextCursor", nextCursor)
            if (retryAfterMs != null) put("retryAfterMs", retryAfterMs.toString())
        }

    /** 响应里的错误码；ok=true 返回空串。 */
    fun errorOf(response: Map<String, String>): String =
        if (response["ok"] == "true") "" else response["code"].orEmpty().ifBlank { GateError.UNKNOWN.code }

    /** 能力协商的 payload。解析不了返回 null（调用方当作不支持 v2，退回 v1）。 */
    fun capabilities(payloadJson: String): GateCapabilities? = runCatching {
        json.decodeFromString<GateCapabilities>(payloadJson)
    }.getOrNull()

    fun capabilitiesPayload(c: GateCapabilities): String = json.encodeToString(GateCapabilities.serializer(), c)
}
