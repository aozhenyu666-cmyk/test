package com.moling.core.redeem

import com.moling.core.link.GATE_V2
import com.moling.core.link.GateCapabilities
import com.moling.core.link.GateError
import com.moling.core.link.GateV2Method
import com.moling.core.link.GateV2Wire
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [GateV2Port] 的协议实现：把每次调用编成 v2 字符串键值，交给 [transport]（App 里是总控 ContentProvider 的 call），再把响应解回来。
 * 这里不验签、不判分；[transport] 返回 null = 总控没装 / 连不上 / 旧总控不认识 v2。
 */
class WireGateV2Port(
    private val transport: (method: String, request: Map<String, String>) -> Map<String, String>?,
    private val clock: () -> Long = System::currentTimeMillis,
) : GateV2Port {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private var seq = 0L

    @Serializable private data class IssueRequest(val policyId: String, val packageName: String)
    @Serializable private data class ReceiptQuery(val requestId: String)
    @Serializable private data class Msg(val message: String = "")

    override fun capabilities(): GateCapabilities? {
        val r = call(GateV2Method.CAPABILITIES, "cap-${next()}", "{}") ?: return null
        if (r["ok"] != "true") return null
        return GateV2Wire.capabilities(r["payloadJson"].orEmpty())?.takeIf { GATE_V2 in it.versions }
    }

    override fun policy(): PortResult<UnlockPolicyView> =
        decode(call(GateV2Method.TRAINING_POLICY, "policy-${next()}", "{}"), UnlockPolicyView.serializer())

    override fun issue(policyId: String, packageName: String, requestId: String): PortResult<ChallengeBundle> =
        decode(call(GateV2Method.CHALLENGE_ISSUE, requestId, json.encodeToString(IssueRequest.serializer(), IssueRequest(policyId, packageName))), ChallengeBundle.serializer())

    override fun submit(submission: UnlockSubmission): PortResult<ExecutionReceipt> =
        decode(call(GateV2Method.CHALLENGE_SUBMIT, submission.requestId, json.encodeToString(UnlockSubmission.serializer(), submission)), ExecutionReceipt.serializer())

    override fun receipt(requestId: String): PortResult<ExecutionReceipt> =
        decode(call(GateV2Method.RECEIPT, requestId, json.encodeToString(ReceiptQuery.serializer(), ReceiptQuery(requestId))), ExecutionReceipt.serializer())

    private fun call(method: String, requestId: String, payload: String): Map<String, String>? {
        val now = clock()
        val req = GateV2Wire.request(method, requestId, now, now + 60_000L, payload)
        return runCatching { transport(method, req) }.getOrNull()
    }

    private fun <T> decode(resp: Map<String, String>?, ser: kotlinx.serialization.KSerializer<T>): PortResult<T> {
        if (resp == null) return PortResult.Failed(PortError(GateError.NOT_AVAILABLE.code, "总控没装或连不上"))
        if (resp["ok"] != "true") {
            val msg = resp["payloadJson"]?.let { runCatching { json.decodeFromString(Msg.serializer(), it).message }.getOrNull() }.orEmpty()
            val code = GateV2Wire.errorOf(resp)
            return PortResult.Failed(PortError(code, msg.ifBlank { "总控拒绝了（$code）" }))
        }
        val v = resp["payloadJson"]?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }
            ?: return PortResult.Failed(PortError(GateError.UNKNOWN.code, "总控的回复看不懂"))
        return PortResult.Ok(v)
    }

    private fun next(): Long = synchronized(this) { ++seq }
}
