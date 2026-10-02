package com.behaviordept.app.ai

import com.behaviordept.app.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 便宜档：出题、批改；旗舰档：周复盘、技能拆解（阶段 3 才用到）。 */
enum class Tier { CHEAP, FLAGSHIP }

data class AiEndpoint(
    val provider: Provider,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

data class AiReply(
    val text: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

/** 所有 AI 失败都变成这几类，界面据此给出“重试”或“改为对照资料自查”。 */
sealed class AiException(message: String) : Exception(message) {
    class NotConfigured : AiException("还没有配置 AI：去设置里填 API Key 和模型")
    class Offline : AiException("现在没有网络")
    class Network(detail: String) : AiException("网络请求失败：$detail")
    class Http(val code: Int, detail: String) : AiException(httpMessage(code, detail))
    class Refused : AiException("模型拒绝回答这次请求")
    class BadFormat(val raw: String, reason: String) : AiException("AI 的回复没按规定格式（$reason）")

    companion object {
        private fun httpMessage(code: Int, detail: String): String {
            val head = when (code) {
                401 -> "API Key 无效"
                402 -> "账户余额不足"
                403 -> "没有权限调用这个模型"
                404 -> "接口地址或模型名不对"
                429 -> "调用太频繁或额度用完"
                529 -> "服务繁忙"
                in 500..599 -> "服务端出错"
                else -> "请求失败"
            }
            return if (detail.isBlank()) "$head（HTTP $code）" else "$head（HTTP $code）：$detail"
        }
    }
}

/**
 * 统一调用层：同时支持 Anthropic Messages 接口和 OpenAI 兼容的 Chat Completions 接口。
 * 只做一问一答，不做聊天。日志里不出现 API Key。
 */
class AiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun complete(endpoint: AiEndpoint, system: String, user: String, maxTokens: Int = 4096): AiReply {
        if (endpoint.apiKey.isBlank() || endpoint.baseUrl.isBlank() || endpoint.model.isBlank()) {
            throw AiException.NotConfigured()
        }
        var last: AiException? = null
        repeat(2) { attempt ->
            if (attempt > 0) delay(1500)
            try {
                return withContext(Dispatchers.IO) { send(endpoint, system, user, maxTokens) }
            } catch (e: AiException.Http) {
                if (e.code !in RETRYABLE) throw e
                last = e
            } catch (e: AiException.Network) {
                last = e
            }
        }
        throw last ?: AiException.Network("未知错误")
    }

    private fun send(endpoint: AiEndpoint, system: String, user: String, maxTokens: Int): AiReply {
        val request = when (endpoint.provider) {
            Provider.ANTHROPIC -> anthropicRequest(endpoint, system, user, maxTokens)
            Provider.OPENAI -> openAiRequest(endpoint, system, user, maxTokens)
        }
        val (code, body) = try {
            http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            throw AiException.Network(e.message ?: e.javaClass.simpleName)
        }
        if (code !in 200..299) throw AiException.Http(code, errorDetail(body))
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw AiException.Http(code, "返回的不是 JSON")
        }
        return when (endpoint.provider) {
            Provider.ANTHROPIC -> parseAnthropic(root, endpoint.model)
            Provider.OPENAI -> parseOpenAi(root, endpoint.model)
        }
    }

    private fun anthropicUrl(base: String): String {
        val b = base.trimEnd('/')
        return if (b.endsWith("/v1")) "$b/messages" else "$b/v1/messages"
    }

    private fun anthropicRequest(e: AiEndpoint, system: String, user: String, maxTokens: Int): Request {
        val payload = buildJsonObject {
            put("model", e.model)
            put("max_tokens", maxTokens)
            put("system", system)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", user)
                })
            })
        }
        return Request.Builder()
            .url(anthropicUrl(e.baseUrl))
            .header("x-api-key", e.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
    }

    private fun openAiRequest(e: AiEndpoint, system: String, user: String, maxTokens: Int): Request {
        val payload = buildJsonObject {
            put("model", e.model)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", user)
                })
            })
        }
        return Request.Builder()
            .url(e.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${e.apiKey}")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
    }

    private fun parseAnthropic(root: JsonObject, model: String): AiReply {
        if (root["stop_reason"].str() == "refusal") throw AiException.Refused()
        val blocks = (root["content"] as? JsonArray) ?: JsonArray(emptyList())
        val text = blocks
            .mapNotNull { it as? JsonObject }
            .filter { it["type"].str() == "text" }
            .joinToString("") { it["text"].str().orEmpty() }
        val usage = root["usage"] as? JsonObject
        return AiReply(
            text = text,
            model = root["model"].str() ?: model,
            inputTokens = usage?.get("input_tokens").int(),
            outputTokens = usage?.get("output_tokens").int(),
        )
    }

    private fun parseOpenAi(root: JsonObject, model: String): AiReply {
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw AiException.Http(200, "返回里没有 choices")
        val message = choice["message"] as? JsonObject
        val text = message?.get("content").str().orEmpty()
        if (choice["finish_reason"].str() == "content_filter" && text.isBlank()) {
            throw AiException.Refused()
        }
        val usage = root["usage"] as? JsonObject
        return AiReply(
            text = text,
            model = root["model"].str() ?: model,
            inputTokens = usage?.get("prompt_tokens").int(),
            outputTokens = usage?.get("completion_tokens").int(),
        )
    }

    /** 从错误响应里取一句人能看懂的说明，两种接口都是 {"error": {"message": ...}}。 */
    private fun errorDetail(body: String): String {
        val msg = runCatching {
            val err = json.parseToJsonElement(body).jsonObject["error"]
            if (err is JsonObject) err["message"].str() else err.str()
        }.getOrNull()
        return (msg ?: body).replace(Regex("\\s+"), " ").take(160)
    }

    private companion object {
        val RETRYABLE = setOf(429, 500, 502, 503, 504, 529)
    }
}

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.int(): Int = (this as? JsonPrimitive)?.intOrNull ?: 0
