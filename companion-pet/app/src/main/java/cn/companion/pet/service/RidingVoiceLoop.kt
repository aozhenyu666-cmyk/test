package cn.companion.pet.service

import android.content.Context
import android.os.PowerManager
import cn.companion.pet.PetApp
import cn.companion.pet.core.partner.TurnChannel
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.coroutines.resume

/** TTS completion -> STT -> model reply -> TTS. One generation owns the microphone. */
class RidingVoiceLoop(private val context: Context, private val allowed: () -> Boolean) {
    private val app = context.applicationContext as PetApp
    private val voice = VoiceInput(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "companion:voice")
    private var running = false
    private var generation = 0
    private var nextListen: Job? = null
    private var utterance: String? = null
    private var speaking = false
    private var pendingPrompt: String? = null
    private var failures = 0
    private var waitingSince = 0L

    fun start(): Boolean {
        if (running) return true
        if (!voice.available || !allowed()) return false
        running = true
        generation++
        wake.acquire(10 * 60_000L)
        scope.launch { say("语音陪伴已打开。戴好耳机，不用看屏幕。你刚才在想什么？") }
        return true
    }

    suspend fun say(text: String): Boolean {
        if (!running || !allowed()) return false
        nextListen?.cancel(); voice.cancel(); app.partner.voiceCapturing = false
        val expected = ++generation
        speaking = true
        val id = UUID.randomUUID().toString()
        utterance = id
        val started = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                app.speech.sayWithLifecycle(text, id, onStarted = {
                    if (continuation.isActive) continuation.resume(expected == generation && running && allowed())
                }, onFinished = { ok ->
                    if (continuation.isActive) continuation.resume(false)
                    if (expected == generation && running) {
                        speaking = false; utterance = null
                        if (!allowed()) { pendingPrompt = null }
                        else if (ok) {
                            failures = 0
                            val prompt = pendingPrompt; pendingPrompt = null
                            if (prompt != null) scope.launch { say(prompt) } else scheduleListen(350)
                        }
                        else { app.partner.noteBlocked("语音播放失败，已停止骑行模式"); app.settings.partnerRiding = false; stop() }
                    }
                })
                continuation.invokeOnCancellation { app.speech.stop(id) }
            }
        } ?: false
        if (!started && expected == generation) { speaking = false; app.speech.stop(id) }
        return started
    }

    fun reply(text: String, prompt: String? = null) { pendingPrompt = prompt; scope.launch { say(text) } }

    fun tick() {
        if (!running) return
        if (!wake.isHeld) wake.acquire(10 * 60_000L)
        if (!allowed()) {
            generation++; nextListen?.cancel(); voice.cancel(); app.partner.voiceCapturing = false
            utterance?.let(app.speech::stop); utterance = null; speaking = false; pendingPrompt = null
            return
        }
        if (app.partner.voiceCapturing && System.currentTimeMillis() - waitingSince > 60_000) {
            voice.cancel(); app.partner.voiceCapturing = false; scheduleListen(1000)
        }
        if (!speaking && !app.partner.voiceCapturing && !app.partner.busy.value && nextListen?.isActive != true) scheduleListen(500)
    }

    private fun scheduleListen(waitMillis: Long) {
        nextListen?.cancel()
        nextListen = scope.launch { delay(waitMillis); listen() }
    }

    private fun listen() {
        if (!running || !allowed() || speaking || app.partner.busy.value) return
        if (!voice.available) { app.settings.partnerRiding = false; stop(); return }
        val expected = generation
        app.partner.voiceCapturing = true
        waitingSince = System.currentTimeMillis()
        voice.start { text ->
            if (expected != generation || !running) return@start
            app.partner.voiceCapturing = false
            if (!text.isNullOrBlank()) {
                failures = 0
                app.partner.send(text, TurnChannel.VOICE, voice.lastUncertain)
            } else {
                val quiet = voice.lastError == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT || voice.lastError == android.speech.SpeechRecognizer.ERROR_NO_MATCH
                if (!quiet) failures++
                if (failures >= 5) { app.partner.noteBlocked("系统语音识别连续失败，已停止骑行模式"); app.settings.partnerRiding = false; stop() }
                else scheduleListen((1000L shl failures.coerceAtMost(3)))
            }
        }
    }

    fun stop() {
        running = false; pendingPrompt = null; generation++; nextListen?.cancel(); voice.cancel()
        app.partner.voiceCapturing = false
        utterance?.let(app.speech::stop); utterance = null; speaking = false
        if (wake.isHeld) wake.release()
    }
}
