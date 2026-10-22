package com.moling.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.moling.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 清掉朗读不念的东西：动作行是给按钮的，念出来只是噪音。 */
private val NOT_SPOKEN = Regex("【[^】]*】[^\n]*")

/**
 * 她的声音（v8）：
 *  - 设置里填了语音模型（OpenAI 兼容的 /audio/speech，比如 OpenAI 的 gpt-4o-mini-tts）就用它，声音自然得多；
 *  - 没填、或者它不通，退回系统朗读；手机连朗读引擎都没有就静默跳过——聊天不该因为念不出来而卡住。
 * [speak] 的 onDone 在念完后（主线程）回调：对讲模式靠它“她说完你接着说”。
 */
object Speaker {
    private const val TAG = "MolingSpeaker"

    private var app: Context? = null
    private var store: SettingsStore? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val done = HashMap<String, () -> Unit>()
    private var seq = 0
    /** 每次 [speak] 一个令牌；[stop] 作废。云 TTS / 播放器晚回来时令牌对不上就丢。 */
    private var token = 0L

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    }

    fun prepare(context: Context) {
        app = context.applicationContext
        if (store == null) store = SettingsStore(context.applicationContext)
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val ctx = app
        val clean = text.replace(NOT_SPOKEN, "").trim().take(600)
        if (ctx == null || clean.isEmpty()) {
            onDone?.let { main.post(it) }
            return
        }
        stop()
        val my = token
        val v = store?.voice()
        if (v != null && v.ready()) {
            scope.launch {
                val file = runCatching { cloud(ctx, v, clean) }.onFailure { Log.w(TAG, "语音模型不通，退回系统朗读", it) }.getOrNull()
                main.post {
                    // 拉取期间 stop() 或新的 speak 已经把这次作废：回来也不播。
                    if (my != token) return@post
                    if (file != null) play(file, onDone) else system(ctx, clean, onDone)
                }
            }
        } else {
            system(ctx, clean, onDone)
        }
    }

    /** OpenAI 兼容的 /audio/speech：返回一段 mp3。 */
    private fun cloud(ctx: Context, v: com.moling.app.data.VoiceSettings, text: String): File {
        val base = v.baseUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/audio/speech")) base else "$base/audio/speech"
        val body = buildJsonObject {
            put("model", v.model)
            put("input", text)
            put("voice", v.voice.ifBlank { "nova" })
            put("response_format", "mp3")
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${v.apiKey.trim()}").post(body).build()
        http.newCall(req).execute().use { r ->
            if (r.code !in 200..299) error("语音模型返回 ${r.code}")
            val bytes = r.body?.bytes() ?: error("语音模型没返回声音")
            if (bytes.size < 200) error("语音模型返回的不是声音")
            return File(ctx.cacheDir, "moling-voice.mp3").apply { writeBytes(bytes) }
        }
    }

    private fun play(file: File, onDone: (() -> Unit)?) {
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release(); if (player === it) player = null; onDone?.invoke() }
                setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; onDone?.invoke(); true }
                prepare()
                start()
            }
        }.onFailure { onDone?.invoke() }
    }

    private fun system(ctx: Context, text: String, onDone: (() -> Unit)?) {
        if (failed) {
            onDone?.invoke()
            return
        }
        val engine = tts
        if (engine != null && ready) {
            say(engine, text, onDone)
            return
        }
        pending = text to onDone
        if (engine != null) return // 正在初始化，等回调一起念
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = finish(utteranceId)
                    @Deprecated("系统要求实现")
                    override fun onError(utteranceId: String?) = finish(utteranceId)
                })
                ready = true
                pending?.let { (t, d) -> tts?.let { say(it, t, d) } }
            } else {
                failed = true
                Log.w(TAG, "没有可用的朗读引擎（$status），以后都跳过")
                pending?.second?.let { main.post(it) }
            }
            pending = null
        }
    }

    private fun say(engine: TextToSpeech, text: String, onDone: (() -> Unit)?) {
        val id = "moling-${seq++}"
        if (onDone != null) synchronized(done) { done[id] = onDone }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun finish(id: String?) {
        val d = synchronized(done) { id?.let { done.remove(it) } } ?: return
        main.post(d)
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        failed = false
        pending = null
    }

    /** 掐断正在念的（关掉朗读、开始听他说话时）。被掐断的不回调，晚到的云 TTS / 播放也不播。 */
    fun stop() {
        token++
        runCatching { tts?.stop() }
        synchronized(done) { done.clear() }
        runCatching { player?.stop(); player?.release() }
        player = null
    }
}
