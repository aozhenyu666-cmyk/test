package app.jobtracker.ai

import app.jobtracker.config.AppConfig
import app.jobtracker.data.repo.SettingsRepository
import app.jobtracker.security.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Anthropic Messages 接口。请求只发往设置里的接口地址；密钥只放在请求头里，不写日志。
 */
class AnthropicClient(
    private val settings: SettingsRepository,
    private val apiKeys: ApiKeyStore,
    private val network: NetworkMonitor,
    private val config: AppConfig,
    http: OkHttpClient = OkHttpClient(),
) : ModelClient {
    private val http = http.newBuilder()
        .callTimeout(config.requestTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    override suspend fun callModel(role: ModelRole, system: String, user: String, outputSchema: JsonObject?): String {
        val apiKey = apiKeys.get()?.takeIf { it.isNotBlank() } ?: throw ModelException(ModelError.NoApiKey)
        // 无网络时直接提示，不发请求也不重试
        if (!network.isOnline()) throw ModelException(ModelError.NoNetwork)

        val s = settings.get()
        val model = when (role) {
            ModelRole.ANALYSIS -> s.strongModel
            ModelRole.DRAFT -> s.fastModel
        }
        val effort = when (role) {
            ModelRole.ANALYSIS -> config.analysisEffort
            ModelRole.DRAFT -> config.draftEffort
        }
        val fallback = useServerFallback(s.apiBaseUrl, model, config)

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", config.maxOutputTokens)
            put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
            if (effort.isNotEmpty() || outputSchema != null) {
                putJsonObject("output_config") {
                    if (effort.isNotEmpty()) put("effort", effort)
                    if (outputSchema != null) {
                        putJsonObject("format") {
                            put("type", "json_schema")
                            put("schema", outputSchema)
                        }
                    }
                }
            }
            if (fallback) put("fallbacks", "default")
        }

        val request = Request.Builder()
            .url(s.apiBaseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", config.anthropicVersion)
            .apply { if (fallback) header("anthropic-beta", SERVER_FALLBACK_BETA) }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val (code, text) = withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: UnknownHostException) {
                throw ModelException(ModelError.NoNetwork)
            } catch (e: ConnectException) {
                throw ModelException(ModelError.NoNetwork)
            } catch (e: InterruptedIOException) {
                // callTimeout 和 socket 超时都会走到这里
                throw ModelException(ModelError.Timeout)
            } catch (e: IOException) {
                throw ModelException(ModelError.NetworkFailure)
            }
        }
        if (code !in 200..299) throw ModelException(ModelError.Http(code, errorMessage(text)))
        return extractText(text)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val SERVER_FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** 只在直连官方接口、且模型支持时开启拒答兜底；中转地址不一定认这个参数 */
        fun useServerFallback(baseUrl: String, model: String, config: AppConfig): Boolean =
            baseUrl.toHttpUrlOrNull()?.host == config.officialApiHost && model in config.serverFallbackModels

        /** 取出所有文本块拼接；拒答、截断都算失败 */
        fun extractText(responseBody: String): String {
            val json = try {
                Json.parseToJsonElement(responseBody).jsonObject
            } catch (e: SerializationException) {
                throw ModelException(ModelError.InvalidOutput)
            } catch (e: IllegalArgumentException) {
                throw ModelException(ModelError.InvalidOutput)
            }
            when (json["stop_reason"]?.jsonPrimitive?.contentOrNull) {
                "refusal" -> throw ModelException(ModelError.Refused)
                "max_tokens" -> throw ModelException(ModelError.InvalidOutput)
            }
            val text = json["content"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            if (text.isBlank()) throw ModelException(ModelError.InvalidOutput)
            return text
        }

        /** 接口错误格式：{"type":"error","error":{"type":"...","message":"..."}} */
        private fun errorMessage(responseBody: String): String? = try {
            Json.parseToJsonElement(responseBody).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }
}
