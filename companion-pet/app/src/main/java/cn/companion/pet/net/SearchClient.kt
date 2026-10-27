package cn.companion.pet.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
 * 一条可溯源的搜索结果。
 *
 * [url] 是它「真读过」的唯一凭据：没有 url 的条目一律丢弃，不许进入任何后续产出。
 */
data class SearchHit(
    val title: String,
    val url: String,
    val siteName: String,
    val date: String?,
    val summary: String,
)

/** 搜索失败的具体种类。调用方按种类决定降级方式，不要靠猜异常。 */
enum class SearchFailure {
    /** 没配 Key，根本没发请求。 */
    NO_KEY,

    /** HTTP 状态不对（含 408 / 429）。 */
    HTTP,

    /** 请求超时。 */
    TIMEOUT,

    /** 连不上、断网、DNS 失败。 */
    NETWORK,

    /** 响应不是预期结构，解析不出来。 */
    MALFORMED,

    /** 额度用完（HTTP 402）。 */
    QUOTA,
}

sealed interface SearchResult {
    data class Ok(val hits: List<SearchHit>) : SearchResult
    data class Failed(val kind: SearchFailure, val httpCode: Int? = null) : SearchResult
}

/** 搜索能力的抽象：上层的挑选逻辑只依赖这个接口，方便换通道。 */
interface SearchApi {
    suspend fun search(query: String, count: Int = 5): SearchResult
}

/**
 * DeepSeek Search API 客户端。
 *
 * 只做传输与解析：不解释内容、不重试、不写日志。
 * 任何失败都翻译成 [SearchResult.Failed]，绝不抛异常给调用方 —— 联网失败必须静默降级。
 */
class DeepSeekSearchClient(
    private val apiKey: () -> String,
    base: OkHttpClient = OkHttpClient(),
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val timeoutMillis: Long = 10_000L,
) : SearchApi {

    private val http: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun search(query: String, count: Int): SearchResult {
        val keyword = query.trim()
        if (keyword.isEmpty() || count <= 0) return SearchResult.Failed(SearchFailure.MALFORMED)
        val key = apiKey().trim()
        // 没配 Key 就不发请求：不浪费配额，也让设置页能如实显示「没配 Key」。
        if (key.isEmpty()) return SearchResult.Failed(SearchFailure.NO_KEY)

        val body = buildJsonObject {
            put("query", keyword)
            put("count", count.coerceIn(1, MAX_COUNT))
            put("need_content", false)
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(JSON))
            .header("Authorization", "Bearer " + key)
            .build()

        val client = http.newBuilder()
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(request)

        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive) return
                    val kind = if (e is InterruptedIOException) SearchFailure.TIMEOUT else SearchFailure.NETWORK
                    cont.resume(SearchResult.Failed(kind))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { readResponse(it) }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }

    private fun readResponse(response: Response): SearchResult {
        if (!response.isSuccessful) {
            val kind = if (response.code == 402) SearchFailure.QUOTA else SearchFailure.HTTP
            return SearchResult.Failed(kind, response.code)
        }
        val source = response.body?.source() ?: return SearchResult.Failed(SearchFailure.MALFORMED)
        val text = try {
            // 多请求 1 字节，用来判断是否超过上限；超了就当结构不对，不硬吞。
            if (source.request(MAX_BODY_BYTES + 1)) return SearchResult.Failed(SearchFailure.MALFORMED)
            source.buffer.readUtf8()
        } catch (e: InterruptedIOException) {
            return SearchResult.Failed(SearchFailure.TIMEOUT)
        } catch (e: IOException) {
            return SearchResult.Failed(SearchFailure.NETWORK)
        }
        return parse(text)
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/tools/search"
        const val MAX_COUNT = 20
        const val MAX_BODY_BYTES = 256L * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * 纯函数解析：缺 url 的条目丢弃（无法溯源），结构不符返回 MALFORMED，任何情况都不抛。
         *
         * 这里的 title / summary 一律当**素材文本**处理，原样保存，不执行、不解释 ——
         * 搜索结果里写着「忽略之前的指令」也只是标题。
         */
        fun parse(raw: String): SearchResult {
            val root = try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return SearchResult.Failed(SearchFailure.MALFORMED)

            val results = root["results"] as? JsonArray
                ?: return SearchResult.Failed(SearchFailure.MALFORMED)

            val hits = results.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val url = obj.string("url")?.trim().orEmpty()
                // 没有 url 就无法溯源，直接丢弃，宁可少几条也不许留下能编的对象。
                if (url.isEmpty()) return@mapNotNull null
                SearchHit(
                    title = obj.string("title").orEmpty(),
                    url = url,
                    siteName = obj.string("site_name").orEmpty(),
                    date = obj.string("date")?.takeIf { it.isNotBlank() },
                    summary = obj.string("summary")?.takeIf { it.isNotBlank() }
                        ?: obj.string("snippet").orEmpty(),
                )
            }
            return SearchResult.Ok(hits)
        }

        /** 只认字符串字段；数字/对象/null 都当缺字段，避免把脏数据带进标题。 */
        private fun JsonObject.string(key: String): String? {
            val value = this[key] as? JsonPrimitive ?: return null
            return if (value.isString) value.content else null
        }
    }
}

/**
 * 能力自检：把搜索结果翻译成设置页能直接显示的一句中文。
 *
 * 只描述「通道现在能不能用」，不承诺一定能搜到什么。
 */
object SearchCapability {
    fun levelOf(result: SearchResult): String = when (result) {
        is SearchResult.Ok -> "可用"
        is SearchResult.Failed -> when (result.kind) {
            SearchFailure.NO_KEY -> "没配 Key"
            SearchFailure.QUOTA -> "额度用完"
            SearchFailure.TIMEOUT -> "超时"
            SearchFailure.NETWORK -> "连不上"
            SearchFailure.HTTP -> "服务报错"
            SearchFailure.MALFORMED -> "响应异常"
        }
    }
}
