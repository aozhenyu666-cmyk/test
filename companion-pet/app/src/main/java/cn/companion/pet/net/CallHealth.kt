package cn.companion.pet.net

/** 最近一次调用模型的结果，给“开始”页显示用。只存结论和时间，不存请求或回复内容。 */
data class CallStatus(val ok: Boolean, val message: String, val at: Long)

interface CallStatusSink {
    fun record(status: CallStatus)
}

/** 把传输层的失败翻译成用户能自己处理的一句话。 */
object CallDiagnosis {
    const val OK = "正常"
    const val BAD_FORMAT = "模型回复了，但不是要求的 JSON 格式。可以换个模型（比如 deepseek-chat）试试"

    fun describe(result: ChatResult): String = when (result) {
        is ChatResult.Success -> OK
        is ChatResult.Failure -> when (result.kind) {
            ChatFailureKind.INVALID_CONFIG -> "接口地址或模型名没填对（地址要 https 开头）"
            ChatFailureKind.TIMEOUT -> "等了太久没回应，可能是网络慢或服务商繁忙"
            ChatFailureKind.NETWORK -> "连不上服务商，检查网络或接口地址"
            ChatFailureKind.MALFORMED_RESPONSE -> "服务商返回的内容看不懂，检查接口地址是不是 OpenAI 兼容的"
            ChatFailureKind.HTTP -> http(result.httpStatus)
        }
    }

    private fun http(code: Int?): String = when (code) {
        401, 403 -> "API Key 不对，或者和服务商不匹配（HTTP $code）"
        402 -> "服务商账户余额不足（HTTP 402）"
        404 -> "接口地址或模型名不对（HTTP 404）"
        400, 422 -> "服务商拒绝了请求，多半是模型名不对（HTTP $code）"
        429 -> "请求太频繁或服务商额度用完了（HTTP 429）"
        null -> "服务商返回错误"
        in 300..399 -> "接口地址发生了跳转，请填最终地址（HTTP $code）"
        in 500..599 -> "服务商那边出错了，稍后会再试（HTTP $code）"
        else -> "服务商返回错误（HTTP $code）"
    }
}

/**
 * 包一层：所有调用入口（开口、接话、想一想、夜间整理、测试连接）都会留下最近一次结果。
 * 以前失败时桌宠只是不说话，用户无从知道是 Key、网络还是模型名的问题。
 */
class RecordingChatClient(
    private val inner: ChatCompletionClient,
    private val sink: CallStatusSink,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatCompletionClient {
    override suspend fun complete(request: ChatRequest): ChatResult {
        val result = inner.complete(request)
        runCatching { sink.record(CallStatus(result is ChatResult.Success, CallDiagnosis.describe(result), clock())) }
        return result
    }
}
