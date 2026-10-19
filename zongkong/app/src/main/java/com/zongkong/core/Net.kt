package com.zongkong.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** 网络请求的结果。Err.message 直接显示给用户。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val message: String) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(f(value))
    is ApiResult.Err -> this
}

val ZkJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

object Net {
    val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    fun client(timeoutSeconds: Long = 40): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    data class Raw(val code: Int, val body: String)

    /** 发一个请求，拿回状态码和正文；网络错误转成中文说明。 */
    suspend fun send(http: OkHttpClient, request: Request): ApiResult<Raw> = withContext(Dispatchers.IO) { sendOnIo(http, request) }

    /**
     * 读响应正文也是网络读取，必须在后台线程：Android 在主线程上读网络会直接抛
     * NetworkOnMainThreadException（界面上点“同步”“提交验收”时就是从主线程发起的）。
     */
    private suspend fun sendOnIo(http: OkHttpClient, request: Request): ApiResult<Raw> {
        val response = try {
            http.newCall(request).await()
        } catch (e: InterruptedIOException) {
            return ApiResult.Err("请求超时")
        } catch (e: IOException) {
            return ApiResult.Err("网络连接失败：${e.message ?: e.javaClass.simpleName}")
        }
        return response.use { resp ->
            try {
                ApiResult.Ok(Raw(resp.code, resp.body?.string().orEmpty()))
            } catch (e: InterruptedIOException) {
                ApiResult.Err("请求超时")
            } catch (e: IOException) {
                ApiResult.Err("网络连接失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun post(url: String, body: JsonElement, headers: Map<String, String>): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody(JSON_TYPE)).build()

    fun patch(url: String, body: JsonElement, headers: Map<String, String>): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .patch(body.toString().toRequestBody(JSON_TYPE)).build()

    fun get(url: String, headers: Map<String, String>): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()

    fun parseObject(text: String): JsonObject? = try {
        ZkJson.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        null
    }

    /** 常见错误体里的 message：OpenAI 是 error.message，Notion 是 message。 */
    fun errorMessage(text: String): String {
        val obj = parseObject(text) ?: return text.trim().take(200)
        val nested = (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        val flat = (obj["message"])?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        return (nested ?: flat ?: text).trim().take(200)
    }
}

/** OkHttp 异步调用包成挂起函数，协程取消时一并取消请求。 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
        }
    })
    cont.invokeOnCancellation { cancel() }
}
