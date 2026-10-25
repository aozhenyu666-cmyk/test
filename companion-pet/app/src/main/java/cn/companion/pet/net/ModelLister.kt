package cn.companion.pet.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * “获取模型列表”：向服务商的 OpenAI 兼容 `/models` 要当前可用的模型名，免得手填填错（参考 JARVIS 的 Fetch live model list）。
 * 和聊天请求一样：只走 https、不跟随重定向、Key 只发给填的那个地址。
 */
class ModelLister(base: OkHttpClient = OkHttpClient()) {
    sealed interface Result {
        data class Success(val models: List<String>) : Result
        data class Failure(val reason: String) : Result
    }

    private val http = base.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun list(endpoint: String, apiKey: String): Result {
        val url = modelsUrl(endpoint) ?: return Result.Failure("接口地址不对（要 https 开头）")
        val builder = Request.Builder().url(url).get()
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer " + apiKey.trim())
        val call = http.newCall(builder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(Result.Failure(if (e is InterruptedIOException) "等太久没回应" else "连不上服务商"))
                }

                override fun onResponse(call: Call, response: Response) {
                    val r = response.use { read(it) }
                    if (cont.isActive) cont.resume(r)
                }
            })
        }
    }

    private fun read(response: Response): Result {
        if (!response.isSuccessful) {
            return Result.Failure(when (response.code) {
                401, 403 -> "Key 不对或和这个服务商不匹配（HTTP ${response.code}）"
                404 -> "这个服务商不提供模型列表（HTTP 404），只能手填"
                else -> "服务商返回 HTTP ${response.code}"
            })
        }
        val source = response.body?.source() ?: return Result.Failure("服务商没返回内容")
        val text = try {
            if (source.request(MAX_BODY_BYTES + 1)) return Result.Failure("模型列表太大")
            source.buffer.readUtf8()
        } catch (e: IOException) {
            return Result.Failure("读取失败")
        }
        val models = parseModels(text)
        return if (models.isEmpty()) Result.Failure("服务商返回的列表是空的或看不懂") else Result.Success(models)
    }

    companion object {
        const val MAX_BODY_BYTES = 512L * 1024

        /** `https://host/v1` 或 `.../chat/completions` → `https://host/v1/models`。 */
        fun modelsUrl(endpoint: String): String? = runCatching {
            EndpointPolicy.normalize(endpoint).removeSuffix("/chat/completions") + "/models"
        }.getOrNull()

        /** OpenAI 格式 `{"data":[{"id":...}]}`；也接受 `{"models":[...]}`。去重、排序、最多 200 个。 */
        fun parseModels(text: String): List<String> = runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val list = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: JsonArray(emptyList())
            list.mapNotNull { item ->
                when (item) {
                    is JsonObject -> ((item["id"] ?: item["name"]) as? JsonPrimitive)?.content
                    is JsonPrimitive -> item.content
                    else -> null
                }
            }.map { it.removePrefix("models/").trim() }.filter { it.isNotEmpty() && it.length <= 120 }
                .distinct().sorted().take(200)
        }.getOrDefault(emptyList())

        private val VISION_HINTS = listOf("vl", "vision", "4o", "4.1", "gpt-5", "gemini", "glm-4v", "qvq", "omni", "pixtral", "llava", "image")

        /** 名字看起来能看图的模型（只是提示，不保证）。 */
        fun looksVisual(model: String): Boolean {
            val m = model.lowercase()
            return VISION_HINTS.any { h -> if (h == "vl") Regex("(^|[-_.])vl([-_.]|$)").containsMatchIn(m) else m.contains(h) }
        }
    }
}
