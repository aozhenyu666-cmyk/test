package com.zongkong.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** 大模型接口。验收、测试连接都走这里，方便测试时替换。 */
fun interface LlmApi {
    suspend fun complete(system: String, user: String): ApiResult<String>
}

/** OpenAI 兼容的 Chat Completions（DeepSeek、Kimi、通义、OpenAI 等都支持）。 */
class LlmClient(
    private val config: () -> AiConfig,
    private val http: OkHttpClient = Net.client(),
) : LlmApi {

    override suspend fun complete(system: String, user: String): ApiResult<String> {
        val cfg = config()
        if (!cfg.ready) return ApiResult.Err("还没有配置 AI 接口（设置 → AI 接口）")
        val url = chatUrl(cfg.baseUrl) ?: return ApiResult.Err("接口地址格式不对：${cfg.baseUrl}")
        val body = buildJsonObject {
            put("model", cfg.model.trim())
            put("stream", false)
            put("temperature", 0.2)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", system) })
                    add(buildJsonObject { put("role", "user"); put("content", user) })
                },
            )
            if (cfg.jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
        }
        val raw = when (val r = Net.send(http, Net.post(url, body, mapOf("Authorization" to "Bearer ${cfg.apiKey.trim()}")))) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> r.value
        }
        if (raw.code !in 200..299) return ApiResult.Err(httpMessage(raw.code, Net.errorMessage(raw.body)))
        return content(raw.body)
    }

    companion object {
        /** 用户可能填 https://api.deepseek.com、…/v1 或完整的 …/chat/completions。 */
        fun chatUrl(baseUrl: String): String? {
            val t = baseUrl.trim().trimEnd('/')
            if (!t.startsWith("https://") && !t.startsWith("http://")) return null
            if (t.substringAfter("://").isBlank()) return null
            return if (t.endsWith("/chat/completions")) t else "$t/chat/completions"
        }

        fun content(body: String): ApiResult<String> {
            val text = try {
                Net.parseObject(body)?.get("choices")?.jsonArray?.get(0)?.jsonObject
                    ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                null
            }
            return if (text.isNullOrBlank()) ApiResult.Err("AI 返回的内容为空或结构不对：${body.take(120)}") else ApiResult.Ok(text)
        }

        fun httpMessage(code: Int, detail: String): String {
            val hint = when (code) {
                400 -> "请求被拒绝（如果服务商不支持 JSON 模式，可在设置里关掉）"
                401, 403 -> "密钥不对或已失效"
                402 -> "账户余额不足"
                404 -> "接口地址或模型名不对"
                429 -> "请求太频繁或额度用完"
                in 500..599 -> "服务商那边出错了"
                else -> "请求失败"
            }
            return if (detail.isBlank()) "AI 接口返回 $code：$hint" else "AI 接口返回 $code：$hint（$detail）"
        }

        /** 从模型回复里取出 JSON 对象。有的模型会包一层 ```json，或者前后多说几句。 */
        fun extractJson(text: String): JsonObject? {
            Net.parseObject(text.trim())?.let { return it }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return Net.parseObject(text.substring(start, end + 1))
        }
    }
}
