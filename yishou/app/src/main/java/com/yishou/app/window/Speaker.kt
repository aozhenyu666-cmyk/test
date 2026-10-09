package com.yishou.app.window

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** 系统自带 TextToSpeech 的简单封装。初始化完成前要读的内容先存着，完成后再读。 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: String? = null

    private val _problem = MutableStateFlow<String?>(null)
    /** 朗读不可用的原因，可用时为 null */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _problem.value = "系统朗读引擎不可用。可以在系统设置 → 无障碍 → 文字转语音 里检查。"
            return
        }
        val r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            _problem.value = "系统朗读引擎不支持中文。可以在系统设置 → 文字转语音 里换一个引擎或下载中文语音。"
            return
        }
        ready = true
        pending?.let { speak(it) }
        pending = null
    }

    fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yishou")
    }

    fun stop() {
        pending = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }
}
