package com.zongkong.app.system

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.zongkong.app.zk
import com.zongkong.core.ApiResult
import com.zongkong.core.Net
import com.zongkong.core.VoiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * 总控的嘴和耳朵。
 *  说：配了语音接口用接口（/audio/speech，声音自然），没配用手机自带的朗读。
 *  听：配了接口就录音发给 /audio/transcriptions；没配由界面调手机自带的语音输入。
 */
object Voice {
    private const val TAG = "Voice"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http by lazy { Net.client(60) }
    private var player: MediaPlayer? = null
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null

    enum class State { IDLE, SPEAKING, LISTENING, THINKING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    fun set(s: State) { _state.value = s }

    private fun cfg(context: Context): VoiceConfig = context.zk.store.config.value.voice

    private fun endpoint(base: String, path: String): String {
        val t = base.trim().trimEnd('/')
        return if (t.endsWith(path)) t else "$t/$path"
    }

    /** 念一段话。[onDone] 念完回调（主线程）。 */
    fun speak(context: Context, text: String, onDone: () -> Unit = {}) {
        val clean = text.replace(Regex("【选项】.*$", RegexOption.MULTILINE), "").replace(Regex("[*#`>]"), "").trim()
        if (clean.isBlank()) { onDone(); return }
        stopSpeaking()
        val c = cfg(context)
        _state.value = State.SPEAKING
        if (!c.apiReady) {
            Speaker.speak(context, clean)
            // 系统朗读没有可靠的结束回调：按字数估一个时长
            scope.launch {
                kotlinx.coroutines.delay((clean.length * 230L).coerceIn(1500, 30_000))
                if (_state.value == State.SPEAKING) _state.value = State.IDLE
                onDone()
            }
            return
        }
        scope.launch {
            val file = withContext(Dispatchers.IO) { tts(context, c, clean.take(1500)) }
            if (file == null) {
                Speaker.speak(context, clean)
                _state.value = State.IDLE
                onDone()
                return@launch
            }
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { it.release(); player = null; _state.value = State.IDLE; onDone() }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "播放失败", e)
                _state.value = State.IDLE
                onDone()
            }
        }
    }

    fun stopSpeaking() {
        runCatching { player?.stop(); player?.release() }
        player = null
        Speaker.stop()
        if (_state.value == State.SPEAKING) _state.value = State.IDLE
    }

    private fun tts(context: Context, c: VoiceConfig, text: String): File? = try {
        val body = buildJsonObject {
            put("model", c.ttsModel.ifBlank { "tts-1" })
            put("input", text)
            put("voice", c.ttsVoice.ifBlank { "alloy" })
            put("response_format", "mp3")
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(endpoint(c.baseUrl, "audio/speech")).header("Authorization", "Bearer ${c.apiKey.trim()}").post(body).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "语音接口 ${r.code}：${r.body?.string()?.take(200)}"); null }
            else File(context.cacheDir, "say.mp3").also { f -> r.body?.byteStream()?.use { ins -> f.outputStream().use { ins.copyTo(it) } } }
        }
    } catch (e: Exception) {
        Log.w(TAG, "语音接口失败", e)
        null
    }

    /** 能不能用接口听写（否则界面用手机自带的语音输入）。 */
    fun canTranscribe(context: Context) = cfg(context).apiReady

    /** 开始录音（要有麦克风权限）。 */
    fun startRecording(context: Context): Boolean = try {
        stopSpeaking()
        val f = File(context.cacheDir, "hear.m4a")
        recFile = f
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16000)
        r.setAudioEncodingBitRate(48000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        _state.value = State.LISTENING
        true
    } catch (e: Exception) {
        Log.w(TAG, "录音失败", e)
        recorder = null
        _state.value = State.IDLE
        false
    }

    /** 停止录音，转成文字。 */
    suspend fun stopAndTranscribe(context: Context): ApiResult<String> {
        val r = recorder ?: return ApiResult.Err("没有在录音")
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        _state.value = State.THINKING
        val f = recFile ?: return ApiResult.Err("没有录到声音")
        if (f.length() < 2000) { _state.value = State.IDLE; return ApiResult.Err("太短了，按住多说一会儿") }
        val c = cfg(context)
        return withContext(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", c.sttModel.ifBlank { "whisper-1" })
                    .addFormDataPart("language", "zh")
                    .addFormDataPart("file", "hear.m4a", f.asRequestBody("audio/mp4".toMediaType()))
                    .build()
                val req = Request.Builder().url(endpoint(c.baseUrl, "audio/transcriptions")).header("Authorization", "Bearer ${c.apiKey.trim()}").post(body).build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) ApiResult.Err("听写接口 ${resp.code}：${Net.errorMessage(text).take(120)}")
                    else {
                        val t = runCatching { Net.parseObject(text)?.get("text")?.jsonPrimitive?.contentOrNull }.getOrNull() ?: text
                        if (t.isBlank()) ApiResult.Err("没听清") else ApiResult.Ok(t.trim())
                    }
                }
            } catch (e: Exception) {
                ApiResult.Err("听写失败：${e.message}")
            } finally {
                _state.value = State.IDLE
            }
        }
    }

    fun cancelRecording() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        _state.value = State.IDLE
    }

    /** 测一下说话接口。 */
    suspend fun test(context: Context): String = withContext(Dispatchers.IO) {
        val c = cfg(context)
        if (!c.apiReady) return@withContext "没填接口：用手机自带的朗读"
        if (tts(context, c, "你好，我是总控。") != null) "说话接口正常" else "说话接口不通：检查地址、密钥、模型名"
    }
}
