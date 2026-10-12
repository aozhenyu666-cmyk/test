package com.zongkong.app.system

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** 把参谋的话读出来（系统自带的朗读引擎）。第一次用时初始化，之后复用。 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(context: Context, text: String) {
        val t = text.replace(Regex("【选项】.*"), "").trim().take(400)
        if (t.isEmpty()) return
        val engine = tts
        if (engine != null && ready) {
            engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, "zk")
            return
        }
        pending = t
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                    pending?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "zk") }
                } else {
                    Log.w("Speaker", "朗读引擎不可用：$status")
                }
                pending = null
            }
        }
    }

    fun stop() {
        tts?.stop()
    }
}
