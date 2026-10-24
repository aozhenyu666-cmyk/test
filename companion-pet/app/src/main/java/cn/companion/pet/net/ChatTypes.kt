package cn.companion.pet.net

enum class ChatRole { SYSTEM, USER, ASSISTANT }

data class ChatMessage(
    val role: ChatRole,
    val content: String,
    /** JPEG data URL；为空时仍发送普通字符串 content，兼容旧接口。 */
    val imageDataUrls: List<String> = emptyList(),
)

data class ChatRequest(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val messages: List<ChatMessage>,
    val maxTokens: Int = 150,
    val timeoutMillis: Long = 10_000,
)

enum class ChatFailureKind { INVALID_CONFIG, HTTP, TIMEOUT, NETWORK, MALFORMED_RESPONSE }

sealed interface ChatResult {
    data class Success(val content: String) : ChatResult
    data class Failure(val kind: ChatFailureKind, val httpStatus: Int? = null) : ChatResult
}

interface ChatCompletionClient {
    suspend fun complete(request: ChatRequest): ChatResult
}
