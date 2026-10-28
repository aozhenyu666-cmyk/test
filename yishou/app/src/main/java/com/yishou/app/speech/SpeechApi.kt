package com.yishou.app.speech

import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class SpeechApi(
    private val config: () -> SpeechConfig,
    private val http: OkHttpClient = defaultHttpClient(),
) {

    /** 把一段 WAV 录音转成文字。 */
    suspend fun transcribe(wav: ByteArray): LlmResult<String> {
        val cfg = config().input
        if (!cfg.ready) return LlmResult.Err(LlmError.NotConfigured)
        val url = endpoint(cfg.baseUrl, "audio/transcriptions")
            ?: return LlmResult.Err(LlmError.Network("接口地址格式不对：${cfg.baseUrl}"))
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", cfg.model.trim())
            .addFormDataPart("language", cfg.language.ifBlank { "zh" })
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "answer.wav", wav.toRequestBody(WAV))
            .build()
        return call(cfg, url, body) { bytes -> parseTranscript(String(bytes)) }
    }

    /** 把一句话合成为可直接解码的 mp3 或 wav 音频。 */
    suspend fun synthesize(text: String): LlmResult<ByteArray> {
        val cfg = config().output
        if (!cfg.ready) return LlmResult.Err(LlmError.NotConfigured)
        if (cfg.format !in setOf("mp3", "wav")) return LlmResult.Err(LlmError.Local("朗读暂时支持 mp3 或 wav 音频"))
        val url = endpoint(cfg.baseUrl, "audio/speech")
            ?: return LlmResult.Err(LlmError.Network("接口地址格式不对：${cfg.baseUrl}"))
        val json = JSONObject()
            .put("model", cfg.model.trim())
            .put("input", text)
            .put("response_format", cfg.format)
        if (cfg.voice.isNotBlank()) json.put("voice", cfg.voice.trim())
        return call(cfg, url, json.toString().toRequestBody(JSON)) { bytes ->
            if (bytes.isEmpty()) LlmResult.Err(LlmError.BadFormat("返回的音频为空")) else LlmResult.Ok(bytes)
        }
    }

    private suspend fun <T> call(
        cfg: SpeechEndpoint,
        url: String,
        body: okhttp3.RequestBody,
        parse: (ByteArray) -> LlmResult<T>,
    ): LlmResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey.trim()}")
            .post(body)
            .build()
        try {
            http.newCall(request).awaitSpeech().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) LlmResult.Err(LlmError.Http(resp.code, String(bytes).take(200)))
                else parse(bytes)
            }
        } catch (e: InterruptedIOException) {
            LlmResult.Err(LlmError.Timeout)
        } catch (e: IOException) {
            LlmResult.Err(LlmError.Network(e.message ?: e.javaClass.simpleName))
        }
    }

    companion object {
        private val WAV = "audio/wav".toMediaType()
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()

        /**
         * 用户可能填 https://api.x.com、…/v1，或者直接贴了完整的 …/audio/transcriptions，
         * 统一成 {base}/{path}。不是 http(s) 地址时返回 null。
         */
        fun endpoint(baseUrl: String, path: String): String? {
            var t = baseUrl.trim().trimEnd('/')
            if (!t.startsWith("https://") && !t.startsWith("http://")) return null
            if (t.length <= "https://".length) return null
            for (tail in listOf("/audio/transcriptions", "/audio/speech", "/chat/completions")) {
                if (t.endsWith(tail)) t = t.removeSuffix(tail)
            }
            return "$t/$path"
        }

        /** 听写结果一般是 {"text": "..."}；个别接口直接返回纯文本。 */
        fun parseTranscript(body: String): LlmResult<String> {
            val text = try {
                JSONObject(body).optString("text", "")
            } catch (e: JSONException) {
                body.takeUnless { it.trimStart().startsWith("{") || it.trimStart().startsWith("<") } ?: ""
            }.trim()
            return if (text.isEmpty()) LlmResult.Err(LlmError.BadFormat("没有听写出文字")) else LlmResult.Ok(text)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun Call.awaitSpeech(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { response.close() }
        }
    })
}
