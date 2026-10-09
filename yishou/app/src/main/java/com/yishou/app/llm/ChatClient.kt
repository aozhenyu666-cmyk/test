package com.yishou.app.llm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** 设置页里由用户填写的接口配置。代码里不写死任何服务商。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 是否发送 response_format = json_object。DeepSeek、OpenAI 支持；个别服务商不支持时关掉。 */
    val jsonMode: Boolean,
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** 请求失败的原因。message 直接显示给用户，要说清楚是哪里出了问题。 */
sealed class LlmError(val message: String) {
    data object NotConfigured : LlmError("还没有填写接口地址、密钥或模型名，请到设置页填写")
    data object Timeout : LlmError("请求超时，没有在规定时间内返回")
    class Network(detail: String) : LlmError("网络连接失败：$detail")
    class Http(val code: Int, detail: String) : LlmError(httpMessage(code, detail))
    class BadFormat(detail: String) : LlmError("模型返回的内容不是约定的 JSON：$detail")

    companion object {
        private fun httpMessage(code: Int, detail: String): String {
            val hint = when (code) {
                400 -> "请求被拒绝。如果服务商不支持 JSON 模式，可在设置里关掉"
                401, 403 -> "密钥不对或已失效"
                402 -> "账户余额不足"
                404 -> "接口地址或模型名不对"
                429 -> "请求太频繁或额度用完"
                in 500..599 -> "服务商那边出错了"
                else -> "请求失败"
            }
            val tail = detail.trim().take(200)
            return if (tail.isEmpty()) "接口返回 $code：$hint" else "接口返回 $code：$hint（$tail）"
        }
    }
}

/** 记录每次请求的用量，盘点页用。kind 是请求类型（开局、判定、识图……）。 */
fun interface UsageSink {
    fun record(kind: String, ok: Boolean, promptTokens: Int, completionTokens: Int, millis: Long, requestChars: Int)
}

sealed interface LlmResult<out T> {
    data class Ok<T>(val value: T) : LlmResult<T>
    data class Err(val error: LlmError) : LlmResult<Nothing>
}

/**
 * 调用 OpenAI 兼容的 Chat Completions 接口，返回模型回复的文本。
 * 每次请求整体超时 30 秒；协程被取消时请求也会被取消。
 */
class ChatClient(
    private val config: () -> LlmConfig,
    private val http: OkHttpClient = defaultHttpClient(),
    private val usage: UsageSink? = null,
) {

    suspend fun complete(system: String, user: String, kind: String = "其他"): LlmResult<String> {
        val cfg = config()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return send(cfg, messages, cfg.jsonMode, kind, system.length + user.length)
    }

    /**
     * 带一张图片的请求（OpenAI 兼容的 image_url 格式，图片用 data URL 内嵌）。
     * 只用于识图模型；返回纯文本，不要求 JSON。
     */
    suspend fun completeWithImage(system: String, text: String, imageDataUrl: String, kind: String = "识图"): LlmResult<String> {
        val cfg = config()
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageDataUrl)))
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", content))
        return send(cfg, messages, jsonMode = false, kind = kind, requestChars = system.length + text.length)
    }

    private suspend fun send(
        cfg: LlmConfig,
        messages: JSONArray,
        jsonMode: Boolean,
        kind: String,
        requestChars: Int,
    ): LlmResult<String> {
        val started = System.currentTimeMillis()
        val tokens = IntArray(2)
        val result = sendOnce(cfg, messages, jsonMode, tokens)
        usage?.record(kind, result is LlmResult.Ok, tokens[0], tokens[1], System.currentTimeMillis() - started, requestChars)
        return result
    }

    /** tokens 用来带回响应里的 usage：[输入 token, 输出 token]。 */
    private suspend fun sendOnce(cfg: LlmConfig, messages: JSONArray, jsonMode: Boolean, tokens: IntArray): LlmResult<String> {
        if (!cfg.isComplete) return LlmResult.Err(LlmError.NotConfigured)

        val body = JSONObject()
            .put("model", cfg.model.trim())
            .put("messages", messages)
            .put("stream", false)
        if (jsonMode) body.put("response_format", JSONObject().put("type", "json_object"))

        val url = chatCompletionsUrl(cfg.baseUrl)
            ?: return LlmResult.Err(LlmError.Network("接口地址格式不对：${cfg.baseUrl}"))

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey.trim()}")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        val response = try {
            http.newCall(request).await()
        } catch (e: InterruptedIOException) {
            return LlmResult.Err(LlmError.Timeout)
        } catch (e: IOException) {
            return LlmResult.Err(LlmError.Network(e.message ?: e.javaClass.simpleName))
        }

        return response.use { resp ->
            val text = try {
                resp.body?.string().orEmpty()
            } catch (e: InterruptedIOException) {
                return LlmResult.Err(LlmError.Timeout)
            } catch (e: IOException) {
                return LlmResult.Err(LlmError.Network(e.message ?: e.javaClass.simpleName))
            }
            if (!resp.isSuccessful) {
                LlmResult.Err(LlmError.Http(resp.code, errorDetail(text)))
            } else {
                extractUsage(text).let { (p, c) -> tokens[0] = p; tokens[1] = c }
                extractContent(text)
            }
        }
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 陪练请求整体 30 秒超时。识图要上传图片、模型也更慢，用 60 秒。 */
        fun defaultHttpClient(timeoutSeconds: Long = 30): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

        /**
         * 用户可能填 https://api.deepseek.com、…/v1 或完整的 …/chat/completions，统一成完整地址。
         * 不是 http(s) 地址时返回 null。
         */
        fun chatCompletionsUrl(baseUrl: String): String? {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return null
            if (trimmed.length <= "https://".length) return null
            return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }

        /** 从 Chat Completions 的响应里取出 choices[0].message.content。 */
        fun extractContent(responseText: String): LlmResult<String> = try {
            val content = JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
            if (content.isBlank()) {
                LlmResult.Err(LlmError.BadFormat("回复为空"))
            } else {
                LlmResult.Ok(content)
            }
        } catch (e: JSONException) {
            LlmResult.Err(LlmError.BadFormat("接口响应结构不对：${responseText.take(120)}"))
        }

        /** 取出 usage.prompt_tokens / completion_tokens，取不到为 0。 */
        fun extractUsage(responseText: String): Pair<Int, Int> = try {
            val u = JSONObject(responseText).optJSONObject("usage")
            (u?.optInt("prompt_tokens", 0) ?: 0) to (u?.optInt("completion_tokens", 0) ?: 0)
        } catch (e: JSONException) {
            0 to 0
        }

        /** OpenAI 兼容接口的错误一般是 {"error": {"message": "..."}}，取出 message，取不到就原样截断。 */
        private fun errorDetail(text: String): String = try {
            JSONObject(text).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: text
        } catch (e: JSONException) {
            text
        }
    }
}

/** OkHttp 的异步调用包成挂起函数，协程取消时取消请求，不阻塞线程。 */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // 已被取消时由 onCancellation 关掉响应，避免连接泄漏
            cont.resume(response) { response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
        }
    })
    cont.invokeOnCancellation { cancel() }
}
