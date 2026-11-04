package com.dshdriver.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Reuses Appreciate's transient duck/release pattern. Never opens a microphone. */
class CheckinVoice(context: Context) : TextToSpeech.OnInitListener {
    private val am=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setOnAudioFocusChangeListener { }.build()
    private var pending: String?=null
    private var ready=false
    private var closed=false
    private val tts=TextToSpeech(context.applicationContext,this)
    override fun onInit(status: Int) {
        if(closed || status!=TextToSpeech.SUCCESS) {pending=null;return}
        ready=tts.setLanguage(Locale.SIMPLIFIED_CHINESE) !in setOf(TextToSpeech.LANG_MISSING_DATA,TextToSpeech.LANG_NOT_SUPPORTED)
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
            override fun onStart(id: String?) {Engine.selfSpeaking(true)}
            override fun onDone(id: String?) = release()
            @Deprecated("Android callback") override fun onError(id: String?) = release()
        })
        pending?.let { pending=null;speak(it) }
    }
    fun speak(text: String) {
        if(closed) return
        if(!ready) {pending=text;return}
        if(am.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if(tts.speak(text.take(500),TextToSpeech.QUEUE_FLUSH,null,"awareness")!=TextToSpeech.SUCCESS) release()
    }
    private fun release() {Engine.selfSpeaking(false);am.abandonAudioFocusRequest(focus)}
    fun close() {closed=true;pending=null;tts.stop();tts.shutdown();release()}
}
