package cn.companion.pet.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import cn.companion.pet.core.action.SpeechPort
import java.util.Locale
import java.util.UUID

/** One lazily initialized engine; state is confined to the main looper. */
class Speech(private val context: Context) : SpeechPort {
    private data class Pending(val text: String, val id: String, val receipt: (Boolean, String) -> Unit)
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var generation = 0
    private var pending: Pending? = null
    private var speakingId: String? = null
    private val cancelledIds = LinkedHashSet<String>()

    private fun markCancelled(id: String) {
        cancelledIds.add(id)
        while (cancelledIds.size > 2048) cancelledIds.remove(cancelledIds.first())
    }

    fun say(text: String) = submit(text, UUID.randomUUID().toString()) { _, _ -> }

    override fun submit(text: String, id: String, receipt: (Boolean, String) -> Unit) {
        handler.post {
            if (id in cancelledIds) { receipt(false, "播报已取消"); return@post }
            val next = Pending(text, id, receipt)
            if (ready) { speakNow(next); return@post }
            pending?.receipt?.invoke(false, "已被新的播报替代")
            pending = next
            if (tts != null) return@post
            val expected = ++generation
            try { tts = TextToSpeech(context.applicationContext) { status ->
                handler.post initialized@{
                    if (generation != expected) return@initialized
                    val language = if (status == TextToSpeech.SUCCESS) runCatching { tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) }.getOrNull() else null
                    ready = status == TextToSpeech.SUCCESS && language != null && language >= TextToSpeech.LANG_AVAILABLE
                    val waiting = pending
                    pending = null
                    if (ready && waiting != null) speakNow(waiting)
                    else if (!ready) {
                        waiting?.receipt?.invoke(false, "系统 TTS 不可用或缺少中文语音数据")
                        runCatching { tts?.shutdown() }; tts = null
                    }
                }
            } } catch (_: Exception) {
                pending?.receipt?.invoke(false, "系统 TTS 初始化失败")
                pending = null; tts = null; ready = false
            }
        }
    }

    private fun speakNow(item: Pending) {
        if (item.id in cancelledIds) { item.receipt(false, "播报已取消"); return }
        val code = runCatching { tts?.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, item.id) }.getOrNull()
        speakingId = if (code == TextToSpeech.SUCCESS) item.id else null
        item.receipt(code == TextToSpeech.SUCCESS,
            if (code == TextToSpeech.SUCCESS) "系统 TTS 已接受播报（不代表已播放完成）" else "系统 TTS 拒绝了本次播报")
    }

    override fun stop(id: String) {
        handler.post {
            markCancelled(id)
            if (pending?.id == id) { pending?.receipt?.invoke(false, "播报已取消"); pending = null }
            if (speakingId == id) { runCatching { tts?.stop() }; speakingId = null }
            if (!ready && pending == null) { generation++; runCatching { tts?.shutdown() }; tts = null }
        }
    }

    fun stopAll() {
        handler.post {
            pending?.let { markCancelled(it.id); it.receipt(false, "播报已取消") }
            pending = null
            speakingId?.let { markCancelled(it) }
            runCatching { tts?.stop() }; speakingId = null
            if (!ready) { generation++; runCatching { tts?.shutdown() }; tts = null }
        }
    }

    fun shutdown() {
        handler.post {
            generation++
            pending?.receipt?.invoke(false, "播报服务已关闭"); pending = null
            runCatching { tts?.shutdown() }
            tts = null; ready = false; speakingId = null
        }
    }
}
