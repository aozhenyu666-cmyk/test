package cn.companion.pet.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 按住说话：系统 SpeechRecognizer。不可用（没有识别服务或没给麦克风权限）时 [available] 为 false，
 * 界面隐藏麦克风入口，引导用户用输入法的语音键。
 * 必须在主线程调用。
 */
class VoiceInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var partial = ""
    private var callback: ((String?) -> Unit)? = null

    /** 上一次失败的系统错误码；成功或重新开始时清空。用于告诉用户真正的原因，而不是一律“没听清”。 */
    var lastError: Int? = null
        private set

    val available: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            SpeechRecognizer.isRecognitionAvailable(context)

    /** 开始听。[onResult] 收到最终文字（失败或没听清为 null），只回调一次。 */
    fun start(onResult: (String?) -> Unit) {
        cancel()
        lastError = null
        if (!available) { onResult(null); return }
        partial = ""
        callback = onResult
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (r == null) { finish(null); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finish(text?.takeIf { it.isNotBlank() } ?: partial.takeIf { it.isNotBlank() })
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
            }
            override fun onError(error: Int) {
                if (partial.isBlank()) lastError = error
                finish(partial.takeIf { it.isNotBlank() })
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 按住期间不要因为短暂停顿就结束
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        }
        runCatching { r.startListening(intent) }.onFailure { finish(null) }
    }

    /** 松手：停止录音，等最终结果。 */
    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        callback = null
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun finish(text: String?) {
        val cb = callback
        callback = null
        runCatching { recognizer?.destroy() }
        recognizer = null
        cb?.invoke(text)
    }

    companion object {
        fun describe(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "网络问题"
            SpeechRecognizer.ERROR_AUDIO -> "录不到声音（后台时系统可能不给麦克风）"
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "识别服务出错或断开"
            SpeechRecognizer.ERROR_CLIENT -> "识别服务拒绝了请求"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到说话"
            SpeechRecognizer.ERROR_NO_MATCH -> "没识别出文字"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限（后台启动时系统也会这样）"
            else -> "错误码 $error"
        }
    }
}
