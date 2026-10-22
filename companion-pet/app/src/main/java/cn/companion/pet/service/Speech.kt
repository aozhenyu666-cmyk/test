package cn.companion.pet.service

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 语音播报。默认关闭（机械音破坏伴生感），只有设置里打开才会初始化。 */
class Speech(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun say(text: String) {
        val t = tts
        if (t == null) {
            pending = text
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                    pending?.let { speakNow(it) }
                }
                pending = null
            }
            return
        }
        if (ready) speakNow(text)
    }

    private fun speakNow(text: String) {
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pet") }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
