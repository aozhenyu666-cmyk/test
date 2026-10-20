package com.moling.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 模型调用的结果。Err 的 message 直接给用户看，所以都写成中文。 */
sealed class LlmResult<out T> {
    data class Ok<T>(val value: T) : LlmResult<T>()
    data class Err(val message: String) : LlmResult<Nothing>()
}

/**
 * OpenAI 兼容的 Chat Completions（DeepSeek、Kimi、通义、OpenAI 都支持）。
 * 请求在 IO 线程发，30 秒封顶；失败只返回 Err，不抛给界面。
 */
class LlmClient(private val config: () -> ModelSettings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // 整体也封顶 30 秒：连接慢的时候不要把测试按钮吊到一分钟。
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun complete(system: String, user: String): LlmResult<String> {
        val cfg = config()
        if (cfg.apiKey.isBlank()) return LlmResult.Err("还没有配置 AI 接口（设置里填）")
        val url = chatUrl(cfg.baseUrl) ?: return LlmResult.Err("接口地址格式不对：${cfg.baseUrl}")
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
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey.trim()}")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        return try {
            withTimeout(TIMEOUT_MS) {
                // 读正文也算网络读取，必须在 IO 线程，不能在主线程读。
                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (resp.code !in 200..299) LlmResult.Err(httpMessage(resp.code, errorMessage(text)))
                        else content(text)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            LlmResult.Err("请求超时（30 秒），网络可能不通")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LlmResult.Err("网络连接失败：${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            LlmResult.Err("接口地址格式不对：${cfg.baseUrl}")
        } catch (e: Exception) {
            LlmResult.Err("请求出错：${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TIMEOUT_MS = 30_000L
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 用户可能填 https://api.deepseek.com、…/v1 或完整的 …/chat/completions。 */
        fun chatUrl(baseUrl: String): String? {
            val t = baseUrl.trim().trimEnd('/')
            if (!t.startsWith("https://") && !t.startsWith("http://")) return null
            if (t.substringAfter("://").isBlank()) return null
            return if (t.endsWith("/chat/completions")) t else "$t/chat/completions"
        }

        /** 从返回里取 choices[0].message.content；空内容算失败。 */
        fun content(body: String): LlmResult<String> {
            val text = try {
                (json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject
                    ?.get("message") as? JsonObject)?.get("content")?.let { (it as? JsonPrimitive)?.contentOrNull }
            } catch (e: Exception) {
                null
            }
            return if (text.isNullOrBlank()) {
                LlmResult.Err("AI 返回的内容为空或结构不对：${body.take(120)}")
            } else {
                LlmResult.Ok(text)
            }
        }

        /** 错误体里的说明：OpenAI 是 error.message，有的服务商直接给 message。 */
        fun errorMessage(body: String): String {
            val obj = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                return body.trim().take(200)
            }
            val nested = ((obj["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            val flat = (obj["message"] as? JsonPrimitive)?.contentOrNull
            return (nested ?: flat ?: body).trim().take(200)
        }

        /** 状态码翻成人话，再带上服务商的原话，方便用户自己改。 */
        fun httpMessage(code: Int, detail: String): String {
            val hint = when (code) {
                400 -> "请求被拒绝（检查模型名和参数）"
                401, 403 -> "密钥不对或已失效"
                402 -> "账户余额不足"
                404 -> "接口地址或模型名不对"
                429 -> "请求太频繁或额度用完"
                in 500..599 -> "服务商那边出错了"
                else -> "请求失败"
            }
            return if (detail.isBlank()) "AI 接口返回 $code：$hint" else "AI 接口返回 $code：$hint（$detail）"
        }
    }
}
