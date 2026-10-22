package cn.companion.pet.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * OpenAI 兼容 Chat Completions 的非流式客户端。
 *
 * 只做传输：不解释 content、不重试、不计数、不写日志。
 * 关闭重定向和连接失败重试，3xx 当作失败，凭据不会被带到别的地址。
 */
class DeepSeekClient(base: OkHttpClient = OkHttpClient()) : ChatCompletionClient {

    private val http: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun complete(request: ChatRequest): ChatResult {
        val endpoint = runCatching { EndpointPolicy.normalize(request.endpoint) }.getOrNull()
            ?: return ChatResult.Failure(ChatFailureKind.INVALID_CONFIG)
        if (request.model.isBlank() || request.messages.isEmpty() || request.maxTokens <= 0 || request.timeoutMillis <= 0) {
            return ChatResult.Failure(ChatFailureKind.INVALID_CONFIG)
        }

        val body = buildJsonObject {
            put("model", request.model.trim())
            put("messages", buildJsonArray {
                request.messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role.name.lowercase())
                        put("content", m.content)
                    })
                }
            })
            put("stream", false)
            put("max_tokens", request.maxTokens)
        }.toString()

        val builder = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(JSON))
        val key = request.apiKey.trim()
        if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key")

        val client = http.newBuilder()
            .callTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(builder.build())

        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive) return
                    val kind = if (e is InterruptedIOException) ChatFailureKind.TIMEOUT else ChatFailureKind.NETWORK
                    cont.resume(ChatResult.Failure(kind))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { parse(it) }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }

    private fun parse(response: Response): ChatResult {
        if (!response.isSuccessful) return ChatResult.Failure(ChatFailureKind.HTTP, response.code)
        val source = response.body?.source() ?: return ChatResult.Failure(ChatFailureKind.MALFORMED_RESPONSE)
        val text = try {
            // 多请求 1 字节，用来判断是否超过上限
            if (source.request(MAX_BODY_BYTES + 1)) return ChatResult.Failure(ChatFailureKind.MALFORMED_RESPONSE)
            source.buffer.readUtf8()
        } catch (e: InterruptedIOException) {
            return ChatResult.Failure(ChatFailureKind.TIMEOUT)
        } catch (e: IOException) {
            return ChatResult.Failure(ChatFailureKind.NETWORK)
        }
        return try {
            val root = Json.parseToJsonElement(text).jsonObject
            val choices = root["choices"] as? JsonArray
            val message = (choices?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            val content = message?.get("content") as? JsonPrimitive
            if (content == null || !content.isString || content.content.isBlank()) {
                ChatResult.Failure(ChatFailureKind.MALFORMED_RESPONSE)
            } else {
                ChatResult.Success(content.content)
            }
        } catch (e: Exception) {
            ChatResult.Failure(ChatFailureKind.MALFORMED_RESPONSE)
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 256L * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
