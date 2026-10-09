package com.yishou.app.window

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yishou.app.YishouApp
import com.yishou.app.llm.LlmResult
import com.yishou.app.log.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 陪练的声音。填了朗读接口就用接口合成的声音，失败时退回系统朗读；没填就用系统朗读。
 * speak 的 onDone 在读完（或读不出来）时调用一次；被 stop 或下一句打断时不调用。
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val app = context.applicationContext as YishouApp
    private val tts = TextToSpeech(app, this)
    private var ready = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var apiJob: Job? = null

    /** 每说一句加一；回调里对不上号说明已被打断 */
    @Volatile private var generation = 0

    private val _problem = MutableStateFlow<String?>(null)
    /** 朗读不可用的原因，可用时为 null */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _problem.value = "系统朗读引擎不可用。可以在系统设置 → 无障碍 → 文字转语音 里检查。"
            RunLog.e("朗读", _problem.value!!)
            return
        }
        val r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            _problem.value = "系统朗读引擎不支持中文。可以在系统设置 → 文字转语音 里换一个引擎或下载中文语音。"
            RunLog.e("朗读", _problem.value!!)
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
        })
        ready = true
        pending?.let { (text, done) -> speak(text, done) }
        pending = null
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val gen = ++generation
        stopPlayback()
        _speaking.value = true
        apiJob?.cancel()
        if (app.settings.speech.value.ttsReady) {
            if (ready) tts.stop()
            apiJob = scope.launch { speakApi(text, gen, onDone) }
        } else {
            speakSystem(text, gen, onDone)
        }
    }

    private suspend fun speakApi(text: String, gen: Int, onDone: (() -> Unit)?) {
        when (val r = app.speechApi.synthesize(text)) {
            is LlmResult.Err -> {
                RunLog.e("朗读接口", "${r.error.message}，改用系统朗读")
                if (gen == generation) speakSystem(text, gen, onDone)
            }
            is LlmResult.Ok -> {
                if (gen != generation) return
                val file = withContext(Dispatchers.IO) {
                    File(app.cacheDir, "say.mp3").apply { writeBytes(r.value) }
                }
                if (gen != generation) return
                try {
                    val mp = MediaPlayer()
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    mp.setDataSource(file.path)
                    mp.setOnCompletionListener { done(gen, onDone) }
                    mp.setOnErrorListener { _, what, _ ->
                        RunLog.e("朗读接口", "播放出错（$what），改用系统朗读")
                        if (gen == generation) speakSystem(text, gen, onDone)
                        true
                    }
                    mp.prepare()
                    mp.start()
                    player = mp
                } catch (e: Exception) {
                    RunLog.e("朗读接口", "播放失败，改用系统朗读", e)
                    speakSystem(text, gen, onDone)
                }
            }
        }
    }

    private val callbacks = mutableMapOf<String, Pair<Int, (() -> Unit)?>>()

    private fun speakSystem(text: String, gen: Int, onDone: (() -> Unit)?) {
        if (!ready) {
            if (_problem.value != null) {
                // 读不出来也要让流程往下走（比如接着听你说）
                done(gen, onDone)
            } else {
                pending = text to onDone
            }
            return
        }
        val id = "yishou-$gen"
        synchronized(callbacks) { callbacks[id] = gen to onDone }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun finished(utteranceId: String?) {
        val entry = synchronized(callbacks) { utteranceId?.let { callbacks.remove(it) } } ?: return
        main.post { done(entry.first, entry.second) }
    }

    private fun done(gen: Int, onDone: (() -> Unit)?) {
        if (gen != generation) return
        stopPlayback()
        _speaking.value = false
        onDone?.invoke()
    }

    private fun stopPlayback() {
        player?.let {
            try { it.stop() } catch (e: IllegalStateException) {}
            it.release()
        }
        player = null
    }

    fun stop() {
        generation++
        pending = null
        apiJob?.cancel()
        stopPlayback()
        synchronized(callbacks) { callbacks.clear() }
        if (ready) tts.stop()
        _speaking.value = false
    }

    fun shutdown() {
        stop()
        scope.cancel()
        tts.shutdown()
    }
}
