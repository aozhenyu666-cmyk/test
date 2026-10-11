package com.yishou.app.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.yishou.app.YishouApp
import com.yishou.app.llm.LlmResult
import com.yishou.app.log.RunLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** 听一次的结果 */
sealed interface Heard {
    data class Text(val text: String) : Heard
    /** 没开口，或者被取消 */
    data object Nothing : Heard
    data class Failed(val message: String) : Heard
}

enum class ListenPhase { IDLE, LISTENING, TRANSCRIBING }

/**
 * 听你说一段话并转成文字。填了听写接口就自己录音（说完自动停）再发给接口；
 * 没填就用手机自带的语音识别。同一时间只听一处。
 */
class VoiceInput(private val app: YishouApp) {

    private val _phase = MutableStateFlow(ListenPhase.IDLE)
    val phase: StateFlow<ListenPhase> = _phase.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** 当前音量 0–1，界面画波纹用 */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val busy = AtomicBoolean(false)
    @Volatile private var stopFlag = AtomicBoolean(false)
    @Volatile private var cancelFlag = AtomicBoolean(false)
    @Volatile private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 用的是哪种听写，显示在界面上 */
    fun engineName(): String = if (app.settings.speech.value.sttReady) "语音接口" else "系统识别"

    suspend fun listen(): Heard {
        if (!hasPermission()) return Heard.Failed("没有麦克风权限。点麦克风时在系统弹窗里允许。")
        if (!busy.compareAndSet(false, true)) return Heard.Failed("另一处正在听")
        stopFlag = AtomicBoolean(false)
        cancelFlag = AtomicBoolean(false)
        return try {
            _phase.value = ListenPhase.LISTENING
            if (app.settings.speech.value.sttReady) listenApi() else listenSystem()
        } finally {
            _phase.value = ListenPhase.IDLE
            _level.value = 0f
            busy.set(false)
        }
    }

    /** 我说完了：马上停止录音，把已经说的拿去听写。 */
    fun finish() {
        stopFlag.set(true)
        main.post { recognizer?.stopListening() }
    }

    /** 不要了：停止录音，什么都不交。 */
    fun cancel() {
        cancelFlag.set(true)
        main.post { recognizer?.cancel() }
    }

    private suspend fun listenApi(): Heard {
        val stop = stopFlag
        val cancel = cancelFlag
        val pcm = try {
            record(stop, cancel)
        } catch (e: Exception) {
            RunLog.e("录音", "麦克风打不开", e)
            return Heard.Failed("麦克风打不开：${e.message ?: e.javaClass.simpleName}")
        } ?: return Heard.Nothing
        if (cancel.get()) return Heard.Nothing
        _phase.value = ListenPhase.TRANSCRIBING
        _level.value = 0f
        return when (val r = app.speechApi.transcribe(Vad.wav(pcm, RATE))) {
            is LlmResult.Ok -> if (cancel.get()) Heard.Nothing else Heard.Text(r.value)
            is LlmResult.Err -> {
                RunLog.e("听写", r.error.message)
                Heard.Failed("听写失败：${r.error.message}")
            }
        }
    }

    /** 录一段话：说完自动停；一直没开口返回 null。 */
    @SuppressLint("MissingPermission")
    private suspend fun record(stop: AtomicBoolean, cancel: AtomicBoolean): ByteArray? = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord 初始化失败")
        }
        val vad = Vad()
        val frame = ShortArray(RATE / 50)
        val out = ByteArrayOutputStream()
        rec.startRecording()
        try {
            while (isActive && !cancel.get()) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) break
                for (i in 0 until n) {
                    val v = frame[i].toInt()
                    out.write(v and 0xff)
                    out.write((v shr 8) and 0xff)
                }
                val rms = Vad.rms(frame, n)
                val st = vad.feed(rms, n * 1000L / RATE)
                _level.value = vad.level(rms)
                if (stop.get() || st == Vad.State.DONE || st == Vad.State.NO_SPEECH) break
            }
        } finally {
            try { rec.stop() } catch (e: IllegalStateException) {}
            rec.release()
        }
        val spoke = vad.state == Vad.State.DONE || (vad.state == Vad.State.SPEAKING && vad.spokenMs >= Vad.MIN_SPEECH_MS)
        if (spoke && !cancel.get()) out.toByteArray() else null
    }

    private suspend fun listenSystem(): Heard = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            return@withContext Heard.Failed("这台手机没有可用的系统语音识别。到设置 → 语音 填一个听写接口就能用。")
        }
        suspendCancellableCoroutine<Heard> { cont ->
            val sr = SpeechRecognizer.createSpeechRecognizer(app)
            recognizer = sr
            fun done(h: Heard) {
                if (recognizer === sr) recognizer = null
                sr.destroy()
                if (cont.isActive) cont.resume(h)
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onRmsChanged(rmsdB: Float) {
                    _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }
                override fun onEndOfSpeech() {
                    _phase.value = ListenPhase.TRANSCRIBING
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    done(if (text.isNullOrEmpty() || cancelFlag.get()) Heard.Nothing else Heard.Text(text))
                }
                override fun onError(error: Int) {
                    val h = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> Heard.Nothing
                        else -> Heard.Failed(systemError(error)).also { RunLog.e("系统识别", it.message) }
                    }
                    done(h)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_600L)
            try {
                sr.startListening(intent)
            } catch (e: Exception) {
                RunLog.e("系统识别", "启动失败", e)
                done(Heard.Failed("系统语音识别启动失败"))
            }
            cont.invokeOnCancellation {
                main.post {
                    if (recognizer === sr) recognizer = null
                    sr.cancel()
                    sr.destroy()
                }
            }
        }
    }

    private fun systemError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统识别需要联网，网络不通"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "系统识别没有麦克风权限"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统识别正忙，稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "系统识别的服务端出错"
        else -> "系统识别出错（代码 $code）"
    }

    companion object {
        const val RATE = 16_000
    }
}
