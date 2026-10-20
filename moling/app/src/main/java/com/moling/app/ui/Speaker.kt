package com.moling.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** 清掉朗读不念的东西：动作行是给按钮的，念出来只是噪音。 */
private val NOT_SPOKEN = Regex("【动作】[^\n]*")

/**
 * 系统朗读的包装。第一次真的要念时才初始化，之后复用；
 * 手机没有朗读引擎就静默跳过——聊天不该因为念不出来而卡住。
 *
 * TextToSpeech 必须拿 Context 创建，而 [speak] 的签名里没有 Context，
 * 所以界面在进入对话页时先 [prepare] 一次（只记 application context，不初始化引擎）。
 */
object Speaker {
    private const val TAG = "MolingSpeaker"

    private var app: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: String? = null

    fun prepare(context: Context) {
        app = context.applicationContext
    }

    fun speak(text: String) {
        val ctx = app ?: return
        val clean = text.replace(NOT_SPOKEN, "").trim().take(400)
        if (clean.isEmpty() || failed) return
        val engine = tts
        if (engine != null && ready) {
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "moling")
            return
        }
        pending = clean
        if (engine != null) return // 正在初始化，等回调一起念
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
                ready = true
                pending?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "moling") }
            } else {
                failed = true
                Log.w(TAG, "没有可用的朗读引擎（$status），以后都跳过")
            }
            pending = null
        }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        failed = false
        pending = null
    }

    /** 用户关掉朗读时顺手掐断正在念的。 */
    fun stop() {
        runCatching { tts?.stop() }
    }
}
