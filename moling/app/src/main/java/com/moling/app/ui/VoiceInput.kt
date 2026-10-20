package com.moling.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 系统语音输入：自己不录音，交给系统的识别服务（所以墨灵不申请录音权限）。
 * 识别完只把文字交给界面填进输入框，发不发由用户决定。
 */
class VoiceInput(context: Context) {
    private val app = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var destroyed = false

    fun start(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (destroyed) return
        this.onResult = onResult
        this.onError = onError
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            onError("这台手机没有语音识别，直接打字吧")
            return
        }
        val r = ensure() ?: run {
            onError("语音识别打不开，直接打字吧")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { r.startListening(intent) }.onFailure {
            Log.w(TAG, "开始识别失败", it)
            onError("语音识别没起来：${it.message ?: "未知原因"}")
        }
    }

    private fun ensure(): SpeechRecognizer? {
        recognizer?.let { return it }
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(app) }.getOrNull() ?: return null
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()
                if (said.isNullOrEmpty()) onError?.invoke("没听清，再说一次") else onResult?.invoke(said)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "识别失败：$error")
                onError?.invoke(errorText(error))
            }
        })
        recognizer = r
        return r
    }

    /** 用户点停了；系统那边也会自己结束。 */
    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    fun destroy() {
        destroyed = true
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        onResult = null
        onError = null
    }

    /** 错误码说成人话，别让用户对着 -1 发呆。 */
    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "网络不好，语音识别没连上"
        SpeechRecognizer.ERROR_AUDIO -> "没听到声音，看看麦克风"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错了"
        SpeechRecognizer.ERROR_CLIENT -> "识别被系统断了，再点一次"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "上一次识别还没结束，等一下"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "系统没给识别权限，直接打字吧"
        else -> "语音识别失败了（$code），直接打字吧"
    }

    private companion object {
        const val TAG = "MolingVoice"
    }
}
